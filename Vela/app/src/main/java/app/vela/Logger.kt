package app.vela

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал приложения. Пишет в logcat (тег Vela/<модуль>), в файл filesDir/logs/vela.log (с ротацией до 512 КБ)
 * и в память — последние записи показываются в Настройках. Токены подписок, UUID, ссылки на серверы и пароли
 * скрываются автоматически, поэтому журналом можно делиться. Необработанные падения тоже попадают сюда.
 */
object VLog {
    private const val MAX_FILE = 512 * 1024L
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    /** Последние записи для экрана журнала. */
    val lines = mutableStateListOf<String>()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "vela.log")
        file = f
        runCatching { f.readLines().takeLast(100) }.getOrNull()?.let { lines.addAll(it) }
        info("App", "Vela запущен · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            error("Crash", "необработанная ошибка в потоке ${t.name}", ex)
            prev?.uncaughtException(t, ex)
        }
    }

    fun debug(tag: String, msg: String) = write('D', tag, msg, null)
    fun info(tag: String, msg: String) = write('I', tag, msg, null)
    fun warn(tag: String, msg: String, tr: Throwable? = null) = write('W', tag, msg, tr)
    fun error(tag: String, msg: String, tr: Throwable? = null) = write('E', tag, msg, tr)

    /** Весь журнал одним текстом — для «Поделиться». */
    fun dump(): String = synchronized(this) { lines.joinToString("\n") }

    fun clear() = synchronized(this) {
        lines.clear()
        file?.let { it.delete(); File(it.path + ".1").delete() }
        Unit
    }

    private val rules = listOf(
        Regex("(https?://[^/\\s]+)/[^\\s?#]+") to "$1/…",                                    // токен в пути подписки
        Regex("(vless|vmess|trojan|ss|hy2|hysteria2|socks|wireguard)://\\S+") to "$1://<скрыто>",
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") to "<uuid>",
        Regex("(?i)(password|token|pbk|sid|hwid)([=:\"\\s]+)[^\\s\",&]+") to "$1$2<скрыто>"
    )

    fun redact(s: String): String = rules.fold(s) { acc, (r, to) -> r.replace(acc, to) }

    private fun stack(tr: Throwable): String =
        StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString().take(2000)

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?) {
        val text = redact(msg) + (tr?.let { "\n" + redact(stack(it)) } ?: "")
        Log.println(
            when (level) { 'D' -> Log.DEBUG; 'W' -> Log.WARN; 'E' -> Log.ERROR; else -> Log.INFO },
            "Vela/$tag", text
        )
        synchronized(this) {
            val line = "${fmt.format(Date())} $level/$tag: $text"
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            val f = file ?: return
            runCatching {
                if (f.length() > MAX_FILE) { val old = File(f.path + ".1"); old.delete(); f.renameTo(old) }
                f.appendText(line + "\n")
            }
        }
    }
}
