package com.cone.agent.memory.records

import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** What one extraction pass changed, for the little "记住了" note under the reply. */
data class MemoryChange(val content: String, val updated: Boolean)

/** Result of an explicit `remember` call. */
enum class RememberOutcome { ADDED, UPDATED, DUPLICATE, REJECTED }

/**
 * The Q&A memory engine, ported from ConeCode's Memory 2.0.
 *
 * Replaces the old flat fact list: candidates are extracted only on value signals, classified into
 * typed layers, deduped against existing records, and written through the lifecycle (reinforce /
 * merge / reconcile / reject) with version history. Retrieval is a ranked BM25 query scoped to the
 * current project instead of "paste every memory into the prompt".
 */
@Singleton
class MemoryService @Inject constructor(
    private val store: MemoryRecordStore,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val records: Flow<List<MemoryRecord>> = store.records
    val enabled: Flow<Boolean> = store.enabled

    suspend fun setEnabled(value: Boolean) = store.setEnabled(value)

    /**
     * Extracts durable value nodes from one finished exchange. Returns the changes worth showing
     * to the user; a provider failure simply yields nothing (memory is never allowed to break a turn).
     */
    suspend fun maybeExtract(
        userText: String,
        assistantText: String,
        projectId: String?,
        projectName: String?,
        sessionId: String?,
    ): List<MemoryChange> {
        if (!store.enabled.first()) return emptyList()
        store.ensureMigrated()
        val messages = listOf(
            ExchangeMessage("user", userText),
            ExchangeMessage("assistant", assistantText),
        )
        if (MemoryExtraction.isLowValueExchange(messages)) return emptyList()
        if (!MemoryExtraction.shouldExtract(messages)) return emptyList()

        val records = store.records.first()
        val known = records.filter { it.isActive }.map { it.content }
        val signals = MemoryExtraction.detectValueSignals(messages)
        val (system, user) = MemoryExtraction.buildRequest(messages, known, signals, projectName, userText)
        val raw = complete(system, user, 1200) ?: return emptyList()
        val parsed = MemoryExtraction.parse(raw)
        if (parsed.isEmpty()) return emptyList()

        val candidates = parsed.map { MemoryRules.classify(it, projectId, userStated = false) }
        val decisions = MemoryRules.dedupe(candidates, records)
        val (next, changes) = applyDecisions(records, decisions, sessionId)
        if (changes.isNotEmpty()) store.save(next)
        return changes
    }

    /**
     * Fire-and-forget extraction: the answer must never wait on a memory pass, and a failure here
     * must never surface as an error in the turn.
     */
    fun observeExchange(
        userText: String,
        assistantText: String,
        projectId: String?,
        projectName: String?,
        sessionId: String?,
        incognito: Boolean,
        onSaved: suspend (List<MemoryChange>) -> Unit,
    ) {
        if (incognito) return
        scope.launch {
            val changes = runCatching {
                maybeExtract(userText, assistantText, projectId, projectName, sessionId)
            }.getOrDefault(emptyList())
            if (changes.isNotEmpty()) runCatching { onSaved(changes) }
        }
    }

    /** The prompt block for this task, or blank when nothing relevant exists. */
    suspend fun retrieveBlock(query: String, projectId: String?, limit: Int = 8): String {
        if (!store.enabled.first()) return ""
        if (!MemoryRetrieval.needsHistory(query)) return ""
        store.ensureMigrated()
        val records = store.records.first()
        return MemoryRetrieval.formatBlock(MemoryRetrieval.search(records, query, projectId, limit))
    }

    suspend fun relevant(query: String, projectId: String?, limit: Int = 8): List<MemoryRecord> {
        if (!store.enabled.first()) return emptyList()
        if (!MemoryRetrieval.needsHistory(query)) return emptyList()
        store.ensureMigrated()
        return MemoryRetrieval.search(store.records.first(), query, projectId, limit).map { it.record }
    }

    /** Manual edit from settings: the user's wording becomes a confirmed fact. */
    suspend fun updateContent(id: String, content: String) {
        val fact = content.trim()
        if (fact.isEmpty()) return
        val records = store.records.first()
        val record = records.firstOrNull { it.id == id } ?: return
        val updated = MemoryLifecycle.update(
            record,
            MemoryLifecycle.Patch(
                title = MemoryRules.deriveTitle(fact),
                content = fact,
                status = "confirmed",
                confidence = 1.0,
            ),
            System.currentTimeMillis(),
        ).copy(origin = MemoryWire.ORIGIN_USER)
        store.save(records.map { if (it.id == id) updated else it })
    }

    /**
     * The explicit `remember` tool: a fact the user asked to keep is user-stated by definition,
     * so it enters as a confirmed global user fact — the strongest form the engine has.
     */
    suspend fun remember(fact: String): RememberOutcome {
        val text = fact.trim()
        if (text.length < 4 || MemoryRules.looksLikeSecret(text)) return RememberOutcome.REJECTED
        store.ensureMigrated()
        val records = store.records.first()
        val candidate = MemoryRules.Classified(
            title = MemoryRules.deriveTitle(text),
            content = text,
            type = "user_fact",
            layer = "global",
            projectId = null,
            tags = listOf("user_fact"),
            importance = 0.8,
            confidence = 1.0,
            status = "confirmed",
            why = null,
        )
        val decisions = MemoryRules.dedupe(listOf(candidate), records)
        if (decisions.isEmpty()) return RememberOutcome.REJECTED
        val (next, changes) = applyDecisions(records, decisions, null)
        return when (val decision = decisions.first()) {
            is MemoryRules.Decision.Add -> {
                store.save(next)
                RememberOutcome.ADDED
            }
            is MemoryRules.Decision.Merge, is MemoryRules.Decision.Reconcile -> {
                store.save(next)
                RememberOutcome.UPDATED
            }
            is MemoryRules.Decision.Reinforce -> {
                store.save(next)
                RememberOutcome.DUPLICATE
            }
            is MemoryRules.Decision.Reject -> {
                if (decision.reason == "duplicate within this extraction") RememberOutcome.DUPLICATE
                else RememberOutcome.REJECTED
            }
        }
    }

    /**
     * One-time import of the legacy flat workflow habits into records. The habits store was the
     * code agent's memory before Memory 2.0; its facts are user-confirmed preferences, so they
     * enter as confirmed global user facts and the old store is never written again.
     */
    suspend fun importLegacyHabits(habits: List<String>) {
        if (store.habitsMigrated()) return
        store.ensureMigrated()
        val records = store.records.first()
        val existing = records.map { MemoryText.normalize(it.content) }.toSet()
        val now = System.currentTimeMillis()
        val imported = habits
            .map { it.trim() }
            .filter { it.isNotEmpty() && !MemoryRules.looksLikeSecret(it) }
            .filter { MemoryText.normalize(it) !in existing }
            .distinct()
            .map { text ->
                MemoryRecord(
                    id = "legacy-habit-" + MemoryRules.newId(),
                    title = MemoryRules.deriveTitle(text),
                    content = text,
                    type = "user_fact",
                    layer = "global",
                    importance = 0.75,
                    confidence = 0.95,
                    status = "confirmed",
                    origin = MemoryWire.ORIGIN_MIGRATION,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        if (imported.isNotEmpty()) store.save(records + imported)
        store.markHabitsMigrated()
    }

    /** The explicit `forget` tool: tombstones every active record matching the keyword. */
    suspend fun forget(keyword: String): List<String> {
        val needle = MemoryText.normalize(keyword)
        if (needle.isEmpty()) return emptyList()
        val records = store.records.first()
        val hits = records.filter {
            it.isActive && (
                MemoryText.normalize(it.content).contains(needle) ||
                    MemoryText.normalize(it.title).contains(needle)
                )
        }
        if (hits.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val ids = hits.map { it.id }.toSet()
        store.save(records.map { if (it.id in ids) MemoryLifecycle.tombstone(it, now) else it })
        return hits.map { it.content }
    }

    /** Soft delete, so the record does not come back through a stale read. */
    suspend fun delete(id: String) {
        val records = store.records.first()
        val record = records.firstOrNull { it.id == id } ?: return
        val updated = MemoryLifecycle.tombstone(record, System.currentTimeMillis())
        store.save(records.map { if (it.id == id) updated else it })
    }

    suspend fun clear() = store.save(emptyList())

    /* ---------------- internals ---------------- */

    private fun applyDecisions(
        records: List<MemoryRecord>,
        decisions: List<MemoryRules.Decision>,
        sessionId: String?,
    ): Pair<List<MemoryRecord>, List<MemoryChange>> {
        val now = System.currentTimeMillis()
        val map = records.associateBy { it.id }.toMutableMap()
        val changes = ArrayList<MemoryChange>()
        for (decision in decisions) {
            when (decision) {
                is MemoryRules.Decision.Add -> {
                    val record = fromCandidate(decision.candidate, now, sessionId)
                    map[record.id] = record
                    changes += MemoryChange(record.content, updated = false)
                }
                is MemoryRules.Decision.Reinforce -> {
                    val updated = MemoryLifecycle.reinforce(decision.existing, now, decision.candidate.content)
                    map[updated.id] = updated
                }
                is MemoryRules.Decision.Merge -> {
                    val incoming = fromCandidate(decision.candidate, now, sessionId)
                    val merged = MemoryLifecycle.merge(decision.existing, incoming, now)
                    map[merged.id] = merged
                    changes += MemoryChange(merged.content, updated = true)
                }
                is MemoryRules.Decision.Reconcile -> {
                    val (record, outcome) = MemoryLifecycle.reconcile(decision.existing, decision.candidate, now)
                    map[record.id] = record
                    if (outcome == "kept_both") {
                        // A genuine disagreement: keep both sides instead of picking a winner.
                        val incoming = fromCandidate(decision.candidate, now, sessionId)
                        map[incoming.id] = incoming
                        changes += MemoryChange(incoming.content, updated = false)
                    } else {
                        changes += MemoryChange(record.content, updated = true)
                    }
                }
                is MemoryRules.Decision.Reject -> Unit
            }
        }
        return map.values.sortedBy { it.createdAt } to changes
    }

    private fun fromCandidate(candidate: MemoryRules.Classified, now: Long, sessionId: String?): MemoryRecord =
        MemoryRecord(
            id = MemoryRules.newId(),
            title = candidate.title.ifBlank { MemoryRules.deriveTitle(candidate.content) },
            content = candidate.content,
            type = candidate.type,
            layer = candidate.layer,
            projectId = candidate.projectId,
            tags = candidate.tags,
            importance = candidate.importance,
            confidence = candidate.confidence,
            status = candidate.status,
            origin = MemoryWire.ORIGIN_AGENT,
            why = candidate.why,
            sourceSessionId = sessionId,
            createdAt = now,
            updatedAt = now,
            version = 1,
        )

    private suspend fun complete(system: String, user: String, maxTokens: Int): String? {
        val selected = settingsRepository.chatModel.first()
            ?: settingsRepository.selectedModel.first()
            ?: return null
        val provider = providerRepository.resolve(selected.providerId) ?: return null
        return withTimeout(60_000) {
            llmClient.chat(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = selected.modelId,
                messages = listOf(
                    ChatMessageDto("system", listOf(ContentPart.text(system))),
                    ChatMessageDto("user", listOf(ContentPart.text(user))),
                ),
                protocol = provider.protocol,
                temperature = 0.2,
                maxTokens = maxTokens,
            ).getOrNull()?.content
        }
    }
}
