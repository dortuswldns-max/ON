package com.example.oppanavi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * ON 블랙박스 카메라 모듈
 *
 * 설계 원칙:
 * 1. ON Core(GPS/GPX/지도)와 완전 독립 — 이 파일 삭제만으로 블박 전면 제거 가능
 * 2. 모든 설정값은 상단 CONSTANTS에 집중 — 추후 SharedPreferences 전환 용이
 * 3. 카메라 오류 시 ON 네비 기능 유지 (앱 종료 절대 금지)
 * 4. 로그 촘촘하게 — 실주행 발열/성능 분석용
 *
 * 제거 방법:
 * - CameraModule.kt 삭제
 * - MainActivity에서 cameraModule 관련 코드 제거
 * - build.gradle에서 CameraX 의존성 제거
 * - AndroidManifest에서 CAMERA 권한 제거
 */
class CameraModule(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : SensorEventListener {

    // ========== CONSTANTS (추후 설정값화 대상) ==========
    companion object {
        private const val TAG = "ON_CameraModule"

        // 순환 버퍼 설정
        const val BUFFER_DURATION_SEC = 50          // 이벤트 이전 확보 시간 (초)
        const val SEGMENT_DURATION_SEC = 20         // 개별 세그먼트 길이 (초)
        const val BUFFER_SEGMENT_COUNT = 3          // 순환 버퍼 세그먼트 수 (50초 커버)

        // 이벤트 저장 범위
        const val EVENT_POST_AUTO_SEC = 10          // 자동 이벤트 이후 저장 (초)
        const val EVENT_POST_MANUAL_SEC = 180       // 수동 이벤트 이후 저장 (초)

        // 영상 품질 (추후 설정값화)
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30

        // 이벤트 트리거 기준값
        const val DECEL_THRESHOLD_KMH = 3.0f       // 급감속 감지 기준 (km/h/s)
        const val IMPACT_THRESHOLD_G = 2.5f         // 충격 감지 기준 (G)
        const val MANUAL_TAP_COUNT = 5              // 수동 트리거 연속 탭 수
        const val MANUAL_TAP_WINDOW_MS = 2000L      // 연속 탭 인정 시간 (ms)
        const val VOLUME_LONG_PRESS_MS = 1000L      // 볼륨 버튼 장누름 기준 (ms)

        // 저장 경로
        const val DIR_ROOT = "ON"
        const val DIR_VIDEO = "VIDEO"
        const val DIR_EVENT = "EVENT"
        const val DIR_LOG = "LOG"

        // 이벤트 영상 관리
        const val MAX_EVENT_FILES = 20              // 최대 이벤트 영상 보관 수

        // 카메라 선택 우선순위
        // CameraSelector: ULTRA_WIDE > WIDE > DEFAULT
        // 구현 복잡도 높을 경우 DEFAULT_BACK_CAMERA 폴백

        // 블랙박스 시작 모드 — 추후 "APP_START" 모드 추가 가능
        // "RIDE_START": 순환버퍼·이벤트감지·로그 모두 Ride Start 시점에 활성화
        // "APP_START" (예정): 앱 실행 즉시 순환버퍼 녹화 시작
        const val BLACKBOX_START_MODE = "RIDE_START"
    }

    // ========== 상태 ==========
    enum class CameraState {
        IDLE,           // 미초기화
        READY,          // 초기화 완료, 대기
        RECORDING,      // 순환 버퍼 녹화 중
        EVENT_SAVING,   // 이벤트 저장 중
        ERROR           // 오류 (ON 네비 계속 동작)
    }

    enum class EventType {
        DECELERATION,   // 급감속
        IMPACT,         // 충격
        MANUAL_TAP,     // 화면 5회 탭
        MANUAL_VOLUME   // 볼륨 버튼 장누름
    }

    var state: CameraState = CameraState.IDLE
        private set

    // 상태 변경 콜백 (MainActivity → UI 갱신용)
    var onStateChanged: ((CameraState) -> Unit)? = null
    var onEventTriggered: ((EventType) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ========== 내부 변수 ==========
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 순환 버퍼
    private val bufferDir: File by lazy {
        File(context.getExternalFilesDir(null), "$DIR_ROOT/BUFFER").also { it.mkdirs() }
    }
    private val videoDir: File by lazy {
        File(context.getExternalFilesDir(null), "$DIR_ROOT/$DIR_VIDEO").also { it.mkdirs() }
    }
    private val eventDir: File by lazy {
        File(context.getExternalFilesDir(null), "$DIR_ROOT/$DIR_EVENT").also { it.mkdirs() }
    }

    private var bufferIndex = 0                         // 현재 쓰는 버퍼 슬롯
    private val bufferFiles = Array<File?>(BUFFER_SEGMENT_COUNT) { null }
    private var segmentStartTime = 0L
    private var isRiding = false
    private var rideVideoFile: File? = null
    private var rideStartTimestamp = ""

    // 이벤트 트리거
    private var lastSpeed = 0f
    private var tapCount = 0
    private var lastTapTime = 0L
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // 로그
    private val logSdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ========== 초기화 ==========

    /**
     * CameraX 초기화 + Preview 연결
     * 실패 시 state = ERROR, ON 네비 계속 동작
     */
    fun initialize(previewView: PreviewView) {
        log("initialize() 시작")
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCamera(previewView)
                } catch (e: Exception) {
                    handleError("CameraProvider 초기화 실패: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            handleError("initialize() 예외: ${e.message}")
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        log("bindCamera() 시작")
        try {
            val provider = cameraProvider ?: run {
                handleError("cameraProvider null")
                return
            }

            // 카메라 선택: 초광각 우선 → 폴백 일반 후면
            val cameraSelector = selectBestCamera(provider)

            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // VideoCapture
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD)) // 720p
                .setExecutor(cameraExecutor)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)

            setState(CameraState.READY)
            log("bindCamera() 완료 — 카메라 바인딩 성공")

            // 가속도 센서 등록
            initSensor()

        } catch (e: Exception) {
            handleError("bindCamera() 예외: ${e.message}")
        }
    }

    /**
     * 초광각 → 일반 후면 순으로 선택
     * 추후 설정화: 사용자가 직접 선택 가능하도록 확장 고려
     */
    private fun selectBestCamera(provider: ProcessCameraProvider): CameraSelector {
        // CameraX에서 렌즈 타입 직접 지정은 제한적
        // 현재: DEFAULT_BACK_CAMERA (가장 안정적)
        // TODO: CameraCharacteristics로 초광각 감지 후 선택 (추후 구현)
        log("카메라 선택: DEFAULT_BACK_CAMERA (초광각 자동선택은 추후 구현)")
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun initSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) log("가속도 센서 없음 — 충격 감지 비활성")
        // 리스너 등록은 onRideStart()에서 수행 (BLACKBOX_START_MODE = RIDE_START)
    }

    private fun registerSensorListener() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            log("가속도 센서 등록 완료")
        }
    }

    private fun unregisterSensorListener() {
        sensorManager?.unregisterListener(this)
        log("가속도 센서 해제")
    }

    // ========== 주행 제어 ==========

    /**
     * Ride Start 시 호출 — 순환버퍼 녹화 + 이벤트 감지 시작 [MODE: RIDE_START]
     */
    fun onRideStart() {
        log("onRideStart() — [MODE: $BLACKBOX_START_MODE] 순환버퍼·이벤트감지 시작")
        if (state == CameraState.ERROR) {
            log("ERROR 상태 — 녹화 스킵, ON 네비 계속")
            return
        }
        isRiding = true
        rideStartTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        bufferIndex = 0
        registerSensorListener()
        startNextSegment()
    }

    /**
     * Ride Finish 시 호출 — 순환버퍼 정지, 마지막 세그먼트 Finalize 후 ride_*.mp4 저장
     */
    fun onRideFinish() {
        log("onRideFinish() — 순환버퍼 종료, ride_$rideStartTimestamp.mp4 저장 예정")
        isRiding = false
        unregisterSensorListener()
        stopCurrentSegment()
    }

    /**
     * 속도 업데이트 — 급감속 감지용
     * MainActivity에서 GPS 속도 업데이트 시 호출
     */
    fun updateSpeed(speedKmh: Float) {
        if (!isRiding || state != CameraState.RECORDING) return
        val delta = lastSpeed - speedKmh
        if (delta >= DECEL_THRESHOLD_KMH) {
            log("급감속 감지: ${lastSpeed} → ${speedKmh} km/h (Δ${delta})")
            triggerEvent(EventType.DECELERATION)
        }
        lastSpeed = speedKmh
    }

    // ========== 순환 버퍼 ==========

    private fun startNextSegment() {
        if (!isRiding) return
        val vc = videoCapture ?: run {
            handleError("videoCapture null — 세그먼트 시작 불가")
            return
        }

        val slotIndex = bufferIndex % BUFFER_SEGMENT_COUNT
        val file = File(bufferDir, "buf_$slotIndex.mp4")
        bufferFiles[slotIndex] = file
        if (file.exists()) file.delete()

        val outputOptions = FileOutputOptions.Builder(file).build()

        try {
            activeRecording = vc.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            segmentStartTime = System.currentTimeMillis()
                            setState(CameraState.RECORDING)
                            log("세그먼트 #$slotIndex 녹화 시작")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                handleError("세그먼트 #$slotIndex 오류: ${event.error}")
                            } else {
                                log("세그먼트 #$slotIndex 완료: ${file.length() / 1024}KB")
                                if (isRiding) {
                                    bufferIndex++
                                    startNextSegment()
                                } else {
                                    saveRideVideo()
                                    cleanupBuffer()
                                    setState(CameraState.READY)
                                }
                            }
                        }
                        else -> {}
                    }
                }

            // SEGMENT_DURATION_SEC 후 자동 교체
            mainHandler.postDelayed({
                if (isRiding && state == CameraState.RECORDING) {
                    log("세그먼트 #$slotIndex 시간 만료 — 다음 세그먼트로")
                    stopCurrentSegment()
                }
            }, SEGMENT_DURATION_SEC * 1000L)

        } catch (e: Exception) {
            handleError("startNextSegment() 예외: ${e.message}")
        }
    }

    private fun stopCurrentSegment() {
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            log("stopCurrentSegment() 예외 (무시): ${e.message}")
        }
    }

    private fun cleanupBuffer() {
        bufferFiles.forEach { it?.delete() }
        bufferFiles.fill(null)
        log("순환 버퍼 정리 완료")
    }

    private fun saveRideVideo() {
        val dest = File(videoDir, "ride_$rideStartTimestamp.mp4")
        val latest = bufferFiles.filterNotNull().filter { it.exists() }
            .maxByOrNull { it.lastModified() }
        latest?.let {
            it.copyTo(dest, overwrite = true)
            rideVideoFile = dest
            log("라이드 영상 저장: ${dest.name} (${dest.length() / 1024}KB) → ${dest.absolutePath}")
        } ?: log("라이드 영상 저장 실패 — 버퍼 없음")
    }

    // ========== 이벤트 트리거 ==========

    /**
     * 이벤트 발생 — 현재 버퍼 보호 + 이후 영상 추가 저장
     */
    fun triggerEvent(type: EventType) {
        if (state != CameraState.RECORDING) {
            log("triggerEvent($type) — 녹화 중 아님, 스킵")
            return
        }
        log("이벤트 트리거: $type")
        setState(CameraState.EVENT_SAVING)
        onEventTriggered?.invoke(type)

        val postDuration = when (type) {
            EventType.MANUAL_TAP, EventType.MANUAL_VOLUME -> EVENT_POST_MANUAL_SEC
            else -> EVENT_POST_AUTO_SEC
        }

        // 현재 버퍼 세그먼트 보호 후 이후 녹화 계속
        val eventTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val eventFile = File(eventDir, "event_${type.name}_$eventTime.mp4")

        // postDuration 후 이벤트 영상 병합 저장
        mainHandler.postDelayed({
            saveEventClip(type, eventFile, postDuration)
        }, postDuration * 1000L)

        // 녹화는 계속
        setState(CameraState.RECORDING)
        manageEventFiles()
    }

    private fun saveEventClip(type: EventType, dest: File, postSec: Int) {
        try {
            val oldest = bufferFiles
                .filterNotNull()
                .filter { it.exists() }
                .minByOrNull { it.lastModified() }
            oldest?.let {
                it.copyTo(dest, overwrite = true)
                log("이벤트 클립 저장: ${dest.name} (${dest.length() / 1024}KB)")
            } ?: log("이벤트 저장 실패 — 버퍼 없음")
        } catch (e: Exception) {
            log("saveEventClip() 예외: ${e.message}")
        }
    }

    /**
     * 이벤트 영상 FIFO 관리 — MAX_EVENT_FILES 초과 시 오래된 것 삭제
     */
    private fun manageEventFiles() {
        val files = eventDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_EVENT_FILES) {
            val toDelete = files.take(files.size - MAX_EVENT_FILES)
            toDelete.forEach {
                it.delete()
                log("이벤트 영상 FIFO 삭제: ${it.name}")
            }
        }
    }

    // ========== 수동 트리거 ==========

    /**
     * 화면 탭 이벤트 — MainActivity에서 호출
     * 연속 MANUAL_TAP_COUNT회 → 수동 이벤트
     */
    fun onScreenTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > MANUAL_TAP_WINDOW_MS) {
            tapCount = 0
        }
        tapCount++
        lastTapTime = now
        log("화면 탭: $tapCount / $MANUAL_TAP_COUNT")
        if (tapCount >= MANUAL_TAP_COUNT) {
            tapCount = 0
            triggerEvent(EventType.MANUAL_TAP)
        }
    }

    /**
     * 볼륨 버튼 장누름 — MainActivity onKeyLongPress에서 호출
     */
    fun onVolumeLongPress() {
        log("볼륨 장누름 — 수동 트리거")
        triggerEvent(EventType.MANUAL_VOLUME)
    }

    // ========== 가속도 센서 (충격 감지) ==========

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (!isRiding || state != CameraState.RECORDING) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalG = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() / 9.8f

        if (totalG > IMPACT_THRESHOLD_G) {
            log("충격 감지: ${totalG}G (기준: ${IMPACT_THRESHOLD_G}G)")
            triggerEvent(EventType.IMPACT)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ========== 시스템 상태 (로그용) ==========

    /**
     * 배터리 온도 (°C) — RideLogger 확장용
     * 블랙박스 발열 분석 핵심 지표
     */
    fun getBatteryTemperature(): Float {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            // BatteryManager.EXTRA_TEMPERATURE는 Intent 기반 — registerReceiver 필요
            // 여기선 간단히 0 반환, RideLogger에서 Intent 방식으로 구현 권장
            0f
        } catch (e: Exception) {
            log("배터리 온도 조회 실패: ${e.message}")
            -1f
        }
    }

    /**
     * RAM 사용량 (MB) — RideLogger 확장용
     */
    fun getRamUsageMb(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        } catch (e: Exception) {
            log("RAM 조회 실패: ${e.message}")
            -1L
        }
    }

    /**
     * 로그용 상태 스냅샷 — RideLogger에서 1초마다 호출
     */
    fun getStatusSnapshot(): CameraStatus {
        return CameraStatus(
            state = state.name,
            isRecording = state == CameraState.RECORDING || state == CameraState.EVENT_SAVING,
            bufferSegmentCount = bufferFiles.count { it?.exists() == true },
            ramUsageMb = getRamUsageMb()
        )
    }

    data class CameraStatus(
        val state: String,
        val isRecording: Boolean,
        val bufferSegmentCount: Int,
        val ramUsageMb: Long
    )

    // ========== 유틸 ==========

    private fun setState(newState: CameraState) {
        if (state != newState) {
            log("상태 변경: $state → $newState")
            state = newState
            mainHandler.post { onStateChanged?.invoke(newState) }
        }
    }

    /**
     * 카메라 오류 처리 — 항상 ERROR 상태로, ON 네비는 계속
     */
    private fun handleError(msg: String) {
        Log.e(TAG, "[ERROR] $msg")
        setState(CameraState.ERROR)
        mainHandler.post { onError?.invoke(msg) }
    }

    private fun log(msg: String) {
        Log.d(TAG, "[${logSdf.format(Date())}] $msg")
    }

    /**
     * 정리 — MainActivity onDestroy에서 호출
     */
    fun release() {
        log("release() — 카메라 모듈 정리")
        isRiding = false
        unregisterSensorListener()
        stopCurrentSegment()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        cleanupBuffer()
        log("release() 완료")
    }
}