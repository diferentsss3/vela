package app.vela

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Subscription(
    val url: String, val title: String, val upload: Long = 0, val download: Long = 0, val total: Long = 0,
    val expire: Long = 0, val intervalH: Int = 24, val updated: Long = 0,
    val servers: List<Server> = emptyList(), val meta: Map<String, String> = emptyMap()
) {
    val used: Long get() = upload + download
    val support: String get() = meta["support-url"] ?: ""
    val web: String get() = meta["profile-web-page-url"] ?: ""
    val announce: String get() = (meta["announce"]?.let { SubRepo.decode(it) } ?: "").take(200)
    fun daysLeft(now: Long = System.currentTimeMillis() / 1000): Int? = if (expire > 0) ((expire - now) / 86400).toInt() else null
    fun due(now: Long = System.currentTimeMillis()) = now - updated > intervalH * 3_600_000L
}

/**
 * Подписки в формате Happ (https://www.happ.su/main/dev-docs/app-management):
 * параметры приходят HTTP-заголовками или строками «#параметр: значение» в теле подписки.
 * Поддерживаются: profile-title, profile-update-interval, subscription-userinfo, support-url, profile-web-page-url,
 * announce, fallback-url, change-user-agent, subscription-request-timeout, per-app-proxy-mode / -list / -list-set.
 * Тело: список ссылок, base64-список или JSON-конфиги Xray. Зашифрованные happ://crypt… не поддерживаются.
 */
object SubRepo {
    /** happ://add/https://…, vela://add/https://… или обычная https://-ссылка. */
    fun normalize(input: String): String? {
        var s = input.trim()
        for (p in listOf("happ://add/", "vela://add/")) if (s.startsWith(p, true)) s = s.substring(p.length)
        if ("%2F" in s || "%3A" in s) s = Uri.decode(s)
        return s.takeIf { it.startsWith("https://", true) }
    }

    /** Текст может прийти как обычная строка или как «base64:…». */
    fun decode(t: String): String =
        if (t.startsWith("base64:")) runCatching { String(Base64.decode(t.removePrefix("base64:"), Base64.DEFAULT)) }.getOrDefault(t) else t

    /** Случайный идентификатор установки: его присылают панели с лимитом устройств (x-hwid). */
    fun hwid(ctx: Context): String {
        val p = ctx.getSharedPreferences("vela", 0)
        return p.getString("hwid", null) ?: UUID.randomUUID().toString().also { p.edit().putString("hwid", it).apply() }
    }

    fun fetch(url: String, old: Subscription?, hwid: String): Subscription =
        try { load(url, url, old, hwid) } catch (e: Exception) {
            // fallback-url: запасной адрес, если основной не ответил
            val fb = old?.meta?.get("fallback-url")?.takeIf { it.startsWith("https://", true) } ?: throw e
            load(fb, url, old, hwid)
        }

    private fun load(fetchUrl: String, key: String, old: Subscription?, hwid: String): Subscription {
        val c = URL(fetchUrl).openConnection() as HttpURLConnection
        val to = ((old?.meta?.get("subscription-request-timeout")?.toIntOrNull() ?: 9).coerceIn(5, 15)) * 1000
        c.connectTimeout = to; c.readTimeout = to + 6000
        c.setRequestProperty("User-Agent", old?.meta?.get("change-user-agent") ?: "Vela/1.0 (Android; Happ-compatible)")
        c.setRequestProperty("x-hwid", hwid); c.setRequestProperty("x-device-os", "Android")
        c.setRequestProperty("x-ver-os", Build.VERSION.RELEASE); c.setRequestProperty("x-device-model", Build.MODEL)
        try {
            VLog.info("Sub", "GET $fetchUrl → ${c.responseCode}")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val raw = c.inputStream.bufferedReader().readText().trim()
            val json = raw.startsWith("[") || raw.startsWith("{")
            val text = if (json || "://" in raw) raw else runCatching { String(Base64.decode(raw, Base64.DEFAULT)) }.getOrDefault(raw)
            val meta = HashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) meta[k.lowercase()] = v[0] }
            val body = ArrayList<String>()
            for (l in text.lines()) {
                val t = l.trim()
                if (!json && t.startsWith("#") && ":" in t) meta[t.substring(1).substringBefore(":").trim().lowercase()] = t.substringAfter(":").trim()
                else body.add(t)
            }
            val ui = (meta["subscription-userinfo"] ?: "").split(";").mapNotNull {
                val p = it.trim().split("="); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
            }.toMap()
            val title = decode(meta["profile-title"] ?: "").takeIf { it.isNotBlank() } ?: old?.title ?: URL(key).host
            val parsed = if (json) LinkParser.fromJson(raw, title) else LinkParser.parse(body.joinToString("\n"), title)
            val servers = parsed.map { it.copy(sub = key) }
            VLog.info("Sub", "формат: ${if (json) "JSON" else "ссылки"}, серверов: ${servers.size}, параметры: ${meta.keys.filter { it.contains("-") }.sorted().take(15)}")
            if (servers.isEmpty()) error("в подписке нет поддерживаемых серверов")
            return Subscription(
                key, title, ui["upload"] ?: 0, ui["download"] ?: 0, ui["total"] ?: 0, ui["expire"] ?: 0,
                meta["profile-update-interval"]?.toIntOrNull()?.coerceAtLeast(1) ?: old?.intervalH ?: 24,
                System.currentTimeMillis(), servers, meta
            )
        } finally { c.disconnect() }
    }

    fun save(ctx: Context, subs: List<Subscription>) {
        val arr = JSONArray()
        subs.forEach { s ->
            val sv = JSONArray()
            s.servers.forEach { sv.put(JSONObject().put("n", it.name).put("c", it.code).put("p", it.proto).put("h", it.host).put("o", it.port).put("d", it.desc).put("cfg", it.config)) }
            val mj = JSONObject()
            s.meta.forEach { (k, v) -> mj.put(k, v) }
            arr.put(JSONObject().put("url", s.url).put("title", s.title).put("up", s.upload).put("down", s.download).put("total", s.total)
                .put("exp", s.expire).put("int", s.intervalH).put("upd", s.updated).put("srv", sv).put("meta", mj))
        }
        ctx.getSharedPreferences("vela", 0).edit().putString("subs", arr.toString()).apply()
    }

    fun load(ctx: Context): List<Subscription> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences("vela", 0).getString("subs", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val url = o.getString("url"); val title = o.getString("title"); val sv = o.getJSONArray("srv")
            val meta = HashMap<String, String>()
            val mo = o.optJSONObject("meta")
            if (mo != null) for (k in mo.keys()) meta[k] = mo.getString(k)
            Subscription(url, title, o.getLong("up"), o.getLong("down"), o.getLong("total"), o.getLong("exp"), o.getInt("int"), o.getLong("upd"),
                (0 until sv.length()).map { j ->
                    val x = sv.getJSONObject(j)
                    Server(x.getString("n"), x.getString("c"), x.getString("p"), x.getString("h"), x.getInt("o"), 0, false, title, url, x.optString("d"), x.optString("cfg"))
                }, meta)
        }
    }.getOrDefault(emptyList())
}
