package com.example.oppanavi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

class RideManager(private val context: Context) {

    private val USER_WEIGHT_KG = 67.0

    var isRideStarted = false
        private set
    var isPaused = false
        private set

    private var rideStartTime = 0L
    private var pausedTime = 0L
    private var pauseStartTime = 0L
    private var totalDistanceM = 0.0
    private var lastRecordedLatLong: LatLong? = null
    private var highSpeedCount = 0

    fun startRide() {
        isRideStarted = true
        rideStartTime = System.currentTimeMillis()
        pausedTime = 0L
        totalDistanceM = 0.0
        lastRecordedLatLong = null
        highSpeedCount = 0
    }

    fun stopRide() {
        isRideStarted = false
        isPaused = false
        rideStartTime = 0L
        pausedTime = 0L
        pauseStartTime = 0L
        totalDistanceM = 0.0
        lastRecordedLatLong = null
        highSpeedCount = 0
    }

    fun pause() {
        isPaused = true
        pauseStartTime = System.currentTimeMillis()
        highSpeedCount = 0
    }

    fun resume() {
        isPaused = false
        pausedTime += System.currentTimeMillis() - pauseStartTime
        highSpeedCount = 0
    }

    fun onLocationUpdate(latLong: LatLong, speedKmh: Int): Boolean {
        if (isRideStarted && !isPaused) {
            lastRecordedLatLong?.let { prev ->
                val dist = haversine(prev, latLong)
                if (dist < 50) totalDistanceM += dist
            }
            lastRecordedLatLong = latLong
        }

        if (isRideStarted && isPaused && speedKmh > 6) {
            highSpeedCount++
            if (highSpeedCount >= 3) {
                resume()
                return true
            }
        } else if (speedKmh <= 6) {
            highSpeedCount = 0
        }
        return false
    }

    fun getDistanceKm() = totalDistanceM / 1000.0

    fun getRideTimeStr(): String {
        val elapsed = getElapsedMs()
        val seconds = (elapsed / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
        } else {
            "%02d:%02d".format(minutes, seconds % 60)
        }
    }

    fun getAvgSpeed(): Double {
        val elapsed = getElapsedMs()
        return if (elapsed > 0 && totalDistanceM > 0) {
            getDistanceKm() / (elapsed / 3600000.0)
        } else 0.0
    }

    fun showFinishDialog(
        onStop: () -> Unit = {},
        onContinue: () -> Unit = {}
    ) {
        val distKm = getDistanceKm()
        val avgSpeed = getAvgSpeed()
        val elapsed = getElapsedMs()
        val hours = elapsed / 3600000.0
        val avgSpeedKmh = if (hours > 0) distKm / hours else 0.0
        val met = when {
            avgSpeedKmh < 12 -> 4.0
            avgSpeedKmh < 16 -> 6.0
            avgSpeedKmh < 20 -> 8.0
            avgSpeedKmh < 24 -> 10.0
            else -> 12.0
        }
        val kcal = (met * USER_WEIGHT_KG * hours).roundToInt()
        val timeStr = getRideTimeStr()

        val summary = "거리: ${"%.1f".format(distKm)}km\n시간: $timeStr\n평균속도: ${"%.1f".format(avgSpeed)}km/h\n칼로리: ${kcal}kcal"

        val finishMsg = when {
            distKm < 10 -> listOf(
                "샤방샤방~🚴‍♂️",
                "가볍게 몸 풀었네 👏",
                "오늘 짧아도 달린 거야! 인정 ✅"
            )
            distKm < 20 -> listOf(
                "리프레시 완료! 고생했어 오빠 ☕",
                "오늘 컨디션 괜찮다 🔥",
                "딱 적당한 거리! 태리도 기분 좋음 😄"
            )
            distKm < 40 -> listOf(
                "이 정도면 훈련인데? 😏",
                "오늘 좀 달렸는데? 허벅지 칭찬해 👏",
                "태리 코딩보다 오빠 라이딩이 더 열심히임 🔥"
            )
            distKm < 100 -> listOf(
                "와, 이 정도면 준프로 인정! 대단해 💪",
                "이 정도면 훈련인데? 😏",
                "나 감동받았어... 진짜로 🥹"
            )
            else -> listOf(
                "엉덩이 안 아파? 체력 장난 아니다! 🍑💪",
                "부산가냐? 레전드..ㄷㄷ🚴‍♂️🔥",
                "서버가 과부하 걸릴 뻔했잖아 이 거리에 🤯"
            )
        }.random()

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(finishMsg)
            .setMessage(summary)
            .setPositiveButton("라이딩 재개") { dialog, _ ->
                dialog.dismiss()
                onContinue()
            }
            .setNegativeButton("라이딩 종료") { _, _ ->
                onStop()
            }
            .setNeutralButton("클립보드 복사") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ride_summary", summary))
                Toast.makeText(context, "복사 완료!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun getElapsedMs() = System.currentTimeMillis() - rideStartTime - pausedTime

    private fun haversine(a: LatLong, b: LatLong): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon/2) * Math.sin(dLon/2)
        return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1-x))
    }
}