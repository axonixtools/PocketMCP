package com.pocketmcp.agent.skills

import com.pocketmcp.agent.model.IntentType
import com.pocketmcp.agent.model.StepAction
import com.pocketmcp.agent.model.TaskGraph
import com.pocketmcp.agent.model.TaskStep
import com.pocketmcp.agent.model.UserIntent

public class YoutubeSkill : AppSkill {
    public override val appId: String = "youtube"

    public override fun canHandle(intent: UserIntent): Boolean = intent.app == appId

    public override fun buildPlan(intent: UserIntent): TaskGraph {
        val steps = mutableListOf<TaskStep>()
        if (IntentType.OPEN_APP in intent.intents || intent.app == appId) {
            steps.add(TaskStep(id = "open_youtube", action = StepAction.OPEN_APP, app = "YouTube"))
        }
        if (!intent.query.isNullOrBlank()) {
            steps.add(TaskStep(id = "search_query", action = StepAction.SEARCH, app = "YouTube", text = intent.query))
        }
        if (IntentType.FOLLOW_ACCOUNT in intent.intents) {
            steps.add(TaskStep(id = "follow_target", action = StepAction.FOLLOW, app = "youtube", text = intent.targetPerson))
        }
        return TaskGraph(steps)
    }
}
