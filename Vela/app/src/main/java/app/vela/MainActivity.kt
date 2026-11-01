package app.vela

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ───────────────────────── ViewModel ─────────────────────────

class VM(app: Application) : AndroidViewModel(app) {
    var pal by mutableStateOf(Pal.Vela)
    var mode by mutableStateOf(Mode.Auto)
    var tab by mutableIntStateOf(0)
    var route by mutableIntStateOf(0)
    var auto by mutableStateOf(true)
    var haptic by mutableStateOf(true)
    var kill by mutableStateOf(true)
    var sel by mutableIntStateOf(0)
    var status by mutableStateOf(Status.Off)
    var down by mutableFloatStateOf(0f)
    var up by mutableFloatStateOf(0f)
    var zapret by mutableStateOf(ZapretCfg())
    val samples = mutableStateListOf<Pair<Long, Float>>()
    val subs = mutableStateListOf<Subscription>()
    var busy by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    val servers = mutableStateListOf(
        Server("Амстердам", "NL", "VLESS Reality", "", 443, 38, true, "Основная подписка"),
        Server("Франкфурт", "DE", "VLESS Reality", "", 443, 46, false, "Основная подписка"),
        Server("Хельсинки", "FI", "Trojan", "", 443, 29, true, "Основная подписка"),
        Server("Стокгольм", "SE", "Hysteria2", "", 443, 57, false, "Основная подписка"),
        Server("Цюрих", "CH", "VMess", "", 443, 71, false, "Резервная"),
        Server("Лондон", "GB", "VLESS Reality", "", 443, 62, false, "Резервная"),
        Server("Токио", "JP", "VLESS Reality", "", 443, 190, false, "Резервная"),
        Server("Нью-Йорк", "US", "Hysteria2", "", 443, 128, false, "Резервная")
    )

    init {
        viewModelScope.launch { VpnState.status.collect { status = it } }
        viewModelScope.launch { VpnState.error.collect { e -> if (e != null) { msg = "Ошибка подключения: $e"; VpnState.error.value = null } } }
        viewModelScope.launch { meter() }
        subs.addAll(SubRepo.load(app)); subs.forEach { servers.addAll(it.servers) }
        subs.filter { it.due() }.forEach { refresh(it.url, true) }   // автообновление по profile-update-interval
    }

    fun best() = servers.indices.filter { servers[it].ping > 0 }.minByOrNull { servers[it].ping } ?: 0
    /** Реальный TCP-пинг до host:port; демо-серверы без адреса получают имитацию. */
    fun pingAll() = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) {
            servers.toList().map { s ->
                if (s.host.isBlank()) maxOf(18, (s.ping * (.7 + Random.nextDouble() * .7)).toInt())
                else runCatching {
                    val t = System.nanoTime()
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(s.host, s.port), 2000) }
                    ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1)
                }.getOrDefault(0)
            }
        }
        r.forEachIndexed { i, p -> if (i < servers.size) servers[i] = servers[i].copy(ping = p) }
    }
    fun fav(i: Int) { servers[i] = servers[i].copy(fav = !servers[i].fav) }
    /** Ссылка подписки (https://, happ://add/…, vela://add/…) или отдельные vless:// trojan:// ss:// hy2:// */
    fun add(text: String) {
        val t = text.trim()
        VLog.info("Sub", "добавление: ${t.take(120)}")
        val url = SubRepo.normalize(t)
        if (url != null) { refresh(url); return }
        val l = LinkParser.parse(t, "Вручную")
        if (l.isEmpty()) msg = "Нужна ссылка подписки https://… или сервер vless://…" else servers.addAll(l)
    }

    fun refresh(url: String, silent: Boolean = false) = viewModelScope.launch {
        if (!silent) busy = true
        runCatching { withContext(Dispatchers.IO) { SubRepo.fetch(url, subs.find { it.url == url }, SubRepo.hwid(getApplication<Application>())) } }
            .onSuccess { s ->
                val i = subs.indexOfFirst { it.url == url }
                if (i >= 0) subs[i] = s else subs.add(s)
                servers.removeAll { it.sub == url }; servers.addAll(s.servers)
                sel = sel.coerceIn(0, servers.lastIndex)
                SubRepo.save(getApplication<Application>(), subs)
                VLog.info("Sub", "«${s.title}»: серверов ${s.servers.size}, JSON-конфигов ${s.servers.count { it.config.isNotBlank() }}")
                if (!silent) msg = "Подписка «${s.title}»: серверов ${s.servers.size}"
            }
            .onFailure { VLog.error("Sub", "обновление $url не удалось", it); if (!silent) msg = "Не удалось загрузить: ${it.message ?: "ошибка сети"}" }
        busy = false
    }

    fun removeSub(url: String) {
        subs.removeAll { it.url == url }; servers.removeAll { it.sub == url }
        sel = sel.coerceIn(0, maxOf(0, servers.lastIndex)); SubRepo.save(getApplication<Application>(), subs)
    }

    /** Happ: per-app-proxy-mode (off/on/bypass) и per-app-proxy-list[-set] из подписки выбранного сервера → раздельное туннелирование. */
    fun applyPerApp() {
        VpnState.server = servers.getOrNull(sel)
        val m = subs.find { it.url == servers.getOrNull(sel)?.sub }?.meta ?: emptyMap()
        val mode = m["per-app-proxy-mode"] ?: "off"
        val list = (m["per-app-proxy-list-set"] ?: m["per-app-proxy-list"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        VpnState.apps = if (mode == "off") emptySet() else list
        VpnState.bypass = mode == "bypass"
    }

    /** Скорость считается по реальным байтам процесса. Пока ядро не подключено (TunnelCore.isReal=false) — демо-значения. */
    private suspend fun meter() {
        var lastB = TrafficStats.getUidRxBytes(Process.myUid()); var lastT = System.nanoTime()
        var tgt = 40f; var tgtT = 0L
        while (true) {
            delay(300)
            val now = System.nanoTime(); val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val dt = (now - lastT) / 1e9f
            var mbps = (rx - lastB) * 8f / 1e6f / dt; lastB = rx; lastT = now
            if (status != Status.On) { down = 0f; up = 0f; samples.clear(); continue }
            if (!TunnelCore.canRun(VpnState.server)) {
                if (now - tgtT > 1_500_000_000L) { tgtT = now; tgt = 25f + Random.nextFloat() * 70f }
                mbps = down + (tgt - down) * .35f
            }
            down += (mbps - down) * .5f; up = down * .3f
            samples.add(now / 1_000_000 to down)
            while (samples.size > 90) samples.removeAt(0)
        }
    }
}

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()
    private val perm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VLog.init(applicationContext)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { App(vm, ::toggle) }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) { i?.dataString?.let { vm.tab = 1; vm.add(it) } }   // happ://add/… и vela://add/…

    private fun toggle() {
        if (VpnState.status.value != Status.Off) {
            startService(Intent(this, VelaVpnService::class.java).setAction(VelaVpnService.STOP)); return
        }
        val i = VpnService.prepare(this)
        if (i != null) perm.launch(i) else startVpn()
    }

    private fun startVpn() { vm.applyPerApp(); startForegroundService(Intent(this, VelaVpnService::class.java)) }
}

// ───────────────────────── UI ─────────────────────────

private fun View.tick(on: Boolean) {
    if (on) performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun App(vm: VM, onPower: () -> Unit) {
    val dark = when (vm.mode) { Mode.Dark -> true; Mode.Light -> false; Mode.Auto -> isSystemInDarkTheme() }
    VelaTheme(vm.pal, dark) {
        val c = LocalVC.current
        val view = LocalView.current
        val ctx = LocalContext.current
        LaunchedEffect(vm.msg) { vm.msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.msg = null } }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // мягкие цветовые пятна на фоне (градиенты вместо тяжёлого blur)
            Box(Modifier.size(340.dp).offset((-120).dp, (-120).dp).background(Brush.radialGradient(listOf(c.ac2.copy(alpha = .32f), Color.Transparent))))
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (vm.tab) {
                        0 -> Home(vm, dark) { view.tick(vm.haptic); onPower() }
                        1 -> Servers(vm)
                        2 -> ZapretScreen(vm)
                        else -> Settings(vm)
                    }
                }
                NavBar(vm.tab) { vm.tab = it; view.tick(vm.haptic) }
            }
        }
    }
}

@Composable private fun T(s: String, color: Color = LocalVC.current.tx, size: Int = 15, w: FontWeight = FontWeight.Medium, modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(s, modifier, color, size.sp, fontWeight = w, textAlign = align)

@Composable private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(LocalVC.current.sf).padding(14.dp), content = content)

@Composable private fun ToggleRow(title: String, sub: String, v: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalVC.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!v) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, w = FontWeight.SemiBold); T(sub, c.mu, 13) }
        Switch(v, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.ac, checkedThumbColor = Color.White,
            uncheckedTrackColor = c.sf2, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable private fun Seg(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val c = LocalVC.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sf2).padding(4.dp)) {
        items.forEachIndexed { i, s ->
            val bg by animateColorAsState(if (i == sel) c.sf else Color.Transparent, tween(250), label = "seg")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSel(i) }.padding(vertical = 10.dp), Alignment.Center) {
                T(s, if (i == sel) c.tx else c.mu, 13, FontWeight.Bold)
            }
        }
    }
}

// ── навигация с нарисованными иконками ──

private val ICONS = listOf(
    "Главная" to listOf("M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z", "M9 12l2 2 4-4"),
    "Серверы" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M3 12h18", "M12 3c3 3 3 15 0 18", "M12 3c-3 3-3 15 0 18"),
    "Zapret" to listOf("M7.5 11h9a2.5 2.5 0 0 1 2.5 2.5v4A2.5 2.5 0 0 1 16.5 20h-9A2.5 2.5 0 0 1 5 17.5v-4A2.5 2.5 0 0 1 7.5 11z", "M8 11V8a4 4 0 0 1 7.6-1.7"),
    "Настройки" to listOf("M4 7h9M17 7h3M4 17h3M11 17h9", "M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
)

@Composable private fun SvgIcon(paths: List<String>, tint: Color, size: Int = 25) {
    val ps = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size.dp)) {
        withTransform({ scale(this@Canvas.size.width / 24f, this@Canvas.size.width / 24f, Offset.Zero) }) {
            ps.forEach { drawPath(it, tint, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

@Composable private fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val c = LocalVC.current
    Row(Modifier.fillMaxWidth().background(c.sf).padding(horizontal = 8.dp, vertical = 6.dp)) {
        ICONS.forEachIndexed { i, (name, p) ->
            val on = i == tab
            val col by animateColorAsState(if (on) c.ac else c.mu, tween(300), label = "nc")
            val lift by animateFloatAsState(if (on) -3f else 0f, spring(.5f, 400f), label = "lift")
            val pill by animateColorAsState(if (on) c.ac.copy(alpha = .16f) else Color.Transparent, tween(300), label = "pill")
            Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(pill)
                .clickable(onClick = { onTab(i) }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.offset(y = lift.dp)) { SvgIcon(p, col) }
                T(name, col, 11, FontWeight.Bold)
            }
        }
    }
}

// ── солнце / луна ──

@Composable private fun SunMoon(dark: Boolean, onClick: () -> Unit) {
    val c = LocalVC.current
    val p by animateFloatAsState(if (dark) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "sm")
    Box(Modifier.size(44.dp).shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(c.sf).clickable(onClick = onClick), Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val r = size.minDimension / 2; val m = center
            val sun = Brush.linearGradient(listOf(Color(0xFFFFD166), Color(0xFFFF7A59)))
            withTransform({ rotate(p * 120f, m); scale(.4f + .6f * p, .4f + .6f * p, m) }) {
                drawCircle(sun, r * .4f, m, alpha = p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0); val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(sun, m + d * (r * .64f), m + d * (r * .94f), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round, alpha = p)
                }
            }
            val q = 1f - p
            withTransform({ rotate(-120f * p, m); scale(.4f + .6f * q, .4f + .6f * q, m) }) {
                val moon = Path.combine(PathOperation.Difference,
                    Path().apply { addOval(Rect(m, r * .8f)) }, Path().apply { addOval(Rect(m + Offset(r * .45f, -r * .32f), r * .72f)) })
                drawPath(moon, Brush.linearGradient(listOf(Color(0xFFC9B8FF), Color(0xFF7B5CFF))), alpha = q)
                drawCircle(Color(0xFFFFE9A8), r * .09f, m + Offset(r * .55f, -r * .55f), alpha = q)
            }
        }
    }
}

// ── главная ──

@Composable private fun Orb(status: Status, onClick: () -> Unit) {
    val c = LocalVC.current
    val inf = rememberInfiniteTransition(label = "orb")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (status == Status.Connecting) 1000 else 7000, easing = LinearEasing)), label = "rot")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearOutSlowInEasing)), label = "wave")
    val breathe by inf.animateFloat(1f, 1.025f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "br")
    val ring by animateFloatAsState(if (status == Status.Off) .25f else 1f, tween(400), label = "ring")
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val ps by animateFloatAsState(if (pressed) .93f else 1f, spring(.5f, 500f), label = "ps")
    val squeeze by animateFloatAsState(if (status == Status.Connecting) .93f else 1f, tween(500), label = "sq")
    Box(Modifier.size(196.dp).scale(ps).clickable(press, null, onClick = onClick), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2; val m = center
            rotate(rot, m) { drawCircle(Brush.sweepGradient(listOf(c.ac2, c.ac, c.ac2), m), r, m, alpha = ring) }
            if (status == Status.On) for (k in 0..1) {
                val w = (wave + k * .5f) % 1f
                drawCircle(c.ac, r * (1f + .5f * w), m, alpha = .4f * (1f - w), style = Stroke(2.dp.toPx()))
            }
            val core = r * .84f * (if (status == Status.Off) breathe else squeeze)
            if (status == Status.On) drawCircle(Brush.radialGradient(listOf(c.ac.copy(alpha = .45f), Color.Transparent), m, r * 1.2f), r * 1.2f, m)
            drawCircle(c.sf, core, m)
            val col = if (status == Status.On) c.ac else c.mu; val s = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            val k = r * .3f
            drawArc(col, -50f, 280f, false, m - Offset(k, k), Size(k * 2, k * 2), style = s)
            drawLine(col, m + Offset(0f, -k * 1.15f), m + Offset(0f, -k * .1f), s.width, StrokeCap.Round)
        }
    }
}

@Composable private fun Spark(samples: List<Pair<Long, Float>>) {
    val c = LocalVC.current
    val now by produceState(System.nanoTime() / 1_000_000) { while (true) withFrameNanos { value = it / 1_000_000 } }
    val ym = remember { floatArrayOf(60f) }
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        if (samples.isEmpty()) return@Canvas
        val pts = samples.map { Offset(size.width - (now - it.first) / 24000f * size.width, it.second) }.toMutableList()
        pts.add(Offset(size.width, pts.last().y))
        ym[0] += (maxOf(60f, pts.maxOf { it.y } * 1.15f) - ym[0]) * .05f
        val p = pts.map { Offset(it.x, size.height * .93f - it.y / ym[0] * size.height * .84f) }
        val line = Path().apply {
            moveTo(p[0].x, p[0].y)
            for (i in 0 until p.size - 1) {
                val a = p.getOrElse(i - 1) { p[i] }; val b = p[i]; val d = p[i + 1]; val e = p.getOrElse(i + 2) { d }
                cubicTo(b.x + (d.x - a.x) / 6, b.y + (d.y - a.y) / 6, d.x - (e.x - b.x) / 6, d.y - (e.y - b.y) / 6, d.x, d.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(p[0].x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(c.ac.copy(alpha = .35f), Color.Transparent)))
        drawPath(line, c.ac, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable private fun Home(vm: VM, dark: Boolean, onPower: () -> Unit) {
    val c = LocalVC.current; val s = vm.servers[vm.sel]
    val view = LocalView.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Vela", size = 22, w = FontWeight.Bold); Spacer(Modifier.weight(1f))
            SunMoon(dark) { vm.mode = if (dark) Mode.Light else Mode.Dark; view.tick(vm.haptic) }
        }
        Spacer(Modifier.height(24.dp))
        Orb(vm.status) { if (vm.auto && vm.status == Status.Off) vm.sel = vm.best(); onPower() }
        Spacer(Modifier.height(20.dp))
        AnimatedContent(when (vm.status) { Status.Off -> "Не подключено"; Status.Connecting -> "Подключаемся…"; Status.On -> "Защищено" }, label = "st") {
            T(it, size = 20, w = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(c.sf)
            .clickable { vm.tab = 1 }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Flag(s.code); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { T(s.name, w = FontWeight.SemiBold); T(s.proto + if (s.ping > 0) " · ${s.ping} мс" else "", c.mu, 13) }
            T("›", c.mu, 20)
        }
        Spacer(Modifier.height(14.dp))
        Seg(listOf("Умный", "Весь трафик", "По приложениям"), vm.route) { vm.route = it }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.weight(1f)) { T("Загрузка", c.mu, 13); T("%.1f Мбит/с".format(vm.down), size = 20, w = FontWeight.Bold) }
            Panel(Modifier.weight(1f)) { T("Отдача", c.mu, 13); T("%.1f Мбит/с".format(vm.up), size = 20, w = FontWeight.Bold) }
        }
        Spacer(Modifier.height(10.dp))
        Panel { T("Скорость в реальном времени", c.mu, 13); Spacer(Modifier.height(6.dp)); Spark(vm.samples) }
        Spacer(Modifier.height(10.dp))
        Panel {
            ToggleRow("Автовыбор сервера", "Всегда самый быстрый по пингу", vm.auto) { vm.auto = it }
            ToggleRow("Zapret", if (vm.zapret.on) "Активен · ${vm.zapret.method}" else "Выключен", vm.zapret.on) { vm.zapret = vm.zapret.copy(on = it) }
        }
    }
}

// ── серверы ──

@Composable private fun Servers(vm: VM) {
    val c = LocalVC.current
    var q by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    val groups = vm.servers.withIndex().filter { q.isBlank() || (it.value.name + it.value.proto).contains(q, true) }.groupBy { it.value.group }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Серверы", size = 20, w = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ vm.pingAll() }) { T("⚡ Пинг", c.ac, 14, FontWeight.Bold) }
            TextButton({ dialog = true }) { T("＋", c.ac, 20, FontWeight.Bold) }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { T("Страна или протокол", c.mu) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ac, unfocusedBorderColor = c.ln, focusedTextColor = c.tx, unfocusedTextColor = c.tx),
            shape = RoundedCornerShape(16.dp))
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = c.ac, trackColor = c.sf2)
        LazyColumn(Modifier.weight(1f)) {
            items(vm.subs.size) { k -> val s = vm.subs[k]; SubCard(s, { vm.refresh(s.url) }, { vm.removeSub(s.url) }) }
            groups.forEach { (g, list) ->
                item { T(g, c.mu, 13, FontWeight.Bold, Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(list.size) { k ->
                    val (i, s) = list[k]
                    val bg by animateColorAsState(if (i == vm.sel) c.sf2 else Color.Transparent, tween(250), label = "row")
                    val pc = if (s.ping < 60) c.ac else if (s.ping < 120) c.ac2 else c.wm
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).clickable { vm.sel = i; vm.auto = false; vm.tab = 0 }
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Flag(s.code); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { T(s.name, w = FontWeight.SemiBold); T(s.desc.ifBlank { s.proto }, c.mu, 13) }
                        T(if (s.ping > 0) "${s.ping} мс" else "—", pc, 13, FontWeight.SemiBold)
                        TextButton({ vm.fav(i) }, contentPadding = PaddingValues(8.dp)) { T(if (s.fav) "★" else "☆", if (s.fav) c.wm else c.mu, 20) }
                    }
                }
            }
        }
    }
    if (dialog) {
        var link by remember { mutableStateOf("") }
        val ctx = LocalContext.current
        AlertDialog(onDismissRequest = { dialog = false }, containerColor = c.bg,
            title = { T("Добавить подписку", size = 18, w = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), placeholder = { T("https://… ссылка подписки или vless://", c.mu, 13) })
                    Row {
                        TextButton({ ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.let { link = it.toString() } }) { T("Вставить", c.ac, 14, FontWeight.Bold) }
                        TextButton({ GmsBarcodeScanning.getClient(ctx).startScan().addOnSuccessListener { link = it.rawValue ?: "" } }) { T("QR-код", c.ac, 14, FontWeight.Bold) }
                    }
                }
            },
            confirmButton = { TextButton({ vm.add(link); dialog = false }) { T("Добавить", c.ac, 15, FontWeight.Bold) } },
            dismissButton = { TextButton({ dialog = false }) { T("Отмена", c.mu) } })
    }
}

private fun gb(b: Long) = "%.1f ГБ".format(b / 1073741824.0)

@Composable private fun SubCard(s: Subscription, onRefresh: () -> Unit, onDelete: () -> Unit) {
    val c = LocalVC.current; val ctx = LocalContext.current
    val prog by animateFloatAsState(if (s.total > 0) (s.used.toFloat() / s.total).coerceIn(0f, 1f) else 0f, tween(800), label = "sub")
    Panel(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T(s.title, w = FontWeight.Bold); T("${s.servers.size} серверов · обновление раз в ${s.intervalH} ч", c.mu, 13) }
            TextButton(onRefresh) { T("↻", c.ac, 20, FontWeight.Bold) }
            TextButton(onDelete) { T("✕", c.mu, 16) }
        }
        if (s.announce.isNotBlank()) T(s.announce, c.ac2, 13, FontWeight.SemiBold, Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        if (s.total > 0) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.sf2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(prog).clip(CircleShape).background(Brush.horizontalGradient(listOf(c.ac2, c.ac))))
            }
            T("${gb(s.used)} из ${gb(s.total)}", c.mu, 13, modifier = Modifier.padding(top = 6.dp))
        } else T("Использовано ${gb(s.used)} · без лимита", c.mu, 13)
        s.daysLeft()?.let { T(if (it >= 0) "Осталось дней: $it" else "Подписка истекла", if (it <= 3) c.wm else c.mu, 13) }
        Row {
            if (s.support.isNotBlank()) TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.support))) }) { T("Поддержка", c.ac, 13, FontWeight.Bold) }
            if (s.web.isNotBlank()) TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.web))) }) { T("Личный кабинет", c.ac, 13, FontWeight.Bold) }
        }
    }
}

// ── журнал ──

@Composable private fun LogPanel() {
    val c = LocalVC.current; val ctx = LocalContext.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Журнал", w = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, VLog.dump()), "Журнал Vela"))
            }) { T("Поделиться", c.ac, 13, FontWeight.Bold) }
            TextButton({ VLog.clear() }) { T("Очистить", c.mu, 13) }
        }
        val tail = VLog.lines.takeLast(10)
        if (tail.isEmpty()) T("Пока пусто", c.mu, 12)
        else tail.forEach { Text(it, color = c.mu, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 3) }
        T("Токены и ссылки на серверы в журнале скрыты", c.mu, 11)
    }
}

// ── Zapret ──

@Composable private fun Lane(on: Boolean) {
    val c = LocalVC.current
    val t by rememberInfiniteTransition(label = "lane").animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "t")
    val wall by animateFloatAsState(if (on) .12f else .55f, tween(600), label = "wall")
    Canvas(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(16.dp)).background(c.sf2)) {
        val w = size.width; val h = size.height; val wx = w / 2; val hw = 13.dp.toPx()
        drawRect(c.wm.copy(alpha = wall), Offset(wx - hw, 0f), Size(hw * 2, h))
        for (k in 0..2) {
            val p = (t + k / 3f) % 1f; val x0 = w * .14f
            if (!on) {
                if (p < .5f) drawCircle(c.ac2, 5.dp.toPx(), Offset(x0 + (wx - hw - x0) * p / .5f, h / 2))
                else if (p < .62f) drawCircle(c.wm, 7.dp.toPx() * (1f - (p - .5f) / .12f), Offset(wx - hw, h / 2))
            } else {
                val x = x0 + (w * .8f - x0) * p; val past = x > wx
                drawCircle(if (past) c.ac else c.ac2, (if (past) 3.5f else 5f).dp.toPx(), Offset(x, h / 2 - if (past) 9.dp.toPx() else 0f))
            }
        }
    }
}

@Composable private fun ZapretScreen(vm: VM) {
    val c = LocalVC.current; val z = vm.zapret
    val profiles = listOf("Универсальный" to "Подходит большинству провайдеров", "YouTube" to "youtube.com, googlevideo.com", "Discord" to "Сайт, мессенджер и голосовые каналы", "Telegram" to "t.me, web.telegram.org")
    val methods = listOf("multisplit", "fakedsplit", "disorder2", "split2", "fake", "syndata")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Zapret · обход DPI", size = 20, w = FontWeight.Bold)
        Panel {
            Lane(z.on); Spacer(Modifier.height(10.dp))
            T(if (z.on) "Обход включён · ${z.method}" else "Провайдер видит, куда вы идёте", size = 15, w = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
        Panel { ToggleRow("Обход блокировок", "Работает без VPN и вместе с ним", z.on) { vm.zapret = z.copy(on = it) } }
        Panel {
            profiles.forEachIndexed { i, (n, d) ->
                Row(Modifier.fillMaxWidth().clickable { vm.zapret = z.copy(prof = i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(n, w = FontWeight.SemiBold); T(d, c.mu, 13) }
                    RadioButton(z.prof == i, { vm.zapret = z.copy(prof = i) }, colors = RadioButtonDefaults.colors(selectedColor = c.ac, unselectedColor = c.ln))
                }
            }
        }
        Panel {
            Row(Modifier.fillMaxWidth().clickable { vm.zapret = z.copy(method = methods[(methods.indexOf(z.method) + 1) % methods.size]) }.padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) { T("Десинхронизация", w = FontWeight.SemiBold); T("Нажмите, чтобы сменить режим", c.mu, 13) }
                T(z.method, c.mu)
            }
            ToggleRow("Блокировать QUIC", "UDP 443: YouTube перейдёт на TCP", z.quic) { vm.zapret = z.copy(quic = it) }
            ToggleRow("Фейковый пакет", "С подменой SNI, чтобы сбить DPI", z.fake) { vm.zapret = z.copy(fake = it) }
            Row { T("TTL фейка", w = FontWeight.SemiBold, modifier = Modifier.weight(1f)); T("${z.ttl}") }
            Slider(z.ttl.toFloat(), { vm.zapret = z.copy(ttl = it.toInt()) }, valueRange = 2f..12f, steps = 9,
                colors = SliderDefaults.colors(thumbColor = c.ac, activeTrackColor = c.ac, inactiveTrackColor = c.sf2))
        }
        Panel { T("Параметры nfqws", c.mu, 13); Spacer(Modifier.height(4.dp)); T(z.nfqwsArgs(), size = 13) }
    }
}

// ── настройки ──

@Composable private fun Settings(vm: VM) {
    val c = LocalVC.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Настройки", size = 20, w = FontWeight.Bold)
        Panel {
            T("Оформление", c.mu, 13); Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Pal.entries.forEach { p ->
                    val on = p == vm.pal
                    val ring by animateColorAsState(if (on) c.ac else Color.Transparent, tween(300), label = "pr")
                    val sc by animateFloatAsState(if (on) 1.08f else 1f, spring(.5f, 400f), label = "ps")
                    Column(Modifier.clickable { vm.pal = p }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).scale(sc).border(2.dp, ring, CircleShape).padding(5.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.c1, p.c2))))
                        Spacer(Modifier.height(6.dp)); T(p.title, if (on) c.tx else c.mu, 12, FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Seg(Mode.entries.map { it.title }, vm.mode.ordinal) { vm.mode = Mode.entries[it] }
        }
        Panel {
            ToggleRow("Kill Switch", "Блокировать интернет, если VPN оборвался", vm.kill) { vm.kill = it }
            ToggleRow("Виброотклик", "Мягкие тактильные сигналы при нажатиях", vm.haptic) { vm.haptic = it }
        }
        Panel { T(if (TunnelCore.isReal) "Ядро Xray: подключено. Через него работают серверы из JSON-подписок." else "Ядро Xray не найдено (libv2ray.aar) — работает демо-режим.", c.mu, 13) }
        LogPanel()
    }
}
