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

    val isFollowMode get() = followMode

    fun setupMap(onTouchDisableFollow: () -> Unit) {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true

        mapView.setOnTouchListener { _, _ ->
            if (followMode) {
                followMode = false
                onTouchDisableFollow()
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

    fun moveToLocation(latLong: LatLong, zoom: Byte? = null) {
        mapView.setCenter(latLong)
        zoom?.let { mapView.setZoomLevel(it) }
    }

    fun drawMyLocation(latLong: LatLong, bearing: Float) {
        locationMarker?.let { mapView.layerManager.layers.remove(it) }
        val drawable = createBicycleBitmap(bearing)
        val bitmap = AndroidGraphicFactory.convertToBitmap(drawable)
        locationMarker = Marker(latLong, bitmap, 0, 0)
        mapView.layerManager.layers.add(locationMarker!!)
    }

    private fun createBicycleBitmap(bearing: Float): android.graphics.drawable.BitmapDrawable {
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