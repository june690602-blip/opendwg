# HATCH 패턴 렌더링 (Native def-line 확장) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비솔리드 HATCH를 임베드된 패턴 정의선(`deflines`)으로 실제 채움 라인(ANSI31·콘크리트·벽돌 등)으로 렌더링한다.

**Architecture:** native serializer(`dwg_serialize.c`)가 로드 시 1회 패턴 평행선 패밀리를 경계 폴리곤으로 스캔라인 클리핑해 라인 세그먼트로 확장하고, 확장된 binary payload를 Kotlin이 디코딩해 `DxfHatch`에 담는다. 렌더러는 줌 인지 게이트로 가까이서는 패턴 라인을, 멀리서는 반투명 솔리드를 그린다. 밀도 캡(native)과 줌 게이트(renderer)로 Phase 10 성능을 유지한다.

**Tech Stack:** C (LibreDWG 바이너리 API, NDK/CMake), Kotlin (ByteBuffer 디코더, Android Canvas), JUnit.

**참고 스펙:** `docs/superpowers/specs/2026-06-09-hatch-pattern-rendering-design.md`

---

## 프로토콜 payload 포맷 (HATCH 레코드, 엔티티 헤더 직후)

```
isSolid              u8
patternFallback      u8     (1 = 반투명 솔리드로 렌더; 곡선 경계/밀도 초과)
minLineSpacingWorld  f64    (월드 단위 최소 평행선 간격; 채움 없으면 0)
num_paths            i32
  per path: num_verts i32, then num_verts × (f64 x, f64 y)   ← 경계 (기존과 동일)
num_fill_segments    i32
  per seg: f64 x1, f64 y1, f64 x2, f64 y2                     ← 신규 패턴 채움 라인
```

`isSolid`/경계 포맷은 기존과 동일. `patternFallback`+`minLineSpacingWorld`가 isSolid 뒤에 삽입되고, 경계 뒤에 `num_fill_segments` 섹션이 추가된다. 다른 엔티티 레코드는 불변이므로 **로드된 엔티티 개수는 변하지 않는다**(프로토콜 동기 검증 기준).

**구현/검증 순서 주의:** 프로토콜 VERSION을 1→2로 올린다. Task 1(Kotlin decoder가 v2 기대)과 native(v2 write, Task 2~3)가 모두 끝나 `.so`를 재빌드하기 전까지는 실제 DWG 로드가 "버전 불일치"로 graceful 실패한다(크래시 아님). 따라서 **실제 파일 통합 검증은 Task 5에서** 수행한다. Task 1의 Kotlin 단위테스트는 손수 만든 v2 버퍼를 쓰므로 독립적으로 통과한다.

---

## Task 1: 프로토콜 버전 + 모델 + 디코더 (Kotlin, TDD)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeProtocol.kt:5`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt:118-122`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt:233-241`
- Test: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

- [ ] **Step 1: 기존 hatch 테스트를 v2 포맷으로 갱신 + 신규 패턴 채움 테스트 작성 (RED)**

`NativeDecoderTest.kt`의 기존 `decode_hatch_twoSolidPaths` 테스트(라인 366~392)를 아래로 **교체**한다. 또한 `buildBuffer` 등 헬퍼는 그대로 사용한다. 버전 상수는 Step 2에서 2로 올리므로, 테스트 버퍼는 `NativeProtocol.VERSION`을 참조해 자동 일치한다.

```kotlin
    private fun hatchEntity(
        layerIdx: Int,
        isSolid: Boolean,
        patternFallback: Boolean,
        minLineSpacing: Double,
        paths: List<List<Pair<Double, Double>>>,
        fillSegments: List<DoubleArray>  // 각 원소 = [x1,y1,x2,y2]
    ): ByteArray {
        val cap = 1 + 4 + 2 + 4 +          // entity header
            1 + 1 + 8 + 4 +               // isSolid, fallback, spacing, num_paths
            paths.sumOf { 4 + it.size * 16 } +
            4 + fillSegments.size * 32
        val b = ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_HATCH.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        b.put(if (isSolid) 1 else 0)
        b.put(if (patternFallback) 1 else 0)
        b.putDouble(minLineSpacing)
        b.putInt(paths.size)
        paths.forEach { p ->
            b.putInt(p.size)
            p.forEach { (x, y) -> b.putDouble(x); b.putDouble(y) }
        }
        b.putInt(fillSegments.size)
        fillSegments.forEach { s ->
            b.putDouble(s[0]); b.putDouble(s[1]); b.putDouble(s[2]); b.putDouble(s[3])
        }
        val out = ByteArray(b.position())
        System.arraycopy(b.array(), 0, out, 0, out.size)
        return out
    }

    @Test
    fun decode_hatch_solid_twoPaths() {
        val bytes = buildBuffer(entities = listOf(hatchEntity(
            layerIdx = -1, isSolid = true, patternFallback = false, minLineSpacing = 0.0,
            paths = listOf(
                listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0),
                listOf(2.0 to 2.0, 3.0 to 2.0, 2.5 to 3.0)
            ),
            fillSegments = emptyList()
        )))
        val h = NativeDecoder.decode(bytes).entities[0]
            as io.github.june690602_blip.cleancad.model.DxfHatch
        assertTrue(h.isSolid)
        assertFalse(h.patternFallback)
        assertEquals(2, h.paths.size)
        assertEquals(4, h.paths[0].size)
        assertEquals(3, h.paths[1].size)
        assertEquals(0, h.fillLines.size)
    }

    @Test
    fun decode_hatch_patternFillLines() {
        val bytes = buildBuffer(entities = listOf(hatchEntity(
            layerIdx = -1, isSolid = false, patternFallback = false, minLineSpacing = 2.5,
            paths = listOf(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)),
            fillSegments = listOf(
                doubleArrayOf(0.0, 1.0, 10.0, 1.0),
                doubleArrayOf(0.0, 3.5, 10.0, 3.5)
            )
        )))
        val h = NativeDecoder.decode(bytes).entities[0]
            as io.github.june690602_blip.cleancad.model.DxfHatch
        assertFalse(h.isSolid)
        assertEquals(2.5, h.minLineSpacing, 1e-9)
        // fillLines: 2 세그먼트 = 4 Vec2 (인접 쌍이 한 세그먼트)
        assertEquals(4, h.fillLines.size)
        assertEquals(0.0, h.fillLines[0].x, 1e-9)
        assertEquals(1.0, h.fillLines[0].y, 1e-9)
        assertEquals(10.0, h.fillLines[1].x, 1e-9)
        assertEquals(3.5, h.fillLines[2].y, 1e-9)
    }

    @Test
    fun decode_hatch_patternFallback() {
        val bytes = buildBuffer(entities = listOf(hatchEntity(
            layerIdx = -1, isSolid = false, patternFallback = true, minLineSpacing = 0.0,
            paths = listOf(listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)),
            fillSegments = emptyList()
        )))
        val h = NativeDecoder.decode(bytes).entities[0]
            as io.github.june690602_blip.cleancad.model.DxfHatch
        assertTrue(h.patternFallback)
        assertEquals(0, h.fillLines.size)
    }
```

- [ ] **Step 2: 테스트 실행해 컴파일 실패/RED 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*NativeDecoderTest*"`
Expected: 컴파일 실패 — `DxfHatch`에 `patternFallback`/`minLineSpacing`/`fillLines` 없음, `hatchEntity`가 참조하는 필드 미존재.

- [ ] **Step 3: VERSION 상수 올리기**

`NativeProtocol.kt` 라인 5를 수정:

```kotlin
    const val VERSION: Int = 2
```

- [ ] **Step 4: DxfHatch 모델 확장**

`DxfEntity.kt` 라인 118~122를 교체:

```kotlin
data class DxfHatch(
    override val layer: String,
    val isSolid: Boolean,
    val patternFallback: Boolean,
    val minLineSpacing: Double,
    val paths: List<List<Vec2>>,
    val fillLines: List<Vec2>
) : DxfEntity()
```

- [ ] **Step 5: decodeHatch 확장**

`NativeDecoder.kt` 라인 233~241을 교체:

```kotlin
    private fun decodeHatch(buf: ByteBuffer, layer: String): DxfHatch {
        val isSolid = buf.get() != 0.toByte()
        val patternFallback = buf.get() != 0.toByte()
        val minLineSpacing = buf.double
        val numPaths = buf.int
        val paths = List(numPaths) {
            val n = buf.int
            List(n) { Vec2(buf.double, buf.double) }
        }
        val numFill = buf.int
        val fillLines = ArrayList<Vec2>(numFill * 2)
        repeat(numFill) {
            fillLines.add(Vec2(buf.double, buf.double))
            fillLines.add(Vec2(buf.double, buf.double))
        }
        return DxfHatch(layer, isSolid, patternFallback, minLineSpacing, paths, fillLines)
    }
```

- [ ] **Step 6: 테스트 실행해 GREEN 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*NativeDecoderTest*"`
Expected: PASS (기존 + 신규 hatch 테스트 모두 통과). 전체 단위테스트 개수는 기존 +2 (solid 1개 교체, pattern/fallback 2개 추가).

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/native/NativeProtocol.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt
git commit -m "feat: extend HATCH protocol v2 with pattern fill lines (decoder)"
```

---

## Task 2: Native — 공통 헬퍼 + 경계 루프 빌더 (호/bulge 테셀레이션)

**Files:**
- Modify: `app/src/main/cpp/dwg_serialize.h:9`
- Modify: `app/src/main/cpp/dwg_serialize.c` (라인 10 근처 상수, 라인 647 `write_hatch` 직전에 헬퍼 추가)

- [ ] **Step 1: 프로토콜 버전 + 상수 추가**

`dwg_serialize.h` 라인 9 수정:

```c
#define DWGB_PROTOCOL_VERSION 2
```

`dwg_serialize.c` 라인 10(`#define DWGB_MAX_ENTITIES 500000`) 아래에 추가:

```c
#define DWGB_PI 3.14159265358979323846
#define HATCH_ARC_MIN_SEGS 8
#define HATCH_ARC_MAX_SEGS 64
#define HATCH_MAX_FILL_SEGMENTS 4000     /* hatch당 채움 세그먼트 상한 → 초과 시 솔리드 폴백 */
#define HATCH_MAX_FAMILY_LINES 20000     /* defline당 평행선 상한 → 초과 시 솔리드 폴백 */
```

- [ ] **Step 2: DBuf + 테셀레이션 헬퍼 추가 (`write_hatch` 직전, 현재 라인 647 `/* ---- 9a-5: HATCH ---- */` 위)**

```c
/* ---- HATCH 패턴 채움용 동적 double 버퍼 + 헬퍼 ---- */

typedef struct { double *v; int n; int cap; } DBuf;

static void dbuf_init(DBuf *b) { b->v = NULL; b->n = 0; b->cap = 0; }
static void dbuf_free(DBuf *b) { free(b->v); b->v = NULL; b->n = 0; b->cap = 0; }
static int  dbuf_push(DBuf *b, double x) {
    if (b->n >= b->cap) {
        int nc = b->cap ? b->cap * 2 : 64;
        double *nv = (double *)realloc(b->v, (size_t)nc * sizeof(double));
        if (!nv) return 0;
        b->v = nv; b->cap = nc;
    }
    b->v[b->n++] = x; return 1;
}
static void dbuf_push_xform(DBuf *b, double x, double y,
                            double sx, double sy, double rot, double tx, double ty) {
    affine_point(&x, &y, sx, sy, rot, tx, ty);
    dbuf_push(b, x); dbuf_push(b, y);
}

/* 원호를 짧은 선분으로 분할해 점들을 push (시작점 제외, 끝점 포함). 로컬좌표로 샘플 후 변환. */
static void emit_arc(DBuf *b, double cx, double cy, double r,
                     double a0, double a1, int ccw,
                     double sx, double sy, double rot, double tx, double ty) {
    double sweep = a1 - a0;
    if (ccw)  { while (sweep < 0) sweep += 2.0 * DWGB_PI; }
    else      { while (sweep > 0) sweep -= 2.0 * DWGB_PI; }
    double aSweep = fabs(sweep);
    int segs = (int)ceil(aSweep / (DWGB_PI / 16.0));   /* ~11.25° 간격 */
    if (segs < HATCH_ARC_MIN_SEGS) segs = HATCH_ARC_MIN_SEGS;
    if (segs > HATCH_ARC_MAX_SEGS) segs = HATCH_ARC_MAX_SEGS;
    for (int i = 1; i <= segs; ++i) {
        double a = a0 + sweep * ((double)i / (double)segs);
        dbuf_push_xform(b, cx + r * cos(a), cy + r * sin(a), sx, sy, rot, tx, ty);
    }
}

/* polyline bulge 구간을 원호로 분할해 push (시작점 제외, 끝점 포함). */
static void emit_bulge(DBuf *b, double x0, double y0, double x1, double y1, double bulge,
                       double sx, double sy, double rot, double tx, double ty) {
    if (fabs(bulge) < 1e-9) { dbuf_push_xform(b, x1, y1, sx, sy, rot, tx, ty); return; }
    double theta = 4.0 * atan(bulge);
    double half = theta / 2.0, s = sin(half);
    if (fabs(s) < 1e-9) { dbuf_push_xform(b, x1, y1, sx, sy, rot, tx, ty); return; }
    double cot = cos(half) / s;
    double cx = (x0 + x1) / 2.0 - (y1 - y0) / 2.0 * cot;
    double cy = (y0 + y1) / 2.0 + (x1 - x0) / 2.0 * cot;
    double r  = sqrt((x0 - cx) * (x0 - cx) + (y0 - cy) * (y0 - cy));
    double a0 = atan2(y0 - cy, x0 - cx);
    double a1 = atan2(y1 - cy, x1 - cx);
    emit_arc(b, cx, cy, r, a0, a1, (bulge > 0) ? 1 : 0, sx, sy, rot, tx, ty);
}

/* 경계 path 하나를 변환된 월드 점열(out)로 빌드. 타원호/스플라인(curve_type 3,4)을
 * 만나면 코드(chord)로 근사하고 *unsupported=1 (호출자가 patternFallback 처리). */
static void build_one_loop(DBuf *out, Dwg_HATCH_Path *path, int *unsupported,
                           double sx, double sy, double rot, double tx, double ty) {
    if (path->flag & 2) {                       /* polyline path */
        BITCODE_BL nv = path->num_segs_or_paths;
        if (nv == 0 || !path->polyline_paths) return;
        dbuf_push_xform(out, path->polyline_paths[0].point.x,
                             path->polyline_paths[0].point.y, sx, sy, rot, tx, ty);
        BITCODE_BL lim = path->closed ? nv : (nv > 0 ? nv - 1 : 0);
        for (BITCODE_BL i = 0; i < lim; ++i) {
            BITCODE_BL j = (i + 1) % nv;
            double ax = path->polyline_paths[i].point.x, ay = path->polyline_paths[i].point.y;
            double bx = path->polyline_paths[j].point.x, by = path->polyline_paths[j].point.y;
            double bl = path->bulges_present ? path->polyline_paths[i].bulge : 0.0;
            if (fabs(bl) > 1e-9) emit_bulge(out, ax, ay, bx, by, bl, sx, sy, rot, tx, ty);
            else                 dbuf_push_xform(out, bx, by, sx, sy, rot, tx, ty);
        }
    } else {                                     /* segment path */
        if (!path->segs) return;
        for (BITCODE_BL s = 0; s < path->num_segs_or_paths; ++s) {
            Dwg_HATCH_PathSeg *sg = &path->segs[s];
            if (sg->curve_type == 1) {           /* LINE */
                if (s == 0)
                    dbuf_push_xform(out, sg->first_endpoint.x, sg->first_endpoint.y,
                                    sx, sy, rot, tx, ty);
                dbuf_push_xform(out, sg->second_endpoint.x, sg->second_endpoint.y,
                                sx, sy, rot, tx, ty);
            } else if (sg->curve_type == 2) {    /* CIRCULAR ARC (각도 단위=라디안 가정) */
                if (s == 0)
                    dbuf_push_xform(out, sg->center.x + sg->radius * cos(sg->start_angle),
                                         sg->center.y + sg->radius * sin(sg->start_angle),
                                    sx, sy, rot, tx, ty);
                emit_arc(out, sg->center.x, sg->center.y, sg->radius,
                         sg->start_angle, sg->end_angle, sg->is_ccw ? 1 : 0,
                         sx, sy, rot, tx, ty);
            } else {                              /* ELLIPTICAL ARC / SPLINE → chord 근사 */
                *unsupported = 1;
                if (s == 0)
                    dbuf_push_xform(out, sg->first_endpoint.x, sg->first_endpoint.y,
                                    sx, sy, rot, tx, ty);
                dbuf_push_xform(out, sg->second_endpoint.x, sg->second_endpoint.y,
                                sx, sy, rot, tx, ty);
            }
        }
    }
}
```

- [ ] **Step 3: NDK 빌드로 컴파일 확인**

Run: `./gradlew :app:compileDebugSources` 또는 `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 errors. (헬퍼는 아직 미사용 — `write_hatch` 재작성은 Task 3. 미사용 static 함수 경고가 날 수 있으나 에러 아님.)

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.h app/src/main/cpp/dwg_serialize.c
git commit -m "feat: add HATCH boundary tessellation helpers (arc/bulge) in native"
```

---

## Task 3: Native — 패턴 채움 스캔라인 생성 + write_hatch 재작성

**Files:**
- Modify: `app/src/main/cpp/dwg_serialize.c` (Task 2 헬퍼 직후 fill 헬퍼 추가, `write_hatch` 라인 649~709 교체)

- [ ] **Step 1: uv 세그먼트 emit + 스캔라인 채움 헬퍼 추가 (Task 2 헬퍼 뒤, `write_hatch` 앞)**

알고리즘: 각 def-line을 월드로 변환(pt0, 단위방향, offset → affine로 블록변환 흡수)한 뒤,
방향=u축·수직=v축인 좌표계로 회전해 평행선을 수평선(v=k·off_v)으로 단순화한다. 각 수평선과
경계 에지의 u-교차점을 even-odd로 페어링해 내부 구간을 구하고, dash 패턴(있으면)을 위상
`k·off_u`에 맞춰 배치한다.

```c
/* uv 좌표 [uA,uB]@vk 구간을 월드 세그먼트(x1,y1,x2,y2)로 fill에 push. */
static int emit_uv_seg(DBuf *fill, double p0x, double p0y,
                       double dirx, double diry, double perpx, double perpy,
                       double uA, double uB, double vk) {
    double ax = p0x + uA * dirx + vk * perpx, ay = p0y + uA * diry + vk * perpy;
    double bx = p0x + uB * dirx + vk * perpx, by = p0y + uB * diry + vk * perpy;
    if (!dbuf_push(fill, ax)) return 0; if (!dbuf_push(fill, ay)) return 0;
    if (!dbuf_push(fill, bx)) return 0; if (!dbuf_push(fill, by)) return 0;
    return 1;
}

/* 한 def-line의 평행선 패밀리를 경계로 클립해 fill에 push. perp 간격을 min_spacing에 반영.
 * 반환: 1 정상, 0 폭발(밀도/라인수 초과 → 호출자가 폴백 처리). */
static int emit_defline_fill(DBuf *fill, Dwg_HATCH_DefLine *dl,
                             double **loop_pts, int *loop_np, int nloops,
                             double sx, double sy, double rot, double tx, double ty,
                             double *min_spacing) {
    double p0x = dl->pt0.x, p0y = dl->pt0.y;
    double ux = dl->pt0.x + cos(dl->angle), uy = dl->pt0.y + sin(dl->angle);
    double ox = dl->pt0.x + dl->offset.x,   oy = dl->pt0.y + dl->offset.y;
    affine_point(&p0x, &p0y, sx, sy, rot, tx, ty);
    affine_point(&ux, &uy, sx, sy, rot, tx, ty);
    affine_point(&ox, &oy, sx, sy, rot, tx, ty);
    double dirx = ux - p0x, diry = uy - p0y;
    double along = sqrt(dirx * dirx + diry * diry);
    if (along < 1e-12) return 1;                 /* 퇴화 → 이 defline 무시 */
    dirx /= along; diry /= along;
    double perpx = -diry, perpy = dirx;
    double offx = ox - p0x, offy = oy - p0y;
    double off_u = offx * dirx + offy * diry;
    double off_v = offx * perpx + offy * perpy;
    if (fabs(off_v) < 1e-9) return 1;            /* 평행선 겹침 → 무시 */
    if (fabs(off_v) < *min_spacing) *min_spacing = fabs(off_v);

    double vmin = 1e18, vmax = -1e18;
    for (int L = 0; L < nloops; ++L) {
        double *pp = loop_pts[L]; int np = loop_np[L];
        for (int i = 0; i < np; ++i) {
            double X = pp[2*i] - p0x, Y = pp[2*i+1] - p0y;
            double v = X * perpx + Y * perpy;
            if (v < vmin) vmin = v; if (v > vmax) vmax = v;
        }
    }
    if (vmin > vmax) return 1;
    double klo_d = vmin / off_v, khi_d = vmax / off_v;
    long klo = (long)floor(fmin(klo_d, khi_d)) - 1;
    long khi = (long)ceil (fmax(klo_d, khi_d)) + 1;
    if (khi - klo > HATCH_MAX_FAMILY_LINES) return 0;

    double total = 0.0;
    for (BITCODE_BS i = 0; i < dl->num_dashes; ++i) total += fabs(dl->dashes[i]) * along;

    for (long k = klo; k <= khi; ++k) {
        double vk = (double)k * off_v;
        double cross[512]; int nc = 0;
        for (int L = 0; L < nloops; ++L) {
            double *pp = loop_pts[L]; int np = loop_np[L];
            for (int i = 0; i < np; ++i) {
                int j = (i + 1) % np;
                double X1 = pp[2*i]   - p0x, Y1 = pp[2*i+1] - p0y;
                double X2 = pp[2*j]   - p0x, Y2 = pp[2*j+1] - p0y;
                double v1 = X1 * perpx + Y1 * perpy;
                double v2 = X2 * perpx + Y2 * perpy;
                if ((v1 <= vk && vk < v2) || (v2 <= vk && vk < v1)) {
                    double t = (vk - v1) / (v2 - v1);
                    double u1 = X1 * dirx + Y1 * diry;
                    double u2 = X2 * dirx + Y2 * diry;
                    if (nc < 512) cross[nc++] = u1 + t * (u2 - u1);
                }
            }
        }
        if (nc < 2) continue;
        for (int a = 1; a < nc; ++a) {           /* insertion sort */
            double key = cross[a]; int bb = a - 1;
            while (bb >= 0 && cross[bb] > key) { cross[bb+1] = cross[bb]; bb--; }
            cross[bb+1] = key;
        }
        double phase = (double)k * off_u;
        for (int pi = 0; pi + 1 < nc; pi += 2) {
            double uA = cross[pi], uB = cross[pi+1];
            if (uB - uA < 1e-9) continue;
            if (dl->num_dashes <= 0 || total <= 1e-9) {
                if (!emit_uv_seg(fill, p0x, p0y, dirx, diry, perpx, perpy, uA, uB, vk)) return 0;
            } else {
                double m = floor((uA - phase) / total);
                double cur = phase + m * total;
                int guard = 0;
                while (cur < uB && guard++ < 200000) {
                    for (BITCODE_BS i = 0; i < dl->num_dashes; ++i) {
                        double len = fabs(dl->dashes[i]) * along;
                        double segEnd = cur + len;
                        if (dl->dashes[i] > 0) {
                            double a = fmax(cur, uA), b = fmin(segEnd, uB);
                            if (b > a && !emit_uv_seg(fill, p0x, p0y, dirx, diry,
                                                      perpx, perpy, a, b, vk)) return 0;
                        }
                        cur = segEnd;
                        if (dl->dashes[i] == 0) cur += 1e-6;   /* 0(dot) 무한루프 방지 */
                        if (cur >= uB) break;
                    }
                }
            }
            if (fill->n / 4 > HATCH_MAX_FILL_SEGMENTS) return 0;
        }
    }
    return 1;
}
```

- [ ] **Step 2: write_hatch 재작성 (현재 라인 649~709 전체 교체)**

```c
static void write_hatch(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_HATCH *e = obj->tio.entity->tio.HATCH;
    write_entity_header(w, dwg, obj, DWGB_TYPE_HATCH);
    uint8_t isSolid = e->is_solid_fill ? 1 : 0;

    int nloops = (int)e->num_paths;
    if (nloops < 0) nloops = 0;
    double **loop_pts = NULL;
    int     *loop_np  = NULL;
    int      unsupported = 0;
    if (nloops > 0 && e->paths) {
        loop_pts = (double **)calloc((size_t)nloops, sizeof(double *));
        loop_np  = (int *)    calloc((size_t)nloops, sizeof(int));
        if (!loop_pts || !loop_np) {
            free(loop_pts); free(loop_np);
            w_u8(w, isSolid); w_u8(w, 0); w_f64(w, 0.0); w_i32(w, 0); w_i32(w, 0);
            return;
        }
        for (int L = 0; L < nloops; ++L) {
            DBuf lb; dbuf_init(&lb);
            build_one_loop(&lb, &e->paths[L], &unsupported, sx, sy, rot, tx, ty);
            loop_pts[L] = lb.v; loop_np[L] = lb.n / 2;
        }
    }

    DBuf fill; dbuf_init(&fill);
    uint8_t patternFallback = 0;
    double  min_spacing = 1e18;
    if (!isSolid && nloops > 0 && e->num_deflines > 0) {
        if (unsupported) {
            patternFallback = 1;                 /* 곡선 경계 → 솔리드 폴백 */
        } else {
            int ok = 1;
            for (BITCODE_BS d = 0; d < e->num_deflines && ok; ++d)
                ok = emit_defline_fill(&fill, &e->deflines[d], loop_pts, loop_np, nloops,
                                       sx, sy, rot, tx, ty, &min_spacing);
            if (!ok) { dbuf_free(&fill); dbuf_init(&fill); patternFallback = 1; }
        }
    }
    double out_spacing = (patternFallback || min_spacing >= 1e18) ? 0.0 : min_spacing;

    w_u8(w, isSolid);
    w_u8(w, patternFallback);
    w_f64(w, out_spacing);
    w_i32(w, nloops);
    for (int L = 0; L < nloops; ++L) {
        int nv = loop_np ? loop_np[L] : 0;
        w_i32(w, nv);
        for (int i = 0; i < nv; ++i) { w_f64(w, loop_pts[L][2*i]); w_f64(w, loop_pts[L][2*i+1]); }
    }
    int nfill = fill.n / 4;
    w_i32(w, nfill);
    for (int i = 0; i < nfill; ++i) {
        w_f64(w, fill.v[4*i]);   w_f64(w, fill.v[4*i+1]);
        w_f64(w, fill.v[4*i+2]); w_f64(w, fill.v[4*i+3]);
    }

    dbuf_free(&fill);
    if (loop_pts) { for (int L = 0; L < nloops; ++L) free(loop_pts[L]); free(loop_pts); }
    free(loop_np);
}
```

- [ ] **Step 3: NDK 빌드 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 errors.

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.c
git commit -m "feat: generate HATCH pattern fill lines via scanline clip in native"
```

---

## Task 4: 렌더러 — 줌 인지 게이트 + 패턴 라인 그리기

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt` (companion 상수 라인 248 근처, `drawHatch` 라인 362~384)

- [ ] **Step 1: 패턴 라인 최소 화면 간격 상수 추가**

`EntityRenderer.kt`의 companion object에서 `HATCH_FILL_ALPHA_MASK`(라인 248) 정의 **아래**에 추가:

```kotlin
        /** 패턴 채움 라인 최소 화면 간격(px). 이 미만이면 라인 대신 반투명 솔리드(조밀 폴백). */
        const val HATCH_MIN_PATTERN_SPACING_PX: Double = 3.0
```

- [ ] **Step 2: drawHatch 교체 (라인 362~384)**

```kotlin
    private fun drawHatch(e: DxfHatch, canvas: Canvas, matrix: Matrix) {
        if (e.paths.isEmpty()) return
        val path = Path()
        for (boundary in e.paths) {
            if (boundary.size < 2) continue
            val first = CoordTransform.worldToScreen(boundary[0], matrix)
            path.moveTo(first.x, first.y)
            for (i in 1 until boundary.size) {
                val pt = CoordTransform.worldToScreen(boundary[i], matrix)
                path.lineTo(pt.x, pt.y)
            }
            path.close()
        }
        val screenSpacing = e.minLineSpacing * CoordTransform.currentScale(matrix)
        val showPattern = !e.isSolid && !e.patternFallback &&
            e.fillLines.isNotEmpty() && screenSpacing >= HATCH_MIN_PATTERN_SPACING_PX
        when {
            showPattern -> drawHatchFillLines(e, canvas, matrix)
            e.isSolid || e.patternFallback || e.fillLines.isNotEmpty() -> {
                // 솔리드 hatch, 폴백, 또는 이 줌에선 패턴이 너무 조밀 → 반투명 솔리드
                fillPaint.color = (linePaint.color and 0x00FFFFFF) or HATCH_FILL_ALPHA_MASK
                canvas.drawPath(path, fillPaint)
            }
            // else: 비솔리드 + 패턴 정보 없음 → 채움 없이 경계만
        }
        // 경계는 항상 그림 (솔리드든 패턴이든 boundary 시각화)
        canvas.drawPath(path, linePaint)
    }

    /** 패턴 채움 라인(월드 세그먼트)을 스크린으로 변환해 한 번에 drawLines. */
    private fun drawHatchFillLines(e: DxfHatch, canvas: Canvas, matrix: Matrix) {
        val pts = e.fillLines
        val arr = FloatArray(pts.size * 2)
        var j = 0
        for (p in pts) {
            val s = CoordTransform.worldToScreen(p, matrix)
            arr[j++] = s.x; arr[j++] = s.y
        }
        canvas.drawLines(arr, linePaint)
    }
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 errors.

- [ ] **Step 4: 전체 단위테스트 재확인 (회귀 없음)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 모든 단위테스트 PASS (Task 1 신규 포함).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt
git commit -m "feat: zoom-aware HATCH pattern line rendering with solid fallback"
```

---

## Task 5: 통합 검증 + 좌표계 캘리브레이션 (에뮬/실기)

**Files:** 없음(검증). 필요 시 Task 2/3 코드 조정.

> ⚠️ **에뮬레이터 함정 (CLAUDE.md):** native `.so` 변경 후 `adb install -r`로는 갱신 안 됨.
> **반드시 `adb uninstall io.github.june690602_blip.cleancad` 후 재설치.**

- [ ] **Step 1: 빌드 + 클린 재설치**

```bash
./gradlew :app:assembleDebug
adb uninstall io.github.june690602_blip.cleancad
adb install app/build/outputs/apk/debug/app-debug.apk
```
Expected: Success.

- [ ] **Step 2: 실제 DWG 로드 + 엔티티 개수 패리티 확인**

`04_참고도면.dwg`(또는 저장소의 `ref.dwg`)를 앱으로 연 뒤:

```bash
adb logcat -d | grep -E "CleanCAD|dwgjni"
```
Expected: 파싱 성공(rc 0), 엔티티 개수가 Phase 10 기준치와 **동일**(HATCH 레코드만 커져 byte 수↑, 개수 불변 → 프로토콜 v2 동기 확인). 버전 불일치/디코드 예외 없음.

- [ ] **Step 3: 좌표계 캘리브레이션 — ANSI31 각도/간격 실측**

ANSI31(45° 대각선) 또는 콘크리트 패턴이 있는 영역으로 줌인. 시각 확인:
- 패턴 라인이 경계 안에서 ~45° 방향으로 균일 간격으로 그려지는가?
- 라인 방향이 90° 틀어졌거나, 간격이 비현실적이거나, 경계 밖으로 새지 않는가?

각도가 틀리면 `dl->angle` 단위(라디안 가정)·`emit_arc`의 호 각도 단위를 의심.
간격이 틀리면 def-line이 로컬 패턴공간일 가능성 → 엔티티 `e->angle`/`e->scale_spacing` 적용 필요.
조정이 필요하면 Task 3 `emit_defline_fill`의 변환부를 수정하고 Step 1~2 재실행.

진단용 임시 로그(필요 시 `dwg_serialize.c`에 `#include <android/log.h>` 후
`__android_log_print(ANDROID_LOG_INFO,"dwgjni","defline angle=%f off=(%f,%f) ndash=%d",
dl->angle, dl->offset.x, dl->offset.y, dl->num_dashes)` 삽입 → 확인 후 제거).

- [ ] **Step 4: 줌 게이트 동작 확인**

- fit(전체 보기): 패턴 영역이 반투명 솔리드로 보임(라인 폭주 없음, 팬/줌 부드러움).
- 작업 줌(줌인): 같은 영역이 실제 패턴 라인으로 전환.
Expected: 전환이 매끄럽고, fit에서 Phase 10 수준 프레임 유지(렉 없음).

- [ ] **Step 5: 곡선 경계/밀도 폴백 확인**

원형/곡선 경계 hatch가 있으면(예: 기둥 단면) → 패턴 대신 반투명 솔리드로 표시되고 누출 없음.
매우 크고 조밀한 hatch → 폭주 없이 솔리드 폴백.
Expected: 크래시·ANR·검정 떡덩어리 없음.

- [ ] **Step 6: CLAUDE.md 상태 갱신 + 핸드오프 노트**

`CLAUDE.md`의 "진행 중 ⏳ 이슈 1: HATCH 패턴 미지원"을 "작동 중 ✅"로 이동, Phase 8.6 완료 요약 추가(근본원인/수정/측정/검증). 핸드오프: `docs/superpowers/handoff/2026-06-09-phase8.6-hatch-pattern.md`.

- [ ] **Step 7: 커밋**

```bash
git add CLAUDE.md docs/superpowers/handoff/2026-06-09-phase8.6-hatch-pattern.md
git commit -m "docs: mark HATCH pattern rendering complete (Phase 8.6)"
```

---

## Self-Review (작성자 체크)

**1. 스펙 커버리지:**
- 실제 패턴 재현(def-line 기반) → Task 3 `emit_defline_fill`. ✓
- Native 확장 → Task 2/3. ✓
- 경계: 라인/폴리라인(+bulge)/원호 → Task 2 `build_one_loop`/`emit_bulge`/`emit_arc`. ✓
- 타원호/스플라인 → chord 근사 + `unsupported`→patternFallback (Task 2/3). ✓
- dash 패턴 → Task 3 dash walk. ✓
- 밀도 캡 + 라인수 캡 → `HATCH_MAX_FILL_SEGMENTS`/`HATCH_MAX_FAMILY_LINES` (Task 3). ✓
- 프로토콜 payload 확장 → Task 1(decoder)+Task 3(serializer), VERSION 2 양측. ✓
- 모델/디코더 → Task 1. ✓
- 줌 게이트 + fill line 그리기 → Task 4. ✓
- minLineSpacing → Task 3 산출, Task 1 디코드, Task 4 게이트. ✓
- 좌표계 캘리브레이션 → Task 5 Step 3. ✓
- 테스트(단위+통합+패리티) → Task 1, Task 5. ✓
- gradient/MPOLYGON 제외 → write_hatch는 HATCH만 처리(기존 dispatch 불변). ✓

**2. 플레이스홀더 스캔:** TBD/임의 생략 없음. 모든 스텝에 실제 코드/명령/기대출력 포함. 캘리브레이션 조정은 의도된 경험적 검증 스텝(가정=라디안/already-resolved 명시). ✓

**3. 타입 일관성:**
- `DxfHatch(layer, isSolid, patternFallback, minLineSpacing, paths, fillLines)` — 모델(Task1 S4)·디코더(Task1 S5)·렌더러(Task4) 동일 필드명. ✓
- payload 순서 isSolid→patternFallback→minLineSpacing(f64)→paths→fill — native write(Task3)와 kotlin read(Task1) 동일. ✓
- `EntityBounds.kt`의 `DxfHatch`는 `paths`만 읽으므로(flatten) 변경 불필요 — 생성자는 디코더 1곳만. ✓
- `currentScale`/`worldToScreen` 시그니처 일치(CoordTransform). ✓
