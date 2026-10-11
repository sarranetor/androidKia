package it.ehimacchina.obd

import androidx.compose.foundation.BorderStroke
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

object Palette {
    val Background = Color(0xFF0B0E13)
    val Surface = Color(0xFF141922)
    val SurfaceHigh = Color(0xFF1C2330)
    val Outline = Color(0xFF2A3242)
    val Text = Color(0xFFE8ECF2)
    val Muted = Color(0xFF8B95A7)
    val Accent = Color(0xFF4CC9F0)
    val OnAccent = Color(0xFF00222E)
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Crit = Color(0xFFF87171)
}

/** Colore per un livello di avviso (null = normale). */
fun levelColor(l: Level?, normal: Color = Palette.Text) = when (l) {
    Level.CRIT -> Palette.Crit
    Level.WARN -> Palette.Warn
    null -> normal
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Color(0xFF0F3A4A),
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Accent,
    secondaryContainer = Palette.SurfaceHigh,
    onSecondaryContainer = Palette.Text,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Crit,
)

@Composable
fun EhiTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

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
