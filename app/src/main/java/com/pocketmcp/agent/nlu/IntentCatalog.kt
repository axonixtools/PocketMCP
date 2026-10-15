package com.pocketmcp.agent.nlu

import com.pocketmcp.agent.model.IntentType

public object IntentCatalog {
    public val openKeywords: List<String> = listOf("open", "launch", "start")
    public val searchKeywords: List<String> = listOf("search", "find", "look up", "lookup")
    public val followKeywords: List<String> = listOf("follow", "subscribe")
    public val bookmarkKeywords: List<String> = listOf("bookmark", "save post", "save tweet")
    public val reportKeywords: List<String> = listOf("report", "flag", "complain")

    public fun detectIntents(lower: String): List<IntentType> {
        val found = mutableListOf<IntentType>()
        if (openKeywords.any { lower.contains(it) }) found.add(IntentType.OPEN_APP)
        if (searchKeywords.any { lower.contains(it) }) found.add(IntentType.SEARCH_IN_APP)
        if (followKeywords.any { lower.contains(it) }) found.add(IntentType.FOLLOW_ACCOUNT)
        if (bookmarkKeywords.any { lower.contains(it) }) found.add(IntentType.BOOKMARK_CONTENT)
        if (reportKeywords.any { lower.contains(it) }) found.add(IntentType.REPORT_CONTENT)
        if (found.isEmpty()) found.add(IntentType.UNKNOWN)
        return found
    }
}
