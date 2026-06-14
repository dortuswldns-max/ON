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

class MapManager(
    private val context: Context,
    private val mapView: MapView
) {
    private var locationMarker: Marker? = null
    private var followMode = true
    private var snapCount = 0
    private var releaseCount = 0
    private val SNAP_THRESHOLD = 3
    private val SNAP_DIST_M = 20.0
    private val RELEASE_THRESHOLD = 3
    private var isSnapped = false
    val isFollowMode get() = followMode

    fun setupMap(onTouchDisableFollow: () -> Unit) {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true

        mapView.setOnTouchListener { _, event ->
            if (followMode) {
                // 핀치줌(두 손가락)은 추적 유지, 단순 터치만 추적 OFF
                if (event.pointerCount == 1 &&
                    event.action == android.view.MotionEvent.ACTION_DOWN) {
                    followMode = false
                    onTouchDisableFollow()
                }
            }
            false
        }

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
}