package com.pocketmcp.agent.skills

import com.pocketmcp.agent.model.TaskGraph
import com.pocketmcp.agent.model.UserIntent

public class AppSkillRegistry(
    private val skills: List<AppSkill> = listOf(YoutubeSkill(), XSkill())
) {
    public fun planFor(intent: UserIntent): TaskGraph? {
        val skill = skills.firstOrNull { it.canHandle(intent) } ?: return null
        return skill.buildPlan(intent)
    }
}
