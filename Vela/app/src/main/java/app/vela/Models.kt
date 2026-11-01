package app.vela

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class Server(
    val name: String, val code: String, val proto: String, val host: String, val port: Int,
    val ping: Int, val fav: Boolean = false, val group: String, val sub: String = "", val desc: String = "",
    val config: String = ""   // полный Xray-конфиг (для JSON-подписок)
)

data class ZapretCfg(
    val on: Boolean = false, val prof: Int = 0, val method: String = "multisplit",
    val quic: Boolean = true, val fake: Boolean = true, val ttl: Int = 6
) {
    /** Параметры nfqws из bol-van/zapret (для root/OpenWrt/ПК). Режимы идут в порядке фаз: fake → split. */
    fun nfqwsArgs(): String = buildList {
        add("--dpi-desync=" + if (fake && method != "fake") "fake,$method" else method)
        if (fake) { add("--dpi-desync-ttl=$ttl"); add("--dpi-desync-fooling=md5sig") }
    }.joinToString(" ")
}

object LinkParser {
    private val schemes = mapOf(
        "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "ss" to "Shadowsocks",
        "hy2" to "Hysteria2", "hysteria2" to "Hysteria2", "socks" to "SOCKS5", "wireguard" to "WireGuard"
    )

    /** Список ссылок (по одной в строке) или base64 такого списка. */
    fun parse(text: String, group: String): List<Server> {
        val raw = text.trim()
        val body = if ("://" in raw) raw else runCatching { String(Base64.decode(raw, Base64.DEFAULT)) }.getOrDefault("")
        return body.lines().mapNotNull { one(it.trim(), group) }
    }

    private fun one(u: String, group: String): Server? {
        val proto = schemes[u.substringBefore("://", "")] ?: return null
        if (proto == "VMess") return vmess(u, group)
        val uri = Uri.parse(u)
        val frag = uri.fragment ?: proto
        // Happ: vless://…#Название?serverDescription=<base64> — подпись под именем сервера
        val name = frag.substringBefore("?serverDescription=").ifBlank { proto }
        val desc = decodeDesc(frag.substringAfter("?serverDescription=", ""))
        return Server(name, flagOf(name), proto, uri.host ?: "", uri.port.takeIf { it > 0 } ?: 443, 0, false, group, "", desc)
    }

    private fun vmess(u: String, group: String): Server? = runCatching {
        val j = JSONObject(String(Base64.decode(u.removePrefix("vmess://").substringBefore("#"), Base64.DEFAULT)))
        val name = j.optString("ps").ifBlank { "VMess" }
        Server(name, flagOf(name), "VMess", j.optString("add"), j.optInt("port", 443), 0, false, group, "", decodeDesc(j.optString("serverDescription")))
    }.getOrNull()

    /** JSON-подписка Happ/Xray: массив (или один объект) готовых конфигов с полем remarks. Полный конфиг сохраняется в Server.config. */
    fun fromJson(text: String, group: String): List<Server> = runCatching {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        val skip = setOf("", "freedom", "blackhole", "dns", "direct", "block", "loopback")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val outs = o.optJSONArray("outbounds") ?: return@mapNotNull null
            val list = (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }
            val ob = list.firstOrNull { it.optString("tag") == "proxy" && it.optString("protocol") !in skip }
                ?: list.firstOrNull { it.optString("protocol") !in skip } ?: return@mapNotNull null
            val p = ob.optString("protocol")
            val st = ob.optJSONObject("settings")
            var host: String; var port: Int
            if (p == "wireguard") {
                val e = st?.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint").orEmpty()
                host = e.substringBeforeLast(":"); port = e.substringAfterLast(":", "").toIntOrNull() ?: 51820
            } else {
                val ep = st?.optJSONArray("vnext")?.optJSONObject(0) ?: st?.optJSONArray("servers")?.optJSONObject(0) ?: st
                host = ep?.optString("address") ?: ""; port = ep?.optInt("port", 443) ?: 443
            }
            val sec = ob.optJSONObject("streamSettings")?.optString("security").orEmpty()
            val proto = p.uppercase() + if (sec == "reality") " Reality" else ""
            val name = o.optString("remarks").ifBlank { proto }
            val desc = decodeDesc(o.optJSONObject("meta")?.optString("serverDescription").orEmpty())
            Server(name, flagOf(name), proto, host, port, 0, false, group, "", desc, o.toString())
        }
    }.getOrDefault(emptyList())

    private fun decodeDesc(d: String): String {
        if (d.isBlank()) return ""
        val dec = runCatching { String(Base64.decode(d, Base64.DEFAULT)) }.getOrNull()
        return (if (dec != null && dec.isNotBlank() && dec.none { it.isISOControl() }) dec else d).take(30)
    }

    fun flagOf(s: String): String {
        val cps = s.codePoints().toArray()
        if (cps.size >= 2 && cps[0] in 127462..127487 && cps[1] in 127462..127487)
            return "" + (cps[0] - 127397).toChar() + (cps[1] - 127397).toChar()
        return "??"
    }
}
