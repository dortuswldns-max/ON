# OppaNavi — Claude 작업 가이드

## 환경 경로

| 항목 | 경로 |
|------|------|
| JAVA_HOME | `C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot` |
| ANDROID_HOME | `C:\Users\USER\AppData\Local\Android\Sdk` |
| adb | `C:\Users\USER\AppData\Local\Android\Sdk\platform-tools\adb.exe` |

## 빌드 고정값

- **compileSdk = 36** — 변경 금지. `app/build.gradle.kts`의 `compileSdk { version = release(36) }` 블록 유지.
- **targetSdk = 36**, **minSdk = 24** — 함께 고정.
- **Java 호환성**: `SOURCE_COMPATIBILITY = JavaVersion.VERSION_11` / `TARGET_COMPATIBILITY = VERSION_11`

## 금지 사항

### 1. `androidx.core:core` 버전 1.19.x 사용 금지
`app/build.gradle.kts`에 `core:1.19`가 직접 선언되어 있음.  
`libs.versions.toml`의 `coreKtx = "1.19.0"`은 현재 허용되어 있으나, `core` 직접 의존성을 `1.19`로 올리지 말 것.  
버전 업그레이드 제안 시 반드시 호환성 확인 후 진행.

### 2. EFAB (ExtendedFloatingActionButton) 사용 금지
UI 컴포넌트로 EFAB를 도입하지 말 것. 기존 FAB 또는 커스텀 버튼 유지.

### 3. `bearing = 0` 하드코딩 금지
방위각(bearing)을 0으로 고정하는 코드 작성 금지.  
GPS 또는 센서에서 실시간으로 계산된 값을 사용해야 함.

## 프로젝트 구조

```
D:\OppaNavi\
├── app/
│   ├── build.gradle.kts          # 앱 빌드 설정 (compileSdk 36 고정)
│   └── src/main/java/com/example/oppanavi/
│       ├── MainActivity.kt       # 진입점, UI 조합 및 권한 처리
│       ├── MapManager.kt         # Mapsforge 지도 렌더링 및 오버레이
│       ├── GpxManager.kt         # GPX 파일 로드·파싱·경로 표시
│       ├── GpxEngine.kr.kt       # GPX 경로 탐색 엔진 (안내 로직)
│       ├── AppLocationManager.kt # GPS/위치 권한 및 실시간 위치 관리
│       ├── RideManager.kt        # 라이딩 세션 시작·종료 제어
│       ├── RideLogger.kt         # 라이딩 데이터 기록 (발열·RAM·이벤트)
│       ├── CameraModule.kt       # 블랙박스 카메라 녹화 (CameraX 기반)
│       └── ui/theme/             # Compose 테마 (Color, Theme, Type)
├── gradle/
│   └── libs.versions.toml        # 버전 카탈로그 (AGP 9.2.1, Kotlin 2.2.10)
├── local.properties              # sdk.dir=C:\Android\Sdk (gitignore됨)
└── build.gradle.kts              # 루트 빌드 설정
```

## 주요 의존성 메모

- **Mapsforge 0.17.0** — 오프라인 지도 렌더링 (mapsforge-core / map / map-android)
- **CameraX 1.3.1** — 블랙박스 녹화 모듈 (camera-core / camera2 / lifecycle / video / view)
- **Compose BOM 2026.02.01** — UI 프레임워크
- `androidx.core:core` 및 `core-ktx`는 `1.13.1` 고정 (직접 선언)

## adb 명령 예시

```powershell
# 장치 연결 확인
& "C:\Users\USER\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices

# 앱 로그 확인
& "C:\Users\USER\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s OppaNavi
```
