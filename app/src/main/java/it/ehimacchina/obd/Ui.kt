package it.ehimacchina.obd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ colori
// Palette scura da cruscotto: sfondo quasi nero, superfici leggermente più chiare,
// un solo colore d'accento (ciano) e tre colori di stato usati ovunque nello stesso modo.

/** Colori dell'app per un tema (scuro o chiaro). */
data class AppColors(
    val background: Color, val surface: Color, val surfaceHigh: Color, val outline: Color,
    val text: Color, val muted: Color, val accent: Color, val onAccent: Color, val accentContainer: Color,
    val ok: Color, val warn: Color, val crit: Color, val dark: Boolean,
)

/** Tema scuro: sfondo quasi nero, accento ciano (predefinito, adatto di notte in auto). */
val DarkColors = AppColors(
    background = Color(0xFF0B0E13), surface = Color(0xFF141922), surfaceHigh = Color(0xFF1C2330), outline = Color(0xFF2A3242),
    text = Color(0xFFE8ECF2), muted = Color(0xFF8B95A7), accent = Color(0xFF4CC9F0), onAccent = Color(0xFF00222E),
    accentContainer = Color(0xFF0F3A4A), ok = Color(0xFF34D399), warn = Color(0xFFFBBF24), crit = Color(0xFFF87171), dark = true,
)

/** Tema chiaro: sfondo grigio chiarissimo, colori più scuri e saturi per restare leggibili al sole. */
val LightColors = AppColors(
    background = Color(0xFFF3F5F8), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEAEEF3), outline = Color(0xFFD3DAE3),
    text = Color(0xFF12161C), muted = Color(0xFF5B6577), accent = Color(0xFF0A7FAE), onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFD6F0FA), ok = Color(0xFF15803D), warn = Color(0xFFB45309), crit = Color(0xFFDC2626), dark = false,
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

/**
 * Accesso ai colori del tema corrente (scuro o chiaro), usato in tutta l'interfaccia.
 * Stessa palette dappertutto: un accento e tre colori di stato (ok, attenzione, critico).
 */
object Palette {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceHigh
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.outline
    val Text: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.ok
    val Warn: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warn
    val Crit: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.crit
}

/** Colore per un livello di avviso (null = normale). */
@Composable
fun levelColor(l: Level?, normal: Color = Palette.Text) = when (l) {
    Level.CRIT -> Palette.Crit
    Level.WARN -> Palette.Warn
    null -> normal
}

private fun schemeFor(c: AppColors) = (if (c.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentContainer,
    onPrimaryContainer = c.accent,
    secondary = c.accent,
    secondaryContainer = c.surfaceHigh,
    onSecondaryContainer = c.text,
    background = c.background,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceHigh,
    onSurfaceVariant = c.muted,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceHigh,
    surfaceContainerHighest = c.surfaceHigh,
    outline = c.outline,
    outlineVariant = c.outline,
    error = c.crit,
)

@Composable
fun EhiTheme(light: Boolean = false, content: @Composable () -> Unit) {
    val c = if (light) LightColors else DarkColors
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = schemeFor(c), content = content)
    }
}

/** Numeri grandi con cifre a larghezza fissa: non "ballano" quando cambiano. */
val Numbers = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)

// ------------------------------------------------------------------ struttura pagine

/** Pagina con barra in alto (indietro, titolo, azioni) e contenuto scorrevole. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onTitleLongPress: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            }
            Column(
                Modifier.weight(1f).let { m ->
                    if (onTitleLongPress != null) m.combinedClickable(onClick = {}, onLongClick = onTitleLongPress) else m
                },
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Scheda arrotondata con titolo opzionale. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
                            letterSpacing = 1.2.sp, modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

// ------------------------------------------------------------------ piccoli elementi

@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Etichetta arrotondata con pallino colorato, es. lo stato della connessione. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Avviso a tutta larghezza (giallo = attenzione, rosso = critico, ciano = informazione). */
@Composable
fun Banner(text: String, color: Color, icon: ImageVector = Icons.Filled.Warning, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Apri", tint = color)
    }
}

@Composable
fun InfoBanner(text: String, onClick: (() -> Unit)? = null) = Banner(text, Palette.Accent, Icons.Filled.Info, onClick)

/** Riga di una lista di controllo: spunta verde se fatto, cerchio vuoto se no. */
@Composable
fun CheckRow(done: Boolean, label: String, detail: String? = null, pendingColor: Color = Palette.Muted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "fatto", tint = Palette.Ok, modifier = Modifier.size(20.dp))
        } else {
            Box(Modifier.size(20.dp).padding(2.dp).border(2.dp, pendingColor, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (done) Palette.Text else Palette.Text.copy(alpha = 0.85f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (done) Palette.Muted else pendingColor)
        }
    }
}

/** Etichetta a sinistra, valore a destra. */
@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Numbers), color = valueColor)
    }
}

/** Codice errore come "badge" monospazio colorato. */
@Composable
fun CodeBadge(code: String, color: Color) {
    Text(
        code,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Testo secondario piccolo e grigio (spiegazioni). */
@Composable
fun Hint(text: String, color: Color = Palette.Muted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Barra sottile di livello 0..1 (es. giri rispetto al massimo). */
@Composable
fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
    }
}

/** Riquadro del cruscotto: etichetta, valore grande con unità, barra opzionale. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    valueSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Text,
    level: Float? = null,
    levelColor: Color = Palette.Accent,
) {
    val alarm = color != Palette.Text
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (alarm) color.copy(alpha = 0.10f) else Palette.Surface)
            .border(1.dp, if (alarm) color.copy(alpha = 0.6f) else Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, letterSpacing = 1.2.sp, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            Text(value, style = Numbers, fontSize = valueSize, color = color, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.labelLarge, color = Palette.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (level != null) LevelBar(level, levelColor) else Spacer(Modifier.height(4.dp))
    }
}

/** Scheda selezionabile (per scegliere la sorgente dei dati). */
@Composable
fun ChoiceCard(selected: Boolean, title: String, description: String, icon: Painter, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val border = if (selected) Palette.Accent else Palette.Outline.copy(alpha = 0.6f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Accent.copy(alpha = 0.10f) else Palette.Surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Palette.Accent else Palette.Muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** Pulsante di navigazione grande, con icona sopra e testo sotto. */
@Composable
fun NavTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(26.dp))
            if (badge != null) {
                Text(
                    badge, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp)
                        .clip(RoundedCornerShape(50)).background(Palette.Warn).padding(horizontal = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Grafico a linea minimale: valori nel tempo fra [min] e [max], con una linea di riferimento
 * tratteggiata (es. 0,45 V per la sonda, 0 % per la correzione). I null interrompono la linea.
 */
@Composable
fun Sparkline(values: List<Double?>, min: Double, max: Double, reference: Double?, color: Color, modifier: Modifier = Modifier) {
    val refColor = Palette.Muted.copy(alpha = 0.6f) // letto qui: dentro il Canvas non si può leggere il tema
    Canvas(modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)) {
        fun y(v: Double) = size.height * (1 - ((v - min) / (max - min)).coerceIn(0.0, 1.0)).toFloat()
        if (reference != null) {
            drawLine(
                refColor, Offset(0f, y(reference)), Offset(size.width, y(reference)),
                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val path = Path()
        var pen = false
        values.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val x = i * step
            if (pen) path.lineTo(x, y(v)) else path.moveTo(x, y(v))
            pen = true
        }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

/** Etichetta per i dati nati col simulatore o con la riproduzione di un log. */
@Composable
fun SimBadge() {
    Text(
        "SIMULATO", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Palette.Accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Accent.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
