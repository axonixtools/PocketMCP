package com.pocketmcp.util

import java.security.SecureRandom

/** Generates cryptographically random API keys for the MCP server. */
public object ApiKeyGenerator {
    private const val DEFAULT_LENGTH: Int = 32
    private const val ALPHABET: String =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    public fun generate(length: Int = DEFAULT_LENGTH): String {
        require(length > 0) { "length must be positive" }
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}
