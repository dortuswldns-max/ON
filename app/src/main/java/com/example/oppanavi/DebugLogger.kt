package com.example.oppanavi

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================
 * DebugLogger
 * ============================================================
 *
 * ON 내부 시스템 흐름(RouteManager, GraphHopperRouteSource,
 * NavigationEngine, MapManager, Activity Lifecycle) 추적용.
 *
 * RideLogger(실제 라이딩 데이터: speed, gps, distance 등)와는
 * 완전히 별도의 파일/폴더에 기록한다. 절대 혼합하지 않는다.
 *   RideLogger  → /files/ride_logs/ride_log_*.csv  (라이딩 시작~종료)
 *   DebugLogger → /files/debug_logs/debug_log_*.txt (앱 실행 전체)
 *
 * 앱이 켜져 있는 동안 전체를 기록한다 — MainActivity.onCreate()에서
 * 시작하고, onDestroy()에서 종료한다 (라이딩 시작/종료와는 무관).
 *
 * Logcat을 보지 않는다는 전제로 설계됨: 테스트(실주행) 후 파일을
 * 꺼내서 시간순으로 읽으면 어떤 일이 있었는지 전부 파악 가능해야 한다.
 * ============================================================
 */
class DebugLogger(private val context: Context) {

    private var fileWriter: FileWriter? = null
    private var logFile: File? = null
    private var isLogging = false

    fun start() {
        if (isLogging) return

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val logDir = File(context.filesDir, "debug_logs")
        if (!logDir.exists()) logDir.mkdirs()

        logFile = File(logDir, "debug_log_$dateStr.txt")
        fileWriter = FileWriter(logFile, true)
        isLogging = true

        write("APP", "APP_CREATE")
    }

    fun stop(): String? {
        if (!isLogging) return null
        write("APP", "APP_DESTROY")
        try { fileWriter?.close() } catch (e: Exception) { e.printStackTrace() }
        fileWriter = null
        isLogging = false
        return logFile?.absolutePath
    }

    private fun write(category: String, event: String, detail: String = "") {
        if (!isLogging) return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = if (detail.isEmpty()) {
            "$timestamp [$category] $event\n"
        } else {
            "$timestamp [$category] $event — $detail\n"
        }
        try {
            fileWriter?.write(line)
            fileWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------
    // RouteManager
    // ------------------------------------------------------------

    fun routeRequest(start: String, destination: String) {
        write("ROUTE", "ROUTE_REQUEST", "start=$start dest=$destination")
    }

    fun routeSuccess(distanceMeters: Double?, timeSec: Long?, pointCount: Int) {
        write("ROUTE", "ROUTE_SUCCESS", "distance=${distanceMeters}m time=${timeSec}s points=$pointCount")
    }

    fun routeFailed(error: String?) {
        write("ROUTE", "ROUTE_FAILED", "error=${error ?: "unknown"}")
    }

    fun routeClear() {
        write("ROUTE", "ROUTE_CLEAR")
    }

    // ------------------------------------------------------------
    // GraphHopperRouteSource
    // ------------------------------------------------------------

    fun ghCalculateStart() {
        write("GH", "GH_CALCULATE_START")
    }

    fun ghCalculateSuccess(pointCount: Int, distanceMeters: Double, timeSec: Long) {
        write("GH", "GH_CALCULATE_SUCCESS", "points=$pointCount distance=${distanceMeters}m time=${timeSec}s")
    }

    fun ghCalculateFailed(exception: String?) {
        write("GH", "GH_CALCULATE_FAILED", "exception=${exception ?: "unknown"}")
    }

    // ------------------------------------------------------------
    // NavigationEngine
    // ------------------------------------------------------------

    fun navRouteSet(pointCount: Int) {
        write("NAV", "NAV_ROUTE_SET", "points=$pointCount")
    }

    fun navUpdate(nearestIndex: Int, distanceToRoute: Double, remainingDistance: Double, progressPercent: Double) {
        write(
            "NAV", "NAV_UPDATE",
            "nearestIndex=$nearestIndex distanceToRoute=%.1fm remaining=%.0fm progress=%.0f%%".format(
                distanceToRoute, remainingDistance, progressPercent
            )
        )
    }

    fun navClear() {
        write("NAV", "NAV_CLEAR")
    }

    fun navOffRoute(offRouteCount: Int, distanceToRoute: Double) {
        write("NAV", "NAV_OFF_ROUTE", "count=$offRouteCount distanceToRoute=%.1fm".format(distanceToRoute))
    }

    // ------------------------------------------------------------
    // MapManager
    // ------------------------------------------------------------

    fun mapDrawRoute(pointCount: Int) {
        write("MAP", "MAP_DRAW_ROUTE", "points=$pointCount")
    }

    fun mapClearRoute() {
        write("MAP", "MAP_CLEAR_ROUTE")
    }

    fun mapDrawLocation() {
        write("MAP", "MAP_DRAW_LOCATION")
    }

    // ------------------------------------------------------------
    // Activity Lifecycle (start/stop에서 자동 기록되는 CREATE/DESTROY 외 추가분)
    // ------------------------------------------------------------

    fun appResume() {
        write("APP", "APP_RESUME")
    }

    fun appPause() {
        write("APP", "APP_PAUSE")
    }

    // ------------------------------------------------------------
    // 파일 관리 (RideLogger와 동일 패턴)
    // ------------------------------------------------------------

    fun getLogFiles(): List<File> {
        val logDir = File(context.filesDir, "debug_logs")
        return if (logDir.exists()) {
            logDir.listFiles()
                ?.filter { it.extension == "txt" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else emptyList()
    }

    fun deleteLogFile(file: File): Boolean = file.delete()

    val isActive get() = isLogging
}
