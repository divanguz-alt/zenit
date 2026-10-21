package io.github.divanguz_alt.zenit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.divanguz_alt.zenit.ui.DashboardState
import io.github.divanguz_alt.zenit.ui.Format
import io.github.divanguz_alt.zenit.ui.SignalBar
import io.github.divanguz_alt.zenit.ui.theme.Numeric
import io.github.divanguz_alt.zenit.ui.theme.Z
import io.github.divanguz_alt.zenit.ui.theme.ZType
import io.github.divanguz_alt.zenit.gnss.Constellation

@Composable
fun SectionTitle(text: String, top: Dp = 12.dp) {
    Text(text, style = ZType.section, modifier = Modifier.padding(start = 8.dp, top = top))
}

@Composable
fun ZCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Z.Card),
    ) { content() }
}

@Composable
private fun VDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(Z.Divider))

/** One metric cell: label, value + unit, optional sub-line. Shows "Not available" for missing sensors. */
@Composable
fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    sub: String? = null,
    available: Boolean = true,
    first: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (first) 18.dp else 16.dp, end = 6.dp, top = 15.dp, bottom = 15.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("metric:$label"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = ZType.label, maxLines = 1)
        if (!available) {
            Text(Format.DASH, style = ZType.value)
            Text("Not available", style = ZType.sub, maxLines = 1)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = ZType.value, maxLines = 1, modifier = Modifier.alignByBaseline(), softWrap = false)
            if (unit.isNotEmpty()) Text(unit, style = ZType.unit, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        if (sub != null) Text(sub, style = ZType.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Three equal columns separated by hairlines. */
@Composable
fun MetricRow(content: List<@Composable (Modifier) -> Unit>) {
    ZCard {
        Row(Modifier.height(IntrinsicSize.Min)) {
            content.forEachIndexed { i, cell ->
                if (i > 0) VDivider()
                cell(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun PositionCard(d: DashboardState) {
    ZCard {
        Column {
            Row(Modifier.height(IntrinsicSize.Min)) {
                CoordinateCell("Latitude", d.latitude, d.latitudeSecondary, Modifier.weight(1f))
                VDivider()
                CoordinateCell("Longitude", d.longitude, d.longitudeSecondary, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Z.Divider))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Last fix", style = ZType.label.copy(fontWeight = null))
                Text(
                    d.lastFix + if (d.fixIsStale && d.lastFix != Format.DASH) " · stale" else "",
                    style = Numeric.copy(fontSize = 12.sp, color = if (d.fixIsStale) Z.TextSecondary else Z.Text),
                    modifier = Modifier.testTag("lastFix"),
                )
            }
        }
    }
}

@Composable
private fun CoordinateCell(label: String, value: String, secondary: String, modifier: Modifier) {
    Column(
        modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 14.dp).semantics(mergeDescendants = true) {}.testTag("coord:$label"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, style = ZType.label)
        Text(value, style = ZType.value, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        Text(secondary.ifEmpty { " " }, style = ZType.sub, maxLines = 1)
    }
}

/** C/N0 bars per satellite grouped by constellation: used = filled, unused = outline only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalCard(bars: List<SignalBar>, legend: List<io.github.divanguz_alt.zenit.ui.LegendEntry>, hasFix: Boolean) {
    ZCard {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().height(62.dp)) {
                if (bars.isEmpty()) {
                    Text("No satellites in view yet", style = ZType.sub, modifier = Modifier.align(Alignment.Center))
                }
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    bars.forEach { b -> Bar(b, hasFix, Modifier.weight(1f)) }
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                legend.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(l.constellation.color)))
                        Text(l.constellation.displayName, style = TextStyle(fontSize = 12.sp, color = Z.Text))
                        Text("${l.used}/${l.total}", style = Numeric.copy(fontSize = 12.sp, color = Z.TextSecondary))
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(b: SignalBar, hasFix: Boolean, modifier: Modifier) {
    val col = Color(b.constellation.color)
    val target = (b.cn0 * 1.25f).coerceIn(3f, 62f).dp
    val h = animateDpAsState(target, tween(600), label = "bar").value
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
    val stroke = if (hasFix) col else Z.Outline
    Box(
        modifier
            .height(h)
            .clip(shape)
            .background(if (hasFix && b.used) col else Color.Transparent, shape)
            .border(1.dp, stroke.copy(alpha = if (hasFix) (if (b.used) 1f else 0.55f) else 0.8f), shape)
            .testTag("bar:${b.label}"),
    )
}

/** Always-visible bottom bar: heading arrow, orientation, error and fix/sats. */
@Composable
fun BottomBar(d: DashboardState, heading: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Z.BottomBar)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            BarCell("Heading", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeadingArrow(heading)
                    Text(d.headingText, style = ZType.barValue, modifier = Modifier.testTag("heading"))
                }
            }
            BarDivider()
            BarCell("Orientation", Modifier.weight(1f)) { Text(d.orientation, style = ZType.barValue.copy(fontFeatureSettings = null)) }
            BarDivider()
            BarCell("Error", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.error, style = ZType.barValue, modifier = Modifier.alignByBaseline())
                    Text("m", style = ZType.unit, modifier = Modifier.alignByBaseline())
                }
            }
            BarDivider()
            BarCell("Fix / Sats", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        d.usedCount.toString(),
                        style = ZType.barValue.copy(color = if (d.usedCount > 0) Z.Accent else Z.Text),
                        modifier = Modifier.alignByBaseline().testTag("usedCount"),
                    )
                    Text("/${d.visibleCount}", style = Numeric.copy(fontSize = 15.sp, color = Z.TextSecondary), modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
}

@Composable
private fun BarDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x12FFFFFF)))

@Composable
private fun BarCell(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        Text(label, style = ZType.barLabel)
    }
}

/** Red triangle that rotates to the heading along the shortest path. */
@Composable
private fun HeadingArrow(heading: Float?) {
    val angle = remember { Animatable(heading ?: 0f) }
    LaunchedEffect(heading) {
        if (heading == null) return@LaunchedEffect
        val current = angle.value
        val delta = ((heading - current) % 360f + 540f) % 360f - 180f
        angle.animateTo(current + delta, tween(800))
    }
    Canvas(Modifier.size(width = 10.dp, height = 11.dp).rotate(angle.value)) {
        val path = Path().apply {
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, if (heading == null) Z.Outline else Z.Heading)
    }
}

@Composable
fun VerticalSpace(h: Dp) = Spacer(Modifier.height(h))
