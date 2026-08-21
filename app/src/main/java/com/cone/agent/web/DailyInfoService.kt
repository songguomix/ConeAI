package com.cone.agent.web

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everyday, free-of-charge information the agent can read **directly as text** — no API keys, no
 * paid services. Each method returns a compact, model-ready string (or a short failure line the
 * model can act on by falling back to [SearchEngine] web search).
 *
 * Sources are all keyless public endpoints:
 *  - Weather:  wttr.in (`?format=j1` JSON; blank city → IP geolocation).
 *  - News:     Google News RSS (top headlines or a topic search).
 *  - Exchange: open.er-api.com (daily fiat rates, any ISO currency pair).
 *  - Crypto:   CoinGecko simple-price (common coins by name/symbol).
 *  - Stock:    腾讯行情 qt.gtimg.cn (A股 sh/sz、港股 hk、美股 us 实时报价).
 *  - Wiki:     Wikipedia REST page summary (language follows the app locale).
 *  - Holiday:  Nager.Date public holidays (country follows the app locale).
 *  - Route:    OSM Nominatim (geocoding) + the OSRM demo router (driving distance & duration).
 *  - Music:    iTunes Search API (song / artist / album / 30s preview link).
 *  - Lyrics:   LRCLIB (community lyrics, keyless).
 *  - IP:       ipwho.is (public IP, geo, ISP over https).
 *  - Time:     worldtimeapi.org (current time in any IANA timezone; blank → device local time).
 *  - Air:      Open-Meteo air-quality (AQI / PM2.5 / PM10; city via Nominatim, blank → IP locate).
 *  - Book:     Open Library search (title / author / year).
 *  - Dict:     dictionaryapi.dev (English word definitions + phonetics).
 *  - Gold:     gold-api.com (spot gold / silver in USD).
 */
@Singleton
class DailyInfoService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val zh: Boolean get() = Locale.getDefault().language.equals("zh", ignoreCase = true)

    /** Current conditions + a 2-day outlook for [city] (blank → device's IP location). */
    suspend fun weather(city: String?): String = withContext(Dispatchers.IO) {
        val place = city?.trim().orEmpty()
        val path = if (place.isBlank()) "" else URLEncoder.encode(place, "UTF-8")
        val lang = if (zh) "zh" else "en"
        val body = httpGet("https://wttr.in/$path?format=j1&lang=$lang", ua = "curl/8.4.0")
            ?: return@withContext fail(place.ifBlank { if (zh) "当前位置" else "your location" })
        runCatching { formatWeather(JSONObject(body), place) }
            .getOrElse { fail(place.ifBlank { if (zh) "当前位置" else "your location" }) }
    }

    /** Top news headlines, or headlines about [topic] if given. */
    suspend fun news(topic: String?): String = withContext(Dispatchers.IO) {
        val t = topic?.trim().orEmpty()
        val edition = if (zh) "hl=zh-CN&gl=CN&ceid=CN:zh-Hans" else "hl=en-US&gl=US&ceid=US:en"
        val url = if (t.isBlank()) {
            "https://news.google.com/rss?$edition"
        } else {
            "https://news.google.com/rss/search?q=${URLEncoder.encode(t, "UTF-8")}&$edition"
        }
        val body = httpGet(url, ua = BROWSER_UA)
        val titles = body?.let { parseRssTitles(it) }.orEmpty()
        if (titles.isEmpty()) {
            if (zh) "获取新闻失败，可改用 web_search 搜索「${t.ifBlank { "今日新闻" }}」。"
            else "Couldn't fetch news; try web_search for \"${t.ifBlank { "today's news" }}\" instead."
        } else {
            val header = if (t.isBlank()) {
                if (zh) "今日头条新闻：" else "Top headlines:"
            } else {
                if (zh) "「$t」相关新闻：" else "News about \"$t\":"
            }
            header + "\n" + titles.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        }
    }

    /**
     * Fiat exchange rate. [query] holds two ISO codes and an optional amount in any order
     * ("USD CNY"、"100 usd jpy"); blank → USD 对主要货币。Rates refresh daily (open.er-api.com).
     */
    suspend fun exchangeRate(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        val codes = Regex("[A-Za-z]{3}").findAll(q).map { it.value.uppercase() }.toList()
        val amount = Regex("\\d+(?:\\.\\d+)?").find(q)?.value?.toDoubleOrNull() ?: 1.0
        val from = codes.getOrNull(0) ?: "USD"
        val to = codes.getOrNull(1)
        val body = httpGet("https://open.er-api.com/v6/latest/$from", ua = BROWSER_UA)
            ?: return@withContext toolFail(if (zh) "汇率" else "exchange rates")
        runCatching {
            val json = JSONObject(body)
            check(json.optString("result") == "success")
            val rates = json.getJSONObject("rates")
            val date = json.optString("time_last_update_utc").substringBefore(" 0").trim()
            if (to != null && rates.has(to)) {
                val rate = rates.getDouble(to)
                val converted = "%.4f".format(amount * rate).trimEnd('0').trimEnd('.')
                if (zh) "汇率（$date，每日更新）：1 $from = ${"%.4f".format(rate)} $to；$amount $from ≈ $converted $to"
                else "Exchange rate ($date, daily): 1 $from = ${"%.4f".format(rate)} $to; $amount $from ≈ $converted $to"
            } else {
                val majors = listOf("CNY", "EUR", "JPY", "GBP", "HKD", "KRW", "USD")
                    .filter { it != from && rates.has(it) }
                    .joinToString("，") { "$it ${"%.4f".format(rates.getDouble(it))}" }
                if (zh) "1 $from 兑主要货币（$date）：$majors" else "1 $from in major currencies ($date): $majors"
            }
        }.getOrElse { toolFail(if (zh) "汇率" else "exchange rates") }
    }

    /** Crypto spot price via CoinGecko. [query]: coin name/symbol (BTC、以太坊…); blank → BTC+ETH. */
    suspend fun crypto(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim()?.lowercase().orEmpty()
        val ids = COIN_IDS[q] ?: q.ifBlank { "bitcoin,ethereum" }
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=" +
            URLEncoder.encode(ids, "UTF-8") + "&vs_currencies=usd,cny&include_24hr_change=true"
        val body = httpGet(url, ua = BROWSER_UA)
            ?: return@withContext toolFail(if (zh) "加密货币价格" else "crypto prices")
        runCatching {
            val json = JSONObject(body)
            val lines = json.keys().asSequence().mapNotNull { id ->
                val c = json.optJSONObject(id) ?: return@mapNotNull null
                val change = c.optDouble("usd_24h_change", Double.NaN)
                val delta = if (change.isNaN()) "" else "（24h ${"%+.2f".format(change)}%）"
                "$id：$${c.optDouble("usd")} / ¥${c.optDouble("cny")}$delta"
            }.toList()
            check(lines.isNotEmpty())
            (if (zh) "加密货币现价：\n" else "Crypto prices:\n") + lines.joinToString("\n")
        }.getOrElse { toolFail(if (zh) "加密货币价格" else "crypto prices") }
    }

    /**
     * Real-time stock quote via 腾讯行情（keyless）. [query]: 带市场前缀的代码 —
     * A股 sh600519 / sz000001，港股 hk00700，美股 usAAPL。
     */
    suspend fun stock(query: String?): String = withContext(Dispatchers.IO) {
        val code = query?.trim()?.lowercase()?.replace(Regex("[^a-z0-9.]"), "").orEmpty()
        if (!Regex("^(sh|sz|hk|us)[a-z0-9.]+$").matches(code)) {
            return@withContext if (zh) {
                "stock 需要带市场前缀的代码（如 sh600519、sz000001、hk00700、usAAPL）；不知道代码可先用 web_search 查。"
            } else {
                "stock needs a market-prefixed code (sh600519, sz000001, hk00700, usAAPL); use web_search to find it first."
            }
        }
        val body = httpGet("https://qt.gtimg.cn/q=$code", ua = BROWSER_UA, charset = "GBK")
            ?: return@withContext toolFail(if (zh) "股票行情" else "stock quotes")
        runCatching {
            val fields = body.substringAfter('"').substringBefore('"').split('~')
            check(fields.size > 32)
            val name = fields[1]
            val price = fields[3]
            val prevClose = fields[4]
            val changePct = fields.getOrNull(32).orEmpty()
            if (zh) "$name（$code）：现价 $price，昨收 $prevClose，涨跌 $changePct%"
            else "$name ($code): price $price, prev close $prevClose, change $changePct%"
        }.getOrElse { toolFail(if (zh) "股票行情" else "stock quotes") }
    }

    /** Encyclopedia summary via the Wikipedia REST API, in the app language. */
    suspend fun wiki(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        if (q.isBlank()) return@withContext if (zh) "wiki 需要一个词条名。" else "wiki needs a topic."
        val lang = when {
            zh -> "zh"
            Locale.getDefault().language.equals("ja", ignoreCase = true) -> "ja"
            else -> "en"
        }
        val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/" +
            URLEncoder.encode(q.replace(' ', '_'), "UTF-8")
        val body = httpGet(url, ua = BROWSER_UA)
            ?: return@withContext toolFail(if (zh) "「$q」百科" else "wiki for \"$q\"")
        runCatching {
            val json = JSONObject(body)
            val extract = json.optString("extract").takeIf { it.isNotBlank() } ?: error("no extract")
            val title = json.optString("title", q)
            (if (zh) "维基百科「$title」：\n" else "Wikipedia \"$title\":\n") + extract.take(1200)
        }.getOrElse { toolFail(if (zh) "「$q」百科" else "wiki for \"$q\"") }
    }

    /** Public holidays for this year via Nager.Date; country follows the app locale. */
    suspend fun holiday(query: String?): String = withContext(Dispatchers.IO) {
        val year = Regex("(20\\d{2})").find(query.orEmpty())?.value
            ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        val country = when {
            zh -> "CN"
            Locale.getDefault().language.equals("ja", ignoreCase = true) -> "JP"
            else -> "US"
        }
        val body = httpGet("https://date.nager.at/api/v3/PublicHolidays/$year/$country", ua = BROWSER_UA)
            ?: return@withContext toolFail(if (zh) "节假日" else "public holidays")
        runCatching {
            val arr = org.json.JSONArray(body)
            val lines = (0 until arr.length()).map { i ->
                val h = arr.getJSONObject(i)
                "${h.optString("date")} ${h.optString("localName")}"
            }
            check(lines.isNotEmpty())
            (if (zh) "$year 年 $country 法定节假日：\n" else "$year public holidays ($country):\n") +
                lines.joinToString("\n")
        }.getOrElse { toolFail(if (zh) "节假日" else "public holidays") }
    }

    /**
     * Driving-route summary (distance + estimated duration) between "起点 到 终点" — the read-only
     * companion to the agent's `navigate` action, so 问答 can answer "多远/多久" without a map app.
     * Free keyless chain: Nominatim geocodes both ends, the OSRM demo server computes the route.
     */
    suspend fun route(query: String?): String = withContext(Dispatchers.IO) {
        val parts = query.orEmpty()
            .split(Regex("\\s*(?:到|至|->|→|\\bto\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) {
            return@withContext if (zh) {
                "route 需要「起点 到 终点」格式，如 \"北京西站 到 首都机场\"。"
            } else {
                "route needs \"origin to destination\" (e.g. \"Times Square to JFK Airport\")."
            }
        }
        val from = geocode(parts[0])
            ?: return@withContext toolFail(if (zh) "起点「${parts[0]}」定位" else "geocoding \"${parts[0]}\"")
        val to = geocode(parts[1])
            ?: return@withContext toolFail(if (zh) "终点「${parts[1]}」定位" else "geocoding \"${parts[1]}\"")
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.second},${from.first};${to.second},${to.first}?overview=false"
        val body = httpGet(url, ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "路线" else "the route")
        runCatching {
            val r = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val km = "%.1f".format(r.getDouble("distance") / 1000.0)
            val mins = (r.getDouble("duration") / 60.0).toInt().coerceAtLeast(1)
            val duration = if (mins >= 90) {
                if (zh) "${mins / 60} 小时 ${mins % 60} 分钟" else "${mins / 60} h ${mins % 60} min"
            } else {
                if (zh) "$mins 分钟" else "$mins min"
            }
            if (zh) "「${parts[0]}」→「${parts[1]}」驾车路线：约 $km 公里，预计 $duration（不含实时路况）。"
            else "Driving \"${parts[0]}\" → \"${parts[1]}\": about $km km, roughly $duration (traffic not included)."
        }.getOrElse { toolFail(if (zh) "路线" else "the route") }
    }

    /** Place name → (lat, lon) via Nominatim (keyless; identifying UA per its usage policy). */
    private fun geocode(place: String): Pair<Double, Double>? {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1" +
            "&accept-language=${if (zh) "zh" else "en"}&q=" + URLEncoder.encode(place, "UTF-8")
        val body = httpGet(url, ua = APP_UA) ?: return null
        val first = runCatching { org.json.JSONArray(body).optJSONObject(0) }.getOrNull() ?: return null
        val lat = first.optString("lat").toDoubleOrNull() ?: return null
        val lon = first.optString("lon").toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Song lookup via the iTunes Search API — info only; playback is the play_music action's job. */
    suspend fun music(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        if (q.isBlank()) return@withContext if (zh) "music 需要歌名或歌手。" else "music needs a song or artist."
        val country = if (zh) "CN" else "US"
        val url = "https://itunes.apple.com/search?media=music&limit=5&country=$country&term=" +
            URLEncoder.encode(q, "UTF-8")
        val body = httpGet(url, ua = BROWSER_UA)
            ?: return@withContext toolFail(if (zh) "歌曲信息" else "song info")
        runCatching {
            val results = JSONObject(body).getJSONArray("results")
            check(results.length() > 0)
            val lines = (0 until results.length()).map { i ->
                val t = results.getJSONObject(i)
                val mins = t.optLong("trackTimeMillis") / 60000
                val secs = t.optLong("trackTimeMillis") / 1000 % 60
                buildString {
                    append("${i + 1}. ${t.optString("trackName")} — ${t.optString("artistName")}")
                    t.optString("collectionName").takeIf { it.isNotBlank() }?.let { append("《$it》") }
                    if (mins > 0) append(" ${mins}:${"%02d".format(secs)}")
                    t.optString("previewUrl").takeIf { it.isNotBlank() }?.let { append(if (zh) " 试听: $it" else " preview: $it") }
                }
            }
            (if (zh) "「$q」歌曲搜索结果：\n" else "Songs for \"$q\":\n") + lines.joinToString("\n")
        }.getOrElse { toolFail(if (zh) "歌曲信息" else "song info") }
    }

    /** Lyrics via LRCLIB. [query]: 歌名（可加歌手）. */
    suspend fun lyrics(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        if (q.isBlank()) return@withContext if (zh) "lyrics 需要歌名（可加歌手）。" else "lyrics needs a song title."
        val body = httpGet("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"), ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "歌词" else "lyrics")
        runCatching {
            val first = org.json.JSONArray(body).getJSONObject(0)
            val words = first.optString("plainLyrics").takeIf { it.isNotBlank() } ?: error("instrumental")
            val head = "${first.optString("trackName")} — ${first.optString("artistName")}"
            (if (zh) "「$head」歌词：\n" else "Lyrics for \"$head\":\n") + words.take(1500)
        }.getOrElse { toolFail(if (zh) "「$q」歌词" else "lyrics for \"$q\"") }
    }

    /** Public IP + geolocation + ISP via ipwho.is (keyless https). */
    suspend fun ipInfo(): String = withContext(Dispatchers.IO) {
        val body = httpGet("https://ipwho.is/", ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "IP 信息" else "IP info")
        runCatching {
            val json = JSONObject(body)
            check(json.optBoolean("success", true))
            val isp = json.optJSONObject("connection")?.optString("isp").orEmpty()
            if (zh) {
                "本机公网 IP：${json.optString("ip")}\n归属地：${json.optString("country")} ${json.optString("region")} ${json.optString("city")}\n运营商：$isp"
            } else {
                "Public IP: ${json.optString("ip")}\nLocation: ${json.optString("city")}, ${json.optString("region")}, ${json.optString("country")}\nISP: $isp"
            }
        }.getOrElse { toolFail(if (zh) "IP 信息" else "IP info") }
    }

    /** Current time in an IANA timezone（如 Asia/Tokyo）; blank → device local time, no network. */
    suspend fun worldTime(query: String?): String = withContext(Dispatchers.IO) {
        val zone = query?.trim().orEmpty()
        if (zone.isBlank()) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss（EEEE, zzzz）", Locale.getDefault())
            return@withContext (if (zh) "本机当前时间：" else "Local time: ") + fmt.format(java.util.Date())
        }
        val body = httpGet("https://worldtimeapi.org/api/timezone/" + zone.replace(" ", "_"), ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "「$zone」时间" else "time for \"$zone\"")
        runCatching {
            val json = JSONObject(body)
            val dt = json.getString("datetime") // e.g. 2026-07-10T18:00:00.123+09:00
            val pretty = dt.substring(0, 19).replace('T', ' ')
            if (zh) "$zone 当前时间：$pretty（UTC${json.optString("utc_offset")}）"
            else "Current time in $zone: $pretty (UTC${json.optString("utc_offset")})"
        }.getOrElse { toolFail(if (zh) "「$zone」时间" else "time for \"$zone\"") }
    }

    /** Air quality (AQI / PM2.5 / PM10) via Open-Meteo; blank city → locate by public IP. */
    suspend fun airQuality(city: String?): String = withContext(Dispatchers.IO) {
        val place = city?.trim().orEmpty()
        val where: Pair<Double, Double>? = if (place.isBlank()) ipLatLon() else geocode(place)
        val (lat, lon) = where
            ?: return@withContext toolFail(if (zh) "「${place.ifBlank { "当前位置" }}」定位" else "locating \"$place\"")
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
            "&current=us_aqi,pm2_5,pm10,ozone"
        val body = httpGet(url, ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "空气质量" else "air quality")
        runCatching {
            val cur = JSONObject(body).getJSONObject("current")
            val aqi = cur.optInt("us_aqi")
            val level = when {
                aqi <= 50 -> if (zh) "优" else "Good"
                aqi <= 100 -> if (zh) "良" else "Moderate"
                aqi <= 150 -> if (zh) "轻度污染" else "Unhealthy for sensitive"
                aqi <= 200 -> if (zh) "中度污染" else "Unhealthy"
                aqi <= 300 -> if (zh) "重度污染" else "Very unhealthy"
                else -> if (zh) "严重污染" else "Hazardous"
            }
            val name = place.ifBlank { if (zh) "当前位置" else "your location" }
            if (zh) "$name 空气质量：AQI(US) $aqi（$level），PM2.5 ${cur.optDouble("pm2_5")}，PM10 ${cur.optDouble("pm10")}，臭氧 ${cur.optDouble("ozone")}（µg/m³）"
            else "Air quality for $name: US AQI $aqi ($level), PM2.5 ${cur.optDouble("pm2_5")}, PM10 ${cur.optDouble("pm10")}, O₃ ${cur.optDouble("ozone")} (µg/m³)"
        }.getOrElse { toolFail(if (zh) "空气质量" else "air quality") }
    }

    /** Book search via Open Library (title / author / first-publish year). */
    suspend fun book(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        if (q.isBlank()) return@withContext if (zh) "book 需要书名或作者。" else "book needs a title or author."
        val body = httpGet(
            "https://openlibrary.org/search.json?limit=5&fields=title,author_name,first_publish_year&q=" +
                URLEncoder.encode(q, "UTF-8"),
            ua = APP_UA,
        ) ?: return@withContext toolFail(if (zh) "书籍信息" else "book info")
        runCatching {
            val docs = JSONObject(body).getJSONArray("docs")
            check(docs.length() > 0)
            val lines = (0 until docs.length()).map { i ->
                val d = docs.getJSONObject(i)
                val authors = d.optJSONArray("author_name")?.let { arr ->
                    (0 until minOf(arr.length(), 2)).joinToString("、") { arr.getString(it) }
                }.orEmpty()
                val year = d.optInt("first_publish_year").takeIf { it > 0 }?.let { "（$it）" }.orEmpty()
                "${i + 1}. ${d.optString("title")}${if (authors.isBlank()) "" else " — $authors"}$year"
            }
            (if (zh) "「$q」书籍搜索结果：\n" else "Books for \"$q\":\n") + lines.joinToString("\n")
        }.getOrElse { toolFail(if (zh) "书籍信息" else "book info") }
    }

    /** English dictionary via dictionaryapi.dev: phonetics + per-part-of-speech definitions. */
    suspend fun dict(query: String?): String = withContext(Dispatchers.IO) {
        val word = query?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        if (word.isBlank() || word.any { it.code > 0x7F }) {
            return@withContext if (zh) "dict 需要一个英文单词。" else "dict needs an English word."
        }
        val body = httpGet(
            "https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word.lowercase(), "UTF-8"),
            ua = APP_UA,
        ) ?: return@withContext toolFail(if (zh) "「$word」词典释义" else "definition of \"$word\"")
        runCatching {
            val entry = org.json.JSONArray(body).getJSONObject(0)
            val phonetic = entry.optString("phonetic").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val meanings = entry.getJSONArray("meanings")
            val lines = (0 until minOf(meanings.length(), 4)).map { i ->
                val m = meanings.getJSONObject(i)
                val defs = m.getJSONArray("definitions")
                val def = defs.getJSONObject(0).optString("definition")
                "- ${m.optString("partOfSpeech")}: $def"
            }
            "$word$phonetic\n" + lines.joinToString("\n")
        }.getOrElse { toolFail(if (zh) "「$word」词典释义" else "definition of \"$word\"") }
    }

    /** Spot gold / silver (USD) via gold-api.com; also converted to per-gram. */
    suspend fun gold(query: String?): String = withContext(Dispatchers.IO) {
        val q = query?.trim()?.lowercase().orEmpty()
        val (symbol, label) = when {
            q.contains("银") || q.contains("silver") || q.contains("xag") ->
                "XAG" to if (zh) "白银" else "Silver"
            else -> "XAU" to if (zh) "黄金" else "Gold"
        }
        val body = httpGet("https://api.gold-api.com/price/$symbol", ua = APP_UA)
            ?: return@withContext toolFail(if (zh) "${label}价格" else "$label price")
        runCatching {
            val price = JSONObject(body).getDouble("price")
            val perGram = "%.2f".format(price / OUNCE_GRAMS)
            if (zh) "$label 现货：$${"%.2f".format(price)}/盎司（约 $$perGram/克，美元计价，可用 exchange_rate 换算人民币）"
            else "$label spot: $${"%.2f".format(price)}/oz (≈ $$perGram/g, USD)"
        }.getOrElse { toolFail(if (zh) "${label}价格" else "$label price") }
    }

    /** Device's public-IP coordinates via ipwho.is — the blank-city fallback for air quality. */
    private fun ipLatLon(): Pair<Double, Double>? {
        val body = httpGet("https://ipwho.is/", ua = APP_UA) ?: return null
        return runCatching {
            val json = JSONObject(body)
            json.getDouble("latitude") to json.getDouble("longitude")
        }.getOrNull()
    }

    private fun toolFail(what: String): String =
        if (zh) "获取${what}失败，可改用 web_search 搜索。" else "Couldn't fetch $what; try web_search instead."

    /* ---------------- formatting ---------------- */

    private fun formatWeather(json: JSONObject, requested: String): String {
        val cur = json.getJSONArray("current_condition").getJSONObject(0)
        val place = json.optJSONArray("nearest_area")
            ?.optJSONObject(0)?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value")
            ?.takeIf { it.isNotBlank() }
            ?: requested.ifBlank { if (zh) "当前位置" else "your location" }

        val temp = cur.optString("temp_C")
        val feels = cur.optString("FeelsLikeC")
        val humidity = cur.optString("humidity")
        val wind = cur.optString("windspeedKmph")
        val desc = cur.optJSONArray("lang_$LANG_KEY")?.optJSONObject(0)?.optString("value")
            ?.takeIf { it.isNotBlank() }
            ?: cur.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value").orEmpty()

        val days = json.optJSONArray("weather")
        fun dayRange(i: Int): String? {
            val d = days?.optJSONObject(i) ?: return null
            return "${d.optString("mintempC")}~${d.optString("maxtempC")}°C"
        }

        return if (zh) {
            buildString {
                append("$place 天气：\n")
                append("当前 $desc ${temp}°C（体感 ${feels}°C），湿度 ${humidity}%，风速 ${wind}km/h")
                dayRange(0)?.let { append("\n今天 $it") }
                dayRange(1)?.let { append("；明天 $it") }
            }
        } else {
            buildString {
                append("Weather for $place:\n")
                append("Now: $desc ${temp}°C (feels ${feels}°C), humidity ${humidity}%, wind ${wind}km/h")
                dayRange(0)?.let { append("\nToday $it") }
                dayRange(1)?.let { append("; tomorrow $it") }
            }
        }
    }

    /** Pulls the first [MAX_HEADLINES] <item><title> entries out of an RSS feed. */
    private fun parseRssTitles(xml: String): List<String> =
        xml.split("<item>").drop(1) // drop everything before the first item (the channel header)
            .mapNotNull { block ->
                ITEM_TITLE.find(block)?.groupValues?.get(1)?.let { cleanXml(it) }?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(MAX_HEADLINES)

    private fun cleanXml(raw: String): String = raw
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("<[^>]+>"), "")
        .trim()

    private fun fail(place: String): String =
        if (zh) "获取「$place」天气失败，可改用 web_search 搜索天气。"
        else "Couldn't fetch weather for \"$place\"; try web_search instead."

    private fun httpGet(urlStr: String, ua: String, charset: String = "UTF-8"): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(charset(charset)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private val LANG_KEY: String get() = if (zh) "zh" else "en"

    private companion object {
        const val TIMEOUT_MS = 6000
        const val MAX_HEADLINES = 8
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Identifying UA required by the Nominatim / OSRM usage policies. */
        const val APP_UA = "ConeAI/1.0 (Android)"

        /** Troy ounce → grams, for the per-gram gold/silver conversion. */
        const val OUNCE_GRAMS = 31.1035
        val ITEM_TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)

        /** Common coin names/symbols → CoinGecko ids, so 「BTC / 比特币」 both resolve. */
        val COIN_IDS = mapOf(
            "btc" to "bitcoin", "比特币" to "bitcoin", "bitcoin" to "bitcoin",
            "eth" to "ethereum", "以太坊" to "ethereum", "ethereum" to "ethereum",
            "sol" to "solana", "solana" to "solana",
            "bnb" to "binancecoin", "doge" to "dogecoin", "狗狗币" to "dogecoin",
            "xrp" to "ripple", "ada" to "cardano", "ltc" to "litecoin", "莱特币" to "litecoin",
            "usdt" to "tether", "usdc" to "usd-coin", "ton" to "the-open-network",
        )
    }
}
