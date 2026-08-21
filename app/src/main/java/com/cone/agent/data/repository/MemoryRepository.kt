package com.cone.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.memoryStore by preferencesDataStore(name = "cone_memory")

/**
 * What kind of fact a memory holds. Both Gemini's "Saved Info" and ChatGPT's memory converge on the
 * same insight — a user profile isn't a flat list, it's a few stable dimensions — and the grouping
 * pays off three times over: the settings list becomes scannable, the prompt can be organised
 * instead of dumped, and [MemoryCategory.IDENTITY] facts can be treated as always-relevant while
 * the rest are retrieved on demand.
 */
enum class MemoryCategory(val wire: String, val weight: Int) {
    /** Who the user is: name, role, where they live. Always worth injecting. */
    IDENTITY("identity", 6),
    /** Likes, dislikes, dietary restrictions, style preferences. */
    PREFERENCE("preference", 3),
    /** Family, colleagues, pets — people and beings in their life. */
    RELATIONSHIP("relationship", 3),
    /** Routines and recurring context: work hours, commute, tools they use. */
    HABIT("habit", 2),
    /** What they're working towards, deadlines, plans. */
    GOAL("goal", 2),
    OTHER("other", 0);

    companion object {
        fun from(value: String?): MemoryCategory =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * One remembered fact about the user (e.g. 「用户是素食主义者」).
 *
 * Every field after [content] has a default so memories written by older builds keep decoding —
 * this store is the one place in the app where losing data is unrecoverable, since the facts were
 * never anywhere else.
 */
@Serializable
data class MemoryItem(
    val id: Long,
    val content: String,
    val createdAt: Long,
    val category: String = MemoryCategory.OTHER.wire,
    /** Last time this fact was written or superseded — shown as "更新于" in settings. */
    val updatedAt: Long = createdAt,
    /** How many turns have drawn on it; ties are broken by this when trimming. */
    val useCount: Int = 0,
) {
    val categoryEnum: MemoryCategory get() = MemoryCategory.from(category)
}

/** What a write actually did, so the caller can say the right thing (or nothing) to the user. */
enum class MemoryWrite {
    /** A fact we did not have before. */
    ADDED,

    /** A better-worded version of something we already knew; the existing entry was rewritten. */
    REFINED,

    /** Already known, in these words or near enough. Nothing changed. */
    DUPLICATE,

    /** Empty, or carrying something that must never be persisted. */
    REJECTED,
}

/**
 * 问答记忆：facts the user shared in Q&A, persisted **on-device only** and injected into the Q&A
 * system prompt so answers are personalised.
 *
 * Captured automatically after each turn (see MemoryExtractor) rather than only when the user says
 * "记住…", managed by the user in Settings (toggle / edit / per-item delete / clear all). Kept in
 * its own DataStore file — deliberately not a Room table, so the chat database's destructive
 * migration fallback can never wipe memories (and vice versa).
 */
@Singleton
class MemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.memoryStore
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(MemoryItem.serializer())

    /** Master switch for Q&A memory (default on; incognito Q&A bypasses memory regardless). */
    val enabled: Flow<Boolean> = store.data.map { it[KEY_ENABLED] ?: true }

    val memories: Flow<List<MemoryItem>> = store.data.map { decode(it[KEY_ITEMS]) }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    /**
     * Saves one fact. Past [MAX_ITEMS] the least useful entries are evicted, so the prompt footprint
     * stays bounded.
     *
     * Exact-match was not enough of a duplicate check. Capture is automatic and the extractor is a
     * language model, so the same fact arrives worded differently across turns — 「用户喜欢喝咖啡」
     * then 「用户爱喝咖啡」 — and both used to be stored, each spending prompt budget to say the same
     * thing. Near-identical wording is now recognised locally: a restatement is dropped, and one that
     * is clearly more specific rewrites the vaguer entry rather than sitting next to it.
     */
    suspend fun add(content: String, category: MemoryCategory = MemoryCategory.OTHER): MemoryWrite {
        val fact = content.trim()
        if (fact.isEmpty() || isSensitive(fact)) return MemoryWrite.REJECTED
        var result = MemoryWrite.ADDED
        store.edit { prefs ->
            val items = decode(prefs[KEY_ITEMS])
            if (items.any { it.content == fact }) {
                result = MemoryWrite.DUPLICATE
                return@edit
            }
            val now = System.currentTimeMillis()
            // Containment, not similarity: the duplicate that actually shows up is the same fact
            // restated with more detail, and the direction of the containment says which wording to
            // keep. Anything below the threshold is treated as a genuinely new fact — merging two
            // real facts loses information the user can never get back, so this errs towards keeping
            // an extra line.
            val near = items.firstOrNull { MemoryMatcher.containment(it.content, fact) >= NEAR_DUPLICATE }
            if (near != null) {
                if (fact.length > near.content.length) {
                    result = MemoryWrite.REFINED
                    prefs[KEY_ITEMS] = encode(
                        items.map {
                            if (it.id == near.id) {
                                it.copy(content = fact, category = category.wire, updatedAt = now)
                            } else {
                                it
                            }
                        },
                    )
                } else {
                    // We already hold this fact in equal or greater detail.
                    result = MemoryWrite.DUPLICATE
                }
                return@edit
            }
            val id = maxOf(now, (items.maxOfOrNull { it.id } ?: 0L) + 1)
            val item = MemoryItem(id, fact, now, category.wire)
            prefs[KEY_ITEMS] = encode(evict(items + item))
        }
        return result
    }

    /** @see MemoryPolicy.isSensitive */
    fun isSensitive(text: String): Boolean = MemoryPolicy.isSensitive(text)

    /**
     * Replaces [oldId] with [content] in place — the operation an append-only store can't express.
     * Without it "我搬到上海了" leaves the earlier 「用户住在北京」 sitting in the prompt beside it, and
     * the model has to guess which of two flatly contradictory facts is current. Keeping the
     * original id and creation time preserves the fact's history while marking it freshly updated.
     */
    suspend fun supersede(oldId: Long, content: String, category: MemoryCategory): Boolean {
        val fact = content.trim()
        if (fact.isEmpty() || isSensitive(fact)) return false
        var replaced = false
        store.edit { prefs ->
            val items = decode(prefs[KEY_ITEMS])
            val old = items.firstOrNull { it.id == oldId } ?: return@edit
            if (old.content == fact) return@edit
            replaced = true
            prefs[KEY_ITEMS] = encode(
                items.map {
                    if (it.id == oldId) {
                        it.copy(content = fact, category = category.wire, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                },
            )
        }
        return replaced
    }

    /**
     * User-initiated edit from the settings list.
     *
     * Deliberately not passed through [isSensitive], unlike [add] and [supersede]. Those two are
     * reached by a model deciding on its own what to keep, which is what the filter exists to
     * contain; this is the user typing into their own memory list with the entry in front of them.
     */
    suspend fun update(id: Long, content: String) {
        val fact = content.trim()
        if (fact.isEmpty()) return
        store.edit { prefs ->
            prefs[KEY_ITEMS] = encode(
                decode(prefs[KEY_ITEMS]).map {
                    if (it.id == id) it.copy(content = fact, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    /** Records that these facts were actually used, so eviction can favour what earns its place. */
    suspend fun markUsed(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val hit = ids.toSet()
        store.edit { prefs ->
            prefs[KEY_ITEMS] = encode(
                decode(prefs[KEY_ITEMS]).map {
                    if (it.id in hit) it.copy(useCount = it.useCount + 1) else it
                },
            )
        }
    }

    /** Deletes every memory containing [keyword] (case-insensitive); returns what was removed. */
    suspend fun forget(keyword: String): List<String> {
        val needle = keyword.trim()
        if (needle.isEmpty()) return emptyList()
        var removed: List<String> = emptyList()
        store.edit { prefs ->
            val items = decode(prefs[KEY_ITEMS])
            val (hit, keep) = items.partition { it.content.contains(needle, ignoreCase = true) }
            removed = hit.map { it.content }
            if (hit.isNotEmpty()) prefs[KEY_ITEMS] = encode(keep)
        }
        return removed
    }

    suspend fun remove(id: Long) {
        store.edit { prefs ->
            prefs[KEY_ITEMS] = encode(decode(prefs[KEY_ITEMS]).filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY_ITEMS) }
    }

    /**
     * Trims to [MAX_ITEMS] when full. Plain age is the wrong yardstick once capture is automatic —
     * "用户叫张伟" is learned in the first conversation and must outlive a hundred passing details.
     *
     * The dimension decides first, then how often the fact has actually been picked, and only then
     * recency. Ranking on use before category would quietly drop a dietary restriction that simply
     * hasn't come up yet in favour of a pile of newer 其他 notes, since both sit at zero uses and
     * miscellany is always the more recent — exactly the fact a user would be alarmed to lose.
     */
    private fun evict(items: List<MemoryItem>): List<MemoryItem> {
        if (items.size <= MAX_ITEMS) return items
        return items
            .sortedWith(
                compareByDescending<MemoryItem> { it.categoryEnum.weight }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.updatedAt },
            )
            .take(MAX_ITEMS)
            .sortedBy { it.createdAt }
    }

    private fun decode(raw: String?): List<MemoryItem> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun encode(items: List<MemoryItem>): String = json.encodeToString(listSerializer, items)

    private companion object {
        const val MAX_ITEMS = 80

        /**
         * Containment above which two facts are the same thing said twice. Verified against the
         * pairs that must NOT merge (花生/海鲜过敏, 女儿/儿子, 北京/上海 all score below 0.4) as well as
         * the ones that should (a detail-added restatement scores above 0.8).
         */
        const val NEAR_DUPLICATE = 0.8

        val KEY_ENABLED = booleanPreferencesKey("memory_enabled")
        val KEY_ITEMS = stringPreferencesKey("memory_items")
    }
}
