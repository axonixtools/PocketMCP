package com.pocketmcp.agent.nlu

import com.pocketmcp.agent.memory.SessionMemoryStore
import com.pocketmcp.agent.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandParserTest {

    private lateinit var memory: SessionMemoryStore
    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        memory = SessionMemoryStore()
        parser = CommandParser(EntityResolver(memory))
    }

    @Test
    fun `detects search intent and query`() {
        val intent = parser.parse("search cats")
        assertEquals("cats", intent.query)
        assertTrue(IntentType.SEARCH_IN_APP in intent.intents)
        assertNull(intent.app)
    }

    @Test
    fun `detects the youtube app and open intent`() {
        val intent = parser.parse("open youtube")
        assertEquals("youtube", intent.app)
        assertTrue(IntentType.OPEN_APP in intent.intents)
    }

    @Test
    fun `detects follow intent and person`() {
        val intent = parser.parse("follow elonmusk")
        assertEquals("elonmusk", intent.targetPerson)
        assertTrue(IntentType.FOLLOW_ACCOUNT in intent.intents)
    }

    @Test
    fun `splits search and follow in a compound command`() {
        val intent = parser.parse("search lofi and follow elonmusk")
        assertEquals("lofi", intent.query)
        assertEquals("elonmusk", intent.targetPerson)
        assertTrue(IntentType.SEARCH_IN_APP in intent.intents)
        assertTrue(IntentType.FOLLOW_ACCOUNT in intent.intents)
    }

    @Test
    fun `falls back to memory for pronouns and app context`() {
        memory.update(lastApp = "youtube", lastPerson = "alice")
        val intent = parser.parse("follow him")
        assertEquals("alice", intent.targetPerson)
        assertEquals("youtube", intent.app)
        assertTrue(IntentType.FOLLOW_ACCOUNT in intent.intents)
    }

    @Test
    fun `detects bookmark intent`() {
        val intent = parser.parse("bookmark this tweet")
        assertTrue(IntentType.BOOKMARK_CONTENT in intent.intents)
    }

    @Test
    fun `unknown commands are flagged`() {
        val intent = parser.parse("hello there")
        assertEquals(listOf(IntentType.UNKNOWN), intent.intents)
    }
}
