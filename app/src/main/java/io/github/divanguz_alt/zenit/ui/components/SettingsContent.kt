package io.github.divanguz_alt.zenit.ui.components

import androidx.compose.foundation.Canvas
import io.github.divanguz_alt.zenit.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.divanguz_alt.zenit.settings.AltitudeUnit
import io.github.divanguz_alt.zenit.settings.AppSettings
import io.github.divanguz_alt.zenit.settings.CoordinateFormat
import io.github.divanguz_alt.zenit.settings.GlobeStyle
import io.github.divanguz_alt.zenit.settings.HeadingReference
import io.github.divanguz_alt.zenit.settings.PressureUnit
import io.github.divanguz_alt.zenit.settings.SpeedUnit
import io.github.divanguz_alt.zenit.ui.theme.Z
import kotlin.math.cos
import kotlin.math.sin

/** Settings content shown in the ⋮ sheet. Stateless: every change goes through [onChange]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsContent(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    orbitDataAge: String,
    orbitMessage: String?,
    refreshingOrbits: Boolean,
    onRefreshOrbits: () -> Unit,
    versionName: String,
    updateAvailable: Boolean,
    onVersionLongPress: () -> Unit,
    onOpenGithub: () -> Unit,
    onCheckUpdates: () -> Unit,
    onLicenses: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("Satellites, live on the globe.", fontSize = 13.sp, color = Z.TextSecondary)
        }

        Group("Globe style") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleTile("Illustrated", "Dot-matrix land", settings.globeStyle == GlobeStyle.ILLUSTRATED, Modifier.weight(1f), illustrated = true) {
                    onChange { it.copy(globeStyle = GlobeStyle.ILLUSTRATED) }
                }
                StyleTile("Realistic", "Satellite imagery", settings.globeStyle == GlobeStyle.REALISTIC, Modifier.weight(1f), illustrated = false) {
                    onChange { it.copy(globeStyle = GlobeStyle.REALISTIC) }
                }
            }
            ToggleRow(
                "Day/night terminator", settings.dayNightTerminator, enabled = settings.globeStyle == GlobeStyle.REALISTIC,
                sub = "Real sun position with city lights (Realistic only)",
            ) { v -> onChange { it.copy(dayNightTerminator = v) } }
        }

        Group("Units") {
            Choice("Speed", SpeedUnit.entries, settings.speedUnit, { it.label }) { v -> onChange { it.copy(speedUnit = v) } }
            Choice("Altitude", AltitudeUnit.entries, settings.altitudeUnit, { it.label }) { v -> onChange { it.copy(altitudeUnit = v) } }
            Choice("Pressure", PressureUnit.entries, settings.pressureUnit, { it.label }) { v -> onChange { it.copy(pressureUnit = v) } }
            Choice("Coordinates", CoordinateFormat.entries, settings.coordinateFormat, { it.label }) { v -> onChange { it.copy(coordinateFormat = v) } }
            Choice("Heading", HeadingReference.entries, settings.headingReference, { it.label }) { v -> onChange { it.copy(headingReference = v) } }
        }

        Group("Display") {
            ToggleRow("Signal waves", settings.signalWaves) { v -> onChange { it.copy(signalWaves = v) } }
            ToggleRow("Show unused satellites", settings.showUnusedSatellites) { v -> onChange { it.copy(showUnusedSatellites = v) } }
            ToggleRow("Show all constellation satellites", settings.showAllConstellationSatellites) { v ->
                onChange { it.copy(showAllConstellationSatellites = v) }
            }
            ToggleRow("True-scale orbits", settings.trueScaleOrbits, sub = "Real orbit proportions; the Earth zooms out") { v ->
                onChange { it.copy(trueScaleOrbits = v) }
            }
            ToggleRow("ISS time-lapse", settings.issTimeLapse, sub = "Tap the globe to show the ISS") { v -> onChange { it.copy(issTimeLapse = v) } }
            ToggleRow("Use GPS bearing when moving", settings.useGpsBearingWhenMoving, sub = "Above 2 km/h") { v ->
                onChange { it.copy(useGpsBearingWhenMoving = v) }
            }
            ToggleRow("Keep screen on", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
        }

        Group("Data") {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Orbital data updated $orbitDataAge", fontSize = 16.sp, modifier = Modifier.testTag("orbitAge"))
                    Text(orbitMessage ?: "CelesTrak GP elements, refreshed daily", fontSize = 12.sp, color = Z.TextSecondary)
                }
                TextButton(onClick = onRefreshOrbits, enabled = !refreshingOrbits) {
                    Text(if (refreshingOrbits) "Refreshing…" else "Refresh now")
                }
            }
        }

        Group("About") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .combinedClickable(onClick = {}, onLongClick = onVersionLongPress)
                    .testTag("version"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Zenit", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Version $versionName", fontSize = 13.sp, color = Z.TextSecondary)
                }
            }
            LinkRow("GitHub repository", "github.com/divanguz-alt/zenit", onOpenGithub)
            LinkRow("Check for updates", if (updateAvailable) "A new version is available" else null, onCheckUpdates, badge = updateAvailable)
            LinkRow("Licenses & attributions", "NASA, Natural Earth, CelesTrak, satellite.js", onLicenses)
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Z.TextSecondary)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onChange(!checked) }
            .testTag("toggle:$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 16.sp, color = if (enabled) Z.Text else Z.TextTertiary)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Z.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Z.Accent,
                checkedThumbColor = Z.AccentOn,
                uncheckedTrackColor = Z.SheetTile,
                uncheckedBorderColor = Color(0xFF8E918F),
                uncheckedThumbColor = Color(0xFF8E918F),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = Z.TextSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Z.Accent.copy(alpha = 0.18f),
                        activeContentColor = Z.Accent,
                        activeBorderColor = Z.Accent.copy(alpha = 0.6f),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = Z.Text,
                        inactiveBorderColor = Z.Outline,
                    ),
                    icon = {},
                    modifier = Modifier.testTag("choice:$label:${text(opt)}"),
                ) { Text(text(opt), fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, sub: String?, onClick: () -> Unit, badge: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).testTag("link:$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = if (badge) Z.Accent else Z.TextSecondary)
        }
        if (badge) Box(Modifier.size(8.dp).clip(CircleShape).background(Z.Accent))
        Spacer(Modifier.width(4.dp))
        Text("›", fontSize = 22.sp, color = Z.TextSecondary)
    }
}

@Composable
private fun StyleTile(title: String, sub: String, selected: Boolean, modifier: Modifier, illustrated: Boolean, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Z.SheetTile)
            .border(2.dp, if (selected) Z.Accent else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 14.dp)
            .testTag("style:$title"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).shadow(16.dp, CircleShape, ambientColor = Color(0x7346A0FF), spotColor = Color(0x7346A0FF))) {
            Canvas(Modifier.size(64.dp)) {
                val r = size.minDimension / 2
                val c = Offset(r, r)
                if (illustrated) {
                    drawCircle(Color(0xFF07101C), r, c)
                    // Dot-matrix sphere preview
                    var lat = -75.0
                    while (lat <= 75.0) {
                        val n = (12 * cos(Math.toRadians(lat))).toInt().coerceAtLeast(1)
                        for (i in 0 until n) {
                            val lon = -90.0 + (i + 0.5) * 180.0 / n
                            val x = cos(Math.toRadians(lat)) * sin(Math.toRadians(lon))
                            val z = cos(Math.toRadians(lat)) * cos(Math.toRadians(lon))
                            drawCircle(
                                Color(0xFFBFD6FF).copy(alpha = (0.25 + 0.7 * z).toFloat()),
                                radius = 1.1.dp.toPx(),
                                center = Offset(c.x + r * 0.92f * x.toFloat(), c.y - r * 0.92f * sin(Math.toRadians(lat)).toFloat()),
                            )
                        }
                        lat += 15.0
                    }
                } else {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF4F8F5A), 0.35f to Color(0xFF2D6A8F), 0.75f to Color(0xFF0B2A55), 1f to Color(0xFF041327),
                            center = Offset(r * 0.7f, r * 0.6f), radius = r * 1.4f,
                        ),
                        r, c,
                    )
                }
            }
        }
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(sub, fontSize = 12.sp, color = Z.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** The launcher icon, drawn from its two adaptive layers and masked to a circle. */
@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape)) {
        // The layers are 108 dp with the visible icon in the central 72 dp, so scale them up by 1.5.
        val layer = Modifier.matchParentSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Zenit icon", modifier = layer)
    }
}
