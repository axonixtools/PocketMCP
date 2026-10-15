package com.pocketmcp.agent.planner

import com.pocketmcp.agent.model.IntentType
import com.pocketmcp.agent.model.StepAction
import com.pocketmcp.agent.model.TaskGraph
import com.pocketmcp.agent.model.TaskStep
import com.pocketmcp.agent.model.UserIntent
import com.pocketmcp.agent.skills.AppSkillRegistry

public class TaskGraphBuilder(
    private val skillRegistry: AppSkillRegistry,
    private val policyGate: PolicyGate
) {
    public fun build(intent: UserIntent): TaskGraph {
        val skillPlan = skillRegistry.planFor(intent)
        if (skillPlan != null && skillPlan.steps.isNotEmpty()) return skillPlan

        val steps = mutableListOf<TaskStep>()
        if (!intent.app.isNullOrBlank()) {
            steps.add(TaskStep(id = "open_app", action = StepAction.OPEN_APP, app = intent.app))
        }
        if (IntentType.SEARCH_IN_APP in intent.intents && !intent.query.isNullOrBlank()) {
            steps.add(TaskStep(id = "search", action = StepAction.SEARCH, app = intent.app, text = intent.query))
        }
        if (IntentType.FOLLOW_ACCOUNT in intent.intents) {
            steps.add(
                TaskStep(
                    id = "follow",
                    action = StepAction.FOLLOW,
                    app = intent.app,
                    text = intent.targetPerson,
                    riskLevel = policyGate.riskFor(StepAction.FOLLOW)
                )
            )
        }
        if (IntentType.BOOKMARK_CONTENT in intent.intents) {
            steps.add(
                TaskStep(
                    id = "bookmark",
                    action = StepAction.BOOKMARK,
                    app = intent.app,
                    riskLevel = policyGate.riskFor(StepAction.BOOKMARK)
                )
            )
        }
        if (IntentType.REPORT_CONTENT in intent.intents) {
            steps.add(
                TaskStep(
                    id = "report",
                    action = StepAction.REPORT,
                    app = intent.app,
                    riskLevel = policyGate.riskFor(StepAction.REPORT)
                )
            )
        }
        return TaskGraph(steps)
    }
}
