package io.github.divanguz_alt.zenit.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.divanguz_alt.zenit.R
import io.github.divanguz_alt.zenit.globe.Globe
import io.github.divanguz_alt.zenit.globe.GlobeCamera
import io.github.divanguz_alt.zenit.globe.GlobeController
import io.github.divanguz_alt.zenit.globe.IssInfo
import io.github.divanguz_alt.zenit.settings.GlobeStyle
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import io.github.divanguz_alt.zenit.ui.theme.ZenitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The car screen: the globe drawn on the host's map surface, a navigation card with the fix,
 * and actions to recenter, show the ISS, switch globe style, pan and zoom.
 */
class GlobeCarScreen(
    carContext: CarContext,
    private val feed: CarFeed,
    private val settingsRepo: SettingsRepository,
) : Screen(carContext) {

    private val controller = GlobeController(reducedMotion = false)
    private val camera = GlobeCamera()
    private var viewport by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var issShownAt by mutableStateOf<Long?>(null)
    @Volatile private var issInfo: IssInfo? = null
    private var status: CarStatus? = null

    private val surfaceView = CarSurfaceView(carContext, lifecycle) {
        ZenitTheme {
            val scene by feed.scene.collectAsState()
            Globe(
                scene = scene,
                controller = controller,
                visible = true,
                issShownAt = issShownAt,
                reducedMotion = false,
                onTap = {},
                onIssInfo = { issInfo = it },
                modifier = Modifier.fillMaxSize().background(Color.Black),
                camera = camera,
                viewport = viewport,
                radiusFraction = 0.36f,
            )
        }
    }

    // --- Map input from the host (touch drag, rotary nudge, pinch) -------------------------------

    private val main = Handler(Looper.getMainLooper())
    private var lastScroll = 0L
    private val endDrag = Runnable { controller.dragEnd() }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = surfaceView.attach(surfaceContainer)
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = surfaceView.detach()

        override fun onStableAreaChanged(stableArea: Rect) {
            // The stable area is always visible whatever the template shows; centring there keeps
            // the globe from jumping when the action strip hides and reappears.
            if (!stableArea.isEmpty) {
                viewport = androidx.compose.ui.geometry.Rect(
                    stableArea.left.toFloat(), stableArea.top.toFloat(), stableArea.right.toFloat(), stableArea.bottom.toFloat(),
                )
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            val now = SystemClock.uptimeMillis()
            if (now - lastScroll > DRAG_GAP_MS) controller.dragStart()
            val dt = ((now - lastScroll).coerceAtMost(DRAG_GAP_MS)) / 1000.0
            lastScroll = now
            // The host reports how far the content scrolled (opposite to the finger).
            controller.drag(-distanceX, -distanceY, radius(), dt)
            main.removeCallbacks(endDrag)
            main.postDelayed(endDrag, DRAG_GAP_MS)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            main.removeCallbacks(endDrag)
            controller.fling(velocityX, velocityY, radius())
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            controller.zoomBy(scaleFactor)
        }
    }

    private fun radius(): Float = camera.frame?.radiusPx ?: 300f

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacksAndMessages(null)
                surfaceView.detach()
            }
        })
        // Refresh the card at most once a second, and only when its text changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val next = CarStatus.from(feed.dashboard.value, controller.phase, issShownAt != null, issInfo)
                    if (next != status) invalidate()
                    delay(1_000)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = CarStatus.from(feed.dashboard.value, controller.phase, issShownAt != null, issInfo)
        status = s
        val realistic = feed.settings.value.globeStyle == GlobeStyle.REALISTIC

        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Recenter").setIcon(icon(R.drawable.ic_car_recenter))
                    .setOnClickListener { recenter() }.build(),
            )
            .addAction(
                Action.Builder().setTitle(if (issShownAt != null) "Hide ISS" else "ISS").setIcon(icon(R.drawable.ic_car_iss))
                    .setOnClickListener {
                        issShownAt = if (issShownAt == null) System.currentTimeMillis() else null
                        if (issShownAt == null) issInfo = null
                        invalidate()
                    }.build(),
            )
            .addAction(
                Action.Builder().setTitle(if (realistic) "Illustrated" else "Realistic").setIcon(icon(R.drawable.ic_car_globe))
                    .setOnClickListener { toggleStyle() }.build(),
            )
            .build()

        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { controller.zoomBy(1.25f) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { controller.zoomBy(0.8f) }.build())
            .build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(MessageInfo.Builder(s.title).setText(s.text).build())
            .setActionStrip(actions)
            .setMapActionStrip(mapActions)
            .setPanModeListener { }
            .setBackgroundColor(CarColor.createCustom(BACKGROUND, BACKGROUND))
            .build()
    }

    private fun recenter() {
        val scene = feed.scene.value
        val lat = scene.userLat
        val lon = scene.userLon
        controller.zoomBy(1f / controller.userZoom)
        if (lat != null && lon != null) controller.recenter(System.currentTimeMillis(), lat, lon)
    }

    private fun toggleStyle() {
        lifecycleScope.launch {
            settingsRepo.update {
                it.copy(globeStyle = if (it.globeStyle == GlobeStyle.REALISTIC) GlobeStyle.ILLUSTRATED else GlobeStyle.REALISTIC)
            }
            invalidate()
        }
    }

    private fun icon(res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private companion object {
        const val DRAG_GAP_MS = 250L
        const val BACKGROUND = 0xFF0B1210.toInt()
    }
}
