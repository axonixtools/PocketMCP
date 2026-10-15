package com.pocketmcp.agent.planner

import com.pocketmcp.agent.model.RiskLevel
import com.pocketmcp.agent.model.StepAction

public class PolicyGate {
    public fun riskFor(action: StepAction): RiskLevel {
        return when (action) {
            StepAction.REPORT -> RiskLevel.HIGH
            StepAction.FOLLOW, StepAction.BOOKMARK -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    public fun requiresConfirmation(action: StepAction): Boolean {
        return riskFor(action) == RiskLevel.HIGH
    }
}
