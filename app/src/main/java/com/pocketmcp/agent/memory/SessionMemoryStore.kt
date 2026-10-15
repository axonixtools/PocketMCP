package com.pocketmcp.agent.memory

import com.pocketmcp.agent.model.EntityContext

public class SessionMemoryStore {
    @Volatile
    private var context: EntityContext = EntityContext()

    public fun getContext(): EntityContext = context

    public fun update(
        lastApp: String? = context.lastApp,
        lastPerson: String? = context.lastPerson,
        lastContentHint: String? = context.lastContentHint,
    ): Unit {
        context = context.copy(
            lastApp = lastApp ?: context.lastApp,
            lastPerson = lastPerson ?: context.lastPerson,
            lastContentHint = lastContentHint ?: context.lastContentHint,
        )
    }
}
