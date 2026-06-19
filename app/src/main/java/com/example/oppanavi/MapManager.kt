package com.example.oppanavi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.StreamRenderTheme
import java.io.File
import java.io.FileOutputStream
import org.mapsforge.core.graphics.Style
import org.mapsforge.map.layer.overlay.Polyline

class MapManager(
    private val context: Context,
    private val mapView: MapView,
    private val debugLogger: DebugLogger
) {
    private var locationMarker: Marker? = null
    private var routeLayer: Polyline? = null
    private var followMode = true
    private var snapCount = 0
    private var releaseCount = 0
    private val SNAP_THRESHOLD = 3
    private val SNAP_DIST_M = 20.0
    private val RELEASE_THRESHOLD = 3
    private var isSnapped = false
    val isFollowMode get() = followMode

    fun setupMap(onTouchDisableFollow: () -> Unit, onLongPress: (LatLong) -> Unit) {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true
        setupMapTouchListener(onTouchDisableFollow, onLongPress)

        val mapFile = copyMapFromAssets("south_korea.map")
        val tileCache = AndroidUtil.createTileCache(
            context, "mapcache",
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
            StreamRenderTheme("", context.assets.open("default.xml"))
        )
        mapView.layerManager.layers.add(tileRendererLayer)
        mapView.setZoomLevel(15.toByte())
    }

    fun enableFollow() {
        followMode = true
    }

    fun disableFollow() {
        followMode = false
    }

    // 줌만 변경 (추적 유지)
    fun zoomIn() {
        val current = mapView.model.mapViewPosition.zoomLevel
        mapView.setZoomLevel((current + 1).coerceAtMost(20).toByte())
    }

    fun zoomOut() {
        val current = mapView.model.mapViewPosition.zoomLevel
        mapView.setZoomLevel((current - 1).coerceAtLeast(1).toByte())
    }

    fun moveToLocation(latLong: LatLong, zoom: Byte? = null) {
        mapView.setCenter(latLong)
        zoom?.let { mapView.setZoomLevel(it) }
    }

    fun drawMyLocation(latLong: LatLong, bearing: Float,
                       distToRoute: Double = Double.MAX_VALUE,
                       projectedPoint: LatLong? = null) {
        locationMarker?.let { mapView.layerManager.layers.remove(it) }

        val displayPoint = if (projectedPoint != null) {
            if (distToRoute <= SNAP_DIST_M) {
                snapCount++
                releaseCount = 0
                if (snapCount >= SNAP_THRESHOLD) isSnapped = true
            } else {
                releaseCount++
                snapCount = 0
                if (releaseCount >= RELEASE_THRESHOLD) isSnapped = false
            }
            if (isSnapped) projectedPoint else latLong
        } else {
            isSnapped = false
            snapCount = 0
            releaseCount = 0
            latLong
        }

        val drawable = createBicycleBitmap(bearing)
        val bitmap = AndroidGraphicFactory.convertToBitmap(drawable)
        locationMarker = Marker(displayPoint, bitmap, 0, 0)
        mapView.layerManager.layers.add(locationMarker!!)
    }

    fun drawRoute(points: List<LatLong>) {
        routeLayer?.let { mapView.layerManager.layers.remove(it) }

        if (points.size < 2) {
            routeLayer = null
            return
        }
        debugLogger.mapDrawRoute(points.size)

        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.STROKE)
            setColor(android.graphics.Color.parseColor("#FF0000"))
            setStrokeWidth(10f)
        }

        val polyline = Polyline(paintStroke, AndroidGraphicFactory.INSTANCE)
        polyline.getLatLongs().addAll(points)

        mapView.layerManager.layers.add(polyline)
        routeLayer = polyline
    }

    fun clearRouteLayer() {
        debugLogger.mapClearRoute()
        routeLayer?.let { mapView.layerManager.layers.remove(it) }
        routeLayer = null
    }

    private fun createBicycleBitmap(bearing: Float): android.graphics.drawable.BitmapDrawable {
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val wheelPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1565C0")
            style = Paint.Style.STROKE
            strokeWidth = 8f
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
            strokeWidth = 12f
        }
        canvas.drawCircle(44f, 110f, 32f, wheelFill)
        canvas.drawCircle(44f, 110f, 32f, outlinePaint)
        canvas.drawCircle(44f, 110f, 32f, wheelPaint)
        canvas.drawCircle(116f, 110f, 32f, wheelFill)
        canvas.drawCircle(116f, 110f, 32f, outlinePaint)
        canvas.drawCircle(116f, 110f, 32f, wheelPaint)

        val framePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1565C0")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(44f, 110f, 80f, 56f, framePaint)
        canvas.drawLine(80f, 56f, 116f, 110f, framePaint)
        canvas.drawLine(80f, 56f, 80f, 84f, framePaint)
        canvas.drawLine(44f, 110f, 80f, 84f, framePaint)

        val arrowPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#FF6600")
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.rotate(bearing, 80f, 80f)

        val path = Path()
        path.moveTo(80f, 12f)
        path.lineTo(60f, 40f)
        path.lineTo(100f, 40f)
        path.close()
        canvas.drawPath(path, arrowPaint)

        val arrowOutline = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawPath(path, arrowOutline)
        canvas.restore()

        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }

    private fun copyMapFromAssets(fileName: String): File {
        val outFile = File(context.filesDir, fileName)
        if (!outFile.exists()) {
            context.assets.open(fileName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }
    // ============================================================
    // 지도 Long Press → 목적지 선택
    // ============================================================
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_MS = 600L
    private val LONG_PRESS_MOVE_THRESHOLD_PX = 20f
    private var touchDownX = 0f
    private var touchDownY = 0f

    private fun setupMapTouchListener(
        onTouchDisableFollow: () -> Unit,
        onLongPress: (LatLong) -> Unit
    ) {
        mapView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (event.pointerCount == 1) {
                        if (followMode) {
                            followMode = false
                            onTouchDisableFollow()
                        }
                        touchDownX = event.x
                        touchDownY = event.y
                        val runnable = Runnable {
                            val projection = org.mapsforge.map.util.MapViewProjection(mapView)
                            val latLong = projection.fromPixels(touchDownX.toDouble(), touchDownY.toDouble())
                            latLong?.let { onLongPress(it) }
                        }
                        longPressRunnable = runnable
                        longPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                    }
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - touchDownX)
                    val dy = kotlin.math.abs(event.y - touchDownY)
                    if (dx > LONG_PRESS_MOVE_THRESHOLD_PX || dy > LONG_PRESS_MOVE_THRESHOLD_PX) {
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    false
                }
                else -> false
            }
        }
    }
}