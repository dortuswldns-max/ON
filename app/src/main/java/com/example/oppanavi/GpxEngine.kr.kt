package com.example.oppanavi

import android.content.Context
import android.widget.Toast
import org.mapsforge.core.model.LatLong
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class GpxEngine(private val context: Context) {

    val points = mutableListOf<LatLong>()
    val segments = mutableListOf<MutableList<LatLong>>()
    private val pointDistances = mutableListOf<Double>()
    var totalDistance = 0.0
    var nearestIndex = 0

    val OFF_ROUTE_THRESHOLD = 100.0
    private val OFF_ROUTE_COUNT_THRESHOLD = 5
    private var offRouteCount = 0
    private var reconnectCount = 0
    private val RECONNECT_THRESHOLD = 4  // 4회 연속 50m 이내 → 재스냅

    // 보간 간격 (5m마다 포인트 생성)
    private val INTERPOLATE_INTERVAL = 5.0

    fun load(inputStream: InputStream): Boolean {
        points.clear()
        segments.clear()
        pointDistances.clear()
        totalDistance = 0.0
        nearestIndex = 0
        offRouteCount = 0

        return try {
            val factory = SAXParserFactory.newInstance()
            val parser = factory.newSAXParser()
            val waypointFallback = mutableListOf<LatLong>()
            val handler = object : DefaultHandler() {
                private var currentTrackSegment: MutableList<LatLong>? = null
                private var currentRouteSegment: MutableList<LatLong>? = null

                override fun startElement(
                    uri: String, localName: String, qName: String, attributes: Attributes
                ) {
                    when (qName) {
                        "trkseg" -> {
                            currentTrackSegment = mutableListOf<LatLong>().also {
                                segments.add(it)
                            }
                        }
                        "trkpt" -> {
                            readLatLong(attributes)?.let { point ->
                                val segment = currentTrackSegment
                                    ?: mutableListOf<LatLong>().also {
                                        currentTrackSegment = it
                                        segments.add(it)
                                    }
                                segment.add(point)
                            }
                        }
                        "rtept" -> {
                            readLatLong(attributes)?.let { point ->
                                val segment = currentRouteSegment
                                    ?: mutableListOf<LatLong>().also {
                                        currentRouteSegment = it
                                        segments.add(it)
                                    }
                                segment.add(point)
                            }
                        }
                        "wpt" -> {
                            readLatLong(attributes)?.let { waypointFallback.add(it) }
                        }
                    }
                }

                override fun endElement(uri: String, localName: String, qName: String) {
                    if (qName == "trkseg") currentTrackSegment = null
                }
            }
            parser.parse(inputStream, handler)

            segments.removeAll { it.size < 2 }
            if (segments.isEmpty() && waypointFallback.size >= 2) {
                segments.add(waypointFallback)
            }

            // 보간 적용 — segments 자체를 촘촘하게 만들기
            interpolateSegments()
            rebuildFlatPoints()

            if (points.size < 2) {
                Toast.makeText(context, "GPX 포인트 부족", Toast.LENGTH_SHORT).show()
                return false
            }
            true
        } catch (e: Exception) {
            Toast.makeText(context, "GPX 파싱 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // segments 자체를 5m 간격으로 보간
    private fun interpolateSegments() {
        for (segIdx in segments.indices) {
            val original = segments[segIdx].toList()
            val interpolated = mutableListOf<LatLong>()

            for (i in original.indices) {
                interpolated.add(original[i])
                if (i < original.size - 1) {
                    val a = original[i]
                    val b = original[i + 1]
                    val dist = haversine(a, b)
                    if (dist > INTERPOLATE_INTERVAL) {
                        val steps = (dist / INTERPOLATE_INTERVAL).toInt()
                        for (step in 1 until steps) {
                            val ratio = step.toDouble() / steps
                            val lat = a.latitude + (b.latitude - a.latitude) * ratio
                            val lon = a.longitude + (b.longitude - a.longitude) * ratio
                            interpolated.add(LatLong(lat, lon))
                        }
                    }
                }
            }
            segments[segIdx] = interpolated.toMutableList()
        }
    }

    fun reset() {
        points.clear()
        segments.clear()
        pointDistances.clear()
        totalDistance = 0.0
        nearestIndex = 0
        offRouteCount = 0
        reconnectCount = 0
    }

    data class ProgressInfo(
        val progressPct: Int,
        val remainKm: String,
        val progressKm: String,
        val nearestIdx: Int,
        val distToRoute: Double,
        val isOffRoute: Boolean
    )

    fun updateProgress(current: LatLong): ProgressInfo {
        if (points.isEmpty()) return ProgressInfo(0, "0.0", "0.0", 0, 0.0, false)

        var minDist = Double.MAX_VALUE
        var nearestIdx = nearestIndex

        for (i in points.indices) {
            val d = haversine(current, points[i])
            if (d < minDist) {
                minDist = d
                nearestIdx = i
            }
        }

        // 이탈 중 재합류 감지 — 4회 연속 50m 이내면 재스냅
        if (offRouteCount >= OFF_ROUTE_COUNT_THRESHOLD) {
            if (minDist < 50.0) {
                reconnectCount++
                if (reconnectCount >= RECONNECT_THRESHOLD) {
                    // 이미 위에서 전체탐색 완료 — nearestIdx 그대로 사용
                    reconnectCount = 0
                    offRouteCount = 0
                }
            } else {
                reconnectCount = 0
            }
        }

        nearestIndex = nearestIdx

        if (minDist > OFF_ROUTE_THRESHOLD) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        val isOffRoute = offRouteCount >= OFF_ROUTE_COUNT_THRESHOLD

        val progressDist = pointDistances.getOrElse(nearestIdx) { 0.0 }
        val pct = if (totalDistance > 0) {
            ((progressDist / totalDistance) * 100).roundToInt().coerceIn(0, 100)
        } else 0

        val remainKm = "%.1f".format(maxOf(0.0, totalDistance - progressDist) / 1000.0)
        val progressKm = "%.1f".format(progressDist / 1000.0)

        return ProgressInfo(pct, remainKm, progressKm, nearestIdx, minDist, isOffRoute)
    }

    fun haversine(a: LatLong, b: LatLong): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }

    data class BoundingBox(
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double
    )

    fun getBoundingBox(): BoundingBox? {
        if (points.isEmpty()) return null
        var minLat = points[0].latitude
        var maxLat = points[0].latitude
        var minLon = points[0].longitude
        var maxLon = points[0].longitude
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return BoundingBox(minLat, maxLat, minLon, maxLon)
    }

    private fun readLatLong(attributes: Attributes): LatLong? {
        val lat = attributes.getValue("lat")?.toDoubleOrNull()
        val lon = attributes.getValue("lon")?.toDoubleOrNull()
        return if (lat != null && lon != null) LatLong(lat, lon) else null
    }

    private fun rebuildFlatPoints() {
        points.clear()
        pointDistances.clear()
        totalDistance = 0.0

        for (segment in segments) {
            for (i in segment.indices) {
                if (i > 0) {
                    totalDistance += haversine(segment[i - 1], segment[i])
                }
                points.add(segment[i])
                pointDistances.add(totalDistance)
            }
        }
    }
}