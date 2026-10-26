package io.github.divanguz_alt.zenit.car

import io.github.divanguz_alt.zenit.globe.GlobeScene
import io.github.divanguz_alt.zenit.gnss.GnssRepository
import io.github.divanguz_alt.zenit.gnss.GnssSnapshot
import io.github.divanguz_alt.zenit.orbits.OrbitRepository
import io.github.divanguz_alt.zenit.sensors.SensorAvailability
import io.github.divanguz_alt.zenit.sensors.SensorSnapshot
import io.github.divanguz_alt.zenit.settings.AppSettings
import io.github.divanguz_alt.zenit.settings.SavedFix
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import io.github.divanguz_alt.zenit.ui.DashboardState
import io.github.divanguz_alt.zenit.ui.Derivations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The data the car screen shows, for one Android Auto session. Same derivations as the phone
 * ([Derivations]); the car needs no motion sensors, so it doesn't register them.
 */
class CarFeed(
    scope: CoroutineScope,
    gnss: GnssRepository,
    orbits: OrbitRepository,
    settingsRepo: SettingsRepository,
    private val derive: Derivations,
) {
    private val savedFix = MutableStateFlow<SavedFix?>(null)
    private val noSensors = SensorSnapshot(SensorAvailability(false, false, false, false, false))

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val gnssState: StateFlow<GnssSnapshot> = gnss.updates().stateIn(scope, SharingStarted.Eagerly, GnssSnapshot())

    val scene: StateFlow<GlobeScene> = combine(gnssState, orbits.catalog, settings, ticker) { g, c, cfg, now ->
        derive.buildScene(g, c, cfg, now, savedFix.value).scene
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, GlobeScene())

    val dashboard: StateFlow<DashboardState> = combine(gnssState, settings, ticker) { g, cfg, now ->
        derive.buildDashboard(g, noSensors, cfg, now, savedFix.value)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, DashboardState())

    init {
        scope.launch { savedFix.value = settingsRepo.savedFix() }
    }
}
