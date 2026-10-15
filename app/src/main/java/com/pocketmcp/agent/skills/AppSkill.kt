package com.pocketmcp.agent.skills

import com.pocketmcp.agent.model.TaskGraph
import com.pocketmcp.agent.model.UserIntent

public interface AppSkill {
    public val appId: String
    public fun canHandle(intent: UserIntent): Boolean
    public fun buildPlan(intent: UserIntent): TaskGraph
}
