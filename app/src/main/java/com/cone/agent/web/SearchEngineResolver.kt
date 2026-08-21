package com.cone.agent.web

import android.content.Context
import android.telephony.TelephonyManager
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which [SearchEngine] the built-in browser should use. A concrete user choice always wins.
 * On [SearchEngine.AUTO] we route mainland-China users to 百度 and everyone else to Google.
 *
 * Region detection is **instant and offline-first** (SIM / network operator country → locale → time
 * zone) so a search never blocks — critical because the network-IP probe is exactly what's flaky
 * inside China, where 百度 is the answer. We still fire that IP probe in the background to refine the
 * cached auto-result for the next search, honouring true "by IP" routing without ever stalling.
 */
@Singleton
class SearchEngineResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var autoCache: SearchEngine? = null

    /** The effective engine — the user's pick, or an instant region guess (refined by IP in the bg). */
    suspend fun resolve(): SearchEngine {
        SearchEngine.from(settings.searchEngine.first())?.let { return it }
        autoCache?.let { return it }
        val guess = if (isChinaRegionOffline()) SearchEngine.BAIDU else SearchEngine.GOOGLE
        autoCache = guess
        scope.launch { probeByIp()?.let { autoCache = it } }
        return guess
    }

    /** Offline, instant: is the device most likely in mainland China? */
    private fun isChinaRegionOffline(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        // Where the phone is *currently registered* is the strongest signal, then the SIM's home country.
        tm?.networkCountryIso?.takeIf { it.isNotBlank() }?.let { return it.equals("cn", ignoreCase = true) }
        tm?.simCountryIso?.takeIf { it.isNotBlank() }?.let { return it.equals("cn", ignoreCase = true) }
        Locale.getDefault().country.takeIf { it.isNotBlank() }?.let { return it.equals("CN", ignoreCase = true) }
        return TimeZone.getDefault().id in CHINA_TIME_ZONES
    }

    /** Background IP geolocation to refine [autoCache]; null on any failure (we keep the offline guess). */
    private fun probeByIp(): SearchEngine? {
        val body = runCatching {
            val conn = (URL(TRACE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val loc = body.lineSequence()
            .firstOrNull { it.startsWith("loc=") }
            ?.substringAfter("loc=")
            ?.trim()
        return when {
            loc.isNullOrBlank() -> null
            loc.equals("CN", ignoreCase = true) -> SearchEngine.BAIDU
            else -> SearchEngine.GOOGLE
        }
    }

    private companion object {
        const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        const val PROBE_TIMEOUT_MS = 2500
        val CHINA_TIME_ZONES = setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi", "PRC")
    }
}
