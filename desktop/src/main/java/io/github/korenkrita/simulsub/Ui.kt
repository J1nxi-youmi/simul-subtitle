package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.LocalContentColor
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** magpie's palette, as the phone's res/values(-night)/colors.xml. */
class Palette2(
    val brand: Color, val onBrand: Color, val brandSoft: Color, val bg: Color, val card: Color, val pill: Color,
    val line: Color, val line2: Color, val segTrack: Color, val segThumb: Color, val text: Color, val text2: Color,
    val muted: Color, val faint: Color, val ok: Color, val warn: Color, val warnSoft: Color, val err: Color, val errSoft: Color,
    val dark: Boolean,
)

val LIGHT = Palette2(Color(0xFF4F46E5), Color(0xFFFFFFFF), Color(0xFFEEEDFD), Color(0xFFF4F4F6), Color(0xFFFFFFFF),
    Color(0xFFF1F1F4), Color(0xFFE3E3E8), Color(0xFFECECF0), Color(0xFFE6E6EB), Color(0xFFFFFFFF), Color(0xFF1C1C21),
    Color(0xFF4B4B53), Color(0xFF85858D), Color(0xFFB9B9C2), Color(0xFF189A58), Color(0xFFB45309), Color(0xFFFBF0DF), Color(0xFFD64545),
    Color(0xFFFBE9E9), dark = false)
val DARK = Palette2(Color(0xFFA5B4FC), Color(0xFF14142A), Color(0xFF2B2C47), Color(0xFF1A1A1E), Color(0xFF232327),
    Color(0xFF2D2D33), Color(0xFF333339), Color(0xFF2C2C32), Color(0xFF29292D), Color(0xFF444448), Color(0xFFEDEDF1),
    Color(0xFFB9B9C1), Color(0xFF8B8B94), Color(0xFF56565F), Color(0xFF6FD99B), Color(0xFFF2B544), Color(0xFF3F3220), Color(0xFFF28B82),
    Color(0xFF45272A), dark = true)

val LocalC = staticCompositionLocalOf { LIGHT }
val C: Palette2 @Composable get() = LocalC.current

@Composable
fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DARK else LIGHT
    CompositionLocalProvider(LocalC provides c, LocalContentColor provides c.text) { content() }
}

// ---- controls, in magpie's look ---------------------------------------------------------------------------

/** a group heading above its card ("外观", "语言") */
@Composable fun GroupTitle(t: String) =
    Text(t, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 8.dp))

/** magpie's card: white (or ink) on the page, a hairline edge, 12 dp corners */
@Composable fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(C.card, RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp))
        .padding(horizontal = 16.dp, vertical = 12.dp), content = content)

@Composable fun Group(title: String, content: @Composable ColumnScope.() -> Unit) { GroupTitle(title); Card(content = content) }

@Composable fun Hint(t: String, color: Color = C.text2) = Text(t, color = color, fontSize = 13.sp, lineHeight = 20.sp,
    modifier = Modifier.padding(vertical = 6.dp))

@Composable fun Label(t: String) = Text(t, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

/** the warning box (magpie's warn-soft note) */
@Composable fun Note(t: String) = Text(t, color = C.text, fontSize = 13.sp, lineHeight = 20.sp,
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(C.warnSoft, RoundedCornerShape(8.dp)).padding(10.dp))

@Composable fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line2))

/** magpie's segmented control: a grey track with a white (or grey) thumb on the chosen one */
@Composable fun <T> Seg(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(C.segTrack, RoundedCornerShape(10.dp)).padding(3.dp)) {
        for ((v, label) in options) {
            val on = v == selected
            Box(Modifier.weight(1f).height(34.dp)
                .then(if (on) Modifier.background(C.segThumb, RoundedCornerShape(8.dp)) else Modifier)
                .clickable(enabled = enabled && !on) { onPick(v) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!enabled) C.faint else if (on) C.text else C.text2)
            }
        }
    }
}

/** a setting with a switch on the right; [hint] below the title */
@Composable fun SwitchRow(title: String, hint: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (enabled) C.text else C.faint)
            hint?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2, modifier = Modifier.padding(top = 2.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Pill(on, enabled) { onChange(!on) }
    }
}

/** magpie's switch: a 52×32 pill, ink when on, with a round white knob */
@Composable fun Pill(on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 2.dp)
    Box(Modifier.size(52.dp, 32.dp).background(if (on) C.text else C.line, RoundedCornerShape(16.dp))
        .clickable(enabled) { onClick() }.then(if (enabled) Modifier else Modifier.alpha(0.4f))) {
        Box(Modifier.padding(start = x, top = 2.dp).size(28.dp).background(if (on && C.dark) C.bg else Color.White, CircleShape))
    }
}

/** a full-width dropdown in a pill (magpie's select) */
@Composable fun Dropdown(items: List<String>, selected: Int, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(C.pill, RoundedCornerShape(8.dp)).clickable(enabled) { open = true }
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(items.getOrElse(selected) { "" }, fontSize = 15.sp, color = if (enabled) C.text else C.faint, modifier = Modifier.weight(1f), maxLines = 1)
            Text("⌄", color = C.muted, fontSize = 14.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(C.card)) {
            items.forEachIndexed { i, t ->
                DropdownMenuItem(onClick = { open = false; if (i != selected) onPick(i) }, modifier = Modifier.heightIn(min = 32.dp)) {
                    Text(t, fontSize = 14.sp, color = C.text, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}


/** magpie's slider: ink on a grey line */
@Composable fun Slider2(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean = true, onChange: (Float) -> Unit) =
    Slider(value, onChange, valueRange = range, steps = steps, enabled = enabled, colors = SliderDefaults.colors(
        thumbColor = C.text, activeTrackColor = C.text, inactiveTrackColor = C.line,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))

/** a button: [primary] filled in the brand colour, otherwise outlined; [danger] in red text */
@Composable fun Btn(t: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true,
                    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 34.dp)
        .then(if (primary) Modifier.background(if (enabled) C.brand else C.brand.copy(alpha = 0.5f), shape)
              else Modifier.border(1.dp, C.line, shape))
        .clickable(enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = when { !enabled -> C.faint; primary -> C.onBrand; danger -> C.err; else -> C.text })
    }
}

/** a borderless text button (清除、删除) */
@Composable fun TextBtn(t: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    Text(t, fontSize = 14.sp, color = if (!enabled) C.faint else if (danger) C.err else C.text2,
        modifier = Modifier.clickable(enabled) { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp))

@Composable fun RowScope.Grow() = Spacer(Modifier.weight(1f))

/** a one-line text field in a pill; [password] hides the text */
@Composable fun Field(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, enabled: Boolean = true) {
    Box(Modifier.fillMaxWidth().height(38.dp).background(C.pill, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = C.muted, fontSize = 14.sp, maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = if (enabled) C.text else C.faint, fontSize = 14.sp), cursorBrush = SolidColor(C.brand),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

/** a multi-line text field, [minLines] high, growing with its text */
@Composable fun Area(value: String, onChange: (String) -> Unit, placeholder: String, minLines: Int = 3) {
    Box(Modifier.fillMaxWidth().heightIn(min = (20 * minLines + 16).dp).background(C.pill, RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Text(placeholder, color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
        BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = C.text, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(C.brand))
    }
}

/** the status dot of the home card */
@Composable fun Dot(color: Color) = Box(Modifier.size(9.dp).background(color, CircleShape))

