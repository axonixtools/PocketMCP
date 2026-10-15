package com.pocketmcp.agent.planner

import com.pocketmcp.agent.model.IntentType
import com.pocketmcp.agent.model.RiskLevel
import com.pocketmcp.agent.model.StepAction
import com.pocketmcp.agent.model.UserIntent
import com.pocketmcp.agent.skills.AppSkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGraphBuilderTest {

    private val policyGate = PolicyGate()
    private val builder = TaskGraphBuilder(AppSkillRegistry(), policyGate)

    @Test
    fun `generic plans open the app and run searches`() {
        val graph = builder.build(
            UserIntent(
                rawCommand = "search cats on instagram",
                app = "instagram",
                query = "cats",
                targetPerson = null,
                intents = listOf(IntentType.SEARCH_IN_APP)
            )
        )

        assertEquals(listOf("open_app", "search"), graph.steps.map { it.id })
        assertEquals(StepAction.OPEN_APP, graph.steps[0].action)
        assertEquals("cats", graph.steps[1].text)
    }

    @Test
    fun `youtube intents are handled by the youtube skill`() {
        val graph = builder.build(
            UserIntent(
                rawCommand = "search lofi on youtube",
                app = "youtube",
                query = "lofi",
                targetPerson = null,
                intents = listOf(IntentType.SEARCH_IN_APP)
            )
        )

        assertEquals(listOf("open_youtube", "search_query"), graph.steps.map { it.id })
        assertEquals("YouTube", graph.steps[1].app)
    }

    @Test
    fun `follow steps carry the resolved target and medium risk`() {
        val graph = builder.build(
            UserIntent(
                rawCommand = "follow alice on instagram",
                app = "instagram",
                query = null,
                targetPerson = "alice",
                intents = listOf(IntentType.FOLLOW_ACCOUNT)
            )
        )

        val follow = graph.steps.single { it.action == StepAction.FOLLOW }
        assertEquals("alice", follow.text)
        assertEquals(RiskLevel.MEDIUM, follow.riskLevel)
        assertFalse(policyGate.requiresConfirmation(StepAction.FOLLOW))
    }

    @Test
    fun `report steps are high risk and require confirmation`() {
        val graph = builder.build(
            UserIntent(
                rawCommand = "report this post",
                app = "instagram",
                query = null,
                targetPerson = null,
                intents = listOf(IntentType.REPORT_CONTENT)
            )
        )

        val report = graph.steps.single { it.action == StepAction.REPORT }
        assertEquals(RiskLevel.HIGH, report.riskLevel)
        assertTrue(policyGate.requiresConfirmation(StepAction.REPORT))
    }
}
