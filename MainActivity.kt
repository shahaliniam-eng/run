package com.example.ethsignal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

// ---------- Models ----------
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)
enum class Side { LONG, SHORT, NONE }
data class Signal(
    val side: Side, val entry: Double, val stop: Double,
    val tp1: Double, val tp2: Double, val reasons: List<String>
)

// ---------- Exchange layer  ----------
interface ExchangeApi {
    suspend fun candles(symbol: String, interval: String, limit: Int): List<Candle>
}

// KCEX spot public market data (no API key needed for klines)
// Docs: https://api.kcex.com/api/v1/klines  — row: [openTime, o, h, l, c, volume, closeTime, quoteVol]
// Intervals: 1m 5m 15m 30m 60m 4h 1d
class KcexApi : ExchangeApi {
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    override suspend fun candles(symbol: String, interval: String, limit: Int): List<Candle> =
        withContext(Dispatchers.IO) {
            val q = "symbol=$symbol&interval=$interval&limit=$limit"
            val text = try { get("https://api.kcex.com/api/v1/klines?$q") }
            catch (e: Exception) { get("https://api.kcex.com/api/v3/klines?$q") }
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val a = arr.getJSONArray(i)
                Candle(a.getLong(0), a.getString(1).toDouble(), a.getString(2).toDouble(),
                    a.getString(3).toDouble(), a.getString(4).toDouble(), a.getString(5).toDouble())
            }
        }
}

// ---------- Indicators ----------
fun ema(v: List<Double>, p: Int): List<Double> {
    val k = 2.0 / (p + 1)
    val out = ArrayList<Double>(v.size)
    v.forEachIndexed { i, x -> out.add(if (i == 0) x else x * k + out[i - 1] * (1 - k)) }
    return out
}

fun rsi(c: List<Double>, p: Int = 14): List<Double> {
    val out = MutableList(c.size) { 50.0 }
    if (c.size <= p) return out
    var g = 0.0; var l = 0.0
    for (i in 1..p) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
    g /= p; l /= p
    for (i in p until c.size) {
        if (i > p) {
            val d = c[i] - c[i - 1]
            g = (g * (p - 1) + max(d, 0.0)) / p
            l = (l * (p - 1) + max(-d, 0.0)) / p
        }
        out[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }
    return out
}

fun atr(c: List<Candle>, p: Int = 14): Double {
    val tr = c.indices.map { i ->
        if (i == 0) c[i].h - c[i].l
        else maxOf(c[i].h - c[i].l, abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c))
    }
    return ema(tr, p).last()
}

// ---------- Strategy ----------
// Trend-pullback: trade with EMA20/50/200 trend after price reclaims EMA20.
fun analyze(all: List<Candle>): Signal {
    val cs = all.dropLast(1) // ignore the still-forming candle
    if (cs.size < 210) return Signal(Side.NONE, 0.0, 0.0, 0.0, 0.0, listOf("داده کافی نیست"))
    val close = cs.map { it.c }
    val e20 = ema(close, 20); val e50 = ema(close, 50); val e200 = ema(close, 200)
    val r = rsi(close).last()
    val n = cs.lastIndex
    val a = atr(cs)
    val px = close[n]

    val up = e20[n] > e50[n] && e50[n] > e200[n] && px > e200[n]
    val down = e20[n] < e50[n] && e50[n] < e200[n] && px < e200[n]
    val wasBelow = (1..3).any { close[n - it] < e20[n - it] }
    val wasAbove = (1..3).any { close[n - it] > e20[n - it] }

    val recent = cs.takeLast(10)
    return when {
        up && wasBelow && px > e20[n] && r in 45.0..68.0 -> {
            val stop = minOf(px - 1.5 * a, recent.minOf { it.l } - 0.2 * a)
            val risk = px - stop
            Signal(Side.LONG, px, stop, px + risk, px + 2 * risk,
                listOf("روند صعودی (EMA20>50>200)", "برگشت قیمت بالای EMA20", "RSI = ${"%.1f".format(r)}"))
        }
        down && wasAbove && px < e20[n] && r in 32.0..55.0 -> {
            val stop = maxOf(px + 1.5 * a, recent.maxOf { it.h } + 0.2 * a)
            val risk = stop - px
            Signal(Side.SHORT, px, stop, px - risk, px - 2 * risk,
                listOf("روند نزولی (EMA20<50<200)", "برگشت قیمت زیر EMA20", "RSI = ${"%.1f".format(r)}"))
        }
        else -> Signal(Side.NONE, px, 0.0, 0.0, 0.0,
            listOf("شرایط ورود کامل نیست، صبر کن", "RSI = ${"%.1f".format(r)}"))
    }
}

// ---------- ViewModel ----------
class MainVM : ViewModel() {
    private val api: ExchangeApi = KcexApi()
    var candles by mutableStateOf<List<Candle>>(emptyList())
    var signal by mutableStateOf<Signal?>(null)
    var full by mutableStateOf<Full?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var interval by mutableStateOf("60m")

    fun load(iv: String = interval) {
        interval = iv
        viewModelScope.launch {
            loading = true; error = null
            try {
                val r = loadAll(api, iv)
                candles = r.first
                full = r.second
                signal = r.second.signal
            } catch (e: Exception) {
                error = "خطا در دریافت اطلاعات: ${e.message}"
            }
            loading = false
        }
    }
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }
}

@Composable
fun Screen(vm: MainVM = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    val s = vm.signal
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ETH/USDT", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("15m", "60m", "4h", "1d").forEach {
                FilterChip(selected = vm.interval == it, onClick = { vm.load(it) }, label = { Text(it) })
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.load() }) { Text("تازه‌سازی") }
        }
        val ctx = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.packageName)))
                } else ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
            }) { Text("فعال‌سازی پنجره شناور") }
            OutlinedButton(onClick = { ctx.stopService(Intent(ctx, OverlayService::class.java)) }) { Text("توقف") }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let { Text(it, color = Color.Red) }
        if (vm.candles.isNotEmpty()) Chart(vm.candles, s)
        if (s != null) {
            val title = when (s.side) { Side.LONG -> "سیگنال خرید (Long)"; Side.SHORT -> "سیگنال فروش (Short)"; else -> "بدون سیگنال" }
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (s.side != Side.NONE) {
                Text("ورود: ${"%.2f".format(s.entry)}", color = Color(0xFF42A5F5))
                Text("حد ضرر: ${"%.2f".format(s.stop)}", color = Color(0xFFEF5350))
                Text("هدف ۱ (۱R): ${"%.2f".format(s.tp1)}", color = Color(0xFF66BB6A))
                Text("هدف ۲ (۲R): ${"%.2f".format(s.tp2)}", color = Color(0xFF66BB6A))
            }
            vm.full?.let { f ->
                f.blockedBy?.let { Text("⚠ رویداد مهم: $it - فعلاً وارد نشو", color = Color(0xFFFFB74D)) }
                if (s.side != Side.NONE) {
                    val col = when (f.grade) { "قوی" -> Color(0xFF66BB6A); "متوسط" -> Color(0xFFFFB74D); else -> Color(0xFFEF5350) }
                    Text("کیفیت سیگنال: ${f.grade} (${f.score}/۱۰۰)", color = col, style = MaterialTheme.typography.titleMedium)
                }
                f.factors.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text(f.summary, style = MaterialTheme.typography.bodySmall)
                Text("امتیاز احتمال موفقیت نیست؛ فقط میزان هم‌جهتی عوامل است.", style = MaterialTheme.typography.bodySmall)
            }
            Text("این ابزار مشاوره مالی نیست و سود تضمین نمی‌کند.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Chart(all: List<Candle>, s: Signal?) {
    val data = all.takeLast(60)
    Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        var hi = data.maxOf { it.h }
        var lo = data.minOf { it.l }
        val active = s != null && s.side != Side.NONE
        if (active) {
            hi = maxOf(hi, s!!.entry, s.stop, s.tp2)
            lo = minOf(lo, s.entry, s.stop, s.tp2)
        }
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(p: Double) = (size.height * (1 - (p - lo) / range)).toFloat()
        val w = size.width / data.size
        data.forEachIndexed { i, c ->
            val x = i * w + w / 2
            val col = if (c.c >= c.o) Color(0xFF26A69A) else Color(0xFFEF5350)
            drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), strokeWidth = 2f)
            val top = y(maxOf(c.o, c.c)); val bot = y(minOf(c.o, c.c))
            drawRect(col, Offset(x - w * 0.35f, top), Size(w * 0.7f, max(bot - top, 2f)))
        }
        if (active) {
            fun line(p: Double, col: Color) =
                drawLine(col, Offset(0f, y(p)), Offset(size.width, y(p)), strokeWidth = 3f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            line(s!!.entry, Color(0xFF42A5F5))
            line(s.stop, Color(0xFFEF5350))
            line(s.tp1, Color(0xFF66BB6A))
            line(s.tp2, Color(0xFF66BB6A))
        }
    }
}
