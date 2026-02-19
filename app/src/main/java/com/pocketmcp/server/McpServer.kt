package com.pocketmcp.server

import android.content.Context
import android.util.Log
import com.pocketmcp.tools.CallTool
import com.pocketmcp.tools.ContactsSearchTool
import com.pocketmcp.tools.DeviceInfoTool
import com.pocketmcp.tools.FileReadTool
import com.pocketmcp.tools.FlashlightTool
import com.pocketmcp.tools.GlobalActionTool
import com.pocketmcp.tools.HumanCommandTool
import com.pocketmcp.tools.HttpProxyTool
import com.pocketmcp.tools.LaunchAppTool
import com.pocketmcp.tools.ListAppsTool
import com.pocketmcp.tools.LocationTool
import com.pocketmcp.tools.McpToolRegistry
import com.pocketmcp.tools.MessagingTool
import com.pocketmcp.tools.NotificationTool
import com.pocketmcp.tools.PhoneAlertTool
import com.pocketmcp.tools.PresetAppActionsTool
import com.pocketmcp.tools.ScrollScreenTool
import com.pocketmcp.tools.ScreenStateTool
import com.pocketmcp.tools.SocialMediaTool
import com.pocketmcp.tools.ShellCommandTool
import com.pocketmcp.tools.TranscribeAudioTool
import com.pocketmcp.tools.TranscribeFileTool
import com.pocketmcp.tools.VolumeControlTool
import com.pocketmcp.tools.VoiceRecordTool
import com.pocketmcp.tools.WhatsAppAudioTranscribeTool
import com.pocketmcp.tools.WhatsAppAutomationTool
import com.pocketmcp.tools.WhatsAppBusinessSendTool
import com.pocketmcp.tools.WhatsAppSendTool
import com.pocketmcp.tools.NotificationDiagnosticTool
import com.pocketmcp.tools.ScreenshotTool
import com.pocketmcp.tools.TapTool
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "PocketMCP"
private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val SERVER_VERSION = "0.2.6"

class McpServer(
    private val context: Context,
    private val port: Int = 8080,
    private val apiKey: String? = null
) {
    @Volatile
    private var engine: ApplicationEngine? = null

    private val registry = McpToolRegistry()
    private val sseClients = CopyOnWriteArrayList<suspend (String) -> Unit>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        registry.register(DeviceInfoTool())
        registry.register(LocationTool())
        registry.register(ContactsSearchTool())
        registry.register(ShellCommandTool())
        registry.register(HttpProxyTool())
        registry.register(FileReadTool())
        registry.register(FlashlightTool())
        registry.register(LaunchAppTool())
        registry.register(ListAppsTool())
        registry.register(GlobalActionTool())
        registry.register(ScrollScreenTool())
        registry.register(ScreenStateTool())
        registry.register(VolumeControlTool())
        registry.register(PhoneAlertTool())
        registry.register(VoiceRecordTool())
        registry.register(TranscribeAudioTool())
        registry.register(TranscribeFileTool())
        registry.register(HumanCommandTool())
        registry.register(CallTool())
        registry.register(MessagingTool())
        registry.register(SocialMediaTool())
        registry.register(NotificationTool())
        registry.register(NotificationDiagnosticTool())
        registry.register(WhatsAppAutomationTool())
        registry.register(WhatsAppSendTool())
        registry.register(WhatsAppBusinessSendTool())
        registry.register(WhatsAppAudioTranscribeTool())
        registry.register(PresetAppActionsTool())
        registry.register(ScreenshotTool())
        registry.register(TapTool())
    }

    fun registerTool(tool: McpToolHandler) = registry.register(tool)

    fun isRunning(): Boolean = engine != null

    @Synchronized
    fun start() {
        if (engine != null) {
            Log.i(TAG, "MCP server already running on port $port")
            return
        }

        val createdEngine = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader("X-API-Key")
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Options)
            }
            install(WebSockets)

            routing {
                intercept(ApplicationCallPipeline.Plugins) {
                    if (!apiKey.isNullOrBlank()) {
                        val path = call.request.path()
                        val isSseStream = call.request.httpMethod == HttpMethod.Get &&
                            (path == "/mcp" || path == "/sse")
                        if (path != "/" && path != "/health" && !isSseStream) {
                            val provided = call.request.header("X-API-Key")
                                ?: call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                            if (provided != apiKey) {
                                call.respondText(
                                    "{\"error\":\"Invalid API key\"}",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Unauthorized
                                )
                                finish()
                                return@intercept
                            }
                        }
                    }
                }

                get("/") {
                    val payload = buildJsonObject {
                        put("name", "PocketMCP")
                        put("version", SERVER_VERSION)
                        put("protocol", MCP_PROTOCOL_VERSION)
                        put("port", port)
                        put("tools", registry.getAll().size)
                        put(
                            "endpoints",
                            buildJsonArray {
                                add(JsonPrimitive("/mcp"))
                                add(JsonPrimitive("/sse"))
                                add(JsonPrimitive("/health"))
                            }
                        )
                    }
                    call.respondText(
                        payload.toString(),
                        ContentType.Application.Json
                    )
                }

                get("/health") {
                    val payload = buildJsonObject {
                        put("status", "ok")
                        put("running", isRunning())
                        put("port", port)
                        put("secured", !apiKey.isNullOrBlank())
                    }
                    call.respondText(
                        payload.toString(),
                        ContentType.Application.Json
                    )
                }

                post("/mcp") {
                    val body = call.receiveText()
                    Log.d(TAG, "MCP request: $body")

                    val request = try {
                        val request = json.decodeFromString<JsonRpcRequest>(body)
                        request
                    } catch (e: Exception) {
                        val error = JsonRpcResponse(
                            error = JsonRpcError(McpErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
                        )
                        val responseJson = encodeJsonRpcResponse(error)
                        Log.d(TAG, "MCP response (parse error): $responseJson")
                        call.respondText(responseJson, ContentType.Application.Json)
                        return@post
                    }

                    if (request.id == null) {
                        // JSON-RPC notifications must not receive a JSON-RPC response body.
                        handleNotification(request)
                        call.respond(HttpStatusCode.Accepted)
                        return@post
                    }

                    val response = handleRequest(request)
                    val responseJson = encodeJsonRpcResponse(response)
                    Log.d(TAG, "MCP response: $responseJson")
                    call.respondText(responseJson, ContentType.Application.Json)
                }

                get("/mcp") {
                    streamSse()
                }

                get("/sse") {
                    streamSse()
                }

                get("/tools") {
                    val payload = buildJsonObject {
                        put(
                            "tools",
                            buildJsonArray {
                                registry.getAll().forEach { tool ->
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("inputSchema", tool.inputSchema)
                                        }
                                    )
                                }
                            }
                        )
                    }
                    call.respondText(
                        payload.toString(),
                        ContentType.Application.Json
                    )
                }
            }
        }

        createdEngine.start(wait = false)
        engine = createdEngine
        Log.i(TAG, "MCP server started on port $port")
    }

    @Synchronized
    fun stop() {
        val currentEngine = engine ?: return
        currentEngine.stop(1_000, 5_000)
        engine = null
        sseClients.clear()
        Log.i(TAG, "MCP server stopped")
    }

    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            "initialize" -> {
                val result = McpInitializeResult(
                    protocolVersion = MCP_PROTOCOL_VERSION,
                    serverInfo = McpServerInfo("PocketMCP", SERVER_VERSION),
                    capabilities = McpCapabilities(
                        tools = buildJsonObject { put("listChanged", JsonPrimitive(false)) }
                    )
                )
                JsonRpcResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(McpInitializeResult.serializer(), result)
                )
            }

            "notifications/initialized" -> JsonRpcResponse(
                id = request.id,
                result = JsonObject(emptyMap())
            )

            "tools/list" -> {
                val toolInfos = registry.getAll().map {
                    McpToolInfo(
                        name = it.name,
                        description = it.description,
                        inputSchema = it.inputSchema
                    )
                }
                val result = McpToolsListResult(tools = toolInfos)
                JsonRpcResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(McpToolsListResult.serializer(), result)
                )
            }

            "tools/call" -> {
                val params = request.params?.jsonObject
                    ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(McpErrorCodes.INVALID_PARAMS, "Missing params")
                    )

                val toolName = params["name"]?.jsonPrimitive?.content
                    ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(McpErrorCodes.INVALID_PARAMS, "Missing tool name")
                    )

                val tool = registry.get(toolName)
                    ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(McpErrorCodes.TOOL_NOT_FOUND, "Tool not found: $toolName")
                    )

                try {
                    val args = params["arguments"] as? JsonObject
                    val result = tool.execute(args, context)
                    JsonRpcResponse(
                        id = request.id,
                        result = json.encodeToJsonElement(McpToolCallResult.serializer(), result)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Tool execution error", e)
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(McpErrorCodes.INTERNAL_ERROR, e.message ?: "Tool error")
                    )
                }
            }

            else -> JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    McpErrorCodes.METHOD_NOT_FOUND,
                    "Method not found: ${request.method}"
                )
            )
        }
    }

    private suspend fun handleNotification(request: JsonRpcRequest) {
        when (request.method) {
            "notifications/initialized" -> {
                Log.d(TAG, "Received notifications/initialized")
            }
            else -> {
                Log.d(TAG, "Ignoring notification method: ${request.method}")
            }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.streamSse() {
        call.response.header("Cache-Control", "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(ContentType.parse("text/event-stream")) {
            write("data: {\"type\":\"connected\",\"server\":\"PocketMCP\"}\n\n")
            flush()

            val sender: suspend (String) -> Unit = { message ->
                write("data: $message\n\n")
                flush()
            }
            sseClients.add(sender)

            try {
                while (true) {
                    delay(15_000)
                    write(": ping\n\n")
                    flush()
                }
            } finally {
                sseClients.remove(sender)
            }
        }
    }

    suspend fun broadcast(message: String) {
        sseClients.forEach { it(message) }
    }

    private fun encodeJsonRpcResponse(response: JsonRpcResponse): String {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            response.result?.let { put("result", it) }
            response.error?.let {
                put("error", json.encodeToJsonElement(JsonRpcError.serializer(), it))
            }
            response.id?.let { put("id", it) }
        }
        return payload.toString()
    }
}
