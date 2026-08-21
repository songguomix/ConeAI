package com.cone.agent.agent

import com.cone.agent.data.repository.MemoryCategory
import com.cone.agent.data.repository.MemoryItem
import com.cone.agent.data.repository.MemoryMatcher
import com.cone.agent.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which remembered facts a given turn should see, and renders them for a prompt.
 *
 * Shared by 问答 and 智能体 because both need the same judgement from opposite ends: the Q&A prompt
 * has room for a broad profile, while an agent step is already carrying a screen dump, an app list
 * and a task history and can only afford a few lines. Keeping the ranking here means the two can
 * differ in budget without drifting in behaviour.
 */
@Singleton
class MemoryContext @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Facts worth putting in front of the model for [query], honouring the master switch. */
    suspend fun relevant(query: String, limit: Int): List<MemoryItem> {
        if (!memoryRepository.enabled.first()) return emptyList()
        val all = memoryRepository.memories.first()
        if (all.isEmpty()) return emptyList()
        if (all.size <= limit) return all

        val (always, candidates) = all.partition { it.categoryEnum == MemoryCategory.IDENTITY }
        val budget = (limit - always.size).coerceAtLeast(0)
        val picked = candidates
            .map { item ->
                // Coverage is the share of *this fact's* own bigrams the question mentions, so a
                // long rambling memory no longer outranks a short precise one just by having more
                // characters to collide with. See [MemoryMatcher].
                val coverage = MemoryMatcher.coverage(query, item.content)
                // The dimension a fact belongs to counts as standing evidence of how often it
                // matters, but topical evidence outweighs it: scaled this way, a third of a fact
                // being named in the question already beats any category bonus, so a directly
                // on-topic 其他 note wins over an unrelated 偏好 one.
                item to (coverage * COVERAGE_GAIN + item.categoryEnum.weight)
            }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Double>> { it.second }
                    .thenByDescending { it.first.updatedAt },
            )
            .take(budget)
            .map { it.first }

        // Only a *competitive* selection says anything about usefulness. When everything fits, every
        // fact would score a point every turn and the counter would just re-measure age — which is
        // precisely what eviction already has `updatedAt` for.
        noteUsed(picked.map { it.id })
        return (always + picked).sortedBy { it.createdAt }
    }

    /** Grouped and labelled: the model finds the relevant line in a titled section far more
     *  reliably than in one long undifferentiated list. */
    fun format(memories: List<MemoryItem>): String = buildString {
        memories.groupBy { it.categoryEnum }
            .toSortedMap(compareBy { it.ordinal })
            .forEach { (category, items) ->
                appendLine("[${label(category)}]")
                items.forEach { appendLine("- ${it.content}") }
            }
    }

    /** Records that these facts earned their place, so eviction can favour what keeps being picked. */
    private fun noteUsed(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch { runCatching { memoryRepository.markUsed(ids) } }
    }

    private fun label(category: MemoryCategory): String = when (category) {
        MemoryCategory.IDENTITY -> "身份"
        MemoryCategory.PREFERENCE -> "偏好"
        MemoryCategory.RELATIONSHIP -> "关系"
        MemoryCategory.HABIT -> "习惯"
        MemoryCategory.GOAL -> "目标"
        MemoryCategory.OTHER -> "其他"
    }

    companion object {
        /**
         * Turns a 0..1 coverage into a score that competes with [MemoryCategory.weight] (0..3 once
         * 身份 is set aside). At 10, roughly a third of a fact appearing in the question outweighs
         * every category bonus.
         */
        private const val COVERAGE_GAIN = 10.0

        /** Q&A budget: room for a broad profile. */
        const val CHAT_LIMIT = 24

        /**
         * Agent budget, deliberately much tighter: that prompt already carries the screen dump, the
         * installed-app list and the task history, and a fact the step can't use costs attention it
         * needs for coordinates.
         */
        const val AGENT_LIMIT = 8
    }
}
