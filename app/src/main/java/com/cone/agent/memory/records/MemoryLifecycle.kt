package com.cone.agent.memory.records

/**
 * Memory lifecycle, ported from ConeCode's `lifecycle.ts`.
 *
 * Every write goes through [update] so version, timestamp and history stay consistent, and every
 * overwrite keeps a snapshot — a wrong correction is reversible, and "why does the assistant think
 * that?" is answerable.
 */
object MemoryLifecycle {

    /** Keep enough history for undo/audit without letting a hot record grow forever. */
    const val MAX_HISTORY = 50

    /** Above this, extraction never silently overwrites — only the user can. */
    const val PROTECTED_IMPORTANCE = 0.9

    data class Patch(
        val title: String? = null,
        val content: String? = null,
        val type: String? = null,
        val layer: String? = null,
        val projectId: String? = null,
        val tags: List<String>? = null,
        val importance: Double? = null,
        val confidence: Double? = null,
        val status: String? = null,
        val why: String? = null,
    )

    private fun nextVersion(record: MemoryRecord, now: Long): MemoryRecord = record.copy(
        version = record.version + 1,
        updatedAt = now,
        history = (record.history + MemoryRules.snapshot(record)).takeLast(MAX_HISTORY),
    )

    fun update(record: MemoryRecord, patch: Patch, now: Long): MemoryRecord {
        val versioned = nextVersion(record, now)
        val merged = versioned.copy(
            title = patch.title?.trim()?.take(160) ?: versioned.title,
            content = patch.content?.trim() ?: versioned.content,
            type = patch.type ?: versioned.type,
            layer = patch.layer ?: versioned.layer,
            projectId = patch.projectId ?: versioned.projectId,
            tags = patch.tags?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: versioned.tags,
            importance = patch.importance?.coerceIn(0.0, 1.0) ?: versioned.importance,
            confidence = patch.confidence?.coerceIn(0.0, 1.0) ?: versioned.confidence,
            status = patch.status ?: versioned.status,
            why = patch.why?.trim()?.ifEmpty { null } ?: versioned.why,
        )
        // A global memory has no project; a project memory without one would leak into every scope.
        val scoped = if (merged.layer == "global") merged.copy(projectId = null) else merged
        return if (scoped.layer != "global" && scoped.projectId == null) {
            scoped.copy(layer = "global")
        } else scoped
    }

    /** Re-observing the same fact raises its weight without creating a duplicate. */
    fun reinforce(record: MemoryRecord, now: Long, sharper: String? = null): MemoryRecord {
        if (record.importance >= PROTECTED_IMPORTANCE) return record
        val better = sharper?.takeIf { it.length > record.content.length }
        return update(
            record,
            Patch(
                importance = minOf(1.0, record.importance + 0.06),
                content = better,
            ),
            now,
        )
    }

    /** Merge two records about the same subject; keeps the union, never loses detail. */
    fun merge(primary: MemoryRecord, secondary: MemoryRecord, now: Long): MemoryRecord {
        val content = when {
            primary.content.contains(secondary.content) -> primary.content
            secondary.content.contains(primary.content) -> secondary.content
            primary.content.length >= secondary.content.length ->
                (primary.content + "\n" + secondary.content).take(4000)
            else -> (secondary.content + "\n" + primary.content).take(4000)
        }
        return update(
            primary,
            Patch(
                content = content,
                tags = (primary.tags + secondary.tags).distinct(),
                importance = maxOf(primary.importance, secondary.importance),
                confidence = maxOf(primary.confidence, secondary.confidence),
                why = primary.why ?: secondary.why,
            ),
            now,
        )
    }

    /**
     * New information replaces an old memory only after judging source, time and confidence.
     * When the winner is not clear both versions survive instead of the older one being destroyed.
     */
    fun reconcile(existing: MemoryRecord, incoming: MemoryRules.Classified, now: Long): Pair<MemoryRecord, String> {
        if (existing.importance >= PROTECTED_IMPORTANCE) return existing to "kept_both"
        val incomingIsUserStated = incoming.status == "confirmed" && incoming.confidence >= 0.9
        val existingIsUserStated = existing.origin == MemoryWire.ORIGIN_USER && existing.confidence >= 0.9
        val decisive = incomingIsUserStated && !existingIsUserStated
        val blocked = !incomingIsUserStated && existingIsUserStated
        val materiallyDifferent = !MemoryText.isContained(incoming.content, existing.content) &&
            !MemoryText.isContained(existing.content, incoming.content)

        if (blocked || (now <= existing.updatedAt && !decisive)) return existing to "kept_both"

        if (decisive || incoming.confidence >= existing.confidence + 0.2) {
            val updated = update(
                existing,
                Patch(
                    content = incoming.content,
                    title = incoming.title,
                    type = incoming.type,
                    tags = incoming.tags,
                    importance = maxOf(existing.importance, incoming.importance),
                    confidence = incoming.confidence,
                    status = incoming.status,
                    why = incoming.why,
                ),
                now,
            )
            return updated to "superseded"
        }
        if (materiallyDifferent) return existing to "kept_both"
        return update(existing, Patch(why = incoming.why ?: existing.why), now) to "updated"
    }

    fun supersede(old: MemoryRecord, now: Long): MemoryRecord =
        update(old, Patch(status = "superseded"), now)

    fun archive(record: MemoryRecord, now: Long): MemoryRecord =
        update(record, Patch(status = "archived"), now)

    /** Soft delete: a tombstone, so the record never comes back through a stale read. */
    fun tombstone(record: MemoryRecord, now: Long): MemoryRecord {
        val versioned = nextVersion(record, now)
        return versioned.copy(deletedAt = now, status = "archived")
    }

    /** Restore the immediately preceding version. Undo is itself versioned. */
    fun undo(record: MemoryRecord, now: Long): MemoryRecord? {
        val previous = record.history.lastOrNull() ?: return null
        return update(
            record,
            Patch(
                title = previous.title,
                content = previous.content,
                status = previous.status,
                importance = previous.importance,
                confidence = previous.confidence,
                why = previous.why,
            ),
            now,
        )
    }
}
