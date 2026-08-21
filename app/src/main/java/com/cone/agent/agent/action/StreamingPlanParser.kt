package com.cone.agent.agent.action

/** Events surfaced while a step's model response streams in. */
sealed interface ThinkEvent {
    data class Thought(val text: String) : ThinkEvent
    data class PlanOutline(val steps: List<String>) : ThinkEvent
    data class Action(val action: PlannedAction) : ThinkEvent

    /** Stream finished: the authoritative full plan plus the provider-reported token usage. */
    data class Completed(val plan: ActionPlan, val totalTokens: Int?) : ThinkEvent
}

/**
 * Incremental scanner for the agent's streamed JSON response. [feed] walks arriving text once,
 * character by character, and surfaces each element the moment it is complete — the thought
 * string, the plan outline, and above all every finished `{...}` item of the top-level `actions`
 * array — so the controller can start executing while the model is still emitting the rest.
 *
 * Emission is an optimisation only; correctness is anchored by [finish], which re-parses the whole
 * buffered text through the same [ActionParser] as the non-streaming path and verifies that the
 * streamed items are exactly a prefix of the final plan. A malformed response therefore fails the
 * step exactly like it always did, never half-diverges.
 */
class StreamingPlanParser {

    private val buffer = StringBuilder()
    private var pos = 0
    private var started = false
    private var closed = false
    private var itemError: Exception? = null

    private var depth = 0
    private var inString = false
    private var escaped = false
    private var stringStart = -1

    /** Key/value position tracking, needed only at depth 1 (the top-level object). */
    private var expectTopKey = false
    private var topKey: String? = null

    /** Which top-level key's array is currently open ("actions" / "plan" / another). */
    private var topArray: String? = null
    private var itemStart = -1
    private var planStart = -1

    private val emitted = ArrayList<PlannedAction>()

    val emittedActions: Int get() = emitted.size

    var thoughtEmitted = false
        private set
    var planEmitted = false
        private set

    /** True while no response text has arrived at all (used to detect non-SSE providers). */
    val isEmpty: Boolean get() = buffer.isEmpty()

    fun feed(chunk: String): List<ThinkEvent> {
        buffer.append(chunk)
        if (closed || itemError != null) return emptyList()
        val events = ArrayList<ThinkEvent>(2)
        while (pos < buffer.length) {
            val c = buffer[pos]
            if (!started) {
                // Skip any prose / markdown fence before the first '{' (same as ActionParser).
                if (c == '{') {
                    started = true
                    depth = 1
                    expectTopKey = true
                }
                pos++
                continue
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        inString = false
                        onStringClosed(events)
                    }
                }
                pos++
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    escaped = false
                    stringStart = pos + 1
                }
                ':' -> if (depth == 1) expectTopKey = false
                ',' -> if (depth == 1) {
                    expectTopKey = true
                    topKey = null
                }
                '{' -> {
                    if (topArray == KEY_ACTIONS && depth == 2) itemStart = pos
                    depth++
                }
                '[' -> {
                    if (depth == 1 && !expectTopKey && topArray == null) {
                        topArray = topKey
                        if (topArray == KEY_PLAN) planStart = pos
                    }
                    depth++
                }
                '}' -> {
                    depth--
                    if (topArray == KEY_ACTIONS && depth == 2 && itemStart >= 0) {
                        emitAction(buffer.substring(itemStart, pos + 1), events)
                        itemStart = -1
                        if (itemError != null) return events
                    }
                    if (depth == 0) {
                        closed = true
                        pos = buffer.length
                        return events
                    }
                }
                ']' -> {
                    depth--
                    if (depth == 1 && topArray != null) {
                        if (topArray == KEY_PLAN && planStart >= 0 && !planEmitted) {
                            emitPlan(buffer.substring(planStart, pos + 1), events)
                            planStart = -1
                        }
                        topArray = null
                    }
                }
            }
            pos++
        }
        return events
    }

    /** Authoritative end-of-stream parse — identical to the non-streaming path. */
    fun finish(): ActionPlan {
        itemError?.let { throw it }
        val plan = ActionParser.parse(buffer.toString()).getOrThrow()
        check(plan.actions.take(emitted.size) == emitted) { "streamed actions diverge from final parse" }
        return plan
    }

    private fun onStringClosed(events: MutableList<ThinkEvent>) {
        if (depth != 1) return
        if (expectTopKey) {
            topKey = buffer.substring(stringStart, pos)
        } else if (topKey == KEY_THOUGHT && !thoughtEmitted) {
            runCatching { ActionParser.parseStringLiteral(buffer.substring(stringStart - 1, pos + 1)) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    thoughtEmitted = true
                    events += ThinkEvent.Thought(it)
                }
        }
    }

    private fun emitPlan(raw: String, events: MutableList<ThinkEvent>) {
        runCatching { ActionParser.parseStringList(raw) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                planEmitted = true
                events += ThinkEvent.PlanOutline(it)
            }
    }

    private fun emitAction(raw: String, events: MutableList<ThinkEvent>) {
        try {
            val action = ActionParser.parseItem(raw)
            emitted += action
            events += ThinkEvent.Action(action)
        } catch (e: Exception) {
            // A malformed item means the whole response is malformed: stop emitting so the executed
            // prefix stays consistent with what finish() (the authoritative parse) will reject.
            itemError = e
        }
    }

    private companion object {
        const val KEY_ACTIONS = "actions"
        const val KEY_PLAN = "plan"
        const val KEY_THOUGHT = "thought"
    }
}
