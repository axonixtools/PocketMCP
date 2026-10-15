package com.pocketmcp.agent.dialog

import com.pocketmcp.agent.model.TaskStep

public class ClarificationEngine {
    public fun requiresClarification(steps: List<TaskStep>): String? {
        val hasUnknownTarget = steps.any { (it.action.name == "FOLLOW" || it.action.name == "REPORT") && it.text.isNullOrBlank() }
        return if (hasUnknownTarget) {
            "I need the exact target account/content before completing this action."
        } else {
            null
        }
    }
}
