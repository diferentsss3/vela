package app.vela

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun c(h: Long) = Color(0xFF000000 or h)

// Флаги рисуются в сетке 30×20 и обрезаются кругом (видна центральная часть 20×20).
private fun DrawScope.hs(vararg cols: Long) {
    val h = 20f / cols.size
    cols.forEachIndexed { i, x -> drawRect(c(x), Offset(0f, i * h), Size(30f, h + .05f)) }
}
private fun DrawScope.vs(vararg cols: Long) {
    val w = 30f / cols.size
    cols.forEachIndexed { i, x -> drawRect(c(x), Offset(i * w, 0f), Size(w + .05f, 20f)) }
}
private fun DrawScope.bg(x: Long) = drawRect(c(x), Offset.Zero, Size(30f, 20f))
private fun DrawScope.cross(fg: Long, x: Float = 9f, w: Float = 4f) {
    drawRect(c(fg), Offset(x, 0f), Size(w, 20f)); drawRect(c(fg), Offset(0f, 10f - w / 2), Size(30f, w))
}

private fun DrawScope.flag(code: String): Boolean {
    when (code) {
        "NL" -> hs(0xAE1C28, 0xFFFFFF, 0x21468B)
        "DE" -> hs(0x151515, 0xDD0000, 0xFFCE00)
        "PL" -> hs(0xFFFFFF, 0xDC143C)
        "AT" -> hs(0xED2939, 0xFFFFFF, 0xED2939)
        "UA" -> hs(0x0057B7, 0xFFD700)
        "RU" -> hs(0xFFFFFF, 0x0039A6, 0xD52B1E)
        "FR" -> vs(0x0055A4, 0xFFFFFF, 0xEF4135)
        "IT" -> vs(0x009246, 0xFFFFFF, 0xCE2B37)
        "FI" -> { bg(0xFFFFFF); cross(0x003580) }
        "SE" -> { bg(0x006AA7); cross(0xFECC00) }
        "DK" -> { bg(0xC8102E); cross(0xFFFFFF) }
        "CH" -> { bg(0xDA291C); cross(0xFFFFFF, 13f, 4f); drawRect(c(0xFFFFFF), Offset(9f, 8f), Size(12f, 4f)); drawRect(c(0xFFFFFF), Offset(13f, 4f), Size(4f, 12f)) }
        "JP" -> { bg(0xFFFFFF); drawCircle(c(0xBC002D), 6f, Offset(15f, 10f)) }
        "TR" -> { bg(0xE30A17); drawCircle(Color.White, 5f, Offset(13f, 10f)); drawCircle(c(0xE30A17), 4f, Offset(14.4f, 10f)); drawCircle(Color.White, 1.6f, Offset(18f, 10f)) }
        "US" -> { bg(0xFFFFFF); for (i in 0..6) drawRect(c(0xB22234), Offset(0f, i * 3.08f), Size(30f, 1.54f)); drawRect(c(0x3C3B6E), Offset.Zero, Size(13f, 10.8f)) }
        "GB" -> {
            bg(0x012169)
            for ((col, sw) in listOf(0xFFFFFFL to 4f, 0xC8102EL to 1.6f)) {
                drawLine(c(col), Offset(0f, 0f), Offset(30f, 20f), sw); drawLine(c(col), Offset(30f, 0f), Offset(0f, 20f), sw)
            }
            for ((col, sw) in listOf(0xFFFFFFL to 6f, 0xC8102EL to 3.4f)) {
                drawLine(c(col), Offset(15f, 0f), Offset(15f, 20f), sw); drawLine(c(col), Offset(0f, 10f), Offset(30f, 10f), sw)
            }
        }
        else -> return false
    }
    return true
}

/** Круглый стилизованный флаг: кольцо, тень и блик. Неизвестная страна — цветной значок с кодом. */
@Composable
fun Flag(code: String, size: Dp = 38.dp) {
    val vc = LocalVC.current
    val tm = rememberTextMeasurer()
    Box(Modifier.size(size).shadow(5.dp, CircleShape).border(2.dp, vc.sf, CircleShape).padding(2.dp).clip(CircleShape)) {
        Canvas(Modifier.size(size)) {
            val s = this.size.height / 20f
            withTransform({ scale(s, s, Offset.Zero); translate(-5f, 0f) }) {
                if (!flag(code)) {
                    val h = ((code.getOrElse(0) { '?' }.code * 37 + code.getOrElse(1) { '?' }.code * 91) % 360).toFloat()
                    drawRect(Color.hsv(h, .55f, .72f), Offset.Zero, Size(30f, 20f))
                    drawRect(Color.hsv((h + 35f) % 360f, .55f, .58f), Offset(0f, 10f), Size(30f, 10f))
                }
            }
            if (!withTransformFlag(code)) {
                val t = tm.measure(code, TextStyle(color = Color.White, fontSize = (this.size.height * .3f / density * 1f).sp, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset((this.size.width - t.size.width) / 2f, (this.size.height - t.size.height) / 2f))
            }
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .45f), Color.Transparent), Offset(this.size.width * .3f, this.size.height * .22f), this.size.width * .6f))
        }
    }
}

private fun DrawScope.withTransformFlag(code: String) = code in KNOWN
private val KNOWN = setOf("NL", "DE", "PL", "AT", "UA", "RU", "FR", "IT", "FI", "SE", "DK", "CH", "JP", "TR", "US", "GB")
