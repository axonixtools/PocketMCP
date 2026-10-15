package com.pocketmcp.agent.nlu

import com.pocketmcp.agent.memory.SessionMemoryStore

public class EntityResolver(
    private val memoryStore: SessionMemoryStore
) {
    public fun resolvePerson(rawTarget: String?): String? {
        if (rawTarget.isNullOrBlank()) return memoryStore.getContext().lastPerson
        val compact = rawTarget.trim().lowercase()
        return when (compact) {
            "him", "her", "them", "this person" -> memoryStore.getContext().lastPerson
            else -> rawTarget.trim()
        }
    }

    public fun resolveApp(rawApp: String?): String? {
        if (rawApp.isNullOrBlank()) return memoryStore.getContext().lastApp
        val compact = rawApp.trim().lowercase()
        return when (compact) {
            "yt" -> "youtube"
            "twitter" -> "x"
            else -> compact
        }
    }
}
