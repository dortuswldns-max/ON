package com.example.oppanavi

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RideLogger(private val context: Context) {

    private var fileWriter: FileWriter? = null
    private var logFile: File? = null
    private var isLogging = false
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 1000L  // 1초마다 기록

    // CSV 헤더
    private val CSV_HEADER = "timestamp,elapsed_sec,speed_kmh,accuracy_m,provider," +
            "nearest_index,dist_to_route_m,is_off_route,off_route_count," +
            "latitude,longitude\n"

    fun startLogging() {
        if (isLogging) return

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val fileName = "ride_log_$dateStr.csv"

        // 외부 저장소 없어도 되는 내부 저장소 사용
        val logDir = File(context.filesDir, "ride_logs")
        if (!logDir.exists()) logDir.mkdirs()

        logFile = File(logDir, fileName)
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
        longitude: Double
    ) {
        if (!isLogging) return

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return
        lastUpdateTime = now

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val line = "$timestamp,$elapsedSec,$speedKmh," +
                "${"%.1f".format(accuracyM)},$provider," +
                "$nearestIndex,${"%.1f".format(distToRouteM)}," +
                "$isOffRoute,$offRouteCount," +
                "${"%.6f".format(latitude)},${"%.6f".format(longitude)}\n"

        try {
            fileWriter?.write(line)
            fileWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopLogging(): String? {
        if (!isLogging) return null

        try {
            fileWriter?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

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

    fun deleteLogFile(file: File): Boolean {
        return file.delete()
    }

    val isActive get() = isLogging
}