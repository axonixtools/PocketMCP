package com.pocketmcp.agent.skills

import com.pocketmcp.agent.model.IntentType
import com.pocketmcp.agent.model.StepAction
import com.pocketmcp.agent.model.TaskGraph
import com.pocketmcp.agent.model.TaskStep
import com.pocketmcp.agent.model.UserIntent

public class XSkill : AppSkill {
    public override val appId: String = "x"

    public override fun canHandle(intent: UserIntent): Boolean = intent.app == appId

    public override fun buildPlan(intent: UserIntent): TaskGraph {
        val steps = mutableListOf<TaskStep>()
        if (IntentType.OPEN_APP in intent.intents || intent.app == appId) {
            steps.add(TaskStep(id = "open_x", action = StepAction.OPEN_APP, app = "X"))
        }
        if (!intent.query.isNullOrBlank()) {
            steps.add(TaskStep(id = "search_x", action = StepAction.SEARCH, app = "X", text = intent.query))
        }
        if (IntentType.BOOKMARK_CONTENT in intent.intents) {
            steps.add(TaskStep(id = "bookmark_post", action = StepAction.BOOKMARK, app = "x"))
        }
        if (IntentType.REPORT_CONTENT in intent.intents) {
            steps.add(TaskStep(id = "report_post", action = StepAction.REPORT, app = "x"))
        }
        return TaskGraph(steps)
    }
}
