package com.example.oppanavi

import org.mapsforge.core.model.LatLong

/**
 * ============================================================
 * NavigationEngine
 * ============================================================
 *
 * ON이 "현재 위치를 이해하기 시작하는" 첫 번째 계층.
 *
 * 책임:
 *   1. 현재 RouteResult 보관
 *   2. GPS 위치 입력 수신
 *   3. 가장 가까운 route point 검색
 *   4. 진행 위치(NavigationState) 반환
 *   5. 남은 거리 계산 (실제 경로 누적거리 기준, 직선 비율 추정 아님)
 *
 * 절대 원칙:
 *   - NavigationEngine은 GraphHopperModule을 모른다.
 *   - NavigationEngine은 MapManager를 모른다.
 *   - 오직 RouteResult만 입력받는다.
 *
 * 이번 단계(1단계) 범위:
 *   ✅ RouteResult 저장, GPS 위치 입력, nearest point 검색,
 *      진행 위치 반환, 남은 거리 계산
 *   ❌ 이탈 판단, 재탐색, 음성 안내, 방향 지시, ETA 보정
 *      (이건 NavigationEngine + RouteMonitor 단계에서 처리될 영역)
 *
 * GpxEngine.updateProgress()와 유사한 문제(가장 가까운 점 찾기)를 풀지만,
 * 로직을 그대로 복사하지 않고 독립적으로 구현한다.
 * GpxEngine 책임 = GPX 파일 진행률 표시(라이딩 기록 재생),
 * NavigationEngine 책임 = 모든 RouteResult 기반 실시간 위치 추적.
 * 두 책임은 의도적으로 분리되어 있다 — 나중에 재탐색을 넣을 때
 * 섞여있으면 다시 뜯어야 하기 때문.
 * ============================================================
 */

/**
 * 경로가 존재할 때의 유효한 진행 상태만 표현한다.
 * "경로 없음"은 이 데이터 클래스의 더미 인스턴스가 아니라,
 * NavigationEngine.updateLocation()의 null 반환으로 표현한다.
 *
 * 향후 상태 종류가 늘어나면(NoRoute, OffRoute, Recalculating, Arrived 등)
 * sealed class로 전환할 수 있다. 현재 단계에서는 과설계 방지를 위해
 * 단순 data class + nullable 반환으로 충분하다.
 */
data class NavigationState(
    val currentPosition: LatLong,
    val nearestRoutePoint: LatLong,
    val nearestIndex: Int,
    val distanceToRoute: Double,
    val progressPercent: Double,
    val remainingDistance: Double
)

class NavigationEngine {

    private var currentRoute: RouteResult? = null

    // 1차 구현은 전체 탐색 방식. 향후 "이전 nearestIndex 주변 탐색 우선,
    // 필요 시 전체 탐색 fallback"으로 최적화 가능 — 지금은 복잡한
    // 최적화를 하지 않는다 (route point가 수백~수천 개 수준이면 충분).
    private var nearestIndex = 0

    // route point 간 segment 거리의 누적합. setRoute() 시점에 미리 계산해서
    // remainingDistance 계산 시 매번 다시 더하지 않도록 한다.
    private var cumulativeDistances: List<Double> = emptyList()

    /**
     * 새 경로를 설정한다. RouteManager가 RouteResult를 넘기는 시점에
     * MainActivity가 이 함수를 호출한다 (MapManager.drawRoute()와 같은 자리).
     */
    fun setRoute(route: RouteResult) {
        currentRoute = route
        nearestIndex = 0
        cumulativeDistances = buildCumulativeDistances(route.points)
    }

    /**
     * 현재 GPS 위치를 입력받아 진행 상태를 계산한다.
     *
     * 경로가 없으면(setRoute 호출 전, 또는 경로 해제 후) null을 반환한다.
     * GPS는 항상 흐르지만, 경로가 없는 상태에서는 "아무것도 하지 않음"이
     * 정상 동작이다.
     */
    fun updateLocation(current: LatLong): NavigationState? {
        val route = currentRoute ?: return null
        val points = route.points
        if (points.isEmpty()) return null

        // 1차 구현: 전체 탐색
        var minDist = Double.MAX_VALUE
        var nearestIdx = 0
        for (i in points.indices) {
            val d = haversine(current, points[i])
            if (d < minDist) {
                minDist = d
                nearestIdx = i
            }
        }
        nearestIndex = nearestIdx

        val remaining = remainingDistanceFrom(nearestIdx)
        val totalDistance = cumulativeDistances.lastOrNull() ?: 0.0
        val progressPercent = if (totalDistance > 0) {
            ((totalDistance - remaining) / totalDistance * 100).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        return NavigationState(
            currentPosition = current,
            nearestRoutePoint = points[nearestIdx],
            nearestIndex = nearestIdx,
            distanceToRoute = minDist,
            progressPercent = progressPercent,
            remainingDistance = remaining
        )
    }

    /** 현재 경로 해제. (목적지 변경, 라이딩 종료 등에서 사용) */
    fun clearRoute() {
        currentRoute = null
        nearestIndex = 0
        cumulativeDistances = emptyList()
    }

    // ------------------------------------------------------------
    // 내부 계산
    // ------------------------------------------------------------

    /** points[0]부터 각 지점까지의 누적 거리 리스트. 마지막 값 = 전체 경로 길이. */
    private fun buildCumulativeDistances(points: List<LatLong>): List<Double> {
        if (points.size < 2) return List(points.size) { 0.0 }

        val distances = MutableList(points.size) { 0.0 }
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += haversine(points[i - 1], points[i])
            distances[i] = acc
        }
        return distances
    }

    /** nearestIndex부터 경로 끝까지 남은 실제 누적 거리. */
    private fun remainingDistanceFrom(index: Int): Double {
        val total = cumulativeDistances.lastOrNull() ?: return 0.0
        val current = cumulativeDistances.getOrElse(index) { total }
        return (total - current).coerceAtLeast(0.0)
    }

    private fun haversine(a: LatLong, b: LatLong): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x))
    }
}
