package com.example.oppanavi

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.reader.osm.GraphHopperOSM
import com.graphhopper.routing.util.EncodingManager
import org.mapsforge.core.model.LatLong
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
data class GHRouteInfo(
    val points: List<LatLong>,
    val distanceMeters: Double,
    val timeSec: Long
)
class GraphHopperModule(private val context: Context) {

    private var hopper: GraphHopper? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = ScheduledThreadPoolExecutor(1)
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private val indexing = AtomicBoolean(false)
    private var indexingStartMs = 0L

    var onProgress: ((elapsedSec: Long, cacheKb: Long) -> Unit)? = null
    var onReady:    (() -> Unit)?                                  = null
    var onError:    ((msg: String) -> Unit)?                       = null

    // ── 공개 상태 프로퍼티 ─────────────────────────────────────────
    val isReady: Boolean    get() = hopper != null
    val isIndexing: Boolean get() = indexing.get()
    val elapsedSec: Long    get() =
        if (indexingStartMs > 0) (System.currentTimeMillis() - indexingStartMs) / 1000 else 0L
    val cacheSizeKb: Long   get() {
        val dir = File(context.filesDir, graphCacheDirName)
        return dir.listFiles()?.sumOf { it.length() }?.div(1024) ?: 0L
    }

    private val osmFileName = "south-korea-260616.osm.pbf"
    private val graphCacheDirName = "gh-cache"

    fun initialize() {
        if (hopper != null) {
            Log.d("OppaNavi", "GH: already initialized, skip")
            onReady?.invoke()
            return
        }
        Log.d("OppaNavi", "GH: initialize() called, submitting background task")
        executor.submit {
            try {
                Log.d("OppaNavi", "GH: background thread started (name=${Thread.currentThread().name})")

                // 1단계 — PBF 복사
                val osmFile = copyPbfFromAssets()

                // 2단계 — graph-cache 디렉터리 상태 확인
                val graphDir = File(context.filesDir, graphCacheDirName)
                val cacheExists = graphDir.exists() && graphDir.listFiles()?.isNotEmpty() == true
                Log.d("OppaNavi", "GH: graph-cache dir=${graphDir.absolutePath} exists=$cacheExists")

                // 3단계+4단계 — gh 단일 선언, load or import 단일 흐름
                indexingStartMs = System.currentTimeMillis()
                indexing.set(true)
                startHeartbeat()
                var gh: GraphHopper? = null

                if (cacheExists) {
                    Log.d("OppaNavi", "GH: load() START — using existing cache")
                    val tryLoad = GraphHopper().forMobile()
                    tryLoad.setDataReaderFile(osmFile.absolutePath)
                    tryLoad.setGraphHopperLocation(graphDir.absolutePath)
                    tryLoad.setEncodingManager(EncodingManager.create("bike"))
                    try {
                        if (tryLoad.load(graphDir.absolutePath)) {
                            Log.d("OppaNavi", "GH: load() DONE (${elapsedSec}s)")
                            gh = tryLoad
                        }
                    } catch (e: Exception) {
                        Log.w("OppaNavi", "GH: load() EXCEPTION — purging cache for re-import", e)
                        graphDir.deleteRecursively()
                        Log.d("OppaNavi", "GH: corrupted cache deleted — fallback to import")
                    }
                }

                if (gh == null) {
                    Log.d("OppaNavi", "GH: importOrLoad() START — GraphHopperOSM")
                    val importer = GraphHopperOSM().forMobile()
                    importer.setDataReaderFile(osmFile.absolutePath)
                    importer.setGraphHopperLocation(graphDir.absolutePath)
                    importer.setEncodingManager(EncodingManager.create("bike"))
                    importer.importOrLoad()
                    Log.d("OppaNavi", "GH: importOrLoad() DONE (${elapsedSec}s)")
                    gh = importer
                }

                indexing.set(false)
                stopHeartbeat()
                hopper = gh ?: throw IllegalStateException("GH instance null after init")
                Log.d("OppaNavi", "GH: GH_READY")
                onReady?.invoke()
            } catch (e: Exception) {
                indexing.set(false)
                stopHeartbeat()
                Log.e("OppaNavi", "GH: init FAILED — ${e.javaClass.simpleName}: ${e.message}", e)
                onError?.invoke("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatFuture = scheduler.scheduleAtFixedRate({
            if (indexing.get()) {
                val elapsedSec = (System.currentTimeMillis() - indexingStartMs) / 1000
                val graphDir = File(context.filesDir, graphCacheDirName)
                val cacheBytes = graphDir.listFiles()?.sumOf { it.length() } ?: 0L
                Log.d("OppaNavi", "GH: indexing alive — elapsed=${elapsedSec}s cache=${cacheBytes / 1024}KB thread_alive=true")
                onProgress?.invoke(elapsedSec, cacheBytes / 1024)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun copyPbfFromAssets(): File {
        val dest = File(context.filesDir, osmFileName)
        if (dest.exists()) {
            Log.d("OppaNavi", "GH: PBF already on disk — ${dest.length() / 1024 / 1024}MB, skip copy")
        } else {
            Log.d("OppaNavi", "GH: PBF not found on disk, copying from assets...")
            context.assets.open(osmFileName).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("OppaNavi", "GH: PBF copy DONE — ${dest.length() / 1024 / 1024}MB at ${dest.absolutePath}")
        }
        return dest
    }

    fun route(from: LatLong, to: LatLong): List<LatLong> {
        val gh = hopper ?: run {
            Log.w("OppaNavi", "GH: route() called but not ready yet")
            return emptyList()
        }
        return try {
            val req = GHRequest(from.latitude, from.longitude, to.latitude, to.longitude)
                .setWeighting("fastest")
                .setVehicle("bike")
                .setLocale(Locale.US)
            val rsp = gh.route(req)
            if (rsp.hasErrors()) {
                Log.e("OppaNavi", "GH: route error — ${rsp.errors.first().message}")
                emptyList()
            } else {
                val pts = rsp.best.points
                val result = (0 until pts.size).map { i -> LatLong(pts.getLat(i), pts.getLon(i)) }
                Log.d("OppaNavi", "GH: route OK — ${result.size} points")
                result
            }
        } catch (e: Exception) {
            Log.e("OppaNavi", "GH: route exception — ${e.message}", e)
            emptyList()
        }
    }
    fun routeWithDetails(from: LatLong, to: LatLong): GHRouteInfo? {
        val gh = hopper ?: run {
            Log.w("OppaNavi", "GH: routeWithDetails() called but not ready yet")
            return null
        }
        return try {
            val req = GHRequest(from.latitude, from.longitude, to.latitude, to.longitude)
                .setWeighting("fastest")
                .setVehicle("bike")
                .setLocale(Locale.US)
            val rsp = gh.route(req)
            if (rsp.hasErrors()) {
                Log.e("OppaNavi", "GH: routeWithDetails error — ${rsp.errors.first().message}")
                null
            } else {
                val pts = rsp.best.points
                val points = (0 until pts.size).map { i -> LatLong(pts.getLat(i), pts.getLon(i)) }
                val distanceMeters = rsp.best.distance
                val timeSec = rsp.best.time / 1000
                Log.d(
                    "OppaNavi",
                    "GH: routeWithDetails OK — ${points.size} points, distance=${distanceMeters}m, time=${timeSec}s"
                )
                GHRouteInfo(
                    points = points,
                    distanceMeters = distanceMeters,
                    timeSec = timeSec
                )
            }
        } catch (e: Exception) {
            Log.e("OppaNavi", "GH: routeWithDetails exception — ${e.message}", e)
            null
        }
    }
    fun release() {
        stopHeartbeat()
        scheduler.shutdownNow()
        hopper?.close()
        hopper = null
        executor.shutdown()
        Log.d("OppaNavi", "GH: released")
    }
}
