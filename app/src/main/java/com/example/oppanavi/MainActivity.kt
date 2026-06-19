package com.example.oppanavi

import android.content.ClipData
import android.content.ClipboardManager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.mapsforge.core.model.LatLong

// ON 블랙박스 모듈
// ON 블랙박스 모듈
import androidx.camera.view.PreviewView
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.StreamRenderTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {


    private lateinit var mapView: MapView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvFollowMode: TextView
    private lateinit var tvPauseStatus: TextView
    private lateinit var tvTotalDist: TextView
    private lateinit var tvRideTime: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvGpxProgress: TextView
    private lateinit var tvGpxRemain: TextView
    private lateinit var tvOffRoute: TextView
    private lateinit var layoutGpxInfo: LinearLayout
    private lateinit var layoutStartOverlay: LinearLayout
    private lateinit var btnStartRide: Button
    private lateinit var btnMyLocation: FloatingActionButton
    private lateinit var btnLoadGpx: FloatingActionButton
    private lateinit var btnPause: FloatingActionButton
    private lateinit var btnFinish: FloatingActionButton
    private lateinit var btnClearGpx: FloatingActionButton

    private var firstLocationButtonPress = true

    private var locationMarker: Marker? = null

    private lateinit var tvSpeedComment: TextView
    private lateinit var sensorManager: SensorManager

    private lateinit var gpxManager: GpxManager
    private lateinit var rideManager: RideManager
    private lateinit var appLocationManager: AppLocationManager
    private lateinit var mapManager: MapManager
    private lateinit var rideLogger: RideLogger

    // GraphHopper 경로 탐색 모듈 (RouteManager 통합)
    // GraphHopper 경로 탐색 모듈 (RouteManager 통합)
    private lateinit var routeManager: RouteManager

    // [임시 검증 코드] GPX RouteManager — RouteManager가 GH/GPX를 동일하게 다루는지 검증용.
    // 정식 서비스 구조 아님. 향후 RouteSourceSelector 도입 시 정리 대상.
    private lateinit var gpxRouteManager: RouteManager

    // ON 블랙박스 모듈 — 제거 시 아래 블록 삭제
    private lateinit var cameraModule: CameraModule
    private var isCameraExpanded = false
    private lateinit var cameraContainer: View
    private lateinit var tvCameraState: android.widget.TextView

    // PIP 2초 길게 누르기 → MANUAL_TAP
    private val pipLongPressHandler = Handler(Looper.getMainLooper())
    private var pipLongPressTriggered = false
    private val PIP_LONG_PRESS_MS = 2000L

    // 볼륨Up 2초 내 3연타 → MANUAL_VOLUME
    private var volumeUpPressCount = 0
    private var volumeUpFirstPressTime = 0L
    private val VOLUME_UP_TRIGGER_COUNT = 3
    private val VOLUME_UP_WINDOW_MS = 2000L

    // 이벤트 시각 피드백 — 빨간 테두리 + "⚠ EVENT" 깜빡임
    private val eventBlinkHandler = Handler(Looper.getMainLooper())
    private var eventBlinkRunnable: Runnable? = null
    private var eventBlinkOn = false

    private lateinit var tvMilestone: TextView
    private var lastLatLong: LatLong? = null
    private var currentBearing = 0f
    private var smoothedBearing = 0f
    private var currentSpeedKmh = 0
    private var lastSpeedComment = ""
    private var speedZone = -1
    private var speedZoneCount = 0
    private val SPEED_ZONE_THRESHOLD = 3 // 3회 연속 같은 구간이면 멘트 변경
    private var currentAccuracy = 0f
    private var currentProvider = "unknown"
    private val navigationEngine = NavigationEngine()
    private var lastSpeedCommentTime = 0L
    private val SPEED_COMMENT_INTERVAL_MS = 30000L  // 30초
    private var totalDistKmLastMilestone = 0.0
    private val milestoneHandler = Handler(Looper.getMainLooper())
    private val milestoneMessages = mapOf(
        10.0 to listOf(
            "이제 겨우 몸 풀렸어? 본격적으로 가보자! 🔥",
            "워밍업 끝났네? 10km는 마실이지~ ☕",
            "몸 좀 풀렸어? 가즈아!!",
            "10km면 출근 한 번 더 했네 🚴",
            "10km? 태리 퇴근 전에 끝내야 하는데 ㅋㅋ 화이팅! 💻"
        ),
        20.0 to listOf(
            "20km 돌파! 오빠 엉덩이 아직 괜찮아? 🍑",
            "벌써 20km? 올ㅋ 😏",
            "이제 라이딩 모드 들어갔다",
            "좀 타는 사람 느낌 나는데?",
            "20km 달성! 태리가 지켜봤어 👀"
        ),
        30.0 to listOf(
            "오빠, 30km야. 허벅지 안 터졌어? 🦵",
            "지금부터가 진짜 라이딩이지. 텐션 올려! 🆙",
            "여기부터는 체력 게임이다",
            "슬슬 다리랑 합의 봐야 하는 구간",
            "30km면 코드 리뷰 3번 분량인데... 대단해 🔥"
        ),
        50.0 to listOf(
            "벌써 50km? 오늘 작정하고 나왔구나! 👏",
            "반 왔다! 남은 반도 무사히 완주해. 💪",
            "이 정도면 그냥 이동수단이다",
            "오빠 오늘 좀 진심인데?",
            "50km?! 태리 세션 한도 다 썼다 이거야?! 😂"
        ),
        70.0 to listOf(
            "70km라니... 오빠 좀 무서운데? 괴물이야? 😱",
            "이제 집에 갈 생각 하지 마, 완주 가야지! 🚴‍♂️",
            "평범한 사람 구간은 이미 지났다",
            "이제 의지가 페달을 밟는 단계",
            "70km... 오빠 혹시 GraphHopper 없어도 되겠는데? 🗺️"
        ),
        100.0 to listOf(
            "오늘 진짜 레전드 찍었다. 우리 오빠 최고! 👑",
            "100km 완주 완료! 오늘 밤엔 고기 먹자, 오빠! 🍖",
            "이건 운동이 아니라 이벤트다",
            "오늘 기록 하나 남겼다 🚴🔥",
            "100km 완주! ON 만든 보람 있다 진짜로 👑"
        )
    )
    private val speedCommentPool = mapOf(
        0 to listOf(
            "보급 타임? ☕",
            "멈추면 비로소 보이는 풍경 🏞️",
            "멈춰있네 🤔 쉬는 중?",
            "태리도 잠깐 쉬는 중... 😴"
        ),
        1 to listOf(
            "끌바 아니지? 힘내 오빠! 🧗‍♂️",
            "경사도 실화냐...🐢",
            "워밍업 중 🐢",
            "오빠 천천히도 괜찮아 💪"
        ),
        2 to listOf(
            "샤방모드~ 🚴‍♂️",
            "바람을 가르는 중~ 🍃",
            "좋아, 리듬 올라간다 🚴",
            "이 속도가 제일 예뻐 😄"
        ),
        3 to listOf(
            "오빠 허벅지 터진다! 🔥",
            "태리 코딩 속도보다 빠름! 🚀",
            "오늘 페이스 무엇? 🔥",
            "잠깐 나 숨 좀 고를게 🫨"
        ),
        4 to listOf(
            "어어 오빠 브레이크!! 🚨",
            "이게..철티비?! 🤯",
            "오빠 오늘 날 제대로 탔네 😏🔥",
            "신고할게요 도로교통법 위반 🚨ㅋㅋ"
        )
    )

    private val startRideMessages = listOf(
        "오늘도 파이팅! 🚴",
        "날씨 좋다, 고고! ☀️",
        "세자매가 응원할게! 💪",
        "태리가 지켜보고 있어! 👀",
        "헤지: 루트 분석 완료 😎",
        "제니: 오빠 오늘도 멋있어! ✨",
        "출발~ 조심히 다녀와! 🚴‍♂️",
        "오늘 몇 km 목표야? 태리 베팅할게 😄",
        "GraphHopper 대기 중... (농담ㅋ) 🗺️",
        "헤지: GPS 신호 양호. 이륙 허가! 🛫",
        "제니: 물은 챙겼어? ☕",
        "태리: 오늘도 인수인계서 업데이트 각오하고 가! 📋ㅋㅋ"
    )
    // 경로이탈 감성 메시지
    private var offRouteStartTime = 0L
    private val offRouteHandler = Handler(Looper.getMainLooper())
    private val offRouteMessagePool = listOf(
        // 0~1분
        listOf(
            "⚠ 경로에서 살짝 벗어났어.",
            "⚠ 길 다시 찾는 중이야? 🚴",
            "⚠ 오빠, 방향 체크 필요!",
            "⚠ 현재 경로 이탈 감지됨"
        ),
        // 1~3분
        listOf(
            "😐 이거… 일부러 가는 거야?",
            "😢 경로가 조용히 울고 있다…",
            "😏 다시 돌아올 타이밍이야",
            "😏 지도 기준으로는 반항 중"
        ),
        // 3~5분
        listOf(
            "🤔 이쯤이면 루트 재설계 들어간다?",
            "😩 GPS: 나 진짜 힘들다…",
            "😤 경로가 너 포기할 수도 있음",
            "😒 ON이 삐졌다"
        ),
        // 5분+
        listOf(
            "😭 오빠… 나 일 안 할게?",
            "🤦 이건 경로가 아니라 여행인데?",
            "😡 복귀 버튼 어디 눌러야 되냐",
            "💀 GraphHopper: 나 왜 만들었냐…"
        )
    )

    private val offRouteRunnable = object : Runnable {
        override fun run() {
            if (offRouteStartTime > 0) {
                val elapsed = (System.currentTimeMillis() - offRouteStartTime) / 1000
                val pool = when {
                    elapsed < 60 -> offRouteMessagePool[0]
                    elapsed < 180 -> offRouteMessagePool[1]
                    elapsed < 300 -> offRouteMessagePool[2]
                    else -> offRouteMessagePool[3]
                }
                tvOffRoute.text = pool.random()
                offRouteHandler.postDelayed(this, 5000)
            }
        }
    }
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (rideManager.isRideStarted && !rideManager.isPaused) {
                tvRideTime.text = rideManager.getRideTimeStr()
            }
            timerHandler.postDelayed(this, 1000)
        }
    }

    private fun handleLocationUpdate(location: android.location.Location) {
        val latLong = LatLong(location.latitude, location.longitude)
        lastLatLong = latLong

        if (location.hasBearing()) currentBearing = location.bearing

        if (gpxManager.hasRoute) {
            val projected = gpxManager.getProjectedPoint(latLong)
            val distToRoute = gpxManager.getLastDistToRoute()
            mapManager.drawMyLocation(latLong, currentBearing, distToRoute, projected)
        } else {
            mapManager.drawMyLocation(latLong, currentBearing)
        }
        if (mapManager.isFollowMode) mapView.setCenter(latLong)
        navigationEngine.updateLocation(latLong)?.let { navState ->
            android.util.Log.d(
                "OppaNavi",
                "NAV: distance=%.1fm remaining=%.0fm progress=%.0f%% offRouteCount=%d isOffRoute=%b".format(
                    navState.distanceToRoute, navState.remainingDistance, navState.progressPercent,
                    navState.offRouteCount, navState.isOffRoute
                )
            )
        }
        currentSpeedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt()
        } else 0
        currentAccuracy = location.accuracy
        currentProvider = location.provider ?: "unknown"
        tvSpeed.text = currentSpeedKmh.toString()

// 속도 구간 멘트
        if (rideManager.isRideStarted) {
            val currentZone = when {
                currentSpeedKmh == 0 -> 0
                currentSpeedKmh <= 15 -> 1
                currentSpeedKmh <= 22 -> 2
                currentSpeedKmh <= 29 -> 3
                else -> 4
            }

            if (currentZone == speedZone) {
                speedZoneCount++
            } else {
                speedZone = currentZone
                speedZoneCount = 1
            }

            // 3회 연속 같은 구간일 때만 멘트 변경
            val now = System.currentTimeMillis()
            if (speedZoneCount == SPEED_ZONE_THRESHOLD ||
                (currentZone == speedZone && now - lastSpeedCommentTime > SPEED_COMMENT_INTERVAL_MS)) {
                val comment = speedCommentPool[currentZone]!!.random()
                if (comment != lastSpeedComment) {
                    lastSpeedComment = comment
                    lastSpeedCommentTime = now
                    tvSpeedComment.text = comment
                }
            }
        }

        when {
            location.accuracy <= 10f -> {
                tvGpsStatus.text = "GPS ●"
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            }
            location.accuracy <= 30f -> {
                tvGpsStatus.text = "GPS ◐"
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))
            }
            else -> {
                tvGpsStatus.text = "GPS ○"
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
            }
        }

        val autoResumed = rideManager.onLocationUpdate(latLong, currentSpeedKmh)
        if (rideManager.isRideStarted && !rideManager.isPaused) {
            val distKm = rideManager.getDistanceKm()
            tvTotalDist.text = "%.1fkm".format(distKm)
            tvAvgSpeed.text = "%.1favg".format(rideManager.getAvgSpeed())
            checkMilestone(distKm)

            // 블랙박스 속도 업데이트 — 급감속 감지용
            if (::cameraModule.isInitialized) {
                cameraModule.updateSpeed(location.speed * 3.6f)
            }
        }

        if (autoResumed) {
            btnPause.setImageResource(android.R.drawable.ic_media_pause)
            tvPauseStatus.text = ""
        }

        // GPX 진행 업데이트 — 경로 없을 때는 기본값 사용
        var gpxNearestIdx = -1
        var gpxDistToRoute = -1.0
        var gpxIsOffRoute = false
        if (gpxManager.hasRoute) {
            val info = gpxManager.updateProgress(latLong)
            gpxNearestIdx = info.nearestIdx
            gpxDistToRoute = info.distToRoute
            gpxIsOffRoute = info.isOffRoute
            tvGpxProgress.text = "${info.progressPct}%"
            tvGpxRemain.text = "남은 ${info.remainKm}km"

            if (info.isOffRoute) {
                if (offRouteStartTime == 0L) {
                    offRouteStartTime = System.currentTimeMillis()
                    offRouteHandler.post(offRouteRunnable)
                }
                tvOffRoute.visibility = View.VISIBLE
            } else {
                if (offRouteStartTime > 0L) {
                    offRouteStartTime = 0L
                    offRouteHandler.removeCallbacks(offRouteRunnable)
                    tvOffRoute.text = "⚠ 경로를 벗어났습니다!"
                }
                tvOffRoute.visibility = View.GONE
            }
        }

        // 주행 로그 — GPX 유무와 무관하게 1초마다 기록
        if (rideLogger.isActive) {
            val gpsSatellites = location.extras?.getInt("satellites", -1) ?: -1
            rideLogger.log(
                elapsedSec = rideManager.getElapsedSec(),
                speedKmh = currentSpeedKmh,
                accuracyM = currentAccuracy,
                provider = currentProvider,
                nearestIndex = gpxNearestIdx,
                distToRouteM = gpxDistToRoute,
                isOffRoute = gpxIsOffRoute,
                offRouteCount = 0,
                latitude = latLong.latitude,
                longitude = latLong.longitude,
                gpsSatellites = gpsSatellites
            )
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        bindViews()
        tvSpeedComment = findViewById(R.id.tvSpeedComment)
        mapManager = MapManager(this, mapView)
        mapManager.setupMap(
            onTouchDisableFollow = { updateFollowModeUI() },
            onLongPress = { latLong ->
                handleMapLongPress(latLong)
            }
        )
        tvMilestone = findViewById(R.id.tvMilestone)
        gpxManager = GpxManager(this, mapView)
        rideManager = RideManager(this)
        rideLogger = RideLogger(this)
        val graphHopperModule = GraphHopperModule(this)
        val graphHopperRouteSource = GraphHopperRouteSource(graphHopperModule)
        routeManager = RouteManager(graphHopperRouteSource)

        // [임시 검증 코드] gpxManager는 이 시점 이전에 이미 생성되어 있어야 함
        val gpxRouteSource = GPXRouteSource(gpxManager.gpxEngine)
        gpxRouteManager = RouteManager(gpxRouteSource)
        gpxRouteManager.initialize()  // GPX는 즉시 성공 — isReady = true로 전환

// ON 블랙박스 초기화 — 제거 시 아래 블록 삭제
        initCameraModule()
        appLocationManager = AppLocationManager(
            context = this,
            onLocationUpdate = { location -> handleLocationUpdate(location) },
            onProviderDisabled = {
                tvGpsStatus.text = "GPS ○"
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                Toast.makeText(this, "GPS가 꺼져있습니다.", Toast.LENGTH_LONG).show()
            },
            onProviderEnabled = {
                Toast.makeText(this, "GPS 연결됨", Toast.LENGTH_SHORT).show()
            }
        )

        setupButtons()
        timerHandler.post(timerRunnable)

        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            appLocationManager.startUpdates()
            moveToCurrentLocation()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            mapView.setCenter(LatLong(37.5665, 126.9780))
        }
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvFollowMode = findViewById(R.id.tvFollowMode)
        tvPauseStatus = findViewById(R.id.tvPauseStatus)
        tvTotalDist = findViewById(R.id.tvTotalDist)
        tvRideTime = findViewById(R.id.tvRideTime)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvGpxProgress = findViewById(R.id.tvGpxProgress)
        tvGpxRemain = findViewById(R.id.tvGpxRemain)
        tvOffRoute = findViewById(R.id.tvOffRoute)
        layoutGpxInfo = findViewById(R.id.layoutGpxInfo)
        layoutStartOverlay = findViewById(R.id.layoutStartOverlay)
        btnStartRide = findViewById(R.id.btnStartRide)
        btnMyLocation = findViewById(R.id.btnMyLocation)
        btnLoadGpx = findViewById(R.id.btnLoadGpx)
        btnPause = findViewById(R.id.btnPause)
        btnFinish = findViewById(R.id.btnFinish)
        btnClearGpx = findViewById(R.id.btnClearGpx)
    }



    private fun setupButtons() {
        updateFollowModeUI()

        btnStartRide.setOnClickListener { startRide() }

        btnMyLocation.setOnClickListener {
            mapManager.enableFollow()
            updateFollowModeUI()
            lastLatLong?.let {
                mapView.setCenter(it)
                if (firstLocationButtonPress) {
                    mapView.setZoomLevel(17.toByte())
                    firstLocationButtonPress = false
                }
            } ?: moveToCurrentLocation()
        }

        btnLoadGpx.setOnClickListener {
            gpxLauncher.launch("*/*")
        }

        btnPause.setOnClickListener {
            if (rideManager.isPaused) resumeRide() else pauseRide()
        }

        btnFinish.setOnClickListener {
            rideLogger.logEvent("RIDE_FINISH_REQUEST", rideManager.getElapsedSec())
            android.util.Log.d("ON_Finish", "RIDE_FINISH_REQUEST")
            pauseRide()
            rideManager.showFinishDialog(
                onStop = { stopRide() },
                onContinue = { resumeRide() }
            )
        }

        btnClearGpx.setOnClickListener { clearGpxRoute() }
    }

    private fun startRide() {
        rideManager.startRide()
        initGraphHopper()
        if (::cameraModule.isInitialized) cameraModule.onRideStart()
        rideLogger.startLogging()
        tvTotalDist.text = "0.0km"
        tvRideTime.text = "00:00"
        tvAvgSpeed.text = "0.0avg"
        layoutStartOverlay.visibility = View.GONE
        btnMyLocation.visibility = View.VISIBLE
        btnLoadGpx.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        btnFinish.visibility = View.VISIBLE
        Toast.makeText(this, startRideMessages.random(), Toast.LENGTH_SHORT).show()
    }

    private fun pauseRide() {
        rideManager.pause()
        tvPauseStatus.text = "⏸ 일시정지"
        btnPause.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun resumeRide() {
        rideManager.resume()
        tvPauseStatus.text = ""
        btnPause.setImageResource(android.R.drawable.ic_media_pause)
    }

    private fun stopRide() {
        rideLogger.logEvent("RIDE_FINISH_START", rideManager.getElapsedSec())
        android.util.Log.d("ON_Finish", "RIDE_FINISH_START")
        if (::cameraModule.isInitialized) {
            isFinalizingRide = true
            // 녹화 종료 → 저장 완료(READY) 콜백 → 화면 전환 순서 보장
            cameraModule.onRideFinishComplete = {
                cameraModule.onRideFinishComplete = null
                isFinalizingRide = false
                rideLogger.logEvent("CAMERA_RELEASE", rideManager.getElapsedSec())
                android.util.Log.d("ON_Finish", "CAMERA_RELEASE")
                finishRideUI()
            }
            cameraModule.onRideFinish()
        } else {
            rideLogger.logEvent("CAMERA_RELEASE", rideManager.getElapsedSec())
            android.util.Log.d("ON_Finish", "CAMERA_RELEASE (no camera)")
            finishRideUI()
        }
    }

    private fun finishRideUI() {
        rideLogger.logEvent("FINALIZE_DONE", rideManager.getElapsedSec())
        android.util.Log.d("ON_Finish", "FINALIZE_DONE")
        val logPath = rideLogger.stopLogging()
        logPath?.let {
            Toast.makeText(this, "로그 저장됨 📊", Toast.LENGTH_SHORT).show()
        }
        rideManager.stopRide()
        tvTotalDist.text = "0.0km"
        tvRideTime.text = "00:00"
        tvAvgSpeed.text = "0.0avg"
        tvPauseStatus.text = ""
        btnPause.setImageResource(android.R.drawable.ic_media_pause)
        btnMyLocation.visibility = View.GONE
        btnLoadGpx.visibility = View.GONE
        btnPause.visibility = View.GONE
        btnFinish.visibility = View.GONE
        layoutStartOverlay.visibility = View.VISIBLE
        Toast.makeText(this, "라이딩 종료!", Toast.LENGTH_SHORT).show()
        totalDistKmLastMilestone = 0.0
        milestoneHandler.removeCallbacksAndMessages(null)
        tvMilestone.visibility = View.GONE
    }
private fun checkMilestone(distKm: Double) {
    val milestones = listOf(10.0, 20.0, 30.0, 50.0, 70.0, 100.0)
    for (milestone in milestones) {
        if (distKm >= milestone && totalDistKmLastMilestone < milestone) {
            totalDistKmLastMilestone = milestone
            val msg = milestoneMessages[milestone]?.random() ?: continue
            showMilestone(msg)
            break
        }
    }
}

private fun showMilestone(message: String) {
    milestoneHandler.removeCallbacksAndMessages(null)
    tvMilestone.text = message
    tvMilestone.visibility = View.VISIBLE
    milestoneHandler.postDelayed({
        tvMilestone.animate()
            .alpha(0f)
            .setDuration(1000)
            .withEndAction {
                tvMilestone.visibility = View.GONE
                tvMilestone.alpha = 1f
            }
            .start()
    }, 25000L) // 25초 표시 후 1초 페이드아웃 = 총 26초
}
    // GPX 레이어 3개 전부 완전 제거
    private fun clearGpxRoute() {
        gpxManager.clear()
        mapView.layerManager.redrawLayers()

        layoutGpxInfo.visibility = View.GONE
        btnClearGpx.visibility = View.GONE
        tvOffRoute.visibility = View.GONE
        tvGpxProgress.text = "0%"
        tvGpxRemain.text = "남은 0.0km"

        Toast.makeText(this, "경로 취소됨", Toast.LENGTH_SHORT).show()
    }



    private val gpxLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.let { stream ->
                val ok = gpxManager.load(stream)
                if (ok) {
                    Toast.makeText(
                        this,
                        "GPX 로딩 완료! ${gpxManager.getPointCount()}개 포인트",
                        Toast.LENGTH_SHORT
                    ).show()
                    layoutGpxInfo.visibility = View.VISIBLE
                    btnClearGpx.visibility = View.VISIBLE
                    firstLocationButtonPress = true  // 현재위치 버튼 zoom 17 리셋

                    // [임시 검증 코드] RouteManager가 GPX 경로도 동일하게 처리하는지 확인.
                    // start/destination은 GPXRouteSource 내부에서 무시되므로 더미 값 사용.
                    val dummy = LatLong(0.0, 0.0)
                    gpxRouteManager.calculateRoute(dummy, dummy) { result ->
                        if (result != null) {
                            android.util.Log.d(
                                "OppaNavi",
                                "GPX RouteManager: RouteResult points=${result.points.size}"
                            )
                        } else {
                            android.util.Log.w("OppaNavi", "GPX RouteManager: RouteResult null")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 로딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }



    private fun createBicycleBitmap(): android.graphics.drawable.BitmapDrawable {
        val size = 80
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val wheelPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1565C0")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val wheelFill = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val outlinePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(22f, 55f, 16f, wheelFill)
        canvas.drawCircle(22f, 55f, 16f, outlinePaint)
        canvas.drawCircle(22f, 55f, 16f, wheelPaint)
        canvas.drawCircle(58f, 55f, 16f, wheelFill)
        canvas.drawCircle(58f, 55f, 16f, outlinePaint)
        canvas.drawCircle(58f, 55f, 16f, wheelPaint)

        val framePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1565C0")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(22f, 55f, 40f, 28f, framePaint)
        canvas.drawLine(40f, 28f, 58f, 55f, framePaint)
        canvas.drawLine(40f, 28f, 40f, 42f, framePaint)
        canvas.drawLine(22f, 55f, 40f, 42f, framePaint)

        val arrowPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#FF6600")
            style = Paint.Style.FILL
        }
        val path = Path()
        path.moveTo(40f, 6f)
        path.lineTo(30f, 20f)
        path.lineTo(50f, 20f)
        path.close()
        canvas.drawPath(path, arrowPaint)

        val arrowOutline = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawPath(path, arrowOutline)

        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun drawMyLocation(latLong: LatLong) {
        locationMarker?.let { mapView.layerManager.layers.remove(it) }
        val drawable = createBicycleBitmap()
        val bitmap = AndroidGraphicFactory.convertToBitmap(drawable)
        locationMarker = Marker(latLong, bitmap, 0, 0)
        mapView.layerManager.layers.add(locationMarker!!)
    }

override fun onResume() {
    super.onResume()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    }
    if (androidx.core.app.ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        appLocationManager.startUpdates()
    }
    if (::cameraModule.isInitialized) cameraModule.onAppResume()
}

override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
    appLocationManager.stopUpdates()
}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val rawBearing = (azimuth + 360) % 360

            val diff = abs(rawBearing - smoothedBearing)
            val normalizedDiff = if (diff > 180) 360 - diff else diff
            if (normalizedDiff > 5f) {
                val alpha = 0.2f
                smoothedBearing = smoothedBearing + alpha * normalizedDiff *
                        if (diff > 180) -1 else if (rawBearing > smoothedBearing) 1 else -1
                smoothedBearing = (smoothedBearing + 360) % 360
                currentBearing = smoothedBearing
                lastLatLong?.let { mapManager.drawMyLocation(it, currentBearing) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

private fun updateFollowModeUI() {
    if (mapManager.isFollowMode) {
            btnMyLocation.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#2F80ED")
                )
            tvFollowMode.text = "추적 ON"
            tvFollowMode.setTextColor(android.graphics.Color.parseColor("#2F80ED"))
        } else {
            btnMyLocation.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#888888")
                )
            tvFollowMode.text = "추적 OFF"
            tvFollowMode.setTextColor(android.graphics.Color.parseColor("#888888"))
        }
    }

    private fun moveToCurrentLocation() {
        val latLong = appLocationManager.getLastKnownLocation()
        if (latLong != null) {
            lastLatLong = latLong
            mapView.setCenter(latLong)
            mapManager.drawMyLocation(latLong, currentBearing)
        } else {
            mapView.setCenter(LatLong(37.5665, 126.9780))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            appLocationManager.startUpdates()
            moveToCurrentLocation()
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        }
    }

    private fun copyMapFromAssets(fileName: String): File {
        val outFile = File(filesDir, fileName)
        if (!outFile.exists()) {
            assets.open(fileName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }
    private var backPressedTime = 0L
    private var isFinalizingRide = false

    init {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFinalizingRide) {
                        Toast.makeText(this@MainActivity, "영상 저장 중...", Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "한 번 더 누르면 종료됩니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
    private fun startEventBlink() {
        stopEventBlink()
        val borderPx = (3 * resources.displayMetrics.density).toInt()
        cameraContainer.setPadding(borderPx, borderPx, borderPx, borderPx)
        cameraContainer.setBackgroundColor(android.graphics.Color.RED)
        eventBlinkOn = true
        eventBlinkRunnable = object : Runnable {
            override fun run() {
                eventBlinkOn = !eventBlinkOn
                tvCameraState.text = "⚠ EVENT"
                tvCameraState.alpha = if (eventBlinkOn) 1f else 0f
                tvCameraState.setTextColor(android.graphics.Color.RED)
                eventBlinkHandler.postDelayed(this, 500L)
            }
        }
        eventBlinkHandler.post(eventBlinkRunnable!!)
    }

    private fun stopEventBlink() {
        eventBlinkRunnable?.let { eventBlinkHandler.removeCallbacks(it) }
        eventBlinkRunnable = null
        if (::cameraContainer.isInitialized) {
            cameraContainer.setPadding(0, 0, 0, 0)
            cameraContainer.setBackgroundColor(android.graphics.Color.BLACK)
        }
        if (::tvCameraState.isInitialized) {
            tvCameraState.alpha = 1f
        }
    }

    // 볼륨Up 2초 내 3연타 → MANUAL_VOLUME 이벤트 (볼륨 정상 조절 유지)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
            if (now - volumeUpFirstPressTime > VOLUME_UP_WINDOW_MS) {
                volumeUpPressCount = 1
                volumeUpFirstPressTime = now
            } else {
                volumeUpPressCount++
            }
            if (volumeUpPressCount >= VOLUME_UP_TRIGGER_COUNT) {
                volumeUpPressCount = 0
                if (::cameraModule.isInitialized) {
                    cameraModule.triggerEvent(CameraModule.EventType.MANUAL_VOLUME)
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::routeManager.isInitialized) routeManager.release()
        if (::cameraModule.isInitialized) cameraModule.release()
        timerHandler.removeCallbacks(timerRunnable)
        offRouteHandler.removeCallbacks(offRouteRunnable)
        pipLongPressHandler.removeCallbacksAndMessages(null)
        eventBlinkHandler.removeCallbacksAndMessages(null)
        // 주행 중 비정상 종료시 로그 자동 저장
        if (rideLogger.isActive) {
            rideLogger.stopLogging()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
        milestoneHandler.removeCallbacksAndMessages(null)
    }
    // ========== GraphHopper 경로 탐색 모듈 ==========

    private fun initGraphHopper() {
        android.util.Log.d("OppaNavi", "GH: initGraphHopper() called via RouteManager")
        routeManager.initialize(
            onReady = {
                android.util.Log.d("OppaNavi", "GH: RouteManager READY")
                // 테스트 route — 서울시청 → 광화문
                calculateAndDrawTestRoute()
            },
            onError = { message ->
                android.util.Log.e("OppaNavi", "GH: GH_INIT_ERROR -- $message")
                Toast.makeText(
                    this,
                    "경로 엔진 초기화 실패: ${message ?: "알 수 없는 오류"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    private fun calculateAndDrawTestRoute() {
        val testFrom = LatLong(37.5665, 126.9780)
        val testTo = LatLong(37.5760, 126.9769)

        routeManager.calculateRoute(testFrom, testTo) { result ->
            if (result != null) {
                android.util.Log.d("OppaNavi", "GH: TEST route points=${result.points.size}")
                mapManager.drawRoute(result.points)
            } else {
                Toast.makeText(this, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun handleMapLongPress(destination: LatLong) {
        val start = lastLatLong
        if (start == null) {
            Toast.makeText(this, "현재 위치를 아직 확인할 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        if (!routeManager.isReady) {
            Toast.makeText(this, "경로 엔진이 아직 준비되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "목적지 설정! 경로 탐색 중...", Toast.LENGTH_SHORT).show()

        routeManager.calculateRoute(start, destination) { result ->
            if (result != null) {
                mapManager.drawRoute(result.points)
                android.util.Log.d(
                    "OppaNavi",
                    "ROUTE: distance=${result.distanceMeters}m time=${result.estimatedTimeSec}sec"
                )
                navigationEngine.setRoute(result)
                val distanceKm = (result.distanceMeters ?: 0.0) / 1000.0
                val timeMin = (result.estimatedTimeSec ?: 0L) / 60
                Toast.makeText(
                    this,
                    "거리: %.1fkm / 예상: %d분".format(distanceKm, timeMin),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== ON 블랙박스 모듈 — 제거 시 이 블록 삭제 ==========

    private fun initCameraModule() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        setupCamera()
    }

    private fun hasCameraPermission(): Boolean {
        return androidx.core.app.ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            ),
            REQUEST_CAMERA_PERMISSION
        )
    }

    private fun setupCamera() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreview)
        cameraContainer = findViewById(R.id.cameraContainer)
        tvCameraState = findViewById(R.id.tvCameraState)

        cameraModule = CameraModule(this, this)

        cameraModule.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    CameraModule.CameraState.READY -> {
                        stopEventBlink()
                        cameraContainer.visibility = View.VISIBLE
                        tvCameraState.text = "● REC"
                        tvCameraState.setTextColor(android.graphics.Color.WHITE)
                    }
                    CameraModule.CameraState.RECORDING -> {
                        stopEventBlink()
                        cameraContainer.visibility = View.VISIBLE
                        tvCameraState.text = "● REC"
                        tvCameraState.setTextColor(android.graphics.Color.parseColor("#FF3333"))
                    }
                    CameraModule.CameraState.EVENT_SAVING -> {
                        startEventBlink()
                    }
                    CameraModule.CameraState.ERROR -> {
                        stopEventBlink()
                        cameraContainer.visibility = View.GONE
                        android.util.Log.e("ON_Main", "카메라 오류 — 네비 계속 동작")
                    }
                    else -> {}
                }
            }
        }

        cameraModule.onEventTriggered = { eventType ->
            android.util.Log.d("ON_Main", "이벤트: $eventType")
            if (rideLogger.isActive) {
                rideLogger.logEvent(eventType.name, rideManager.getElapsedSec())
            }
        }

        cameraModule.onEventIgnored = { eventType ->
            android.util.Log.d("ON_Main", "이벤트 중복 무시: $eventType (ALREADY_SAVING)")
        }

        cameraModule.onEventSaveEnd = { ignoreCount ->
            android.util.Log.d("ON_Main", "EVENT_SAVE_END ignoreCount=$ignoreCount")
            if (rideLogger.isActive) {
                rideLogger.logEvent("EVENT_SAVE_END|IGNORE_COUNT:$ignoreCount", rideManager.getElapsedSec())
            }
        }

        cameraModule.initialize(previewView)
        rideLogger.setCameraModule(cameraModule)

        // PIP 터치: 짧은 탭 → 전체화면 전환 / 2초 길게 누르기 → MANUAL_TAP 이벤트
        previewView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pipLongPressTriggered = false
                    pipLongPressHandler.postDelayed({
                        pipLongPressTriggered = true
                        if (::cameraModule.isInitialized) {
                            cameraModule.triggerEvent(CameraModule.EventType.MANUAL_TAP)
                        }
                    }, PIP_LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pipLongPressHandler.removeCallbacksAndMessages(null)
                    if (!pipLongPressTriggered) {
                        isCameraExpanded = !isCameraExpanded
                        val params = cameraContainer.layoutParams
                        if (isCameraExpanded) {
                            params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        } else {
                            params.width = (160 * resources.displayMetrics.density).toInt()
                            params.height = (120 * resources.displayMetrics.density).toInt()
                        }
                        cameraContainer.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pipLongPressHandler.removeCallbacksAndMessages(null)
                    pipLongPressTriggered = false
                    true
                }
                else -> false
            }
        }
    }


    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 1002
    }

    // ========== 블랙박스 모듈 끝 ==========
}
