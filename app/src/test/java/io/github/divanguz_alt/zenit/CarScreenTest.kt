package io.github.divanguz_alt.zenit

import android.content.Intent
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import io.github.divanguz_alt.zenit.car.CarFeed
import io.github.divanguz_alt.zenit.car.GlobeCarScreen
import io.github.divanguz_alt.zenit.car.PermissionScreen
import io.github.divanguz_alt.zenit.gnss.GnssRepository
import io.github.divanguz_alt.zenit.orbits.OrbitRepository
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import io.github.divanguz_alt.zenit.ui.Derivations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

private fun TestCarContext.lifecycleOwnerForTest(): androidx.lifecycle.Lifecycle {
    val owner = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }
    owner.registry.currentState = Lifecycle.State.RESUMED
    return owner.registry
}

/** Template-level checks for the Android Auto screens, using the Car App Library test host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarScreenTest {
    private val app = RuntimeEnvironment.getApplication()
    private val carContext = TestCarContext.createCarContext(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun settings() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "test-${System.nanoTime()}.preferences_pb") },
    )

    private fun feed(settings: SettingsRepository): CarFeed {
        val gnss = GnssRepository(app)
        val orbits = OrbitRepository(app, OkHttpClient(), Json { ignoreUnknownKeys = true })
        return CarFeed(scope, carContext.lifecycleOwnerForTest(), gnss, orbits, settings, Derivations(gnss))
    }

    @Test fun globeScreenIsANavigationTemplateWithPanAndActions() {
        val settings = settings()
        val screen = GlobeCarScreen(carContext, feed(settings), settings)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        val template = screen.onGetTemplate()
        assertTrue(template is NavigationTemplate)
        template as NavigationTemplate

        val info = template.navigationInfo as MessageInfo
        assertEquals("Searching for satellites", info.title.toString())

        val actions = template.actionStrip!!.actions
        assertEquals(listOf("Recenter", "ISS", "Realistic"), actions.map { it.title.toString() })

        val map = template.mapActionStrip
        assertTrue("map strip must contain PAN for drag input", map!!.actions.any { it.type == Action.TYPE_PAN })
        assertTrue(map.actions.size <= 4)
    }

    @Test fun issActionTogglesTheCard() {
        val settings = settings()
        val screen = GlobeCarScreen(carContext, feed(settings), settings)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        val iss = (screen.onGetTemplate() as NavigationTemplate).actionStrip!!.actions[1]
        iss.onClickDelegate!!.sendClick(object : androidx.car.app.OnDoneCallback {})
        val after = screen.onGetTemplate() as NavigationTemplate
        assertEquals("International Space Station", (after.navigationInfo as MessageInfo).title.toString())
        assertEquals("Hide ISS", after.actionStrip!!.actions[1].title.toString())
    }

    @Test fun permissionScreenAsksToOpenThePhone() {
        val screen = PermissionScreen(carContext) { error("not granted") }
        val template = screen.onGetTemplate() as MessageTemplate
        assertTrue(template.message.toString().contains("allow location"))
    }

    @Test fun carAppServiceIsDeclaredAsNavigationApp() {
        val intent = Intent("androidx.car.app.CarAppService").addCategory("androidx.car.app.category.NAVIGATION").setPackage(app.packageName)
        val services = app.packageManager.queryIntentServices(intent, 0)
        assertEquals(listOf("io.github.divanguz_alt.zenit.car.ZenitCarAppService"), services.map { it.serviceInfo.name })
    }

    @Test fun locationServiceRunsOnlyWhileZenitIsOnTheCarScreen() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val gnss = GnssRepository(app)
        val session = io.github.divanguz_alt.zenit.car.ZenitCarSession(
            gnss, OrbitRepository(app, OkHttpClient(), Json { ignoreUnknownKeys = true }), settings(), Derivations(gnss),
        )
        val controller = androidx.car.app.testing.SessionController(session, carContext, Intent())
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearStartedServices()

        controller.moveToState(Lifecycle.State.STARTED)
        val started = shadowApp.nextStartedService
        assertEquals("io.github.divanguz_alt.zenit.car.CarLocationService", started?.component?.className)

        controller.moveToState(Lifecycle.State.CREATED) // ON_STOP: another app took the car screen
        assertEquals("io.github.divanguz_alt.zenit.car.CarLocationService", shadowApp.nextStoppedService?.component?.className)
    }

    @Test fun noLocationServiceWithoutPermission() {
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val gnss = GnssRepository(app)
        val session = io.github.divanguz_alt.zenit.car.ZenitCarSession(
            gnss, OrbitRepository(app, OkHttpClient(), Json { ignoreUnknownKeys = true }), settings(), Derivations(gnss),
        )
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearStartedServices()
        androidx.car.app.testing.SessionController(session, carContext, Intent()).moveToState(Lifecycle.State.STARTED)
        assertEquals(null, shadowApp.nextStartedService)
    }
}
