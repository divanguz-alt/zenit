package io.github.divanguz_alt.zenit.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.divanguz_alt.zenit.R
import io.github.divanguz_alt.zenit.gnss.GnssRepository
import io.github.divanguz_alt.zenit.orbits.OrbitRepository
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import io.github.divanguz_alt.zenit.ui.Derivations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Entry point for Android Auto. */
@AndroidEntryPoint
class ZenitCarAppService : CarAppService() {
    @Inject lateinit var gnss: GnssRepository
    @Inject lateinit var orbits: OrbitRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var derive: Derivations

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Release builds talk only to Google's Android Auto hosts; debug builds also accept the
        // Desktop Head Unit and test hosts.
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(): Session = ZenitCarSession(gnss, orbits, settings, derive)
}

class ZenitCarSession(
    private val gnss: GnssRepository,
    private val orbits: OrbitRepository,
    private val settings: SettingsRepository,
    private val derive: Derivations,
) : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = CarLocationService.stop(carContext)
        })
    }

    override fun onCreateScreen(intent: Intent): Screen =
        if (hasLocationPermission(carContext)) globeScreen() else PermissionScreen(carContext) { globeScreen() }

    private fun globeScreen(): Screen {
        // In the car the app isn't "foreground" for location; a foreground service keeps GNSS alive
        // while the phone is locked in its holder.
        CarLocationService.start(carContext)
        val feed = CarFeed(lifecycleScope, gnss, orbits, settings, derive)
        return GlobeCarScreen(carContext, feed, settings)
    }

    companion object {
        fun hasLocationPermission(context: CarContext): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** Shown until location access has been granted on the phone. Re-checks every two seconds. */
class PermissionScreen(carContext: CarContext, private val next: () -> Screen) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (ZenitCarSession.hasLocationPermission(carContext)) {
                        try {
                            screenManager.push(next())
                            finish()
                        } catch (e: Exception) {
                            Log.w("PermissionScreen", "could not open globe", e)
                        }
                        return@repeatOnLifecycle
                    }
                    delay(2_000)
                }
            }
        }
    }

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("Open Zenit on your phone and allow location access to see the satellites above you.")
            .setTitle("Zenit")
            .setHeaderAction(Action.APP_ICON)
            .build()
}
