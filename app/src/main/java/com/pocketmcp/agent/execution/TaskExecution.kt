package com.pocketmcp.agent.execution

import android.content.Context
import com.pocketmcp.accessibility.PhoneAccessibilityService
import com.pocketmcp.agent.model.ConfidenceScore
import com.pocketmcp.agent.model.ExecutionResult
import com.pocketmcp.agent.model.ExecutionStepResult
import com.pocketmcp.agent.model.StepAction
import com.pocketmcp.agent.model.TaskStep
import com.pocketmcp.agent.perception.BlockerResolver
import com.pocketmcp.agent.perception.PopupDetector
import com.pocketmcp.agent.perception.PopupMatch
import com.pocketmcp.tools.LaunchAppTool
import com.pocketmcp.tools.SearchScreenTool
import com.pocketmcp.tools.SocialMediaTool
import com.pocketmcp.tools.TapTool
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public data class RetryPolicy(
    public val maxAttempts: Int
)

public class StepVerifier {
    public fun verify(resultText: String?, success: Boolean): Boolean {
        if (!success) return false
        val lower = resultText.orEmpty().lowercase()
        return !lower.contains("error")
    }
}

public class StepExecutor(
    private val context: Context,
    private val verifier: StepVerifier
) {
    public suspend fun execute(step: TaskStep, preDismissedCount: Int = 0): ExecutionStepResult {
        var attempts = 0
        var lastMessage = "Not executed"
        var success = false
        var blockersDismissed = preDismissedCount
        val retryPolicy = RetryPolicy(step.maxRetries + 1)

        while (attempts < retryPolicy.maxAttempts && !success) {
            attempts += 1
            val result = when (step.action) {
                StepAction.OPEN_APP -> LaunchAppTool().execute(
                    buildJsonObject {
                        put("action", "open")
                        put("app_name", step.app ?: "")
                    },
                    context
                )
                StepAction.SEARCH -> SearchScreenTool().execute(
                    buildJsonObject {
                        put("action", "search")
                        if (!step.app.isNullOrBlank()) put("app_name", step.app)
                        put("query", step.text ?: "")
                        put("close_popups", true)
                        put("submit", true)
                    },
                    context
                )
                StepAction.FOLLOW -> SocialMediaTool().execute(
                    buildJsonObject {
                        put("platform", step.app ?: "x")
                        put("action", "follow")
                        if (!step.text.isNullOrBlank()) put("query", step.text)
                    },
                    context
                )
                StepAction.BOOKMARK -> SocialMediaTool().execute(
                    buildJsonObject {
                        put("platform", step.app ?: "x")
                        put("action", "bookmark")
                    },
                    context
                )
                StepAction.REPORT -> SocialMediaTool().execute(
                    buildJsonObject {
                        put("platform", step.app ?: "x")
                        put("action", "report")
                    },
                    context
                )
                StepAction.OPEN_PROFILE -> SearchScreenTool().execute(
                    buildJsonObject {
                        put("action", "search")
                        if (!step.app.isNullOrBlank()) put("app_name", step.app)
                        put("query", step.text ?: "")
                    },
                    context
                )
                StepAction.TAP_TEXT -> TapTool().execute(
                    buildJsonObject {
                        put("text", step.text ?: "")
                        put("exact", false)
                    },
                    context
                )
            }

            lastMessage = result.content.firstOrNull()?.text.orEmpty()
            success = verifier.verify(lastMessage, !result.isError)
        }

        return ExecutionStepResult(
            stepId = step.id,
            success = success,
            message = lastMessage,
            attempts = attempts,
            blockersDismissed = blockersDismissed
        )
    }

    public suspend fun executeWithReactiveDismissal(step: TaskStep): ExecutionStepResult {
        var blockersDismissed = 0
        var maxReactiveLoops = 3

        var result = execute(step, blockersDismissed)

        // Reactive: after each step attempt, scan the real screen for popups and dismiss them
        while (!result.success && maxReactiveLoops > 0) {
            maxReactiveLoops--

            val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
            val popupMatch = PopupDetector().detectFromSnapshot(snapshot)
            if (popupMatch == null) break

            val dismissed = attemptDismissPopup(popupMatch, context)
            if (!dismissed) break

            blockersDismissed++
            delay(600)

            // Retry the step after dismissal
            result = execute(step, blockersDismissed)
        }

        return result.copy(blockersDismissed = blockersDismissed)
    }

    private suspend fun attemptDismissPopup(match: PopupMatch, context: Context): Boolean {
        val tapHints = match.tapHints
        for (hint in tapHints) {
            val tapResult = TapTool().execute(
                buildJsonObject {
                    put("text", hint)
                    put("exact", false)
                },
                context
            )
            if (!tapResult.isError) {
                return true
            }
        }
        return false
    }
}

public class TaskOrchestrator(
    private val stepExecutor: StepExecutor,
    private val popupDetector: PopupDetector = PopupDetector(),
    private val blockerResolver: BlockerResolver = BlockerResolver()
) {
    public suspend fun execute(steps: List<TaskStep>): ExecutionResult {
        val stepResults = mutableListOf<ExecutionStepResult>()
        val blockers = mutableListOf<String>()

        steps.forEach { step ->
            // Use reactive execution that scans real screen and dismisses popups
            val result = stepExecutor.executeWithReactiveDismissal(step)
            stepResults.add(result)

            // Post-step screen scan for logging / residual blockers
            val snapshot = PhoneAccessibilityService.captureScreenSnapshot(120)
            val residualPopup = popupDetector.detectFromSnapshot(snapshot)
            if (residualPopup != null) {
                blockers.add(blockerResolver.resolutionForMatch(residualPopup))
            }
        }

        val success = stepResults.all { it.success }
        val totalDismissed = stepResults.sumOf { it.blockersDismissed }
        val confidenceValue = when {
            stepResults.isEmpty() -> 0.0
            success && totalDismissed == 0 -> 0.95
            success && totalDismissed > 0 -> 0.92
            stepResults.any { it.success } -> 0.6
            else -> 0.25
        }
        val confidenceReason = when {
            success && totalDismissed == 0 -> "All steps verified"
            success && totalDismissed > 0 -> "All steps verified with $totalDismissed reactive popup dismissals"
            stepResults.any { it.success } -> "Partial execution with ${stepResults.count { !it.success }} failures"
            else -> "Execution failed"
        }

        return ExecutionResult(
            success = success,
            confidence = ConfidenceScore(confidenceValue, confidenceReason),
            steps = stepResults,
            blockerNotes = blockers
        )
    }
}
