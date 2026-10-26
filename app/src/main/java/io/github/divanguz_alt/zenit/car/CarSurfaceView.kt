package io.github.divanguz_alt.zenit.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Shows Compose content on the car's map surface: a VirtualDisplay renders into the host's Surface
 * and a Presentation on that display hosts a ComposeView, so the phone's Globe composable (GL view
 * plus Canvas overlay) runs unchanged in the car.
 */
class CarSurfaceView(
    private val context: Context,
    private val hostLifecycle: Lifecycle,
    private val content: @Composable () -> Unit,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var owner: ViewTreeOwner? = null

    fun attach(container: SurfaceContainer) {
        detach()
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.createVirtualDisplay(
            "ZenitCar", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        val treeOwner = ViewTreeOwner(hostLifecycle)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(treeOwner)
            setViewTreeSavedStateRegistryOwner(treeOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(treeOwner))
            setContent(content)
        }
        val p = Presentation(context, display.display).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            // The decor view is the root the Compose runtime walks up to.
            window?.decorView?.let {
                it.setViewTreeLifecycleOwner(treeOwner)
                it.setViewTreeSavedStateRegistryOwner(treeOwner)
            }
            setContentView(view)
        }
        try {
            p.show()
        } catch (e: Exception) {
            Log.e(TAG, "Presentation failed", e)
            treeOwner.destroy()
            display.release()
            return
        }
        virtualDisplay = display
        presentation = p
        owner = treeOwner
    }

    fun detach() {
        presentation?.dismiss()
        presentation = null
        owner?.destroy()
        owner = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /**
     * Lifecycle + saved state for the ComposeView. Follows the car screen's lifecycle but starts
     * fresh for each surface, so saved-state restore happens before it reaches STARTED.
     */
    private class ViewTreeOwner(private val host: Lifecycle) : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        private val follower = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_DESTROY) registry.handleLifecycleEvent(event)
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            host.addObserver(follower) // replays events up to the host's current state
        }

        fun destroy() {
            host.removeObserver(follower)
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    private companion object {
        const val TAG = "CarSurfaceView"
    }
}
