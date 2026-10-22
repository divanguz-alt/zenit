package io.github.divanguz_alt.zenit.globe

import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.hypot
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.withFrameNanos
import kotlin.math.min

/** True when the user turned off animations in system settings ("Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The globe: a GLSurfaceView for the Earth and a Compose Canvas above it, both driven from one
 * frame loop that only runs while the screen is resumed and the globe is scrolled into view.
 */
@Composable
fun Globe(
    scene: GlobeScene,
    controller: GlobeController,
    visible: Boolean,
    issShownAt: Long?,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    onIssInfo: (IssInfo?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val camera = remember { GlobeCamera() }
    val painter = remember { GlobeOverlayPainter(context) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var frame by remember { mutableStateOf<GlobeFrame?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var timeSec by remember { mutableDoubleStateOf(0.0) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var scaleK by remember { mutableFloatStateOf(1.0f) }
    val currentScene by rememberUpdatedState(scene)
    val currentVisible by rememberUpdatedState(visible)
    val lifecycleOwner = LocalLifecycleOwner.current

    // Frame loop
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            val t0 = System.nanoTime()
            while (true) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 0.0 else (t - last) / 1e9
                    last = t
                    if (!currentVisible || size.width <= 0f) return@withFrameNanos
                    val s = currentScene
                    val now = System.currentTimeMillis()
                    controller.step(now, dt, s.locked, s.userLat, s.userLon)

                    // True-scale orbits: shrink the Earth so the outermost satellite still fits.
                    val outer = (s.satellites.maxOfOrNull { it.radius } ?: 1.42f)
                        .coerceAtLeast(s.background.maxOfOrNull { it.radius } ?: 0f)
                    val targetK = if (s.trueScale) (1.45f / outer.coerceAtLeast(1.45f)) else 1f
                    scaleK += (targetK - scaleK) * (if (reducedMotion) 1f else (dt * 3).toFloat().coerceIn(0f, 1f))

                    val w = size.width; val h = size.height
                    val radius = (min(w, h) * 0.3f * controller.zoom.toFloat() * scaleK)
                    val f = GlobeFrame(
                        viewLatDeg = controller.viewLat,
                        viewLonDeg = controller.viewLon,
                        centerX = w / 2f,
                        centerY = (h - 70 * density) / 2f + 6 * density,
                        radiusPx = radius,
                        style = s.style,
                        dayNight = s.dayNight,
                        subSolarLat = s.subSolarLat,
                        subSolarLon = s.subSolarLon,
                        density = density,
                    )
                    camera.frame = f
                    frame = f
                    nowMs = now
                    timeSec = (t - t0) / 1e9
                    glView?.requestRender()
                }
            }
        }
    }

    // GLSurfaceView follows the activity lifecycle.
    DisposableEffect(lifecycleOwner, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lastIssReport = remember { longArrayOf(0L) }
    Box(modifier.onSizeChanged { size = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(GlobeRenderer(ctx.applicationContext, camera))
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(controller) { rotateGlobeGestures(camera, controller) }
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        ) {
            val f = frame ?: return@Canvas
            val info = with(painter) {
                draw(f, currentScene, controller, nowMs, timeSec, reducedMotion, issShownAt, scaleK.let { 1.45f / it })
            }
            // Report ISS figures at most twice a second; the caption doesn't need frame rate.
            if (nowMs - lastIssReport[0] > 500 || (info == null) != (issShownAt == null)) {
                lastIssReport[0] = nowMs
                onIssInfo(info)
            }
        }
    }
}

/**
 * Drag to rotate the globe. Only touches that start on (or just around) the globe are taken, so
 * the page still scrolls when the finger lands on the empty space beside it.
 */
private suspend fun PointerInputScope.rotateGlobeGestures(camera: GlobeCamera, controller: GlobeController) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val f = camera.frame ?: return@awaitEachGesture
        val dist = hypot(down.position.x - f.centerX, down.position.y - f.centerY)
        if (dist > f.radiusPx * 1.15f) return@awaitEachGesture
        var dragging = false
        var travelled = Offset.Zero
        var lastTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            if (!dragging) {
                travelled += delta
                if (travelled.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    controller.dragStart()
                }
            }
            if (dragging) {
                val radius = camera.frame?.radiusPx ?: f.radiusPx
                controller.drag(delta.x, delta.y, radius, (change.uptimeMillis - lastTime) / 1000.0)
                change.consume()
            }
            lastTime = change.uptimeMillis
        }
        if (dragging) controller.dragEnd()
    }
}
