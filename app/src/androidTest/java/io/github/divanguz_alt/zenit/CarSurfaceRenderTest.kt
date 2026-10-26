package io.github.divanguz_alt.zenit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.SystemClock
import androidx.car.app.SurfaceContainer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.divanguz_alt.zenit.car.CarSurfaceView
import io.github.divanguz_alt.zenit.globe.Globe
import io.github.divanguz_alt.zenit.globe.GlobeController
import io.github.divanguz_alt.zenit.globe.GlobeSatellite
import io.github.divanguz_alt.zenit.globe.GlobeScene
import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.ui.theme.ZenitTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the car globe exactly the way Android Auto would host it (VirtualDisplay on a Surface the
 * host provides), but into an ImageReader, and checks the frame actually contains the globe.
 * The captured frame is saved as car_render.png in the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class CarSurfaceRenderTest {

    @Test
    fun globeRendersOnACarSurface() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext
        val w = 1280
        val h = 720
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)

        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val scene = GlobeScene(
            userLat = 45.81, userLon = 15.96, locked = true, orbitsAvailable = true,
            satellites = listOf(
                GlobeSatellite("G13", "G13", Constellation.GPS, true, 42f, 38.0, 30.0, 1.42f, false, true, 0),
                GlobeSatellite("E24", "E24", Constellation.GALILEO, true, 39f, 55.0, -5.0, 1.52f, false, true, 1),
                GlobeSatellite("R07", "R07", Constellation.GLONASS, false, 20f, 30.0, 0.0, 1.34f, false, false, 2),
            ),
        )
        val controller = GlobeController(reducedMotion = false)
        // Stable area to the right of a (pretend) navigation card on the left.
        val viewport = Rect(420f, 0f, w.toFloat(), h.toFloat())
        lateinit var view: CarSurfaceView
        instr.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
            view = CarSurfaceView(context, owner.registry) {
                ZenitTheme {
                    Globe(
                        scene = scene, controller = controller, visible = true, issShownAt = null, reducedMotion = false,
                        onTap = {}, onIssInfo = {}, modifier = Modifier.fillMaxSize(),
                        viewport = viewport, radiusFraction = 0.36f,
                    )
                }
            }
            view.attach(SurfaceContainer(reader.surface, w, h, 240))
        }

        // Wait for the globe to appear (first GL frame can take a while on a busy emulator),
        // then give the lock animation and satellite fade-in time to finish.
        val cx = ((viewport.left + viewport.right) / 2).toInt()
        val cy = h / 2
        fun litAtCentre(b: Bitmap): Int {
            var lit = 0
            for (dx in -120..120 step 8) for (dy in -120..120 step 8) {
                val c = b.getPixel(cx + dx, cy + dy)
                if (Color.red(c) + Color.green(c) + Color.blue(c) > 30) lit++
            }
            return lit
        }
        fun capture(): Bitmap? {
            val image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * w
            val padded = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            image.close()
            return Bitmap.createBitmap(padded, 0, 0, w, h)
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        var bmp: Bitmap? = null
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(500)
            val frame = capture() ?: continue
            bmp = frame
            if (litAtCentre(frame) > 200) break
        }
        SystemClock.sleep(3_000)
        bmp = capture() ?: bmp
        checkNotNull(bmp) { "no frame was ever rendered" }

        val dir = context.getExternalFilesDir(null)!!
        File(dir, "car_render.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        instr.runOnMainSync {
            view.detach()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        reader.close()

        // Globe centred in the viewport: its middle is lit (land dots / ocean gradient), the far
        // left (outside the viewport, beyond the atmosphere) stays black.
        val lit = litAtCentre(bmp)
        assertTrue("globe not drawn at the viewport centre (lit=$lit)", lit > 200)
        val corner = bmp.getPixel(10, 10)
        assertTrue("outside the globe should be dark", Color.red(corner) + Color.green(corner) + Color.blue(corner) < 30)
    }
}
