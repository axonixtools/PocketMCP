package com.pocketmcp.tools

import android.content.Context
import com.pocketmcp.agent.dialog.ClarificationEngine
import com.pocketmcp.agent.execution.StepExecutor
import com.pocketmcp.agent.execution.StepVerifier
import com.pocketmcp.agent.execution.TaskOrchestrator
import com.pocketmcp.agent.memory.ExecutionTraceStore
import com.pocketmcp.agent.memory.SessionMemoryStore
import com.pocketmcp.agent.nlu.CommandParser
import com.pocketmcp.agent.nlu.EntityResolver
import com.pocketmcp.agent.planner.PolicyGate
import com.pocketmcp.agent.planner.TaskGraphBuilder
import com.pocketmcp.agent.skills.AppSkillRegistry
import com.pocketmcp.server.McpToolCallResult
import com.pocketmcp.server.McpToolHandler
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class IntelligentActionTool : McpToolHandler {
    override val name: String = "intelligent_action"
    override val description: String =
        "Experimental humanoid orchestration tool. Parses natural language, builds a task graph, executes with retries, and logs traces."

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Natural language command.")
            }
            putJsonObject("dry_run") {
                put("type", "boolean")
                put("description", "When true, only returns execution plan.")
            }
            putJsonObject("confirm_risky") {
                put("type", "boolean")
                put("description", "When true, blocks high-risk actions without confirmation token.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("command")) })
    }

    override suspend fun execute(args: kotlinx.serialization.json.JsonObject?, context: Context): McpToolCallResult {
        val command = argString(args, "command")?.trim()
            ?: return resultError("Missing required argument: command")
        val dryRun = argBoolean(args, "dry_run") ?: false
        val confirmRisky = argBoolean(args, "confirm_risky") ?: true

        val memory = SessionMemoryStore()
        val parser = CommandParser(EntityResolver(memory))
        val policyGate = PolicyGate()
        val graphBuilder = TaskGraphBuilder(AppSkillRegistry(), policyGate)
        val clarification = ClarificationEngine()
        val traceStore = ExecutionTraceStore(context)

        val parsed = parser.parse(command)
        val graph = graphBuilder.build(parsed)

        memory.update(lastApp = parsed.app, lastPerson = parsed.targetPerson)

        clarification.requiresClarification(graph.steps)?.let { reason ->
            return resultError(reason)
        }

        if (confirmRisky && graph.steps.any { policyGate.requiresConfirmation(it.action) }) {
            return resultError("High-risk action detected (e.g., report). Re-run with confirm_risky=false to proceed.")
        }

        if (dryRun) {
            val payload = buildJsonObject {
                put("mode", "dry_run")
                put("command", command)
                put("parsed_app", parsed.app ?: "")
                put("parsed_query", parsed.query ?: "")
                put("parsed_target_person", parsed.targetPerson ?: "")
                put("intents", buildJsonArray { parsed.intents.forEach { add(JsonPrimitive(it.name)) } })
                put("steps", buildJsonArray {
                    graph.steps.forEach { step ->
                        add(
                            buildJsonObject {
                                put("id", step.id)
                                put("action", step.action.name)
                                put("app", step.app ?: "")
                                put("text", step.text ?: "")
                                put("risk", step.riskLevel.name)
                            }
                        )
                    }
                })
            }
            return resultJson(payload)
        }

        val orchestrator = TaskOrchestrator(
            stepExecutor = StepExecutor(context, StepVerifier())
        )
        val result = orchestrator.execute(graph.steps)
        traceStore.writeTrace(
            command = command,
            success = result.success,
            stepMessages = result.steps.map { "${it.stepId}: ${it.message}" }
        )

        val payload = buildJsonObject {
            put("command", command)
            put("success", result.success)
            put("confidence", result.confidence.value)
            put("confidence_reason", result.confidence.reason)
            put("steps", buildJsonArray {
                result.steps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("step_id", step.stepId)
                            put("success", step.success)
                            put("attempts", step.attempts)
                            put("message", step.message)
                        }
                    )
                }
            })
            put("blockers", buildJsonArray { result.blockerNotes.forEach { add(JsonPrimitive(it)) } })
        }
        return resultJson(payload)
    }
}
