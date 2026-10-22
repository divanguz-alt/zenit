package io.github.divanguz_alt.zenit.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.divanguz_alt.zenit.BuildConfig
import io.github.divanguz_alt.zenit.globe.Globe
import io.github.divanguz_alt.zenit.globe.GlobeController
import io.github.divanguz_alt.zenit.globe.GlobePhase
import io.github.divanguz_alt.zenit.globe.IssInfo
import io.github.divanguz_alt.zenit.globe.rememberReducedMotion
import io.github.divanguz_alt.zenit.gnss.FixType
import io.github.divanguz_alt.zenit.ui.components.BottomBar
import io.github.divanguz_alt.zenit.ui.components.DebugScreen
import io.github.divanguz_alt.zenit.ui.components.LicensesDialog
import io.github.divanguz_alt.zenit.ui.components.Metric
import io.github.divanguz_alt.zenit.ui.components.MetricRow
import io.github.divanguz_alt.zenit.ui.components.PositionCard
import io.github.divanguz_alt.zenit.ui.components.RationaleDialog
import io.github.divanguz_alt.zenit.ui.components.SectionTitle
import io.github.divanguz_alt.zenit.ui.components.SettingsContent
import io.github.divanguz_alt.zenit.ui.components.SignalCard
import io.github.divanguz_alt.zenit.ui.components.UpdateDialog
import io.github.divanguz_alt.zenit.ui.theme.Z
import kotlin.math.roundToLong

private val GLOBE_HEIGHT = 430.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity

    val d by vm.dashboard.collectAsStateWithLifecycle()
    val scene by vm.scene.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val access by vm.locationAccess.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val badge by vm.updateBadge.collectAsStateWithLifecycle()
    val debug by vm.debug.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingOrbits.collectAsStateWithLifecycle()
    val orbitMessage by vm.orbitMessage.collectAsStateWithLifecycle()
    // Keep the gnss/sensor collectors alive while the screen is shown.
    vm.gnssState.collectAsStateWithLifecycle()

    val reducedMotion = rememberReducedMotion()
    val controller = remember { GlobeController(reducedMotion) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showLocationRationale by remember { mutableStateOf(false) }
    var issShownAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var issInfo by remember { mutableStateOf<IssInfo?>(null) }

    // --- Permissions ---------------------------------------------------------------------------
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onLocationPermission(res[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    fun requestLocation() {
        val permanentlyDenied = access == LocationAccess.DENIED && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permanentlyDenied) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (loc) vm.onLocationPermission(true) else if (access == LocationAccess.GRANTED) vm.onLocationPermission(false)
    }
    LaunchedEffect(Unit) {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && vm.shouldShowLocationRationale()) showLocationRationale = true
    }

    // Keep screen on
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // --- Layout ---------------------------------------------------------------------------------
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val globeVisible = scroll.value < with(density) { GLOBE_HEIGHT.toPx() }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Z.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = statusBar + 4.dp, bottom = 120.dp + navBar),
        ) {
            Box(Modifier.fillMaxWidth().height(GLOBE_HEIGHT)) {
                Globe(
                    scene = scene,
                    controller = controller,
                    visible = globeVisible,
                    issShownAt = issShownAt,
                    reducedMotion = reducedMotion,
                    onTap = {
                        issShownAt = if (issShownAt == null) System.currentTimeMillis() else null
                        if (issShownAt == null) issInfo = null
                    },
                    onIssInfo = { issInfo = it },
                    modifier = Modifier.fillMaxSize().testTag("globe"),
                )
                MenuButton(badge, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 10.dp)) { sheetOpen = true }
                if (!scene.orbitsAvailable && catalog.loaded) {
                    Text(
                        "Orbits offline · showing sky positions",
                        fontSize = 11.sp, color = Z.TextSecondary,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Color(0x991A1B1F)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                GlobeStatus(
                    d = d,
                    phase = controller.phase,
                    offCenter = controller.offCenter,
                    access = access,
                    issShown = issShownAt != null,
                    issInfo = issInfo,
                    issAvailable = catalog.iss != null,
                    onReacquire = {
                        val lat = scene.userLat
                        val lon = scene.userLon
                        if (controller.offCenter && lat != null && lon != null) {
                            // Globe was turned away: fly back to the fix instead of re-searching.
                            controller.recenter(System.currentTimeMillis(), lat, lon)
                        } else {
                            vm.reacquire()
                            controller.reacquire(System.currentTimeMillis())
                        }
                    },
                    onRequestLocation = ::requestLocation,
                    onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }

            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Position", top = 0.dp)
                PositionCard(d)

                SectionTitle("Motion")
                MetricRow(
                    listOf(
                        { m -> Metric("Speed", d.speed, unit = d.speedUnit, modifier = m, first = true) },
                        { m -> Metric("Altitude MSL", d.altitude, unit = d.altitudeUnit, modifier = m) },
                        { m -> Metric("Accel.", d.accel, unit = "g", available = d.accelAvailable, modifier = m) },
                    ),
                )

                SectionTitle("Attitude")
                MetricRow(
                    listOf(
                        { m -> Metric("Pitch", d.pitch, unit = d.pitchDir, available = d.attitudeAvailable, modifier = m, first = true) },
                        { m -> Metric("Roll", d.roll, unit = d.rollDir, available = d.attitudeAvailable, modifier = m) },
                        { m -> Metric("Rotation", d.rotation, unit = d.rotationDir, available = d.rotationAvailable, modifier = m) },
                    ),
                )

                SectionTitle("Sensors")
                MetricRow(
                    listOf(
                        { m ->
                            Metric("Pressure", d.pressure, unit = "${d.pressureUnit} ${d.pressureArrow}".trim(), available = d.pressureAvailable, modifier = m, first = true)
                        },
                        { m -> Metric("Mag. field", d.magField, unit = "µT", sub = "decl. ${d.declination}", available = d.magAvailable, modifier = m) },
                    ),
                )

                SectionTitle("Signal strength")
                SignalCard(d.bars, d.legend, d.hasFix)
            }
        }

        // Status bar scrim: content scrolls under a solid black status bar, like the prototype.
        Box(Modifier.fillMaxWidth().height(statusBar).background(Z.Background).align(Alignment.TopCenter))

        BottomBar(d, heading, Modifier.align(Alignment.BottomCenter).shadow(30.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)))
    }

    // --- Sheets & dialogs -------------------------------------------------------------------------
    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = Z.Sheet,
            scrimColor = Color(0x8C000000),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsContent(
                    settings = settings,
                    onChange = vm::updateSettings,
                    orbitDataAge = catalog.updatedAtMillis?.let { Format.age(System.currentTimeMillis() - it) } ?: "never",
                    orbitMessage = orbitMessage,
                    refreshingOrbits = refreshing,
                    onRefreshOrbits = vm::refreshOrbits,
                    versionName = vm.versionName,
                    updateAvailable = badge,
                    onVersionLongPress = { showDebug = true },
                    onOpenGithub = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")))
                    },
                    onCheckUpdates = vm::checkForUpdates,
                    onLicenses = { showLicenses = true },
                )
            }
        }
    }

    UpdateDialog(
        state = update,
        currentVersion = vm.versionName,
        onDownload = vm::downloadUpdate,
        onAllowInstalls = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            vm.dismissUpdate()
        },
        onDismiss = vm::dismissUpdate,
    )
    if (showLicenses) LicensesDialog { showLicenses = false }
    if (showDebug) DebugScreen(debug) { showDebug = false }
    if (showLocationRationale) {
        RationaleDialog(
            title = "Location access",
            text = "Zenit uses your phone's GPS to show your position and the navigation satellites above you. " +
                "Your location never leaves the device.",
            confirm = "Continue",
            onConfirm = {
                showLocationRationale = false
                vm.markLocationRationaleShown()
                requestLocation()
            },
            onDismiss = {
                showLocationRationale = false
                vm.markLocationRationaleShown()
                vm.onLocationPermission(false)
            },
        )
    }
}

@Composable
private fun MenuButton(badge: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Settings" }
            .testTag("menu"),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(3) { Box(Modifier.size(4.dp).clip(CircleShape).background(Z.Text)) }
        }
        if (badge) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp).size(8.dp).clip(CircleShape).background(Z.Accent))
        }
    }
}

@Composable
private fun GlobeStatus(
    d: DashboardState,
    phase: GlobePhase,
    offCenter: Boolean,
    access: LocationAccess,
    issShown: Boolean,
    issInfo: IssInfo?,
    issAvailable: Boolean,
    onReacquire: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier,
) {
    val noPermission = access != LocationAccess.GRANTED
    val gpsOff = !noPermission && !d.providerEnabled
    val locked = phase == GlobePhase.LOCKED
    val pillColor = if (locked) Z.Accent else Z.Searching
    val pillText = when {
        noPermission || gpsOff -> "No location"
        locked -> (if (d.fixType == FixType.FIX_2D) "2D Fix" else "3D Fix") + if (offCenter) "  ⌖" else ""
        phase == GlobePhase.LOCKING -> "Locking"
        else -> "Searching"
    }
    val (title, sub) = when {
        issShown && issInfo != null -> "International Space Station" to
            "${issInfo.altitudeKm.roundToLong()} km · ${Format.spacedThousands(issInfo.speedKmh.roundToLong())} km/h · 1 orbit ≈ ${issInfo.periodMin.roundToLong()} min"
        issShown && !issAvailable -> "International Space Station" to "Orbit data not downloaded yet"
        noPermission -> "Location permission needed" to "Zenit shows the satellites above you"
        gpsOff -> "Location is turned off" to "Turn it on to find satellites"
        phase == GlobePhase.SEARCH -> "Searching for satellites" to
            (if (d.visibleCount > 0) "${d.visibleCount} in view · keep a clear view of the sky" else "Keep a clear view of the sky")
        phase == GlobePhase.LOCKING -> "Satellites found" to "Locking position…"
        d.usedCount == 0 -> "Position fixed" to "${d.visibleCount} satellites in view · ${d.accuracyText}"
        else -> "Connected to ${d.usedCount} satellites" to
            "${if (d.fixType == FixType.FIX_2D) "2D" else "3D"} fix · ${d.accuracyText}"
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Z.Outline, RoundedCornerShape(10.dp))
                .clickable(enabled = !noPermission && !gpsOff, onClick = onReacquire)
                .padding(start = 12.dp, end = 14.dp)
                .testTag("statusChip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).shadow(6.dp, CircleShape, ambientColor = pillColor, spotColor = pillColor).clip(CircleShape).background(pillColor))
            Text(pillText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("caption"))
            Text(sub, fontSize = 13.sp, color = Z.TextSecondary)
        }
        if (noPermission || gpsOff) {
            Text(
                if (noPermission) "Allow location" else "Location settings",
                color = Z.AccentOn, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Z.Accent)
                    .clickable(onClick = if (noPermission) onRequestLocation else onOpenLocationSettings)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .testTag("locationAction"),
            )
        }
    }
}
