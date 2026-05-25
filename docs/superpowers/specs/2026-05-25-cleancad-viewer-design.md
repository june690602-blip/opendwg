# CleanCAD Viewer — 설계 문서 (Design Spec)

- **작성일**: 2026-05-25
- **상태**: 승인됨 (구현 계획 작성 전)
- **목적**: 광고 없는 깨끗한 Android DWG 도면 뷰어
- **주 사용자**: 건설/시공 현장에서 도면을 확인하는 사람들
- **프로젝트 위치**: `C:\dev\opendwg` (GitHub 저장소: `june690602-blip/opendwg`)

---

## 1. 배경 및 목표

시중 DWG 뷰어 앱들은 광고가 과도하다. 이 프로젝트는 광고 없는 무료 오픈소스 DWG 뷰어를 직접 만든다. 현장에서 도면을 빠르고 깨끗하게 확인하는 것이 핵심 가치다.

**비목표 (Non-goals)**:
- DWG 편집/생성 (읽기 전용 뷰어)
- 3D 도면 렌더링 (2D 전용)
- 마크업/주석 그리기
- 클라우드 동기화 / 네트워크 기능 (오프라인 전용)

---

## 2. 확정된 결정 사항

| 항목 | 결정 |
|------|------|
| 플랫폼 / 언어 | Android / Kotlin |
| DWG 파싱 | LibreDWG (GNU, GPL v3) |
| 라이선스 | GPL v3, 앱 소스 전체 GitHub 공개 |
| GitHub 저장소 | `opendwg` (사용자: june690602-blip) |
| 앱 표시 이름 | CleanCAD Viewer |
| 패키지명 (applicationId) | `io.github.june690602_blip.cleancad` |
| minSdk | 24 (Android 7.0) |
| targetSdk / compileSdk | 36 (Android 16) |
| 지원 ABI | arm64-v8a, armeabi-v7a, x86_64 |
| UI 프레임워크 | Android Views (XML 레이아웃) + 커스텀 View |
| 빌드 | Gradle (Kotlin DSL) + CMake (LibreDWG NDK 크로스컴파일) |
| 렌더링 파이프라인 | DWG → DXF → 엔티티 파싱 → 커스텀 Canvas |
| 엔티티 범위 | Tier 2 (2D only) |
| 지원 DWG 버전 | R2000~R2018 읽기 목표, R2018+ 베스트 에포트 |
| 뷰어 인터랙션 | Pan / Pinch-Zoom / Fit-to-screen |
| 파일 접근 | SAF (Storage Access Framework) + Share/View Intent |
| 대용량 처리 | 백그라운드 파싱 + 단순 전체 렌더, ~50MB 부드럽게 목표 |

### 라이선스 주의사항 (GPL v3)

LibreDWG(GPL v3)를 JNI로 정적/동적 링크하면 앱 전체가 GPL v3 파생물이 된다. 따라서:
- 앱 소스 전체를 GPL v3로 공개해야 한다 (필수).
- 클로즈드 소스 컴포넌트를 추가할 수 없다.
- Play Store 배포는 가능하나 과거 GPL ↔ Play 약관 마찰 사례가 있으므로 F-Droid를 백업 배포처로 둔다.

### 이름/상표 주의사항

"DWG"는 Autodesk의 상표다. 스토어 *표시 이름*에 직접 넣으면 테이크다운 위험이 있어 "CleanCAD Viewer"로 회피한다. 앱 설명문에서는 검색 노출을 위해 "DWG"를 사용할 수 있다. applicationId는 일관성을 위해 `cleancad`로 통일했다(GitHub 저장소 이름 `opendwg`와는 독립적).

---

## 3. 엔티티 지원 범위 (Tier 2)

**MVP = 1.0 목표 = Tier 2.** 단계적 출시가 아니라 처음부터 Tier 2를 목표로 구현하되, 내부 구현 순서는 기본 엔티티 → 복합 엔티티 순으로 진행한다.

**포함 엔티티**:
- 기본: `LINE`, `LWPOLYLINE`/`POLYLINE`, `ARC`, `CIRCLE`, `ELLIPSE`, `SPLINE`
- 텍스트: `TEXT`, `MTEXT`
- 구조: `LAYER`(테이블), `INSERT`(블록 참조), `BLOCK`(블록 정의)
- 복합: `DIMENSION`(치수), `HATCH`(해치), `LEADER`(지시선)

**제외**:
- 3D 엔티티 전부 (3DSOLID, MESH, SURFACE 등)
- Tier 3 엔티티 (TABLE, WIPEOUT, IMAGE, FIELD 등) — 1.0 이후 검토

---

## 4. 아키텍처 (5계층)

DWG(외국어 문서)를 읽어 화면에 그리는 과정. ①번역기(native)가 원문을 읽기 쉬운 중간 언어(DXF)로 변환 → ②통역사(파서)가 데이터로 정리 → ③화가(렌더러)가 그림.

```
[1] Native (C/JNI)
    libredwg.so + 얇은 JNI 래퍼
    dwgToDxf(inPath: String, outPath: String): Int   ← JNI 경계는 이 함수 하나
        │  (DXF 텍스트 파일 생성)
        ▼
[2] DXF 파서 (Kotlin)
    DXF ENTITIES 섹션 파싱 → 타입별 불변 모델
    DxfLine, DxfPolyline, DxfArc, DxfCircle, DxfEllipse, DxfSpline,
    DxfText, DxfMText, DxfInsert, DxfDimension, DxfHatch, DxfLeader
        ▼
[3] 도면 모델 (Kotlin)
    Drawing(entities: List<DxfEntity>, layers: List<Layer>, extents: BoundingBox)
    전부 불변 data class
        ▼
[4] 렌더러 (Kotlin)
    DrawingView (android.view.View) — onDraw + Canvas + Matrix
    ScaleGestureDetector + GestureDetector (pan/pinch/double-tap-fit)
        ▼
[5] UI (Android Views / XML)
    파일 열기 / 뷰어 / 최근 파일 / 설정
    DrawingView를 XML 레이아웃에 직접 배치
```

### 핵심 설계 의도

**JNI 경계를 `dwgToDxf` 함수 하나로 최소화.** 엔티티별 데이터를 JNI로 마샬링하는 대신 native는 DXF 텍스트 파일만 생성하고, 모든 도면 로직은 Kotlin에 둔다. 이유:
- JNI 표면이 좁아 native 디버깅 부담이 적다.
- 도면 로직이 순수 Kotlin이라 빠른 단위 테스트가 가능하다.
- `dwg2dxf`는 LibreDWG에서 가장 성숙한 변환 경로다.

### 렌더 표면 선택

UI는 Android Views(XML 레이아웃) 기반이다(프로젝트 템플릿이 Empty Views Activity). 무거운 도면 그리기는 커스텀 `DrawingView`(`android.view.View`, onDraw + Canvas + Matrix)로 처리하며, XML 레이아웃에 직접 배치한다. Jetpack Compose는 사용하지 않으므로 Compose↔View 인터롭이 필요 없어 구조가 단순하다.

### 모듈/파일 구성 (가이드)

작은 파일 다수 원칙. 엔티티 파서와 렌더러는 타입별로 분리한다 (예: `parser/`, `model/`, `render/`, `ui/`, `native/`).

---

## 5. 데이터 흐름 (파일 한 개 여는 과정)

```
SAF 피커 또는 Share/View Intent로 content URI 수신
  → 앱 캐시 디렉토리로 복사 (native는 실제 파일 경로 필요)
  → 백그라운드 스레드 (코루틴 Dispatchers.IO):
        dwgToDxf(cache.dwg, cache.dxf)        [JNI]
        DXF 파싱 → Drawing 모델 생성
  → 메인 스레드:
        DrawingView에 Drawing 전달
        Fit-to-screen 적용 → 표시
  → 사용자 Pan / Zoom 인터랙션
```

로딩 중에는 진행 스피너를 표시하고, 실패 시 친절한 에러 메시지를 보여준다.

---

## 6. 에러 처리

| 상황 | 처리 |
|------|------|
| 손상/미지원 DWG | JNI 에러코드 → "이 파일을 열 수 없습니다" |
| 미지원 버전 (2018+) | 베스트 에포트, 부분 렌더라도 표시 |
| 초대형 파일 OOM | catch → "파일이 너무 큽니다" |
| 변환 결과 비어있음 | "도면을 표시할 수 없습니다" + 상세 사유 |
| SAF 권한 문제 | 파일 재선택 유도 |

---

## 7. 테스트 전략

| 대상 | 방식 | 우선도 |
|------|------|--------|
| DXF 파서 | Kotlin 단위 테스트 (샘플 DXF 문자열 → 파싱 모델 검증) | 최고 (빠름, 기기 불필요) |
| 좌표 변환 수학 | Fit/zoom Matrix 단위 테스트 | 높음 |
| Native 파이프라인 | instrumented 테스트 (에뮬레이터, .so 필요, 샘플 DWG → DXF 출력 검증) | 중간 |
| 렌더링/UI | 실제 샘플 DWG로 단계별 수동 검증 (빌드 후 직접 열어 확인) | 수동 |

픽셀 단위 자동화 테스트는 하지 않는다. UI는 실기/에뮬레이터에서 실제 도면으로 검증한다.

---

## 8. 개발 단계 (Phase)

| Phase | 내용 | 상태 |
|-------|------|------|
| **0** | 저장소 클론, `.gitignore`, GPL v3 LICENSE, 프로젝트 골격 | ✅ 완료 |
| **1** | Hello World 빌드·실행 (Kotlin/Views, minSdk 24) | 🔄 진행 중 (프로젝트 생성·설정 정리 완료, 빌드 검증 중) |
| **2** | libredwg NDK 크로스컴파일 (3 ABI: arm64-v8a, armeabi-v7a, x86_64), .so 번들 | ⬜ **최대 난관** |
| **3** | JNI `dwgToDxf` 래퍼 + DXF 파서 + Drawing 모델 (엔드투엔드 "열면 엔티티 추출됨") | ⬜ |
| **4** | DrawingView 렌더러(Tier 2) + 뷰어 화면 + SAF 피커 + 최근 파일 | ⬜ |
| **5** | 다크모드, 화면 회전, Share/View Intent, 설정 | ⬜ |
| **6** | Play Store 출시 준비 (GPL 고지, 이름에 "DWG" 회피, 스크린샷, 앱 서명) | ⬜ |

---

## 9. 주요 리스크

1. **Phase 2 (NDK 크로스컴파일)가 최대 난관.** LibreDWG는 autotools 기반이라 NDK 크로스컴파일 설정이 까다롭다. 여기서 막히면 가장 오래 걸릴 수 있다.
2. **DXF 변환 충실도.** `dwg2dxf`가 일부 엔티티/스타일을 누락할 수 있다. Phase 3에서 실제 샘플로 조기 확인이 필요하다.
3. **GPL ↔ Play Store 마찰.** 과거 사례가 있으므로 F-Droid를 백업 배포처로 둔다.

---

## 10. 향후 검토 (Out of Scope, 1.0 이후)

- 레이어 켜기/끄기 (렌더러를 레이어별 그룹 구조로 리팩터 필요)
- 거리 측정 (두 점 찍기)
- Tier 3 엔티티
- 대용량 파일 타일링/프로그레시브 렌더링
