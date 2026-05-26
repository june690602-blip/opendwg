# Phase 6 설계 스펙 — 렌더링 품질 개선 + Play Store 출시 준비

**작성일:** 2026-05-27  
**상태:** 계획  
**직전 Phase 완료:** Phase 5 (다크모드·설정·최근파일) + 핫픽스 (ANR/OOM 수정, INSERT 블록 확장)

---

## 1. 배경 및 목표

Phase 5까지 완료 후 실제 DWG 파일 2종으로 현장 검증 진행:
- `워킹타워 260418.dwg` (1.1MB) → 정상 렌더링 ✅
- `04_참고도면.dwg` (13MB) → ANR 수정 후 렌더링 성공, 단 두 가지 품질 문제 발견:
  1. **CAD 심볼 폰트 문자가 ◆? 로 깨짐** — `%%` 이스케이프 + 특수 폰트 미처리
  2. **도면이 화면에 너무 작게 표시됨** — 아웃라이어 엔티티가 바운딩박스를 과도하게 넓힘

Phase 6의 목표:
- **6-A** 렌더링 품질 개선 (텍스트 + Fit-to-Screen)
- **6-B** 뷰포트 컬링 (대용량 도면 스크롤 성능)
- **6-C** Play Store / F-Droid 출시 준비

1.0 릴리즈 후보를 만드는 것이 최종 목표.

---

## 2. 범위 (In Scope)

### 6-A: 텍스트 렌더링 개선

#### 6-A-1: DXF `%%` 이스케이프 코드 처리
AutoCAD TEXT/MTEXT의 특수 문자:

| 코드 | 의미 | 처리 방법 |
|------|------|-----------|
| `%%D` | ° (도) | → `°` |
| `%%P` | ± (플러스마이너스) | → `±` |
| `%%C` | ⌀ (지름) | → `⌀` |
| `%%U` | 밑줄 토글 | 무시 (서식 코드 제거) |
| `%%O` | 윗줄 토글 | 무시 |
| `%%nnn` | ASCII 코드 (%%045 = `-`) | → `Char(nnn)` |

구현 위치: `DxfParser.parseText()` / `parseMText()`, 또는 `EntityRenderer.drawText()` 전처리.

#### 6-A-2: MText 서식 코드 개선
현재 `EntityRenderer.drawMText()`에서 일부 서식 코드를 제거하지만 미흡.  
개선:
- `\P` → 줄바꿈 (현재 처리됨, 유지)
- `\~` → non-breaking space
- `\{`, `\}` → `{`, `}` 리터럴
- `{\fFontName|...}` → 폰트 지정 코드 제거 (이미 부분 처리, 정규식 강화)
- `\H텍스트높이;` 등 → 제거

다중 줄 MText: `canvas.drawText()` 대신 `StaticLayout`을 사용해 줄바꿈 지원.

#### 6-A-3: DXF 텍스트 인코딩 (선택적)
LibreDWG가 UTF-8 출력하므로 기본적으로 한글은 동작해야 함.  
만약 깨지는 경우: `dxfFile.bufferedReader(Charsets.UTF_8)` 명시적 지정 + 폴백 `EUC-KR` 재시도.

---

### 6-B: Fit-to-Screen 개선 (아웃라이어 필터링)

**현상:** 일부 도면에서 극단 좌표의 엔티티(예: 타이틀 블록, 치수 보조선 등)가 바운딩박스를 수백 배 확장해 주 도면이 화면에 점처럼 표시됨.

**해결 방법 — 퍼센타일 기반 트리밍:**
```kotlin
// DxfParser.calculateBoundingBox() 대신 trimmedBoundingBox()
fun trimmedBoundingBox(entities: List<DxfEntity>, p: Double = 0.05): BoundingBox? {
    val xs = allPoints.map { it.x }.sorted()
    val ys = allPoints.map { it.y }.sorted()
    val lo = (xs.size * p).toInt()
    val hi = (xs.size * (1 - p)).toInt()
    return BoundingBox(xs[lo], ys[lo], xs[hi], ys[hi])
}
```
- 5th~95th 퍼센타일 범위를 표시 기준으로 사용
- 전체 바운딩박스는 여전히 계산하되, `fitToScreen()`은 트리밍된 박스 사용
- `Drawing` 모델에 `extents`(전체)와 `displayExtents`(트리밍) 두 필드 유지

---

### 6-C: 뷰포트 컬링

**현상:** 50,000 엔티티를 `onDraw()`에서 전부 순회 — 줌인 시 화면 밖 엔티티도 모두 그림.

**해결:**
```kotlin
// DrawingView.onDraw()에서
val viewport = CoordTransform.screenToWorld(Rect(0, 0, width, height), matrix)
renderer.drawAll(entities.filter { it.intersects(viewport) }, canvas, matrix)
```
- 각 엔티티 타입에 `boundingBox(): Rect?` 확장함수 추가
- AABB(Axis-Aligned Bounding Box) 교차 검사로 화면 밖 엔티티 건너뜀
- 단순 구현으로도 줌인 시 5~10배 렌더 속도 향상 기대

---

### 6-D: Play Store / F-Droid 출시 준비

#### 6-D-1: About 화면 (GPL 고지 필수)
- `AboutActivity` 또는 다이얼로그 추가
- GPL v3 전문 표시 or 링크
- LibreDWG 저작권 고지
- 앱 버전 표시

#### 6-D-2: 릴리즈 서명
- Release keystore 생성 (`keytool`)
- `app/build.gradle.kts`에 `signingConfigs` 추가
- `gradle.properties` 또는 환경변수로 키 정보 관리 (커밋하지 않음)

#### 6-D-3: 앱 이름 / 패키지
- "CleanCAD Viewer" — "DWG" 단어 포함 안 함 ✅
- applicationId: `io.github.june690602_blip.cleancad` ✅
- versionCode 자동 증가 전략 (빌드 번호)

#### 6-D-4: Play Store 메타데이터
- 스크린샷 최소 2장 (1080×1920 이상)
- 짧은 설명 (80자)
- 긴 설명 (4000자)
- 아이콘 512×512 PNG

#### 6-D-5: F-Droid 메타데이터
- `metadata/io.github.june690602_blip.cleancad.yml` 작성
- reproducible build 확인

---

## 3. 범위 외 (Out of Scope — 1.0 이후)

- 레이어 켜기/끄기 UI
- 거리 측정 툴
- HATCH 완전 렌더링 (현재 skip, 빗금만)
- POLYLINE (3D), SOLID, FACE3D 등 Tier 3 엔티티
- 프로그레시브/타일 렌더링
- DWG 직접 파싱 (현재 DWG→DXF 변환 경유)

---

## 4. 우선순위

| 우선순위 | 항목 | 이유 |
|----------|------|------|
| 높음 | 6-B: Fit-to-Screen 개선 | 사용자 첫인상 직결 |
| 높음 | 6-A-1: `%%` 이스케이프 | 가장 흔한 텍스트 깨짐 원인 |
| 중간 | 6-C: 뷰포트 컬링 | 대용량 파일 UX |
| 중간 | 6-D-1: About/GPL 고지 | 법적 필수 |
| 낮음 | 6-D-2~5: 배포 준비 | 기술 완성 후 진행 |
| 낮음 | 6-A-2: MText StaticLayout | 다중 줄 드문 케이스 |

---

## 5. 현재 코드베이스 상태 (Phase 6 시작 기준)

### 완료된 것
- Phase 0~5 전체 완료
- ANR/OOM 수정 (`edb41ca`) — DxfReader 스트리밍, MAX_ENTITIES=50,000
- INSERT 블록 확장 (`expandEntities`) — 실제 DWG 렌더링 가능
- 다크모드, 설정, 최근파일, Toolbar, overflow 메뉴

### 관련 파일 목록
```
app/src/main/java/.../parser/
    DxfParser.kt          — parse(), expandEntities(), calculateBoundingBox()
    DxfReader.kt          — BufferedReader 스트리밍

app/src/main/java/.../model/
    Drawing.kt            — Drawing(entities, layers, extents: BoundingBox?)
    DxfEntity.kt          — DxfText, DxfMText, DxfLine, ...

app/src/main/java/.../render/
    DrawingView.kt        — onDraw(), fitToScreen()
    EntityRenderer.kt     — drawText(), drawMText(), drawAll()
    CoordTransform.kt     — fitMatrix(), worldToScreen()

app/src/main/java/.../ui/
    DrawingViewModel.kt   — load(uri), bufferedReader 스트리밍
    ViewerActivity.kt     — state 관찰, DrawingView 연결
```

### 테스트 현황
- 단위 테스트 30개 통과 (DxfParser, DxfReader, CoordTransform)
- 계측 테스트: NativeDwgTest, NativePipelineTest (에뮬레이터 필요)

---

## 6. 검증 기준

| 항목 | 합격 기준 |
|------|-----------|
| `%%D` 처리 | `"각도%%D값"` → `"각도°값"` (단위 테스트) |
| Fit-to-Screen | `04_참고도면.dwg` 열었을 때 도면이 화면 50% 이상 채움 |
| 뷰포트 컬링 | 줌인 상태 `onDraw()` 시간 < 100ms (Systrace) |
| GPL 고지 | About 화면에 전문 또는 링크 표시 |
| 단위 테스트 | 기존 30개 + 신규 테스트 모두 통과 |
