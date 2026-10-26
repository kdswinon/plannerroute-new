# plannerroute Android

영업 방문 업체를 선택하고 출발지 → 방문 업체 → 도착지의 도로 동선을 계산하는 **네이티브 안드로이드 앱**입니다. HTML/WebView 앱이 아닙니다.

## 사용

1. Android 8.0 이상 기기에 APK를 설치합니다.
2. 출발지를 검색·현재 위치·지도 선택 중 하나로 지정합니다.
3. 업체를 등록하고 오늘 방문 업체를 선택합니다.
4. 복귀·마지막 업체 종료·별도 목적지 중 도착 방식을 고릅니다.
5. 오늘 동선을 최적화하고 번호 지도와 타임라인을 확인합니다.
6. 각 업체의 내비게이션 버튼으로 외부 지도 앱을 엽니다.

주황색 `#FF9500`, 배경 `#0B0C0F`, 카드 `#14161A`의 모바일 다크 화면입니다. 오늘 동선·업체 관리·지도·통계 네 메뉴를 제공합니다.

## 지도와 계산

- MapLibre Native Android + OpenFreeMap 공개 다크 스타일. 지도 API Key 입력 없음.
- 업체와 번호: GeoJSON source + circle/symbol layer. 경로: GeoJSON source + line layer.
- OSRM Table의 비대칭 도로 이동시간 행렬을 사용합니다.
- 11개 이하 방문지는 고정 출발지·도착 조건을 포함한 Held–Karp 동적 계획법으로 행렬 기준 최적 순서를 계산합니다.
- 12~40개 방문지는 여러 시작 후보 + nearest-neighbor + 전체 방향성 비용을 평가하는 2-opt 휴리스틱을 사용합니다. 전역 최적을 보장하지 않습니다.
- 최종 순서 전체를 OSRM Route에 요청합니다. 도로 경로 GeoJSON LineString과 구간별 거리·시간을 표시합니다. 직선 대체 경로를 만들지 않습니다.
- 운전시간과 상담시간을 분리하고 총 소요시간에 상담시간을 합산합니다.
- 장소 검색: Photon. 입력 중 자동 검색 없이 검색 버튼으로 요청합니다. 검색 결과를 확인한 후 위치를 지정합니다. 국내 주소·업체 검색 결과가 부족할 때 지도에서 좌표를 지정할 수 있습니다.
- 장소 검색은 30일, 도로 행렬·경로는 24시간 캐시하며 최대 100개 응답만 유지합니다. 저장한 업체는 다시 지오코딩하지 않습니다.

## 저장과 개인정보

안드로이드 내부 SharedPreferences를 사용합니다. 웹 전용 localStorage에 의존하지 않습니다. 업체명·주소·전화번호·상담시간·메모·좌표·오늘 선택과 출발·도착 설정이 저장됩니다. Android 자동 클라우드 백업은 꺼져 있습니다.

JSON 내보내기·가져오기는 시스템 파일 선택기를 사용합니다. 가져오기 전에 버전·좌표·중복 ID·상담시간을 검증하고, 현재 데이터를 교체하기 전 확인받습니다. 백업에는 업체 개인정보가 들어가므로 본인이 관리하는 곳에 보관하세요.

지도·장소 검색·새 도로 계산에는 인터넷이 필요합니다. 검색어는 Photon, 좌표는 OSRM, 지도 요청은 OpenFreeMap에 전달됩니다. 전화번호·메모는 전송하지 않습니다. 위치 권한은 현재 위치 기능을 눌렀을 때만 요청하며, 백그라운드 추적을 하지 않습니다. 지도 장애와 별개로 업체 관리·백업을 이용할 수 있습니다.

## 무료 공개 서버의 한계

기본값은 별도 키 없는 공개 서버입니다. 응답 속도·가용성·국내 주소 검색 품질을 보장하지 않습니다. OSRM은 실시간 교통정보를 반영하지 않고 국내 통행 제한이나 차량별 조건을 충분히 반영하지 못할 수 있습니다. 운전은 실제 내비게이션의 안내와 도로 표지를 따르세요. 무료 서버는 상용 트래픽 SLA를 제공하지 않습니다. 개인용 초기 버전이며 다수 사용자 배포 전 서버 사용 정책과 별도 운영 인프라를 확인해야 합니다.

설정에서 지도 스타일·도로 서버·장소 검색 서버를 교체할 수 있습니다. 검색 서버는 Photon, 도로 서버는 OSRM 호환 HTTPS 엔드포인트여야 합니다.

TMAP·카카오맵·네이버지도는 Android URI로 실행합니다. 앱이 없으면 웹 링크로 연결합니다. TMAP 미설치 시 설치 페이지와 대체 웹 길찾기를 선택하도록 안내합니다. TMAP 웹에서 동일한 차량 안내가 가능하다고 가정하지 않습니다.

## 최적화 순서대로 카카오맵에 보내기

동선 최적화 후 요약 카드의 **카카오맵 · 경유지로 보내기**를 누릅니다. 방문지는 최적화 순서대로 기본 전체 선택되며 제외할 곳만 해제할 수 있습니다. 출발지 복귀·별도 도착지도 마지막 항목에 포함됩니다.

카카오맵 공식 URL Scheme의 경유지 5개 + 목적지 1개 한도에 맞춰 최대 6곳씩 구간을 나눕니다. 구간과 전달 목록을 확인한 뒤 현재 위치에서 길찾기를 엽니다. 다음 구간은 해당 구간 방문 후 루트플래너에서 다시 선택합니다. 전송만으로 방문 완료를 처리하지 않습니다. 실제 도로와 시간은 카카오맵이 다시 계산합니다.

앱이 없으면 동일한 경유지·목적지를 담은 모바일 웹을 엽니다. 카카오맵 6.0.0 이상에서의 경유지 지원이 공식 문서에 안내되어 있으며 실제 설치된 버전에서 전달 결과를 확인해야 합니다. TMAP·네이버지도의 기존 업체별 버튼은 단일 목적지용입니다. 아래 TMAP 지원 범위와 미완료 항목을 참고하세요.

공식 근거(2026-10-23 한국 시각 확인): [카카오맵 Android URL Scheme](https://apis.map.kakao.com/android_v2/docs/api-guide/urlscheme/), [TMAP SDK 변경 기록](https://tmap-skopenapi.readme.io/reference/sdk).

## TMAP 지원 범위와 미완료 항목 (0.1.9)

2026-10-23 한국 시각 확인. 동선 요약의 **TMAP · 방문 순서에서 한 곳씩**은 기존 카카오맵 방문지 선택 화면을 공유합니다. 기본 전체 선택, 제외 선택, 최적화 순서와 복귀·별도 도착지 포함을 유지합니다. 선택한 목록에서 한 곳을 골라 기존 `tmap://route?goalx=경도&goaly=위도&goalname=이름`으로 엽니다. 업체명은 기존처럼 URI 인코딩합니다. 다음 업체는 앱으로 돌아와 목록에서 직접 선택하며 자동 방문 완료·자동 다음 앱 실행은 하지 않습니다. 카카오맵의 경유지 5개 + 목적지 1개 전송과 웹 대체 처리는 재구현하지 않았습니다.

### 확인한 공식 근거

- [TMAP 공식 SDK 변경 기록](https://tmap-skopenapi.readme.io/reference/sdk): Android 1.74(2022-08-17)에 “TMapTapi invokeRoute() 함수 경유지 옵션 5개 추가”를 명시합니다. **티맵이 다중 경유지를 지원하지 않는다는 뜻이 아닙니다.**
- [TMAP 공식 개발 가이드](https://tmapapi.tmapmobility.com/main.html): TMapTapi의 `invokeRoute(HashMap routeInfo)`, `setSKTMapAuthentication(String apiKey)`, 인증 성공·실패 콜백과 API Key 발급·설정 항목을 확인했습니다. 정적으로 읽힌 메뉴·시그니처이며 상세 파라미터 본문을 전부 확보한 것은 아닙니다.
- [TMAP 공식 서비스 안내·약관](https://tmapapi.tmapmobility.com/terms.html): “인증 — TMAP SDK 사용시 인증을 수행”, “TMAP 연동 — TMAP을 실행하여 목적지를 바로 연동하여 길안내 수행”을 각각 명시합니다. 다중 경유지 경로 **정보 API**와 외부 **앱 연동**은 구분합니다. 경로 정보 API의 개수를 앱 호출 한도로 사용하지 않았습니다.

### 실제 코드와 남은 제한

현재 프로젝트 의존성은 MapLibre이며 TMAP SDK 의존성·초기화·인증 호출이 없습니다. 기존 TMAP 호출은 단일 목적지 URI입니다. 비밀 설정 파일·키·인증정보는 조회하지 않았으며, 키 보유 여부는 판단하지 않았습니다. iOS의 `rV1X` 같은 필드를 Android URI에 추측해 적용하지 않았습니다.

**다중 경유지 자동 입력은 미완료입니다.** 공식 SDK의 경유지 지원은 확인했지만, 현재 무인증 URI 방식으로 같은 기능을 제공하는 정확한 공식 규격은 확인되지 않았습니다. 확인된 SDK 경로를 추가하려면 SDK의 정확한 배포·상세 호출 규격과 승인된 인증 사용 경로가 필요합니다. 이번 지시가 새 인증정보·인증 변경을 제외하므로 임의의 키 발급·사용·SDK 인증 도입은 하지 않았습니다. 이를 티맵 기능 자체의 불가능으로 단정하지 않습니다.

### 반영과 빌드 상태

소스 변경 파일은 `MainActivity.java`, `app/build.gradle`, 이 README입니다. 버전은 0.1.9(코드 10)입니다. 기존 PC PowerShell·휴대폰 미러링 방식을 유지합니다. Windows용 스크립트는 이번에 변경하지 않았습니다.

현재 작업실에는 Java·Gradle·Android 실행 도구가 없으며 공식 사이트에 대한 셸 접속도 DNS 해석 오류로 실패했습니다. 서버 컴파일·기기 설치·실행·Windows 스크립트 실행을 완료한 것으로 보고하지 않습니다. 테스트를 추가하거나 실행하지 않았습니다. 변경 부분은 담당자가 읽어 호출 연결·인자·선택 순서·한도·동선 변경 시 차단을 확인했습니다. 이는 컴파일 확인을 대신하지 않습니다.

GitHub 상태 도구가 “프로젝트 소유자 계정으로만 GitHub를 사용할 수 있습니다.”라고 거절했습니다. 현재 대화의 프로젝트 파일 수정 권한과 GitHub 업로드 권한은 별개입니다. 직접 Git·다른 계정·공유 인증으로 우회하지 않았고 이번 변경은 아직 커밋·푸시되지 않았습니다.

업로드에 필요한 사용자 행동은 한 가지입니다. **이 루트플래너 프로젝트의 소유자 계정으로 대화를 열고 ‘현재 프로젝트 변경을 연결된 GitHub에 올려줘’라고 요청하세요.** GitHub 저장소의 소유자나 공동작업자 역할만으로 놀이터 프로젝트 소유자 검사를 대신할 수 없습니다. 업로드 후 아래 기존 PC 명령을 사용합니다.

GitHub 반영 전 PC에서 확인하려면 별도로 제공한 `TMAP-update.patch`를 프로젝트 루트에 저장하고 다음을 실행할 수 있습니다. 이 패치는 작업 시작 때의 0.1.8 파일 기준입니다. `git apply --check`가 실패하면 중단하고 오류를 전달하세요. 기존 변경을 초기화하거나 강제로 덮어쓰지 않습니다.

```powershell
git apply --check .\TMAP-update.patch
if ($LASTEXITCODE -ne 0) { throw 'Patch does not match. Stop and share the error.' }
git apply .\TMAP-update.patch
if ($LASTEXITCODE -ne 0) { throw 'Patch apply failed. Stop and share the error.' }
powershell -ExecutionPolicy Bypass -File .\tests\windows-check.ps1 -SkipTests
```

위 스크립트는 기존 앱 데이터를 유지하며 빌드·설치·실행을 시도합니다. 서명이 다르면 삭제로 우회하지 않습니다. GitHub 업로드 후 이미 이 패치를 적용한 PC에서 다시 가져올 때 충돌이 나면 강제 초기화하지 말고 기존 변경을 보존해야 합니다.

## Windows에서 수정본 설치

기존 프로젝트 폴더의 PowerShell에서 다음을 실행합니다. USB 디버깅이 허용된 휴대폰 한 대와 Android SDK 35가 필요합니다.

```powershell
git pull --ff-only origin main
if ($LASTEXITCODE -ne 0) { throw 'Git pull failed. Stop and share the error.' }
powershell -ExecutionPolicy Bypass -File .\tests\windows-check.ps1 -SkipTests
```

위 명령은 테스트를 실행하지 않고 빌드 → 기존 앱 종료 → 데이터 유지 설치 → 실행·프로세스 확인을 진행합니다. 서명이 맞지 않으면 앱을 삭제하지 않고 중단합니다. 복원본에 Gradle wrapper JAR가 없으면 공식 SHA-256으로 확인한 Gradle 8.9를 프로젝트 내부에 내려받아 사용합니다. Windows 실행과 실제 경유지 전달은 대표님 PC·휴대폰에서 확인할 항목입니다.

## 빌드

JDK 17, Android SDK platform 35, build-tools 35.0.0, Gradle 8.9가 필요합니다.

```sh
./gradlew :app:assembleDebug
```

Android Studio에서도 폴더를 열어 빌드할 수 있습니다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 개발용 서명이므로 Play Store 배포용이 아닙니다. `.tooling`, 로컬 SDK, 빌드 캐시, 서명키, 프로젝트 기억은 Git에 포함하지 않습니다.

## 주요 소스

- `MainActivity.java`: 네이티브 화면·지도·GPS·업체 편집·백업·외부 내비게이션
- `Data.java`: 업체 모델·저장·JSON 검증
- `Routing.java`: 네트워크·캐시·방문 순서 계산·도로 경로

[수정 내역·테스트 결과·Windows 실행 방법](VALIDATION.md)을 참고하세요. Windows용 `tests/windows-check.ps1`은 Android Studio가 준비된 PC에서 핵심 검사, 빌드, USB 설치와 실행 확인을 묶어 수행합니다. 화면/GPS 자동 검증이나 원격 PC 연결 기능은 아닙니다.

## 공식 참고 자료

- [MapLibre Android](https://maplibre.org/maplibre-native/android/examples/getting-started/)
- [OpenFreeMap](https://openfreemap.org/quick_start/)
- [OSRM API](https://project-osrm.org/docs/v5.24.0/api/)
- [Photon 및 공개 서버 정책](https://github.com/komoot/photon)
- [카카오맵 웹 링크](https://apis.map.kakao.com/web/guide/#routeurl)

실제 기기 화면·GPS·외부 내비 앱 연결은 기기에서 별도로 확인해야 합니다. 서버의 JSON 응답 확인은 기기 실행 확인과 다릅니다.
