package app.vela

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

enum class Pal(val title: String, val c1: Color, val c2: Color) {
    Vela("Vela", Color(0xFF7B5CFF), Color(0xFF35E0D0)),
    Aurora("Аврора", Color(0xFFC77DFF), Color(0xFF5CFFC4)),
    Sunset("Закат", Color(0xFFFF4F8B), Color(0xFFFF8A5B)),
    Ocean("Океан", Color(0xFF6C7BFF), Color(0xFF4CC3FF))
}

enum class Mode(val title: String) { Light("Светлая"), Dark("Тёмная"), Auto("Авто") }

@Immutable
class VC(val bg: Color, val sf: Color, val sf2: Color, val tx: Color, val mu: Color,
         val ac: Color, val ac2: Color, val wm: Color, val ln: Color)

private fun v(bg: Long, sf: Long, sf2: Long, tx: Long, mu: Long, ac: Long, ac2: Long, wm: Long, ln: Long) =
    VC(Color(0xFF000000 or bg), Color(0xFF000000 or sf), Color(0xFF000000 or sf2), Color(0xFF000000 or tx),
        Color(0xFF000000 or mu), Color(0xFF000000 or ac), Color(0xFF000000 or ac2),
        Color(0xFF000000 or wm), Color(0xFF000000 or ln))

fun palette(p: Pal, dark: Boolean): VC = when (p) {
    Pal.Vela -> if (dark) v(0x0F1030, 0x191C45, 0x23275A, 0xECEBFF, 0x8F92C4, 0x35E0D0, 0x9B84FF, 0xFF9566, 0x2C3070)
    else v(0xF1F2FA, 0xFFFFFF, 0xE6E8F6, 0x15163A, 0x6A6D9B, 0x0DAF9F, 0x7B5CFF, 0xF0733F, 0xD9DBEE)
    Pal.Aurora -> if (dark) v(0x08171A, 0x0F2A2E, 0x173C42, 0xE6FFF8, 0x7FB4AC, 0x5CFFC4, 0xC77DFF, 0xFFB86B, 0x1F4A50)
    else v(0xEEF8F6, 0xFFFFFF, 0xDDF0EC, 0x0C2A2A, 0x5E8884, 0x00A88A, 0x9B4DE0, 0xE8782E, 0xCBE3DE)
    Pal.Sunset -> if (dark) v(0x1A0F1F, 0x2A1830, 0x3A2243, 0xFFEFE8, 0xC59AAE, 0xFF8A5B, 0xFF4F8B, 0xFFD166, 0x4A2C52)
    else v(0xFFF3EE, 0xFFFFFF, 0xFFE3D8, 0x3A1830, 0x9A6A78, 0xF0603A, 0xD6336C, 0xE39A00, 0xF4D5CB)
    Pal.Ocean -> if (dark) v(0x07142B, 0x0E2447, 0x163463, 0xE8F1FF, 0x8AA8D6, 0x4CC3FF, 0x6C7BFF, 0xFF8F6B, 0x1D3B70)
    else v(0xEAF3FF, 0xFFFFFF, 0xD9E8FB, 0x0B2148, 0x5879A8, 0x1493E0, 0x4E5CE6, 0xF0733F, 0xC9DBF2)
}

val LocalVC = staticCompositionLocalOf { palette(Pal.Vela, true) }

/** Все цвета плавно перетекают за 700 мс — смена темы и палитры без рывков. */
@Composable
fun VelaTheme(pal: Pal, dark: Boolean, content: @Composable () -> Unit) {
    val t = palette(pal, dark)
    val spec = tween<Color>(700)
    val c = VC(
        animateColorAsState(t.bg, spec, label = "bg").value, animateColorAsState(t.sf, spec, label = "sf").value,
        animateColorAsState(t.sf2, spec, label = "sf2").value, animateColorAsState(t.tx, spec, label = "tx").value,
        animateColorAsState(t.mu, spec, label = "mu").value, animateColorAsState(t.ac, spec, label = "ac").value,
        animateColorAsState(t.ac2, spec, label = "ac2").value, animateColorAsState(t.wm, spec, label = "wm").value,
        animateColorAsState(t.ln, spec, label = "ln").value
    )
    val base = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalVC provides c) {
        MaterialTheme(base.copy(background = c.bg, surface = c.sf, primary = c.ac, onSurface = c.tx), content = content)
    }
}
