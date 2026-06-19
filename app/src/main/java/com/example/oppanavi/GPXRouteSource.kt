package com.example.oppanavi

import android.util.Log
import org.mapsforge.core.model.LatLong

/**
 * ============================================================
 * GPXRouteSource
 * ============================================================
 *
 * GpxEngine을 RouteSource 규격에 맞게 감싸는 얇은 어댑터.
 *
 * 이번 단계 목표:
 *   "GPX 기능 이전"이 아니라
 *   "RouteManager가 GraphHopper/GPX라는 서로 다른 경로 공급원을
 *    동일한 RouteResult 형태로 처리할 수 있음을 검증"
 *
 * GPXRouteSource 책임:
 *   - GpxEngine 참조
 *   - points 조회
 *   - RouteResult 변환
 *
 * GPXRouteSource가 하지 않는 것 (기존 GpxManager/GpxEngine 영역 유지,
 * 향후 NavigationEngine 분리 시 이동 대상):
 *   - GPX load
 *   - 진행률 계산 (updateProgress)
 *   - snap 처리
 *   - 이탈 감지
 *   - 지도 표시
 *
 * GpxEngine은 수정하지 않는다. points 구조도 변경하지 않는다.
 * ============================================================
 */
class GPXRouteSource(
    private val gpxEngine: GpxEngine
) : RouteSource {

    /**
     * GPX는 별도 초기화(PBF load, cache 등)가 필요 없다.
     * GPX 파일 로드는 GpxManager 영역의 책임이며, 이 함수가 호출되는
     * 시점에는 이미 로드됐거나 아직 로드되지 않은 상태일 수 있다 —
     * 두 경우 모두 RouteSource 관점에서는 "준비됨"으로 본다.
     * (points가 비어있는 것은 prepare 실패가 아니라 calculateRoute
     * 호출 시점의 정상적인 상태 중 하나로 취급한다.)
     */
    override fun prepare(callback: (PrepareResult) -> Unit) {
        callback(PrepareResult(success = true))
    }

    /**
     * 의도적인 임시 Adapter:
     * GraphHopper는 start/destination을 받아 "계산"하지만,
     * GPX는 이미 정해진 경로 파일이므로 start/destination을 사용하지
     * 않고 gpxEngine.points를 그대로 반환한다.
     *
     * 향후 Navigation Engine 단계에서 RouteRequest 구조
     * (Calculate vs Load)로 확장될 때 이 시그니처도 함께 정리된다.
     *
     * points가 비어있는 경우(아직 GPX 미로드) callback(null)만 단독으로
     * 호출하고 끝내지 않는다 — 로그를 남겨 진단 가능하게 하고, 실패
     * 상태 관리 자체는 RouteManager의 책임으로 넘긴다.
     */
    override fun calculateRoute(
        start: LatLong,
        destination: LatLong,
        callback: (RouteResult?) -> Unit
    ) {
        val points = gpxEngine.points
        Log.d("OppaNavi", "GPXRouteSource_DEBUG: points.size=${points.size}, gpxEngine hash=${gpxEngine.hashCode()}")

        if (points.isEmpty()) {
            Log.w("OppaNavi", "GPXRouteSource: calculateRoute() called but gpxEngine.points is empty (GPX not loaded yet)")
            callback(null)
            return
        }

        callback(RouteResult(points = points.toList()))
    }

    /** GpxEngine은 release할 외부 리소스(스레드, 엔진 핸들 등)가 없다. */
    override fun release() {
        // no-op
    }
}
