package com.pocketmcp.agent.model

public enum class IntentType {
    OPEN_APP,
    SEARCH_IN_APP,
    OPEN_PROFILE,
    FOLLOW_ACCOUNT,
    BOOKMARK_CONTENT,
    REPORT_CONTENT,
    UNKNOWN
}

public enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

public enum class StepAction {
    OPEN_APP,
    SEARCH,
    OPEN_PROFILE,
    FOLLOW,
    BOOKMARK,
    REPORT,
    TAP_TEXT
}

public data class UserIntent(
    public val rawCommand: String,
    public val app: String?,
    public val query: String?,
    public val targetPerson: String?,
    public val intents: List<IntentType>
)

public data class EntityContext(
    public val lastApp: String? = null,
    public val lastPerson: String? = null,
    public val lastContentHint: String? = null
)

public data class TaskStep(
    public val id: String,
    public val action: StepAction,
    public val app: String? = null,
    public val text: String? = null,
    public val metadata: Map<String, String> = emptyMap(),
    public val timeoutMs: Long = 8_000L,
    public val maxRetries: Int = 1,
    public val riskLevel: RiskLevel = RiskLevel.LOW
)

public data class TaskGraph(
    public val steps: List<TaskStep>
)

public data class ConfidenceScore(
    public val value: Double,
    public val reason: String
)

public data class ExecutionStepResult(
    public val stepId: String,
    public val success: Boolean,
    public val message: String,
    public val attempts: Int,
    public val blockersDismissed: Int = 0
)

public data class ExecutionResult(
    public val success: Boolean,
    public val confidence: ConfidenceScore,
    public val steps: List<ExecutionStepResult>,
    public val blockerNotes: List<String> = emptyList()
)
