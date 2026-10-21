package io.github.divanguz_alt.zenit.orbits

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.divanguz_alt.zenit.gnss.Constellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** CelesTrak GP groups Zenit tracks. */
enum class OrbitGroup(val key: String, val query: String, val constellation: Constellation?) {
    GPS("gps-ops", "GROUP=gps-ops", Constellation.GPS),
    GALILEO("galileo", "GROUP=galileo", Constellation.GALILEO),
    GLONASS("glo-ops", "GROUP=glo-ops", Constellation.GLONASS),
    BEIDOU("beidou", "GROUP=beidou", Constellation.BEIDOU),
    SBAS("sbas", "GROUP=sbas", Constellation.SBAS),
    ISS("iss", "CATNR=25544", null),
}

data class OrbitCatalog(
    val satellites: List<OrbitalObject> = emptyList(),
    val iss: OrbitalObject? = null,
    /** Time of the most recent successful download, or null when nothing is cached. */
    val updatedAtMillis: Long? = null,
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = satellites.isEmpty()
}

sealed interface RefreshResult {
    data class Updated(val groups: Int) : RefreshResult
    data object UpToDate : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

/**
 * Downloads CelesTrak OMM data, caches it on disk and exposes propagator-ready objects.
 * Honours CelesTrak's usage policy: each group is fetched at most once every two hours.
 */
@Singleton
class OrbitRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "orbits").apply { mkdirs() }
    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(OrbitCatalog())
    val catalog: StateFlow<OrbitCatalog> = _catalog.asStateFlow()

    init {
        scope.launch { mutex.withLock { reload() } }
    }

    private fun dataFile(g: OrbitGroup) = File(dir, "${g.key}.json")
    private fun stampFile(g: OrbitGroup) = File(dir, "${g.key}.ts")
    private fun lastFetch(g: OrbitGroup): Long = runCatching { stampFile(g).readText().trim().toLong() }.getOrDefault(0L)

    /** True when the cache is older than [maxAgeMillis] (or missing). */
    fun isStale(maxAgeMillis: Long = DAY): Boolean {
        val now = System.currentTimeMillis()
        return OrbitGroup.entries.any { now - lastFetch(it) > maxAgeMillis }
    }

    /**
     * Fetch every group whose cache is older than [minAgeMillis]. [force] lowers the threshold to the
     * two-hour CelesTrak minimum, never below it.
     */
    suspend fun refresh(force: Boolean = false, minAgeMillis: Long = DAY - HOUR): RefreshResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val threshold = if (force) MIN_INTERVAL else maxOf(minAgeMillis, MIN_INTERVAL)
            val due = OrbitGroup.entries.filter { now - lastFetch(it) >= threshold }
            if (due.isEmpty()) return@withContext RefreshResult.UpToDate
            var updated = 0
            var lastError: String? = null
            for (group in due) {
                try {
                    val body = download(group)
                    // Validate before replacing the cache.
                    val records = json.decodeFromString<List<OmmRecord>>(body)
                    if (records.isEmpty()) error("empty response")
                    dataFile(group).writeText(body)
                    stampFile(group).writeText(now.toString())
                    updated++
                } catch (e: Exception) {
                    Log.w(TAG, "refresh ${group.key} failed", e)
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            reload()
            when {
                updated > 0 -> RefreshResult.Updated(updated)
                else -> RefreshResult.Failed(lastError ?: "unknown error")
            }
        }
    }

    private fun download(group: OrbitGroup): String {
        val req = Request.Builder()
            .url("https://celestrak.org/NORAD/elements/gp.php?${group.query}&FORMAT=json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            // CelesTrak answers repeat downloads with a plain-text notice instead of JSON.
            if (!body.trimStart().startsWith("[")) error(body.take(120))
            return body
        }
    }

    private fun reload() {
        val sats = ArrayList<OrbitalObject>()
        var iss: OrbitalObject? = null
        var updated: Long? = null
        for (group in OrbitGroup.entries) {
            val f = dataFile(group)
            if (!f.exists()) continue
            val records = runCatching { json.decodeFromString<List<OmmRecord>>(f.readText()) }.getOrElse {
                Log.w(TAG, "cache ${group.key} unreadable", it)
                emptyList()
            }
            val objects = records.mapNotNull { OrbitalObject.from(it, group.constellation) }
            if (group == OrbitGroup.ISS) iss = objects.firstOrNull() else sats += objects
            val ts = lastFetch(group)
            if (ts > 0) updated = maxOf(updated ?: 0L, ts)
        }
        _catalog.value = OrbitCatalog(sats, iss, updated, loaded = true)
    }

    companion object {
        private const val TAG = "OrbitRepository"
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val MIN_INTERVAL = 2 * HOUR
        const val USER_AGENT = "Zenit-Android (+https://github.com/divanguz-alt/zenit)"
    }
}
