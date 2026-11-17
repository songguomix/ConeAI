package com.cone.agent.data.remote

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

const val FREE_WEEKLY_TOKEN_LIMIT = 2_000_000_000L

private val Context.freeUsageStore by preferencesDataStore(name = "cone_free_usage")

@Singleton
internal class FreeUsage @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.freeUsageStore

    val weeklyUsed: Flow<Long> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.record().forWeek(freeWeekStart(LocalDate.now())).used }
        .distinctUntilChanged()

    suspend fun current(): Long = store.data.first().record().forWeek(freeWeekStart(LocalDate.now())).used

    suspend fun update(resetAt: Instant? = null, tokens: Int = 0) {
        store.edit { prefs ->
            val current = prefs.record()
            val updated = current.forWeek(freeWeekStart(LocalDate.now()), resetAt, tokens)
            if (current != updated) {
                prefs[WEEK_START] = updated.weekStart
                prefs[USED] = updated.used
                updated.resetAt?.let { prefs[RESET_AT] = it.toString() }
            }
        }
    }

    private fun Preferences.record() = FreeUsageRecord(
        weekStart = this[WEEK_START] ?: Long.MIN_VALUE,
        used = this[USED] ?: 0L,
        resetAt = parseFreeInstant(this[RESET_AT]),
    )

    private companion object {
        val WEEK_START = longPreferencesKey("week_start")
        val USED = longPreferencesKey("used")
        val RESET_AT = stringPreferencesKey("reset_at")
    }
}

internal fun freeWeekStart(date: LocalDate): Long =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()

internal data class FreeUsageRecord(val weekStart: Long, val used: Long, val resetAt: Instant?) {
    fun forWeek(weekStart: Long, resetAt: Instant? = null, tokens: Int = 0): FreeUsageRecord {
        val newerReset = resetAt?.takeIf { this.resetAt == null || it > this.resetAt }
        val previous = if (this.weekStart == weekStart && newerReset == null) used.coerceAtLeast(0) else 0L
        val added = tokens.coerceAtLeast(0).toLong()
        return FreeUsageRecord(weekStart, previous.coerceAtMost(Long.MAX_VALUE - added) + added, newerReset ?: this.resetAt)
    }
}
