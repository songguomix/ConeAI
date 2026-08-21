package com.cone.agent.data.repository

/**
 * The one rule every memory store in the app obeys, wherever the text came from.
 *
 * A memory is not a message that scrolls away — it is replayed into the system prompt of every later
 * turn, so a credential captured once is sent to the provider forever after. There are now three
 * writers (the Q&A extractor, the `remember` tool, and the coding agent's workflow memory) and they
 * must not each carry their own copy of this list, because the one that is forgotten is the one that
 * leaks.
 */
object MemoryPolicy {

    /**
     * Deliberately blunt. A false positive costs one forgotten preference; a false negative puts a
     * password in every future prompt.
     */
    fun isSensitive(text: String): Boolean = SENSITIVE.any { text.contains(it, ignoreCase = true) }

    private val SENSITIVE = listOf(
        "密码", "验证码", "身份证", "银行卡", "信用卡", "cvv", "password", "passcode",
        "api key", "apikey", "secret", "token", "私钥", "credential",
    )
}
