package app.vela

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.net.URL

enum class Status { Off, Connecting, On }

object VpnState {
    val status = MutableStateFlow(Status.Off)
    val error = MutableStateFlow<String?>(null)
    @Volatile var apps: Set<String> = emptySet()   // пакеты для раздельного туннелирования
    @Volatile var bypass: Boolean = false          // true — эти приложения идут в обход VPN
    @Volatile var server: Server? = null           // выбранный сервер
}

/** geoip.dat / geosite.dat нужны правилам маршрутизации в JSON-конфигах (geoip:…, geosite:…). Скачиваются один раз. */
object GeoFiles {
    private const val BASE = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"
    fun ensure(ctx: Context) {
        for (n in listOf("geoip.dat", "geosite.dat")) {
            val f = File(ctx.filesDir, n)
            if (f.exists() && f.length() > 0) continue
            runCatching { URL(BASE + n).openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                .onSuccess { VLog.info("Geo", "скачан $n") }
                .onFailure { f.delete(); VLog.warn("Geo", "не удалось скачать $n: ${it.message}") }
        }
    }
}

/**
 * Ядро Xray (libv2ray.aar из 2dust/AndroidLibXrayLite) подключается через reflection:
 * проект собирается и без AAR, а при его наличии серверы из JSON-подписок работают по-настоящему.
 */
object TunnelCore {
    private val lib: Class<*>? = runCatching { Class.forName("libv2ray.Libv2ray") }.getOrNull()
    private var controller: Any? = null

    val isReal: Boolean get() = lib != null
    fun canRun(s: Server?): Boolean = lib != null && s != null && s.config.isNotBlank()

    /** В конфиге оставляем один TUN-вход: трафик приходит из VpnService по дескриптору. Остальное (outbounds, routing, dns) — как в подписке. */
    private fun buildConfig(raw: String): String {
        val o = JSONObject(raw)
        val tun = JSONObject().put("tag", "tun").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        o.put("inbounds", JSONArray().put(tun))
        return o.toString()
    }

    /** Возвращает текст ошибки или null, если ядро запущено. */
    fun start(ctx: Context, tun: ParcelFileDescriptor, config: String): String? {
        val l = lib ?: return "ядро Xray не найдено"
        return try {
            l.getMethod("initCoreEnv", String::class.java, String::class.java).invoke(null, ctx.filesDir.absolutePath, "")
            val cbType = Class.forName("libv2ray.CoreCallbackHandler")
            val handler = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, m, _ ->
                when (m.returnType) { java.lang.Long.TYPE -> 0L; java.lang.Integer.TYPE -> 0; java.lang.Boolean.TYPE -> false; else -> null }
            }
            val c = l.getMethod("newCoreController", cbType).invoke(null, handler) ?: return "ядро не создано"
            val start = c.javaClass.methods.firstOrNull { it.name == "startLoop" } ?: return "в libv2ray нет startLoop"
            if (start.parameterCount != 2) return "нужна версия libv2ray с поддержкой TUN (startLoop с дескриптором)"
            start.invoke(c, buildConfig(config), tun.fd)
            controller = c
            VLog.info("Core", "Xray запущен")
            null
        } catch (e: Throwable) {
            VLog.error("Core", "запуск ядра не удался", e.cause ?: e)
            (e.cause ?: e).message ?: "ошибка запуска ядра"
        }
    }

    fun stop() {
        val c = controller ?: return
        controller = null
        runCatching { c.javaClass.getMethod("stopLoop").invoke(c) }
    }
}

class VelaVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() { super.onCreate(); VLog.init(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopTunnel(); return START_NOT_STICKY }
        startForeground(1, notification())
        VpnState.status.value = Status.Connecting
        Thread { connect() }.start()   // скачивание geo-файлов и запуск ядра — не на главном потоке
        return START_NOT_STICKY
    }

    private fun connect() {
        val server = VpnState.server
        val real = TunnelCore.canRun(server)
        VLog.info("VPN", "подключение: ${server?.name ?: "—"}, ядро=${if (real) "Xray" else "демо"}, приложений в списке=${VpnState.apps.size}${if (VpnState.bypass) " (в обход)" else ""}")
        if (real) GeoFiles.ensure(this)
        val b = Builder().setSession("Vela").setMtu(1500).setBlocking(false)
            .addAddress("10.8.0.2", 32).addAddress("fdfe:dcba:9876::1", 126)
            .addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
        // Без ядра весь трафик ушёл бы в «чёрную дыру» — поэтому маршрутизируем только служебную подсеть.
        if (real) b.addRoute("0.0.0.0", 0).addRoute("::", 0) else b.addRoute("10.8.0.0", 24)
        val apps = VpnState.apps
        // Наше приложение всегда вне туннеля: через него ходят сокеты ядра.
        if (apps.isEmpty()) runCatching { b.addDisallowedApplication(packageName) }
        else if (VpnState.bypass) { runCatching { b.addDisallowedApplication(packageName) }; apps.forEach { runCatching { b.addDisallowedApplication(it) } } }
        else apps.forEach { runCatching { b.addAllowedApplication(it) } }
        val t = b.establish()
        if (t == null) { fail("не удалось создать VPN-туннель"); return }
        tun = t
        if (real) {
            val err = TunnelCore.start(this, t, server!!.config)
            if (err != null) { fail(err); return }
        }
        VpnState.status.value = Status.On
        VLog.info("VPN", "подключено")
    }

    private fun fail(msg: String) { VLog.error("VPN", "сбой: $msg"); VpnState.error.value = msg; stopTunnel() }

    private fun stopTunnel() {
        VLog.info("VPN", "отключение")
        TunnelCore.stop(); tun?.close(); tun = null
        VpnState.status.value = Status.Off
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() = stopTunnel()
    override fun onDestroy() { stopTunnel(); super.onDestroy() }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "vpn").setContentTitle("Vela").setContentText("Соединение защищено")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()
    }

    companion object { const val STOP = "app.vela.STOP" }
}
