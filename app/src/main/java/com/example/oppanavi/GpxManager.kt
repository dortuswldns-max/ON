package com.example.oppanavi

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import java.io.InputStream

class GpxManager(
    private val context: Context,
    private val mapView: MapView
) {
    private val engine = GpxEngine(context)

    // 세그먼트 기반 레이어
    private val gpxLayerOutlines = mutableListOf<Polyline>()
    private val gpxLayerRemains = mutableListOf<Polyline>()
    private val gpxLayerDones = mutableListOf<Polyline>()

    val hasRoute get() = engine.points.isNotEmpty()

    /** GPXRouteSource가 동일한 GpxEngine 상태를 참조하기 위한 read-only 접근. */
    val gpxEngine: GpxEngine get() = engine

    // GPX 불러오기
    fun load(inputStream: InputStream): Boolean {
        val ok = engine.load(inputStream)
        if (ok) {
            drawRouteSegmented()
            autoZoom()
        }
        return ok
    }

    // 진행률 업데이트 — LocationManager에서 위치 받아서 호출
    fun updateProgress(latLong: LatLong): GpxEngine.ProgressInfo {
        val info = engine.updateProgress(latLong)
        updateColorsSegmented(info.nearestIdx)
        return info
    }

    // 경로 전체 초기화
    fun clear() {
        clearLayers()
        engine.reset()
    }

    fun getPointCount() = engine.points.size
    fun getProjectedPoint(latLong: LatLong): LatLong {
        return engine.getProjectedPoint(latLong, engine.nearestIndex)
    }

    fun getLastDistToRoute(): Double {
        return engine.lastDistToRoute
    }
    // ── 내부 함수들 ──────────────────────────────────────

    private fun clearLayers() {
        gpxLayerOutlines.forEach { mapView.layerManager.layers.remove(it) }
        gpxLayerRemains.forEach { mapView.layerManager.layers.remove(it) }
        gpxLayerDones.forEach { mapView.layerManager.layers.remove(it) }
        gpxLayerOutlines.clear()
        gpxLayerRemains.clear()
        gpxLayerDones.clear()
    }

    private fun drawRouteSegmented() {
        clearLayers()
        if (engine.segments.isEmpty()) return

        for (segment in engine.segments) {
            val donePaint = AndroidGraphicFactory.INSTANCE.createPaint()
            donePaint.color = AndroidGraphicFactory.INSTANCE.createColor(180, 150, 150, 150)
            donePaint.strokeWidth = 12f
            donePaint.setStyle(org.mapsforge.core.graphics.Style.STROKE)
            val doneLayer = Polyline(donePaint, AndroidGraphicFactory.INSTANCE)
            gpxLayerDones.add(doneLayer)
            mapView.layerManager.layers.add(doneLayer)

            val outlinePaint = AndroidGraphicFactory.INSTANCE.createPaint()
            outlinePaint.color = AndroidGraphicFactory.INSTANCE.createColor(200, 255, 255, 255)
            outlinePaint.strokeWidth = 20f
            outlinePaint.setStyle(org.mapsforge.core.graphics.Style.STROKE)
            val outlineLayer = Polyline(outlinePaint, AndroidGraphicFactory.INSTANCE)
            outlineLayer.latLongs.addAll(segment)
            gpxLayerOutlines.add(outlineLayer)
            mapView.layerManager.layers.add(outlineLayer)

            val remainPaint = AndroidGraphicFactory.INSTANCE.createPaint()
            remainPaint.color = AndroidGraphicFactory.INSTANCE.createColor(220, 220, 50, 50)
            remainPaint.strokeWidth = 12f
            remainPaint.setStyle(org.mapsforge.core.graphics.Style.STROKE)
            val remainLayer = Polyline(remainPaint, AndroidGraphicFactory.INSTANCE)
            remainLayer.latLongs.addAll(segment)
            gpxLayerRemains.add(remainLayer)
            mapView.layerManager.layers.add(remainLayer)
        }

        mapView.layerManager.redrawLayers()
    }

    private fun updateColorsSegmented(nearestIdx: Int) {
        var globalStart = 0
        for (segmentIndex in engine.segments.indices) {
            val segment = engine.segments[segmentIndex]
            val doneLayer = gpxLayerDones.getOrNull(segmentIndex) ?: continue
            val remainLayer = gpxLayerRemains.getOrNull(segmentIndex) ?: continue
            val globalEnd = globalStart + segment.size - 1

            doneLayer.latLongs.clear()
            remainLayer.latLongs.clear()

            when {
                nearestIdx < globalStart -> remainLayer.latLongs.addAll(segment)
                nearestIdx >= globalEnd -> doneLayer.latLongs.addAll(segment)
                else -> {
                    val localIdx = nearestIdx - globalStart
                    doneLayer.latLongs.addAll(segment.subList(0, localIdx + 1))
                    remainLayer.latLongs.addAll(segment.subList(localIdx, segment.size))
                }
            }
            globalStart += segment.size
        }
        mapView.layerManager.redrawLayers()
    }

    private fun autoZoom() {
        val bbox = engine.getBoundingBox() ?: return
        val centerLat = (bbox.minLat + bbox.maxLat) / 2
        val centerLon = (bbox.minLon + bbox.maxLon) / 2
        mapView.setCenter(LatLong(centerLat, centerLon))

        val span = maxOf(bbox.maxLat - bbox.minLat, bbox.maxLon - bbox.minLon)
        val zoom: Byte = when {
            span > 1.0 -> 9
            span > 0.5 -> 10
            span > 0.2 -> 11
            span > 0.1 -> 12
            span > 0.05 -> 13
            span > 0.02 -> 14
            else -> 15
        }
        mapView.setZoomLevel(zoom)
    }
}