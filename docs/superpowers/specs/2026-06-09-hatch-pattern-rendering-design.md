# HATCH 패턴 렌더링 (Native def-line 확장) — 설계

- 날짜: 2026-06-09
- 대상: CleanCAD Viewer — Phase 8.6 / "진행 중 이슈 1: HATCH 패턴 미지원"
- 상태: 설계 승인됨 (구현 계획 작성 단계)

## 1. 목표 (결론 먼저)

비솔리드 HATCH를 **경계선만 그리던 현재 동작에서, 임베드된 패턴 정의선(`deflines`)으로
실제 채움 라인(ANSI31·콘크리트·벽돌 등)을 재현**하도록 한다. 패턴명 하드코딩 없이
모든 패턴을 일반적으로 지원한다.

비유: 지금은 방의 윤곽선만 그리고 "여기 콘크리트"라는 빗금은 안 그리는 상태다.
LibreDWG가 이미 "어떤 각도로 몇 mm 간격의 빗금"인지 데이터를 풀어 줬으니, 그 지시서대로
빗금을 그어 넣는 작업이다.

## 2. 배경 — 현재 파이프라인과 한계

`DWG → native serializer(Dwg_Data 직접 순회) → binary buffer → Kotlin NativeDecoder
→ Drawing model → custom Canvas(DrawingView)`

현재 HATCH 처리:

- **Native** `write_hatch` (`app/src/main/cpp/dwg_serialize.c`): `isSolid` 플래그 +
  경계 폴리곤(라인 세그먼트)만 직렬화. **패턴 정의선은 완전히 버려진다.** 세그먼트 경계는
  `curve_type == 1`(LINE)만 추출, 폴리라인 경계는 점만(bulge 무시).
- **모델** `DxfHatch` (`model/DxfEntity.kt`): `isSolid: Boolean`, `paths: List<List<Vec2>>`.
- **디코더** `decodeHatch` (`native/NativeDecoder.kt`): 위 두 필드만 읽음.
- **렌더러** `drawHatch` (`render/EntityRenderer.kt`): 경계는 항상 stroke,
  `isSolid`면 25% 알파(`HATCH_FILL_ALPHA_MASK`) 반투명 채움. **패턴 hatch는 경계선만
  남고 채움 라인이 없다** → 다른 CAD 앱 대비 시각적 차이가 큼.

### LibreDWG가 제공하는 데이터 (타당성 확인됨)

`Dwg_Entity_HATCH` (`include/dwg.h`):

- 엔티티: `name`, `is_solid_fill`, `angle`, `scale_spacing`, `double_flag`,
  `num_deflines`, `deflines[]`
- `Dwg_HATCH_DefLine`: `angle`(라인 패밀리 방향), `pt0`(기준점),
  `offset`(인접 평행선으로의 델타 벡터), `num_dashes`, `dashes[]`
- 경계 `Dwg_HATCH_Path`: 폴리라인(`point` + `bulge`) 또는 세그먼트
  (`curve_type` 1=LINE, 2=CIRCULAR_ARC, 3=ELLIPTICAL_ARC, 4=SPLINE)

→ 외부 `.pat` 파일 불필요. 정의선은 이미 해석되어 엔티티에 들어 있다.

## 3. 결정 사항 (승인됨)

1. **목표 수준: 실제 패턴 재현 (def-line 기반).** 범용 단일 해시가 아니라 정의선대로 정확히.
2. **구현 전략: Native 확장.** 로드 시 1회 native(C)에서 패턴 라인을 경계로 클립해
   라인 세그먼트로 생성. 렌더 루프 무변경 → Phase 10 성능(팬/줌 60fps) 유지.
   INSERT/DIMENSION/MLEADER/IMAGE처럼 모든 기하 확장을 native에서 하는 기존 구조와 일관.

## 4. 데이터 흐름

```
DWG → native write_hatch
        ├─ 경계 루프 구성 (affine 변환 적용)
        └─ 패턴 def-line → 평행선 패밀리 → 경계로 스캔라인 클립 → dash 적용 → 라인 세그먼트
    → binary payload (확장)
    → Kotlin decodeHatch → DxfHatch(경계 paths + fillLines + 플래그)
    → EntityRenderer.drawHatch (경계 stroke + 줌 게이트 후 fillLines drawLines)
```

## 5. Native `write_hatch` 알고리즘

### (a) 경계 루프 구성 (월드/블록로컬 좌표, 기존 affine 변환 적용)

- **폴리라인 경로**: 점 + `bulge`. `bulge != 0`이면 두 점 사이 원호를 짧은 선분으로
  테셀레이션(공유 헬퍼 `tessellate_arc`).
- **세그먼트 경로**:
  - `curve_type == 1` (LINE): 양 끝점
  - `curve_type == 2` (CIRCULAR_ARC): center/radius/start·end angle/ccw로 호 테셀레이션
  - `curve_type == 3,4` (ELLIPTICAL_ARC, SPLINE): **v1에서는 호 자체를 테셀레이션하지
    않음**. 해당 세그먼트는 경계 폴리곤에서 직선 코드(chord, 양 끝점 직결)로 근사하고,
    그런 세그먼트를 가진 hatch는 패턴 채움을 생략한 채 `patternFallback`(반투명 솔리드)으로
    렌더. (불완전 경계에 패턴을 클립하면 누출되므로 채움은 포기, 윤곽/솔리드만.
    이는 현행 동작 대비 회귀가 아니며 — 현재는 곡선 경계를 통째로 무시 — 오히려 코드로 근사.)
- 결과: 닫힌 루프들의 폴리라인 집합(점 배열). 여러 루프(외곽 + 구멍) 가능.

### (b) 패턴 채움 생성 (`!is_solid_fill && num_deflines > 0` 일 때만)

각 def-line에 대해:

1. 방향 `d = (cos(angle), sin(angle))`, 기준점 `pt0`, 델타 `offset`.
2. **블록 변환 적용**: hatch가 블록 안이면 INSERT affine(rot/scale/translate)을
   def-line의 `angle`(회전), `offset`(회전+스케일), `pt0`(전체 affine)에도 적용.
   좌표계는 **캘리브레이션 단계에서 실측 검증**(아래 9절).
3. 평행선 패밀리: 라인 `k`는 `pt0 + k*offset`를 지나고 방향 `d`. 경계 bbox를 덮는
   정수 `k` 범위 산출(`offset`의 경계수직 성분으로 간격 계산).
4. 각 라인을 **스캔라인 클리핑**:
   - 경계 모든 에지와 교차점을 구해 라인 파라미터 `t`로 정렬
   - even-odd 규칙으로 내부 구간 `[t_a, t_b]` 쌍 추출
   - 내부 구간 안에 dash 패턴 배치: `num_dashes == 0`이면 구간 전체를 솔리드 선 1개로,
     아니면 dash 배열(양수=그림, 음수=공백, 0=점)을 위상(라인 `k`별 `offset·d` 누적)
     고려해 순회하며 "그림" 구간만 세그먼트로 방출
5. **밀도 캡**: hatch당 방출 세그먼트 수가 `HATCH_MAX_FILL_SEGMENTS`(초안 4000) 초과 시
   채움 생성 중단 → `patternFallback = 1`. (생성 전 추정으로 조기 차단도 가능)

`minLineSpacingWorld`: def-line들의 경계수직 간격 최솟값(변환 후, 월드 단위). 렌더러 줌
게이트용. 채움이 없거나 폴백이면 `0`.

## 6. 프로토콜 payload 확장 (HATCH 레코드)

기존 → 신규 순서:

```
isSolid              (u8)
patternFallback      (u8)    ← 신규: 1이면 반투명 솔리드로 렌더
minLineSpacingWorld  (f64)   ← 신규: 렌더러 줌 게이트용 (없으면 0)
num_paths            (i32)   + 경계 paths [num_verts(i32) + verts(2×f64)...]  (기존)
num_fill_segments    (i32)   + 세그먼트 [x1,y1,x2,y2 (4×f64)]...               ← 신규
```

- 다른 엔티티 레코드는 불변 → **엔티티 개수 동일**(프로토콜 동기 검증 기준 유지),
  HATCH 레코드 바이트만 증가.
- 프로토콜 버전 상수가 있으면 1 올린다(decoder/serializer 동기 확인).

## 7. Kotlin 모델 / 디코더

```kotlin
data class DxfHatch(
    override val layer: String,
    val isSolid: Boolean,
    val patternFallback: Boolean,   // 신규
    val minLineSpacing: Double,     // 신규 (월드 단위, 0 = 채움 없음)
    val paths: List<List<Vec2>>,
    val fillLines: List<Vec2>       // 신규: 인접 쌍이 한 세그먼트 (size 짝수)
) : DxfEntity()
```

`decodeHatch`는 6절 순서대로 읽는다(불변 데이터 클래스, immutable 패턴 유지).

## 8. 렌더러 `drawHatch` (줌 인지 게이트)

```
경계 Path 구성 (기존 로직 유지)
val screenSpacing = (minLineSpacing * CoordTransform.currentScale(matrix))
when {
    isSolid || patternFallback                         -> 반투명 솔리드 채움 (기존 경로)
    fillLines.isNotEmpty() && screenSpacing >= MIN_PX  -> drawLines(fillLines)  // 실제 패턴
    fillLines.isNotEmpty()                             -> 반투명 솔리드 (이 줌엔 너무 조밀)
    else                                               -> 채움 없음
}
경계 stroke 항상 (기존)
```

- `MIN_PX`: 패턴 라인 최소 화면 간격 상수(초안 3px, 기존 텍스트 힌트 임계와 정합).
- `drawLines`: `fillLines`를 `worldToScreen`로 매핑해 `FloatArray` 한 번에 구성,
  `canvas.drawLines(arr, linePaint)` 1회 배치 호출. 색은 라인 색 사용.
- **효과**: 멀리(fit) = 반투명 솔리드(빠름·가독), 가까이(작업 줌) = 실제 패턴 라인.
  Phase 10 공간인덱스/컬링은 hatch 단위로 그대로 작동.

## 9. 성능 안전장치

이중 방어:

1. Native **밀도 캡**(`HATCH_MAX_FILL_SEGMENTS`) — 월드 세그먼트 폭발/메모리 방지.
2. 렌더러 **줌 게이트**(`screenSpacing < MIN_PX`면 솔리드) — 원거리 sub-pixel 라인 방지.

렌더 루프 자체는 라인 배치 그리기만 추가 → Phase 10 60fps 유지.

## 10. 좌표계 캘리브레이션 (구현 중 필수 검증)

가장 불확실한 지점은 def-line `pt0`/`offset`/`angle`이 어느 좌표계인지와 블록 변환
적용 방식이다. 구현 중 **알려진 패턴으로 실측 검증**한다:

- ANSI31(45° 대각선, 알려진 스케일) hatch를 포함한 실제 DWG 로드
- 생성된 fill line의 각도(≈45°)와 간격이 기대값과 맞는지 logcat/시각 확인
- 블록 안 hatch(회전/스케일된 INSERT 자식)로 변환 적용 정확성 확인

## 11. 테스트 (Evidence-Based)

- **Kotlin 단위테스트**: `decodeHatch` 신규 필드 라운드트립 — 크래프트한 `ByteBuffer`로
  `isSolid`/`patternFallback`/`minLineSpacing`/`paths`/`fillLines` 정확 파싱 검증.
  (기존 NativeDecoder 테스트군에 추가)
- **통합/시각 검증** (에뮬 또는 실기):
  - `04_참고도면.dwg`·`ref.dwg` 로드 → 콘크리트/ANSI31 등 패턴 라인 렌더 확인
  - 엔티티 개수 로드 전후 동일(프로토콜 동기 확인)
  - 줌 게이트 동작: fit=솔리드, 줌인=패턴 라인 전환 확인
  - 밀도 캡 발동 케이스 → 폭발 없이 솔리드 폴백
- ⚠️ **에뮬레이터 함정**: native `.so` 변경 시 `adb install -r`로는 갱신 안 됨 →
  uninstall + install 필수.

## 12. 범위

- **포함(v1)**: 라인/폴리라인(+bulge)/원호 경계, dash 패턴, 밀도 캡, 줌 게이트,
  좌표계 캘리브레이션.
- **솔리드 폴백(v1)**: 타원호·스플라인 경계, 밀도 초과 hatch.
- **제외(v1)**: gradient fill(`is_gradient_fill`) — 현행 동작 유지, MPOLYGON.

## 13. 영향 파일 (예상)

- `app/src/main/cpp/dwg_serialize.c` — `write_hatch` 재작성 + 호 테셀레이션/스캔라인
  클리핑/dash 헬퍼
- `app/src/main/cpp/dwg_serialize.h` — 상수(있으면)
- `native/NativeProtocol` (또는 해당 상수 파일) — payload 버전(있으면)
- `model/DxfEntity.kt` — `DxfHatch` 필드 확장
- `native/NativeDecoder.kt` — `decodeHatch` 확장
- `render/EntityRenderer.kt` — `drawHatch` 줌 게이트 + fill line 그리기, 상수
- 테스트 파일 — `decodeHatch` 단위테스트 추가

## 14. 리스크

- **좌표계/블록 변환 오해** → 잘못된 각도/간격. 완화: 10절 캘리브레이션.
- **even-odd vs nonzero** 채움 규칙(구멍 있는 다중 루프). HATCH 기본 island 규칙=even-odd 채택.
- **스플라인/타원 경계 정확도**. v1 솔리드 폴백으로 회피.
- **성능**. 9절 이중 방어.
