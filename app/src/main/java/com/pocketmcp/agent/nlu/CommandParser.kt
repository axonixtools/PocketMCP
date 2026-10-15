package com.pocketmcp.agent.nlu

import com.pocketmcp.agent.model.UserIntent

public class CommandParser(
    private val entityResolver: EntityResolver
) {
    public fun parse(command: String): UserIntent {
        val lower = command.trim().lowercase()
        val app = extractApp(lower)
        val rawPerson = extractPerson(command)
        val query = extractQuery(command)

        return UserIntent(
            rawCommand = command,
            app = entityResolver.resolveApp(app),
            query = query,
            targetPerson = entityResolver.resolvePerson(rawPerson),
            intents = IntentCatalog.detectIntents(lower)
        )
    }

    private fun extractApp(lower: String): String? {
        return when {
            lower.contains("youtube") || lower.contains("yt") -> "youtube"
            lower.contains("twitter") || lower.contains(" x ") || lower.startsWith("x ") -> "x"
            lower.contains("instagram") -> "instagram"
            else -> null
        }
    }

    private fun extractPerson(command: String): String? {
        val patterns = listOf(
            Regex("profile of\\s+(.+?)(?:\\s+and\\s+|$)", RegexOption.IGNORE_CASE),
            Regex("follow\\s+(.+?)(?:\\s+and\\s+|$)", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(command)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractQuery(command: String): String? {
        val patterns = listOf(
            Regex("(?:search|find|look up|lookup)\\s+(.+?)(?:\\s+and\\s+follow|$)", RegexOption.IGNORE_CASE),
            Regex("search\\s+profile\\s+of\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(command)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.isNotBlank() }
    }
}
