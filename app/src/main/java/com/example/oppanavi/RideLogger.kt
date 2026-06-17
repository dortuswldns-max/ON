package com.example.oppanavi

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.BatteryManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RideLogger(private val context: Context) {

    companion object {
        const val DEBUG_LOGGING = true
    }

    private var fileWriter: FileWriter? = null
    private var logFile: File? = null
    private var isLogging = false
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 1000L

    private var cameraModule: CameraModule? = null

    fun setCameraModule(module: CameraModule) {
        cameraModule = module
    }

    private val BASE_HEADER = "timestamp,elapsed_sec,speed_kmh,accuracy_m,provider," +
            "nearest_index,dist_to_route_m,is_off_route,off_route_count," +
            "latitude,longitude,event_type"

    private val DEBUG_HEADER = "battery_temp,ram_mb,camera_state,frame_drop," +
            "segment_index,event_count,gps_satellites,buffer_size_kb"

    private val CSV_HEADER get() =
        if (DEBUG_LOGGING) "$BASE_HEADER,$DEBUG_HEADER\n" else "$BASE_HEADER\n"

    fun startLogging() {
        if (isLogging) return

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val logDir = File(context.filesDir, "ride_logs")
        if (!logDir.exists()) logDir.mkdirs()

        logFile = File(logDir, "ride_log_$dateStr.csv")
        fileWriter = FileWriter(logFile, true)
        fileWriter?.write(CSV_HEADER)
        fileWriter?.flush()

        isLogging = true
        lastUpdateTime = System.currentTimeMillis()
    }

    fun log(
        elapsedSec: Long,
        speedKmh: Int,
        accuracyM: Float,
        provider: String,
        nearestIndex: Int,
        distToRouteM: Double,
        isOffRoute: Boolean,
        offRouteCount: Int,
        latitude: Double,
        longitude: Double,
        gpsSatellites: Int = -1,
        frameDrop: Int = 0
    ) {
        if (!isLogging) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val base = "$timestamp,$elapsedSec,$speedKmh," +
                "${"%.1f".format(accuracyM)},$provider," +
                "$nearestIndex,${"%.1f".format(distToRouteM)}," +
                "$isOffRoute,$offRouteCount," +
                "${"%.6f".format(latitude)},${"%.6f".format(longitude)}"

        // event_type은 일반 로그 행에서 공백, logEvent()에서만 채워짐
        val line = if (DEBUG_LOGGING) {
            val snap = cameraModule?.getStatusSnapshot()
            val batteryTemp = getBatteryTemp()
            val ramMb = snap?.ramUsageMb ?: -1L
            val cameraState = snap?.state ?: "N/A"
            val dropCount = snap?.frameDrop ?: frameDrop
            val segIdx = snap?.segmentIndex ?: -1
            val evtCount = snap?.eventCount ?: -1
            val bufKb = snap?.bufferSizeKb ?: -1L
            "$base,,${"%.1f".format(batteryTemp)},$ramMb,$cameraState,$dropCount," +
                    "$segIdx,$evtCount,$gpsSatellites,$bufKb\n"
        } else {
            "$base,\n"
        }

        try {
            fileWriter?.write(line)
            fileWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 이벤트 발생 시 즉시 호출 — 해당 행에만 event_type 기록, 나머지 컬럼 공백
     * timestamp와 elapsed_sec로 영상 파일(ride_YYYYMMDD_HHmmss.mp4) 내 재생 위치 계산 가능
     *   예) 영상 시작 17:42:00, 이벤트 17:42:13 → 영상 13초 지점
     */
    fun logEvent(eventTypeName: String, elapsedSec: Long) {
        if (!isLogging) return
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // cols 3-11(speed~longitude) 공백 → elapsed_sec 뒤에 쉼표 10개로 9개 빈 컬럼 생성
        val line = "$timestamp,$elapsedSec${",".repeat(10)}$eventTypeName" +
                (if (DEBUG_LOGGING) ",".repeat(8) else "") + "\n"
        try {
            fileWriter?.write(line)
            fileWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getBatteryTemp(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (raw == null || raw == Int.MIN_VALUE) -1f else raw / 10.0f
        } catch (e: Exception) {
            -1f
        }
    }

    fun stopLogging(): String? {
        if (!isLogging) return null
        try { fileWriter?.close() } catch (e: Exception) { e.printStackTrace() }
        fileWriter = null
        isLogging = false
        return logFile?.absolutePath
    }

    fun getLogFiles(): List<File> {
        val logDir = File(context.filesDir, "ride_logs")
        return if (logDir.exists()) {
            logDir.listFiles()
                ?.filter { it.extension == "csv" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else emptyList()
    }

    fun deleteLogFile(file: File): Boolean = file.delete()

    val isActive get() = isLogging
}
