package com.pocketmcp

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.service.McpServerService
import java.security.SecureRandom
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var statusIndicator: View
    private lateinit var serverSwitch: SwitchMaterial
    private lateinit var tvEndpoint: TextView
    private lateinit var etPort: EditText
    private lateinit var etApiKey: EditText
    private lateinit var tvPermissionSummary: TextView
    private lateinit var tvMcpConfigPreview: TextView
    private lateinit var btnStartStop: Button
    private lateinit var cardsContainer: LinearLayout
    private lateinit var cardStatus: View
    private lateinit var cardConfig: View
    private lateinit var cardPermissions: View
    private lateinit var cardQuickConnect: View
    private lateinit var cardUseCases: View

    private var suppressSwitchListener = false
    private var hasAnimatedEntrance = false

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != McpServerService.ACTION_STATUS) {
                return
            }

            val running = intent.getBooleanExtra(McpServerService.EXTRA_RUNNING, false)
            val status = intent.getStringExtra(McpServerService.EXTRA_STATUS)
            val error = intent.getStringExtra(McpServerService.EXTRA_ERROR)
            val secured = intent.getBooleanExtra(McpServerService.EXTRA_SECURED, !currentApiKey().isNullOrBlank())
            updateServerStatus(running, status, error, secured)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        bindViews()
        restoreSavedSettings()
        wireUiActions()
        updatePermissionsSummary()
        requestMissingPermissions()
        renderConnectionInfo()
        updateServerStatus(
            running = false,
            statusText = getString(R.string.server_status_stopped),
            error = null,
            secured = !currentApiKey().isNullOrBlank()
        )
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(McpServerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        queryServiceStatus()
        updatePermissionsSummary()
        renderConnectionInfo()
        if (!hasAnimatedEntrance) {
            cardsContainer.post { animateEntrance() }
            hasAnimatedEntrance = true
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionsSummary()
        }
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        statusIndicator = findViewById(R.id.status_indicator)
        serverSwitch = findViewById(R.id.server_switch)
        tvEndpoint = findViewById(R.id.tvEndpoint)
        etPort = findViewById(R.id.etPort)
        etApiKey = findViewById(R.id.etApiKey)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        tvMcpConfigPreview = findViewById(R.id.tvMcpConfigPreview)
        btnStartStop = findViewById(R.id.btnStartStop)
        cardsContainer = findViewById(R.id.cardsContainer)
        cardStatus = findViewById(R.id.cardStatus)
        cardConfig = findViewById(R.id.cardConfig)
        cardPermissions = findViewById(R.id.cardPermissions)
        cardQuickConnect = findViewById(R.id.cardQuickConnect)
        cardUseCases = findViewById(R.id.cardUseCases)
    }

    private fun wireUiActions() {
        serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchListener) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                startServer()
            } else {
                stopServer()
            }
        }

        btnStartStop.setOnClickListener {
            btnStartStop.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            if (serverSwitch.isChecked) {
                stopServer()
            } else {
                startServer()
            }
        }

        findViewById<Button>(R.id.btnGenerateKey).setOnClickListener {
            val generated = generateApiKey()
            etApiKey.setText(generated)
            saveSettings()
            renderConnectionInfo()
            animateCardPulse(cardConfig)
            Toast.makeText(this, R.string.api_key_generated, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCopyEndpoint).setOnClickListener {
            copyToClipboard("MCP endpoint", tvEndpoint.text.toString())
            Toast.makeText(this, R.string.endpoint_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCopyConfig).setOnClickListener {
            copyToClipboard("MCP config", tvMcpConfigPreview.text.toString())
            Toast.makeText(this, R.string.config_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRequestPermissions).setOnClickListener {
            requestMissingPermissions()
            animateCardPulse(cardPermissions)
        }

        etPort.doAfterTextChanged {
            saveSettings()
            renderConnectionInfo()
        }
        etApiKey.doAfterTextChanged {
            saveSettings()
            renderConnectionInfo()
        }
    }

    private fun startServer() {
        val port = parsePort()
        if (port == null) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            setSwitchChecked(false)
            return
        }

        saveSettings()

        val apiKey = currentApiKey()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            Toast.makeText(this, R.string.security_warning_no_key, Toast.LENGTH_LONG).show()
        }

        val startIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_START
            putExtra(McpServerService.EXTRA_PORT, port)
            if (apiKey.isNotBlank()) {
                putExtra(McpServerService.EXTRA_API_KEY, apiKey)
            }
        }
        ContextCompat.startForegroundService(this, startIntent)
        updateServerStatus(
            running = true,
            statusText = getString(R.string.server_status_starting),
            error = null,
            secured = apiKey.isNotBlank()
        )
    }

    private fun stopServer() {
        val stopIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_STOP
        }
        startService(stopIntent)
        updateServerStatus(
            running = false,
            statusText = getString(R.string.server_status_stopping),
            error = null,
            secured = !currentApiKey().isNullOrBlank()
        )
    }

    private fun queryServiceStatus() {
        val queryIntent = Intent(this, McpServerService::class.java).apply {
            action = McpServerService.ACTION_QUERY_STATUS
        }
        startService(queryIntent)
    }

    private fun updateServerStatus(
        running: Boolean,
        statusText: String?,
        error: String?,
        secured: Boolean
    ) {
        // Keep status card visible even if a status update arrives during entry animations.
        cardStatus.alpha = 1f
        cardStatus.translationY = 0f

        setSwitchChecked(running)
        updatePrimaryButtonText(running)

        val resolvedStatus = resolveStatusText(running, statusText)
        tvStatus.text = resolvedStatus

        val securityText = if (secured) {
            getString(R.string.security_state_enabled)
        } else {
            getString(R.string.security_state_disabled)
        }
        tvStatusDetail.text = if (error.isNullOrBlank()) {
            securityText
        } else {
            "$securityText\n${getString(R.string.last_error_prefix)} $error"
        }

        val indicatorDrawable = if (running) {
            R.drawable.status_indicator_on
        } else {
            R.drawable.status_indicator_off
        }
        statusIndicator.background = ContextCompat.getDrawable(this, indicatorDrawable)
        animateStatusVisuals(running)
    }

    private fun setSwitchChecked(isChecked: Boolean) {
        suppressSwitchListener = true
        serverSwitch.isChecked = isChecked
        suppressSwitchListener = false
    }

    private fun renderConnectionInfo() {
        val port = parsePort() ?: 8080
        val ipAddress = getIpAddress()
        val endpoint = "http://$ipAddress:$port/mcp"
        tvEndpoint.text = endpoint

        val apiKey = currentApiKey().orEmpty()
        tvMcpConfigPreview.text = buildMcpConfig(endpoint, apiKey)
    }

    private fun restoreSavedSettings() {
        val savedPort = preferences.getInt(PREF_PORT, 8080)
        etPort.setText(savedPort.toString())

        if (preferences.contains(PREF_API_KEY)) {
            etApiKey.setText(preferences.getString(PREF_API_KEY, "") ?: "")
        } else {
            val generated = generateApiKey()
            etApiKey.setText(generated)
            saveSettings()
        }
    }

    private fun saveSettings() {
        val port = parsePort() ?: 8080
        val apiKey = currentApiKey().orEmpty()
        preferences.edit()
            .putInt(PREF_PORT, port)
            .putString(PREF_API_KEY, apiKey)
            .apply()
    }

    private fun parsePort(): Int? {
        val value = etPort.text?.toString()?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in 1..65535 }
    }

    private fun currentApiKey(): String? = etApiKey.text?.toString()

    private fun requestMissingPermissions() {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updatePermissionsSummary() {
        val statusLines = listOf(
            permissionLine(Manifest.permission.ACCESS_FINE_LOCATION, getString(R.string.permission_location)),
            permissionLine(Manifest.permission.READ_CONTACTS, getString(R.string.permission_contacts)),
            permissionLine(Manifest.permission.CAMERA, getString(R.string.permission_camera)),
            permissionLine(Manifest.permission.RECORD_AUDIO, getString(R.string.permission_microphone)),
            mediaAudioPermissionLine(),
            accessibilityStatusLine(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLine(
                    Manifest.permission.POST_NOTIFICATIONS,
                    getString(R.string.permission_notifications)
                )
            } else {
                "${getString(R.string.permission_notifications)}: ${getString(R.string.permission_not_required)}"
            }
        )
        tvPermissionSummary.text = statusLines.joinToString("\n")
    }

    private fun permissionLine(permission: String, label: String): String {
        val granted = ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
        val state = if (granted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_missing)
        }
        return "$label: $state"
    }

    private fun accessibilityStatusLine(): String {
        val state = if (isAccessibilityServiceEnabled()) {
            getString(R.string.permission_enabled_in_settings)
        } else {
            getString(R.string.permission_missing_in_settings)
        }
        return "${getString(R.string.permission_accessibility)}: $state"
    }

    private fun mediaAudioPermissionLine(): String {
        val label = getString(R.string.permission_audio_files)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLine(Manifest.permission.READ_MEDIA_AUDIO, label)
        } else {
            permissionLine(Manifest.permission.READ_EXTERNAL_STORAGE, label)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "${packageName}/${PhoneAccessibilityService::class.java.name}"
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { entry ->
            entry.equals(expectedComponent, ignoreCase = true)
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun generateApiKey(length: Int = 32): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }

    private fun buildMcpConfig(endpoint: String, apiKey: String): String {
        val escapedApiKey = apiKey.replace("\"", "\\\"")
        val headersBlock = if (apiKey.isBlank()) {
            ""
        } else {
            """
        "headers": {
          "X-API-Key": "$escapedApiKey"
        }
""".trimIndent()
        }

        return if (headersBlock.isBlank()) {
            """
{
  "mcpServers": {
    "phone": {
      "type": "streamableHttp",
      "url": "$endpoint"
    }
  }
}
""".trimIndent()
        } else {
            """
{
  "mcpServers": {
    "phone": {
      "type": "streamableHttp",
      "url": "$endpoint",
$headersBlock
    }
  }
}
""".trimIndent()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun resolveStatusText(running: Boolean, statusText: String?): String {
        val normalized = statusText?.trim()?.lowercase()
        return when (normalized) {
            null, "", "status" -> if (running) {
                getString(R.string.server_status_running)
            } else {
                getString(R.string.server_status_stopped)
            }
            "running" -> getString(R.string.server_status_running)
            "stopped", "stop", "stopping", "stopped by user" -> getString(R.string.server_status_stopped)
            "starting" -> getString(R.string.server_status_starting)
            "failed", "error" -> getString(R.string.server_status_failed)
            else -> statusText
        }
    }

    private fun updatePrimaryButtonText(running: Boolean) {
        val nextText = if (running) getString(R.string.stop_server) else getString(R.string.start_server)
        if (btnStartStop.text == nextText) {
            return
        }
        btnStartStop.animate()
            .alpha(0.4f)
            .setDuration(90)
            .withEndAction {
                btnStartStop.text = nextText
                btnStartStop.animate().alpha(1f).setDuration(120).start()
            }
            .start()
    }

    private fun animateStatusVisuals(running: Boolean) {
        val targetColor = ContextCompat.getColor(
            this,
            if (running) R.color.status_running_text_color else R.color.status_stopped_text_color
        )
        val colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), tvStatus.currentTextColor, targetColor)
        colorAnimator.duration = 280
        colorAnimator.addUpdateListener { animator ->
            tvStatus.setTextColor(animator.animatedValue as Int)
        }
        colorAnimator.start()

        statusIndicator.animate().cancel()
        statusIndicator.scaleX = 0.88f
        statusIndicator.scaleY = 0.88f
        statusIndicator.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        animateCardPulse(cardStatus)
    }

    private fun animateCardPulse(card: View) {
        card.animate()
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(90)
            .withEndAction {
                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun animateEntrance() {
        val cards = listOf(cardStatus, cardConfig, cardPermissions, cardQuickConnect, cardUseCases)
        cards.forEachIndexed { index, card ->
            card.alpha = 0f
            card.translationY = 36f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 65).toLong())
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun getIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return getString(R.string.ip_unknown)
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress ?: continue
                    if (!address.isLoopbackAddress && !host.contains(':')) {
                        return host
                    }
                }
            }
            getString(R.string.ip_unknown)
        } catch (_: Exception) {
            getString(R.string.ip_unknown)
        }
    }

    private companion object {
        const val PREFS_NAME = "pocket_mcp_prefs"
        const val PREF_PORT = "pref_port"
        const val PREF_API_KEY = "pref_api_key"
        const val PERMISSION_REQUEST_CODE = 1001
    }
}
