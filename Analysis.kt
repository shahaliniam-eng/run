package com.example.ethsignal

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

data class Extras(
    val btc: List<Candle>, val htf: List<Candle>,
    val fng: Int?, val news: Double?, val newsCount: Int
)

data class Full(
    val signal: Signal, val score: Int, val grade: String,
    val factors: List<String>, val blockedBy: String?, val summary: String
)

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "Mozilla/5.0")
    if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
    return c.inputStream.bufferedReader().use { it.readText() }
}

// ---------- High-impact US events (UTC). Verify/update these dates yourself in 2027. ----------
// Source: Fed FOMC calendar + BLS schedule (checked Nov 2026).
// October CPI date differs between sources (Nov 12 vs 13), so both are included.
val EVENTS = listOf(
    "اشتغال آمریکا (NFP)" to "2026-11-06T13:30:00Z",
    "CPI آمریکا (اکتبر)" to "2026-11-12T13:30:00Z",
    "CPI آمریکا (اکتبر)" to "2026-11-13T13:30:00Z",
    "اشتغال آمریکا (NFP)" to "2026-12-04T13:30:00Z",
    "جلسه فدرال رزرو (FOMC)" to "2026-12-09T19:00:00Z",
    "CPI آمریکا (نوامبر)" to "2026-12-10T13:30:00Z"
)

// No entries from 3 hours before until 6 hours after an event.
fun activeEvent(now: Instant): String? = EVENTS.firstOrNull { (_, t) ->
    val x = Instant.parse(t)
    now.isAfter(x.minus(Duration.ofHours(3))) && now.isBefore(x.plus(Duration.ofHours(6)))
}?.first

fun nextEventText(now: Instant): String? =
    EVENTS.map { it.first to Instant.parse(it.second) }
        .filter { it.second.isAfter(now) }
        .minByOrNull { it.second }
        ?.let {
            val h = Duration.between(now, it.second).toHours()
            "رویداد بعدی: ${it.first} (${h / 24} روز و ${h % 24} ساعت دیگر)"
        }

// ---------- Trend, Fear&Greed, news ----------
fun trendOf(cs: List<Candle>): Int {
    if (cs.size < 210) return 0
    val c = cs.map { it.c }
    val e20 = ema(c, 20).last(); val e50 = ema(c, 50).last(); val e200 = ema(c, 200).last()
    val p = c.last()
    return when {
        e20 > e50 && p > e200 -> 1
        e20 < e50 && p < e200 -> -1
        else -> 0
    }
}

private fun fetchFng(): Int? = try {
    JSONObject(httpGet("https://api.alternative.me/fng/?limit=1"))
        .getJSONArray("data").getJSONObject(0).getString("value").toInt()
} catch (e: Exception) { null }

private val POS = listOf("surge", "rally", "soar", "gain", "approv", "adoption", "inflow",
    "record high", "bullish", "upgrade", "partnership", "breakout", "jump", "rebound")
    .map { Regex("\\b$it") }
private val NEG = listOf("crash", "plunge", "hack", "exploit", "ban", "lawsuit", "sues", "charges",
    "outflow", "bearish", "liquidat", "fraud", "drop", "sell-off", "selloff", "decline", "delay",
    "reject", "slump", "tumble")
    .map { Regex("\\b$it") }

// Simple keyword sentiment over last-24h ETH headlines (CryptoCompare news, no key). Range -1..+1.
private fun fetchNews(): Pair<Double, Int>? = try {
    val arr = JSONObject(httpGet("https://min-api.cryptocompare.com/data/v2/news/?lang=EN&categories=ETH"))
        .getJSONArray("Data")
    val cutoff = Instant.now().epochSecond - 86400
    var pos = 0; var neg = 0; var n = 0
    for (i in 0 until minOf(arr.length(), 40)) {
        val a = arr.getJSONObject(i)
        if (a.getLong("published_on") < cutoff) continue
        n++
        val t = a.getString("title").lowercase()
        if (POS.any { it.containsMatchIn(t) }) pos++
        if (NEG.any { it.containsMatchIn(t) }) neg++
    }
    Pair((pos - neg).toDouble() / (pos + neg + 1), n)
} catch (e: Exception) { null }

// ---------- Combined evaluation ----------
fun evaluate(eth: List<Candle>, ex: Extras, now: Instant = Instant.now()): Full {
    val s = analyze(eth)
    val blocked = activeEvent(now)
    val btcT = trendOf(ex.btc.dropLast(1))
    val line = listOfNotNull(
        when (btcT) { 1 -> "BTC صعودی"; -1 -> "BTC نزولی"; else -> "BTC خنثی" },
        ex.fng?.let { "ترس/طمع: $it" },
        ex.news?.let { "خبر: ${"%+.2f".format(it)} (${ex.newsCount} تیتر)" }
    ).joinToString(" | ")
    val summary = line + (nextEventText(now)?.let { "\n$it" } ?: "")

    if (s.side == Side.NONE) return Full(s, 0, "بدون سیگنال", s.reasons, blocked, summary)

    val dir = if (s.side == Side.LONG) 1 else -1
    val f = mutableListOf<String>()
    var score = 40
    f += "الگوی تکنیکال ETH: +40"

    val hT = trendOf(ex.htf.dropLast(1))
    when {
        hT == dir -> { score += 15; f += "هم‌جهت با روند تایم‌فریم بالاتر: +15" }
        hT == -dir -> { score -= 15; f += "خلاف روند تایم‌فریم بالاتر: -15" }
        else -> f += "روند تایم‌فریم بالاتر خنثی: 0"
    }
    when {
        btcT == dir -> { score += 15; f += "هم‌جهت با بیت‌کوین: +15" }
        btcT == -dir -> { score -= 15; f += "خلاف بیت‌کوین: -15" }
        else -> f += "بیت‌کوین خنثی: 0"
    }

    val cs = eth.dropLast(1)
    val avgVol = cs.takeLast(21).dropLast(1).map { it.v }.average()
    if (cs.last().v > avgVol * 1.2) { score += 10; f += "حجم بالاتر از میانگین: +10" }
    else f += "حجم معمولی: 0"

    ex.fng?.let { g ->
        val pts = when {
            s.side == Side.LONG && g >= 75 -> -10
            s.side == Side.SHORT && g <= 25 -> -10
            g in 30..70 -> 10
            else -> 5
        }
        score += pts
        f += "ترس و طمع ($g): ${if (pts >= 0) "+" else ""}$pts"
    }
    ex.news?.let { n ->
        val pts = Math.round(n * dir * 15).toInt()
        score += pts
        f += "احساس اخبار: ${if (pts >= 0) "+" else ""}$pts"
    }

    score = score.coerceIn(0, 100)
    val grade = when {
        blocked != null -> "ورود نکن"
        score >= 75 -> "قوی"
        score >= 55 -> "متوسط"
        else -> "ضعیف"
    }
    return Full(s, score, grade, f, blocked, summary)
}

suspend fun loadAll(api: ExchangeApi, iv: String): Pair<List<Candle>, Full> = coroutineScope {
    val htf = if (iv == "4h" || iv == "1d") "1d" else "4h"
    val eth = async { api.candles("ETHUSDT", iv, 500) }
    val hi = async { runCatching { api.candles("ETHUSDT", htf, 300) }.getOrDefault(emptyList()) }
    val btc = async { runCatching { api.candles("BTCUSDT", iv, 300) }.getOrDefault(emptyList()) }
    val fng = async(Dispatchers.IO) { fetchFng() }
    val news = async(Dispatchers.IO) { fetchNews() }
    val e = eth.await()
    val nw = news.await()
    e to evaluate(e, Extras(btc.await(), hi.await(), fng.await(), nw?.first, nw?.second ?: 0))
}
