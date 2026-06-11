package com.example.oppanavi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val USER_WEIGHT_KG = 67.0

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

    private var locationMarker: Marker? = null


    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private lateinit var gpxManager: GpxManager

    private var followMode = true
    private var lastLatLong: LatLong? = null
    private var currentBearing = 0f
    private var smoothedBearing = 0f
    private var isRideStarted = false

    private var isPaused = false
    private var rideStartTime = 0L
    private var pausedTime = 0L
    private var pauseStartTime = 0L
    private var totalDistanceM = 0.0
    private var lastRecordedLatLong: LatLong? = null
    private var currentSpeedKmh = 0
    private var highSpeedCount = 0

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRideStarted && !isPaused) updateRideTime()
            timerHandler.postDelayed(this, 1000)
        }
    }

      private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val latLong = LatLong(location.latitude, location.longitude)
            lastLatLong = latLong

            if (location.hasBearing()) currentBearing = location.bearing

            drawMyLocation(latLong)
            if (followMode) mapView.setCenter(latLong)

            currentSpeedKmh = if (location.hasSpeed()) {
                (location.speed * 3.6f).roundToInt()
            } else 0
            tvSpeed.text = currentSpeedKmh.toString()

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

            if (isRideStarted && !isPaused) {
                lastRecordedLatLong?.let { prev ->
                    val R = 6371000.0
                    val lat1 = Math.toRadians(prev.latitude)
                    val lat2 = Math.toRadians(latLong.latitude)
                    val dLat = Math.toRadians(latLong.latitude - prev.latitude)
                    val dLon = Math.toRadians(latLong.longitude - prev.longitude)
                    val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                            Math.cos(lat1) * Math.cos(lat2) *
                            Math.sin(dLon/2) * Math.sin(dLon/2)
                    val dist = R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
                    if (dist < 50) totalDistanceM += dist
                }
                lastRecordedLatLong = latLong
                tvTotalDist.text = "%.1fkm".format(totalDistanceM / 1000.0)
                updateAvgSpeed()
            }

            if (isRideStarted && isPaused && currentSpeedKmh > 6) {
                highSpeedCount++
                if (highSpeedCount >= 3) resumeRide()
            } else if (currentSpeedKmh <= 6) {
                highSpeedCount = 0
            }

            if (gpxManager.hasRoute) {
                val info = gpxManager.updateProgress(latLong)
                tvGpxProgress.text = "${info.progressPct}%"
                tvGpxRemain.text = "남은 ${info.remainKm}km"
                tvOffRoute.visibility = if (info.isOffRoute) View.VISIBLE else View.GONE
            }
        }

        override fun onProviderDisabled(provider: String) {
            tvGpsStatus.text = "GPS ○"
            tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
            Toast.makeText(this@MainActivity, "GPS가 꺼져있습니다.", Toast.LENGTH_LONG).show()
        }

        override fun onProviderEnabled(provider: String) {
            Toast.makeText(this@MainActivity, "GPS 연결됨", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        bindViews()
        setupMap()
        gpxManager = GpxManager(this, mapView)
        setupButtons()
        timerHandler.post(timerRunnable)

        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
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

    private fun setupMap() {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true

        mapView.setOnTouchListener { _, _ ->
            if (followMode) {
                followMode = false
                updateFollowModeUI()
            }
            false
        }

        val mapFile = copyMapFromAssets("south_korea.map")
        val tileCache = AndroidUtil.createTileCache(
            this, "mapcache",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor
        )
        val mapDataStore: MapDataStore = MapFile(mapFile)
        val tileRendererLayer = TileRendererLayer(
            tileCache, mapDataStore,
            mapView.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        )
        tileRendererLayer.setXmlRenderTheme(
            StreamRenderTheme("", assets.open("default.xml"))
        )
        mapView.layerManager.layers.add(tileRendererLayer)
        mapView.setZoomLevel(15.toByte())
    }

    private fun setupButtons() {
        updateFollowModeUI()

        btnStartRide.setOnClickListener { startRide() }

        btnMyLocation.setOnClickListener {
            followMode = true
            updateFollowModeUI()
            lastLatLong?.let {
                mapView.setCenter(it)
                mapView.setZoomLevel(17.toByte())
            } ?: moveToCurrentLocation()
        }

        btnLoadGpx.setOnClickListener {
            gpxLauncher.launch("*/*")
        }

        btnPause.setOnClickListener {
            if (isPaused) resumeRide() else pauseRide()
        }

        btnFinish.setOnClickListener { showFinishDialog() }

        btnClearGpx.setOnClickListener { clearGpxRoute() }
    }

    private fun startRide() {
        isRideStarted = true
        rideStartTime = System.currentTimeMillis()
        pausedTime = 0L
        totalDistanceM = 0.0
        lastRecordedLatLong = null
        tvTotalDist.text = "0.0km"
        tvRideTime.text = "00:00"
        tvAvgSpeed.text = "0.0avg"

        layoutStartOverlay.visibility = View.GONE
        btnMyLocation.visibility = View.VISIBLE
        btnLoadGpx.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        btnFinish.visibility = View.VISIBLE

        Toast.makeText(this, "주행 시작! 🚴", Toast.LENGTH_SHORT).show()
    }

    private fun pauseRide() {
        isPaused = true
        pauseStartTime = System.currentTimeMillis()
        highSpeedCount = 0
        tvPauseStatus.text = "⏸ 일시정지"
        btnPause.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun resumeRide() {
        isPaused = false
        pausedTime += System.currentTimeMillis() - pauseStartTime
        highSpeedCount = 0
        tvPauseStatus.text = ""
        btnPause.setImageResource(android.R.drawable.ic_media_pause)
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

    private fun updateRideTime() {
        val elapsed = System.currentTimeMillis() - rideStartTime - pausedTime
        val seconds = (elapsed / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        tvRideTime.text = if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
        } else {
            "%02d:%02d".format(minutes, seconds % 60)
        }
    }

    private fun updateAvgSpeed() {
        val elapsed = System.currentTimeMillis() - rideStartTime - pausedTime
        if (elapsed > 0 && totalDistanceM > 0) {
            val avg = (totalDistanceM / 1000.0) / (elapsed / 3600000.0)
            tvAvgSpeed.text = "%.1favg".format(avg)
        }
    }

    private fun showFinishDialog() {
        val elapsed = System.currentTimeMillis() - rideStartTime - pausedTime
        val seconds = (elapsed / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        val timeStr = if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
        } else {
            "%02d:%02d".format(minutes, seconds % 60)
        }

        val distKm = totalDistanceM / 1000.0
        val avgSpeed = if (elapsed > 0) distKm / (elapsed / 3600000.0) else 0.0
        val kcal = (distKm * USER_WEIGHT_KG * 0.7).roundToInt()

        val summary = "거리: ${"%.1f".format(distKm)}km  시간: $timeStr  평균속도: ${"%.1f".format(avgSpeed)}km/h  칼로리: ${kcal}kcal"

        AlertDialog.Builder(this)
            .setTitle("🚴 주행 종료")
            .setMessage(summary)
            .setPositiveButton("클립보드 복사") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ride_summary", summary))
                Toast.makeText(this, "복사 완료!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("계속 라이딩") { dialog, _ -> dialog.dismiss() }
            .show()
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
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)
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
                lastLatLong?.let { drawMyLocation(it) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateFollowModeUI() {
        if (followMode) {
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

    private fun startLocationUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener
                )
            }
            moveToCurrentLocation()
        } catch (e: SecurityException) {
            mapView.setCenter(LatLong(37.5665, 126.9780))
        }
    }

    private fun moveToCurrentLocation() {
        try {
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (last != null) {
                val latLong = LatLong(last.latitude, last.longitude)
                lastLatLong = latLong
                mapView.setCenter(latLong)
                drawMyLocation(latLong)
            } else {
                mapView.setCenter(LatLong(37.5665, 126.9780))
            }
        } catch (e: SecurityException) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
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

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }
}
