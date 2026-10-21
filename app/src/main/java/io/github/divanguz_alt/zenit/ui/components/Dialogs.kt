package io.github.divanguz_alt.zenit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.divanguz_alt.zenit.ui.DebugInfo
import io.github.divanguz_alt.zenit.ui.Format
import io.github.divanguz_alt.zenit.ui.UpdateUiState
import io.github.divanguz_alt.zenit.ui.theme.Numeric
import io.github.divanguz_alt.zenit.ui.theme.Z
import io.github.divanguz_alt.zenit.update.UpdateInfo

@Composable
fun RationaleDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        containerColor = Z.Sheet,
    )
}

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    currentVersion: String,
    onDownload: (UpdateInfo) -> Unit,
    onAllowInstalls: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == UpdateUiState.Idle) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Sheet,
        title = {
            Text(
                when (state) {
                    is UpdateUiState.Available, is UpdateUiState.Downloading, is UpdateUiState.NeedsInstallPermission -> "Update available"
                    UpdateUiState.Checking -> "Checking for updates"
                    UpdateUiState.UpToDate -> "Zenit is up to date"
                    is UpdateUiState.Failed -> "Update check failed"
                    UpdateUiState.Idle -> ""
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    UpdateUiState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Z.Accent)
                    UpdateUiState.UpToDate -> Text("You have the latest version ($currentVersion).")
                    is UpdateUiState.Failed -> Text(state.message)
                    is UpdateUiState.Available -> ReleaseNotes(state.info, currentVersion)
                    is UpdateUiState.NeedsInstallPermission -> {
                        ReleaseNotes(state.info, currentVersion)
                        Text("To install updates, allow Zenit to install apps in the next screen, then tap Download again.", color = Z.TextSecondary)
                    }
                    is UpdateUiState.Downloading -> {
                        Text("Downloading Zenit ${state.info.tag}…")
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = Z.Accent)
                        Text("${(state.progress * 100).toInt()} %", style = Numeric.copy(color = Z.TextSecondary, fontSize = 12.sp))
                    }
                    UpdateUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUiState.Available -> TextButton(onClick = { onDownload(state.info) }) { Text("Download") }
                is UpdateUiState.NeedsInstallPermission -> TextButton(onClick = onAllowInstalls) { Text("Allow") }
                is UpdateUiState.Downloading -> Unit
                else -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateUiState.Available, is UpdateUiState.NeedsInstallPermission -> TextButton(onClick = onDismiss) { Text("Later") }
                is UpdateUiState.Downloading -> TextButton(onClick = onDismiss) { Text("Cancel") }
                else -> Unit
            }
        },
    )
}

@Composable
private fun ReleaseNotes(info: UpdateInfo, current: String) {
    Text("Zenit ${info.tag} (installed: $current)", fontWeight = FontWeight.Medium)
    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        Text(info.notes.ifBlank { "No release notes." }, color = Z.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Sheet,
        title = { Text("Licenses & attributions") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LICENSES.forEach { (title, body) ->
                    Column {
                        Text(title, fontWeight = FontWeight.Medium)
                        Text(body, color = Z.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val LICENSES = listOf(
    "Zenit" to "MIT License. Copyright (c) 2026 divanguz-alt.",
    "Blue Marble: Next Generation" to "NASA Earth Observatory / Visible Earth (Reto Stöckli). Public domain imagery courtesy of NASA.",
    "Earth at Night (Black Marble 2016)" to "NASA Earth Observatory images by Joshua Stevens, using Suomi NPP VIIRS data from Miguel Román, NASA GSFC. Public domain.",
    "Natural Earth" to "Land polygons from Natural Earth (naturalearthdata.com). Public domain.",
    "CelesTrak" to "Orbital elements (GP / OMM) courtesy of CelesTrak, Dr. T.S. Kelso. Used under CelesTrak's usage policy.",
    "satellite.js" to "SGP4/SDP4 propagator ported to Kotlin from satellite.js, MIT License, Copyright (c) 2013 Shashwat Kandadai and UCSC; based on David Vallado's reference implementation.",
    "Roboto Flex" to "Copyright 2017 The Roboto Flex Project Authors. SIL Open Font License 1.1.",
    "AndroidX, Jetpack Compose, Hilt, WorkManager, DataStore" to "Apache License 2.0, The Android Open Source Project / Google LLC.",
    "OkHttp" to "Apache License 2.0, Square, Inc.",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0, JetBrains s.r.o.",
)

/** ⋮ → long-press on the version. Predicted vs. measured satellite directions. */
@Composable
fun DebugScreen(info: DebugInfo, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                Text("Debug · orbit matching", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            val pass = info.gpsWithinOneDegree >= 5
            Text(
                "GPS within 1°: ${info.gpsWithinOneDegree}/${info.gpsMatched}  " + if (pass) "PASS" else "need ≥ 5",
                color = if (pass) Z.Accent else Z.Heading,
                style = Numeric,
            )
            Text("Catalogue: ${info.catalogSize} objects · data ${info.orbitAge}", color = Z.TextSecondary, fontSize = 13.sp)
            Text("DOP: ${info.dop} · NMEA MSL: ${info.nmeaAltitude}", color = Z.TextSecondary, fontSize = 13.sp, style = Numeric)
            Row {
                listOf("Sat" to 0.8f, "Meas az/el" to 1.4f, "Pred az/el" to 1.4f, "Δ°" to 0.7f).forEach { (h, w) ->
                    Text(h, color = Z.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(w))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(info.matches) { m ->
                    Column {
                        Row {
                            val s = Numeric.copy(fontSize = 13.sp)
                            Text(m.label + if (m.byPrn) "" else "*", style = s, modifier = Modifier.weight(0.8f))
                            Text("${Format.fixed(m.measuredAz, 1)}/${Format.fixed(m.measuredEl, 1)}", style = s, modifier = Modifier.weight(1.4f))
                            Text("${Format.fixed(m.predictedAz, 1)}/${Format.fixed(m.predictedEl, 1)}", style = s, modifier = Modifier.weight(1.4f))
                            Text(Format.fixed(m.separation, 2), style = s.copy(color = if (m.separation <= 1.0) Z.Accent else Z.Text), modifier = Modifier.weight(0.7f))
                        }
                        Text(m.objectName, color = Z.TextTertiary, fontSize = 11.sp)
                    }
                }
            }
            if (info.matches.isEmpty()) Text("No matches yet. Needs a location and orbital data.", color = Z.TextSecondary)
            Text("* matched geometrically (no PRN in the catalogue name)", color = Z.TextTertiary, fontSize = 11.sp)
        }
    }
}
