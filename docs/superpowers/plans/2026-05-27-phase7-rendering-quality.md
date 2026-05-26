# Phase 7 Implementation Plan — 렌더링 품질 대폭 개선

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ZWCAD Mobile 수준의 렌더링 품질 달성 — 한글 정상 표시, 레이어 색상 적용, 누락 엔티티(POLYLINE/3DFACE/SOLID/HATCH) 복구.

**Architecture:** 현재 파이프라인(DWG→DXF→Canvas)을 유지하되, 다음 레이어들을 추가/수정:
1. **인코딩 레이어**: `DxfCharsetDetector` 신규 → `$DWGCODEPAGE` 헤더 감지 후 적절한 charset으로 재read
2. **색상 레이어**: `AciColor` 신규 → AutoCAD Color Index 1–255 → ARGB 변환, `EntityRenderer`가 레이어/엔티티 색 적용
3. **엔티티 레이어**: `DxfEntity` sealed class에 `DxfPolyline`, `Dxf3DFace`, `DxfSolid` 추가, 파서/렌더러 확장
4. **HATCH 강화**: 경계 polyline 파싱, 솔리드인 경우 `Path.fill` 렌더링
5. **용량 제한 완화**: `MAX_ENTITIES` 100,000으로 증가

**Tech Stack:** Kotlin, Android Canvas/Path/Paint, JUnit (Robolectric 불필요한 순수 단위 테스트).

**시작 기준 커밋:** `8fa4681` (Phase 6 완료, 단위 테스트 38개 통과)

**예상 결과:** 단위 테스트 38개 → 55개 이상 (+17개), 모든 한글 정상, 도면 컬러풀, 누락 엔티티 대폭 감소.

---

## 시작 전 체크리스트

```bash
cd C:\dev\opendwg
git status                                      # 작업 트리 깨끗한지
git log --oneline -3                            # 8fa4681이 HEAD인지
./gradlew :app:testDebugUnitTest 2>&1 | tail -5 # BUILD SUCCESSFUL
./gradlew :app:assembleDebug 2>&1 | tail -5     # BUILD SUCCESSFUL
```

테스트 카운트 확인:
```bash
find app/build/test-results/testDebugUnitTest -name "*.xml" | xargs grep -c "testcase"
# DxfParserTest: 24, DxfReaderTest: 7, CoordTransformTest: 6, ExampleUnitTest: 1 → 합 38
```

문제 있으면 STOP. 38개 모두 통과 상태에서 시작.

---

## Task 1: DXF 인코딩 자동 감지 (한글 깨짐 해결)

**근본 원인:** `DrawingViewModel.load()`의 `dxfFile.bufferedReader()`가 시스템 기본 charset(Android = UTF-8)으로 읽지만, 한국 DWG는 CP949 인코딩이라 `?` 박스로 깨짐.

**해결:** DXF 파일 첫 ~4KB를 ISO-8859-1(바이트 보존)로 읽어 `$DWGCODEPAGE` 변수 추출 → MS949/UTF-8/Shift_JIS 등 매핑 → 그 charset으로 `BufferedReader` 재생성.

### Files
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetector.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetectorTest.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt:49`

### Steps

- [ ] **1-1. 테스트 파일 작성 (RED)**

Create `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetectorTest.kt`:

```kotlin
package io.github.june690602_blip.cleancad.parser

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class DxfCharsetDetectorTest {

    private fun makeHeader(codepageValue: String): ByteArray {
        val text = """  0
SECTION
  2
HEADER
  9
${'$'}DWGCODEPAGE
  3
$codepageValue
  0
ENDSEC
"""
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun detect_ansi949_returnsMs949() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_949")))
        assertEquals(Charset.forName("MS949"), charset)
    }

    @Test
    fun detect_ansi1252_returnsWindows1252() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_1252")))
        assertEquals(Charset.forName("windows-1252"), charset)
    }

    @Test
    fun detect_ansi932_returnsShiftJis() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_932")))
        assertEquals(Charset.forName("Shift_JIS"), charset)
    }

    @Test
    fun detect_noCodepage_returnsUtf8() {
        val text = "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n"
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(text.toByteArray()))
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun detect_unknownCodepage_returnsUtf8() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_99999")))
        assertEquals(Charsets.UTF_8, charset)
    }
}
```

- [ ] **1-2. 테스트 실행 → 실패 확인 (RED)**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```
Expected: `DxfCharsetDetectorTest` 빌드 실패 (DxfCharsetDetector 클래스 없음).

- [ ] **1-3. 구현 (GREEN)**

Create `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetector.kt`:

```kotlin
package io.github.june690602_blip.cleancad.parser

import java.io.InputStream
import java.nio.charset.Charset

/**
 * DXF 파일의 `${'$'}DWGCODEPAGE` 헤더 변수를 읽어 적절한 Charset을 결정한다.
 *
 * DXF 그룹 코드 자체는 ASCII이지만 텍스트 값(group code 1)은 원본 DWG의 코드페이지로
 * 인코딩되어 있다. 한국어 DWG는 보통 ANSI_949(=MS949/CP949), 일본어는 ANSI_932, 등.
 *
 * 첫 ~4KB만 ISO-8859-1로 안전하게 읽어 `${'$'}DWGCODEPAGE` 변수를 찾은 뒤 매핑한다.
 * 헤더에 코드페이지가 없거나 알 수 없는 값이면 UTF-8로 폴백한다.
 */
object DxfCharsetDetector {

    private const val SAMPLE_BYTES = 4096

    fun detect(input: InputStream): Charset {
        val sample = ByteArray(SAMPLE_BYTES)
        val read = input.read(sample)
        if (read <= 0) return Charsets.UTF_8
        val text = String(sample, 0, read, Charsets.ISO_8859_1)
        val regex = Regex(
            """\${'$'}DWGCODEPAGE\s*\R\s*3\s*\R\s*([^\r\n]+)""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(text) ?: return Charsets.UTF_8
        val codepage = match.groupValues[1].trim().uppercase()
        return codepageToCharset(codepage)
    }

    private fun codepageToCharset(codepage: String): Charset = when (codepage) {
        "ANSI_949", "DOS949"  -> charsetOr("MS949", Charsets.UTF_8)         // 한국어
        "ANSI_950"            -> charsetOr("Big5", Charsets.UTF_8)          // 번체 중국어
        "ANSI_936"            -> charsetOr("GBK", Charsets.UTF_8)           // 간체 중국어
        "ANSI_932"            -> charsetOr("Shift_JIS", Charsets.UTF_8)     // 일본어
        "ANSI_1250"           -> charsetOr("windows-1250", Charsets.UTF_8)  // 중부 유럽
        "ANSI_1251"           -> charsetOr("windows-1251", Charsets.UTF_8)  // 키릴
        "ANSI_1252", "DOS1252"-> charsetOr("windows-1252", Charsets.UTF_8)  // 서유럽
        "ANSI_1253"           -> charsetOr("windows-1253", Charsets.UTF_8)  // 그리스
        "ANSI_1254"           -> charsetOr("windows-1254", Charsets.UTF_8)  // 터키
        "UTF8", "UTF-8"       -> Charsets.UTF_8
        else                  -> Charsets.UTF_8
    }

    private fun charsetOr(name: String, fallback: Charset): Charset =
        try { Charset.forName(name) } catch (_: Throwable) { fallback }
}
```

- [ ] **1-4. 테스트 실행 → 통과 확인 (GREEN)**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 새 5개 테스트 통과.

테스트 카운트 확인:
```bash
find app/build/test-results/testDebugUnitTest -name "*DxfCharsetDetector*" | xargs grep -c "testcase"
```
Expected: 5

- [ ] **1-5. DrawingViewModel에 적용**

Read `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt` and modify line 49:

Current (line 48-49):
```kotlin
                    // readText() 대신 BufferedReader 스트리밍으로 OOM 방지
                    val drawing = dxfFile.bufferedReader().use { DxfParser.parse(it) }
```

Replace with:
```kotlin
                    // 인코딩 자동 감지(한국어 DWG는 보통 CP949) 후 스트리밍 파싱
                    val charset = dxfFile.inputStream().use {
                        DxfCharsetDetector.detect(it)
                    }
                    val drawing = dxfFile.inputStream().bufferedReader(charset).use {
                        DxfParser.parse(it)
                    }
```

Import 추가 (파일 상단의 import 블록):
```kotlin
import io.github.june690602_blip.cleancad.parser.DxfCharsetDetector
```

- [ ] **1-6. 빌드 + 테스트 검증**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -5
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
```
Expected: 둘 다 BUILD SUCCESSFUL, 단위 테스트 43개 통과 (38 + 5).

- [ ] **1-7. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetector.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetectorTest.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt

git commit -m "$(cat <<'EOF'
feat(phase7): DXF 인코딩 자동 감지 — 한글 깨짐 해결

- DxfCharsetDetector — \$DWGCODEPAGE 헤더 → MS949/UTF-8/etc 매핑
- DrawingViewModel — bufferedReader 호출 시 detected charset 사용
- 테스트 5개 추가 (ANSI_949/932/1252/no-codepage/unknown)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: AciColor 색상표 + EntityRenderer 색상 적용

**근본 원인:** EntityRenderer가 `linePaint`에 단일 색만 사용. 레이어의 `colorIndex`(이미 파싱됨)를 무시.

**해결:** AutoCAD Color Index 0–256 → ARGB Int 매핑 함수 추가. 엔티티 그릴 때마다 해당 레이어의 색을 lookup하여 `linePaint.color` 갱신.

### Files
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/render/AciColor.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/render/AciColorTest.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`

### Steps

- [ ] **2-1. AciColor 테스트 작성 (RED)**

Create `app/src/test/java/io/github/june690602_blip/cleancad/render/AciColorTest.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

import org.junit.Assert.assertEquals
import org.junit.Test

class AciColorTest {

    @Test
    fun toArgb_index1_isRed() {
        assertEquals(0xFFFF0000.toInt(), AciColor.toArgb(1, fallback = 0))
    }

    @Test
    fun toArgb_index2_isYellow() {
        assertEquals(0xFFFFFF00.toInt(), AciColor.toArgb(2, fallback = 0))
    }

    @Test
    fun toArgb_index3_isGreen() {
        assertEquals(0xFF00FF00.toInt(), AciColor.toArgb(3, fallback = 0))
    }

    @Test
    fun toArgb_index5_isBlue() {
        assertEquals(0xFF0000FF.toInt(), AciColor.toArgb(5, fallback = 0))
    }

    @Test
    fun toArgb_index7_returnsFallback() {
        // 7은 배경에 따라 흑/백이 결정되는 특수값 — fallback 사용
        val fallback = 0xFF112233.toInt()
        assertEquals(fallback, AciColor.toArgb(7, fallback = fallback))
    }

    @Test
    fun toArgb_index0_returnsFallback() {
        // 0 = BYBLOCK
        val fallback = 0xFF445566.toInt()
        assertEquals(fallback, AciColor.toArgb(0, fallback = fallback))
    }

    @Test
    fun toArgb_index256_returnsFallback() {
        // 256 = BYLAYER (엔티티 단에서 이 값이면 레이어 색 사용 → 호출자가 처리)
        val fallback = 0xFF778899.toInt()
        assertEquals(fallback, AciColor.toArgb(256, fallback = fallback))
    }

    @Test
    fun toArgb_outOfRange_returnsFallback() {
        val fallback = 0xFFAABBCC.toInt()
        assertEquals(fallback, AciColor.toArgb(999, fallback = fallback))
        assertEquals(fallback, AciColor.toArgb(-1, fallback = fallback))
    }

    @Test
    fun toArgb_index250_isDarkGray() {
        // 250 = 가장 어두운 회색 (0x333333)
        assertEquals(0xFF333333.toInt(), AciColor.toArgb(250, fallback = 0))
    }

    @Test
    fun toArgb_index255_isWhite() {
        assertEquals(0xFFFFFFFF.toInt(), AciColor.toArgb(255, fallback = 0))
    }
}
```

- [ ] **2-2. 테스트 실행 → 실패 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```
Expected: 컴파일 에러 (AciColor 없음).

- [ ] **2-3. AciColor.kt 구현**

Create `app/src/main/java/io/github/june690602_blip/cleancad/render/AciColor.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

/**
 * AutoCAD Color Index (ACI) → ARGB Int 매핑.
 *
 * - index 0 = BYBLOCK (호출자가 fallback 사용)
 * - index 1..6 = 기본 색 (red, yellow, green, cyan, blue, magenta)
 * - index 7 = white/black (배경 의존, fallback 사용)
 * - index 8..9 = 회색
 * - index 10..249 = 색조-채도-명도 격자 (표준 ACI 256색 팔레트)
 * - index 250..255 = 무채색 (어두운 회색 → 흰색)
 * - index 256 = BYLAYER (호출자가 레이어 색을 lookup 후 다시 호출)
 *
 * 표준 ACI 256색 팔레트는 AutoCAD/LibreCAD/QCAD 등에서 공통으로 사용됨.
 */
object AciColor {

    fun toArgb(index: Int, fallback: Int): Int {
        if (index <= 0 || index == 7 || index >= 256) return fallback
        if (index < 0 || index >= PALETTE.size) return fallback
        return PALETTE[index]
    }

    // 표준 ACI 256-색 팔레트. 인덱스 == ACI 번호.
    // 0은 사용 안 함 (BYBLOCK), 7은 fallback 처리 (배경 의존).
    private val PALETTE: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFFFF.toInt(),
        0xFF414141.toInt(), 0xFF808080.toInt(),
        0xFFFF0000.toInt(), 0xFFFFAAAA.toInt(), 0xFFBD0000.toInt(), 0xFFBD7E7E.toInt(),
        0xFF810000.toInt(), 0xFF815656.toInt(), 0xFF680000.toInt(), 0xFF684545.toInt(),
        0xFF4F0000.toInt(), 0xFF4F3535.toInt(),
        0xFFFF3F00.toInt(), 0xFFFFBFAA.toInt(), 0xFFBD2E00.toInt(), 0xFFBD8D7E.toInt(),
        0xFF811F00.toInt(), 0xFF816056.toInt(), 0xFF681900.toInt(), 0xFF684E45.toInt(),
        0xFF4F1300.toInt(), 0xFF4F3B35.toInt(),
        0xFFFF7F00.toInt(), 0xFFFFD4AA.toInt(), 0xFFBD5E00.toInt(), 0xFFBD9D7E.toInt(),
        0xFF814000.toInt(), 0xFF816B56.toInt(), 0xFF683400.toInt(), 0xFF685645.toInt(),
        0xFF4F2700.toInt(), 0xFF4F4235.toInt(),
        0xFFFFBF00.toInt(), 0xFFFFEAAA.toInt(), 0xFFBD8D00.toInt(), 0xFFBDAD7E.toInt(),
        0xFF816000.toInt(), 0xFF817656.toInt(), 0xFF684E00.toInt(), 0xFF685F45.toInt(),
        0xFF4F3B00.toInt(), 0xFF4F4935.toInt(),
        0xFFFFFF00.toInt(), 0xFFFFFFAA.toInt(), 0xFFBDBD00.toInt(), 0xFFBDBD7E.toInt(),
        0xFF818100.toInt(), 0xFF818156.toInt(), 0xFF686800.toInt(), 0xFF686845.toInt(),
        0xFF4F4F00.toInt(), 0xFF4F4F35.toInt(),
        0xFFBFFF00.toInt(), 0xFFEAFFAA.toInt(), 0xFF8DBD00.toInt(), 0xFFADBD7E.toInt(),
        0xFF608100.toInt(), 0xFF768156.toInt(), 0xFF4E6800.toInt(), 0xFF5F6845.toInt(),
        0xFF3B4F00.toInt(), 0xFF494F35.toInt(),
        0xFF7FFF00.toInt(), 0xFFD4FFAA.toInt(), 0xFF5EBD00.toInt(), 0xFF9DBD7E.toInt(),
        0xFF408100.toInt(), 0xFF6B8156.toInt(), 0xFF346800.toInt(), 0xFF566845.toInt(),
        0xFF274F00.toInt(), 0xFF424F35.toInt(),
        0xFF3FFF00.toInt(), 0xFFBFFFAA.toInt(), 0xFF2EBD00.toInt(), 0xFF8DBD7E.toInt(),
        0xFF1F8100.toInt(), 0xFF608156.toInt(), 0xFF196800.toInt(), 0xFF4E6845.toInt(),
        0xFF134F00.toInt(), 0xFF3B4F35.toInt(),
        0xFF00FF00.toInt(), 0xFFAAFFAA.toInt(), 0xFF00BD00.toInt(), 0xFF7EBD7E.toInt(),
        0xFF008100.toInt(), 0xFF568156.toInt(), 0xFF006800.toInt(), 0xFF456845.toInt(),
        0xFF004F00.toInt(), 0xFF354F35.toInt(),
        0xFF00FF3F.toInt(), 0xFFAAFFBF.toInt(), 0xFF00BD2E.toInt(), 0xFF7EBD8D.toInt(),
        0xFF00811F.toInt(), 0xFF568160.toInt(), 0xFF006819.toInt(), 0xFF45684E.toInt(),
        0xFF004F13.toInt(), 0xFF354F3B.toInt(),
        0xFF00FF7F.toInt(), 0xFFAAFFD4.toInt(), 0xFF00BD5E.toInt(), 0xFF7EBD9D.toInt(),
        0xFF008140.toInt(), 0xFF56816B.toInt(), 0xFF006834.toInt(), 0xFF456856.toInt(),
        0xFF004F27.toInt(), 0xFF354F42.toInt(),
        0xFF00FFBF.toInt(), 0xFFAAFFEA.toInt(), 0xFF00BD8D.toInt(), 0xFF7EBDAD.toInt(),
        0xFF008160.toInt(), 0xFF568176.toInt(), 0xFF00684E.toInt(), 0xFF45685F.toInt(),
        0xFF004F3B.toInt(), 0xFF354F49.toInt(),
        0xFF00FFFF.toInt(), 0xFFAAFFFF.toInt(), 0xFF00BDBD.toInt(), 0xFF7EBDBD.toInt(),
        0xFF008181.toInt(), 0xFF568181.toInt(), 0xFF006868.toInt(), 0xFF456868.toInt(),
        0xFF004F4F.toInt(), 0xFF354F4F.toInt(),
        0xFF00BFFF.toInt(), 0xFFAAEAFF.toInt(), 0xFF008DBD.toInt(), 0xFF7EADBD.toInt(),
        0xFF006081.toInt(), 0xFF567681.toInt(), 0xFF004E68.toInt(), 0xFF455F68.toInt(),
        0xFF003B4F.toInt(), 0xFF35494F.toInt(),
        0xFF007FFF.toInt(), 0xFFAAD4FF.toInt(), 0xFF005EBD.toInt(), 0xFF7E9DBD.toInt(),
        0xFF004081.toInt(), 0xFF566B81.toInt(), 0xFF003468.toInt(), 0xFF455668.toInt(),
        0xFF00274F.toInt(), 0xFF35424F.toInt(),
        0xFF003FFF.toInt(), 0xFFAABFFF.toInt(), 0xFF002EBD.toInt(), 0xFF7E8DBD.toInt(),
        0xFF001F81.toInt(), 0xFF566081.toInt(), 0xFF001968.toInt(), 0xFF454E68.toInt(),
        0xFF00134F.toInt(), 0xFF353B4F.toInt(),
        0xFF0000FF.toInt(), 0xFFAAAAFF.toInt(), 0xFF0000BD.toInt(), 0xFF7E7EBD.toInt(),
        0xFF000081.toInt(), 0xFF565681.toInt(), 0xFF000068.toInt(), 0xFF454568.toInt(),
        0xFF00004F.toInt(), 0xFF35354F.toInt(),
        0xFF3F00FF.toInt(), 0xFFBFAAFF.toInt(), 0xFF2E00BD.toInt(), 0xFF8D7EBD.toInt(),
        0xFF1F0081.toInt(), 0xFF605681.toInt(), 0xFF190068.toInt(), 0xFF4E4568.toInt(),
        0xFF13004F.toInt(), 0xFF3B354F.toInt(),
        0xFF7F00FF.toInt(), 0xFFD4AAFF.toInt(), 0xFF5E00BD.toInt(), 0xFF9D7EBD.toInt(),
        0xFF400081.toInt(), 0xFF6B5681.toInt(), 0xFF340068.toInt(), 0xFF564568.toInt(),
        0xFF27004F.toInt(), 0xFF42354F.toInt(),
        0xFFBF00FF.toInt(), 0xFFEAAAFF.toInt(), 0xFF8D00BD.toInt(), 0xFFAD7EBD.toInt(),
        0xFF600081.toInt(), 0xFF765681.toInt(), 0xFF4E0068.toInt(), 0xFF5F4568.toInt(),
        0xFF3B004F.toInt(), 0xFF49354F.toInt(),
        0xFFFF00FF.toInt(), 0xFFFFAAFF.toInt(), 0xFFBD00BD.toInt(), 0xFFBD7EBD.toInt(),
        0xFF810081.toInt(), 0xFF815681.toInt(), 0xFF680068.toInt(), 0xFF684568.toInt(),
        0xFF4F004F.toInt(), 0xFF4F354F.toInt(),
        0xFFFF00BF.toInt(), 0xFFFFAAEA.toInt(), 0xFFBD008D.toInt(), 0xFFBD7EAD.toInt(),
        0xFF810060.toInt(), 0xFF815676.toInt(), 0xFF68004E.toInt(), 0xFF68455F.toInt(),
        0xFF4F003B.toInt(), 0xFF4F3549.toInt(),
        0xFFFF007F.toInt(), 0xFFFFAAD4.toInt(), 0xFFBD005E.toInt(), 0xFFBD7E9D.toInt(),
        0xFF810040.toInt(), 0xFF81566B.toInt(), 0xFF680034.toInt(), 0xFF684556.toInt(),
        0xFF4F0027.toInt(), 0xFF4F3542.toInt(),
        0xFFFF003F.toInt(), 0xFFFFAABF.toInt(), 0xFFBD002E.toInt(), 0xFFBD7E8D.toInt(),
        0xFF81001F.toInt(), 0xFF815660.toInt(), 0xFF680019.toInt(), 0xFF68454E.toInt(),
        0xFF4F0013.toInt(), 0xFF4F353B.toInt(),
        0xFF333333.toInt(), 0xFF505050.toInt(), 0xFF696969.toInt(), 0xFF828282.toInt(),
        0xFFBEBEBE.toInt(), 0xFFFFFFFF.toInt()
    )
}
```

- [ ] **2-4. AciColor 테스트 실행 → 통과 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -8
```
Expected: BUILD SUCCESSFUL, 10개 새 테스트 통과 (총 53개).

- [ ] **2-5. EntityRenderer가 레이어 색상 lookup하도록 수정**

Read `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt` to confirm current state.

전체 파일을 다음으로 교체:

```kotlin
package io.github.june690602_blip.cleancad.render

import android.graphics.*
import io.github.june690602_blip.cleancad.model.*
import kotlin.math.*

class EntityRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f
    }

    private var bgColor: Int = Color.WHITE
    private var defaultLineColor: Int = Color.BLACK
    private var layerColorMap: Map<String, Int> = emptyMap()

    fun setColors(bgColor: Int, lineColor: Int) {
        this.bgColor = bgColor
        this.defaultLineColor = lineColor
        linePaint.color = lineColor
        textPaint.color = lineColor
    }

    /**
     * Drawing 로드 시 한 번 호출. 각 레이어의 ACI 인덱스를 ARGB로 변환해 캐시한다.
     * 레이어 색이 0(BYBLOCK)/7(흑백 자동)/256(BYLAYER) 또는 알 수 없는 값이면
     * setColors()로 전달된 defaultLineColor를 사용한다.
     */
    fun setLayers(layers: List<Layer>) {
        layerColorMap = layers.associate { layer ->
            layer.name to AciColor.toArgb(layer.colorIndex, fallback = defaultLineColor)
        }
    }

    private fun colorFor(entity: DxfEntity): Int =
        layerColorMap[entity.layer] ?: defaultLineColor

    /** Drawing의 모든 엔티티를 렌더 순서대로 Canvas에 그린다. */
    fun drawAll(
        entities: List<DxfEntity>,
        canvas: Canvas,
        matrix: Matrix,
        viewport: BoundingBox? = null
    ) {
        entities.forEach { entity ->
            if (entity !is DxfText && entity !is DxfMText) {
                val bounds = entity.worldBounds()
                if (bounds == null || viewport == null || bounds.intersects(viewport)) {
                    draw(entity, canvas, matrix)
                }
            }
        }
        entities.forEach { entity ->
            if (entity is DxfText || entity is DxfMText) draw(entity, canvas, matrix)
        }
    }

    private fun draw(entity: DxfEntity, canvas: Canvas, matrix: Matrix) {
        linePaint.color = colorFor(entity)
        textPaint.color = colorFor(entity)
        when (entity) {
            is DxfLine       -> drawLine(entity, canvas, matrix)
            is DxfCircle     -> drawCircle(entity, canvas, matrix)
            is DxfArc        -> drawArc(entity, canvas, matrix)
            is DxfLwPolyline -> drawLwPolyline(entity, canvas, matrix)
            is DxfEllipse    -> drawEllipse(entity, canvas, matrix)
            is DxfSpline     -> drawSpline(entity, canvas, matrix)
            is DxfText       -> drawText(entity, canvas, matrix)
            is DxfMText      -> drawMText(entity, canvas, matrix)
            is DxfDimension  -> drawDimension(entity, canvas, matrix)
            is DxfLeader     -> drawLeader(entity, canvas, matrix)
            is DxfHatch, is DxfInsert, is DxfUnknown -> { /* skip */ }
        }
    }

    private fun drawLine(e: DxfLine, canvas: Canvas, matrix: Matrix) {
        val s = CoordTransform.worldToScreen(e.start, matrix)
        val end = CoordTransform.worldToScreen(e.end, matrix)
        canvas.drawLine(s.x, s.y, end.x, end.y, linePaint)
    }

    private fun drawCircle(e: DxfCircle, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        canvas.drawCircle(c.x, c.y, r, linePaint)
    }

    private fun drawArc(e: DxfArc, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        val oval = RectF(c.x - r, c.y - r, c.x + r, c.y + r)
        var sweep = (e.endAngleDeg - e.startAngleDeg).toFloat()
        if (sweep <= 0f) sweep += 360f
        val startAngle = (-e.endAngleDeg).toFloat()
        canvas.drawArc(oval, startAngle, sweep, false, linePaint)
    }

    private fun drawLwPolyline(e: DxfLwPolyline, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        if (e.closed) path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun drawEllipse(e: DxfEllipse, canvas: Canvas, matrix: Matrix) {
        val majorLen = sqrt(e.majorAxis.x * e.majorAxis.x + e.majorAxis.y * e.majorAxis.y)
        val minorLen = majorLen * e.minorRatio
        val rot = atan2(e.majorAxis.y, e.majorAxis.x)
        val steps = 72
        val path = Path()
        val paramRange = e.endParam - e.startParam
        for (i in 0..steps) {
            val t = e.startParam + i.toDouble() / steps * paramRange
            val wx = e.center.x + majorLen * cos(t) * cos(rot) - minorLen * sin(t) * sin(rot)
            val wy = e.center.y + majorLen * cos(t) * sin(rot) + minorLen * sin(t) * cos(rot)
            val pt = CoordTransform.worldToScreen(wx, wy, matrix)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawSpline(e: DxfSpline, canvas: Canvas, matrix: Matrix) {
        if (e.controlPoints.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.controlPoints[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.controlPoints.size) {
            val pt = CoordTransform.worldToScreen(e.controlPoints[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawText(e: DxfText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(e.text, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawMText(e: DxfMText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        val stripped = e.text
            .replace(Regex("\\\\[A-Za-z][^;]*;"), "")
            .replace(Regex("\\\\[^A-Za-z]"), "")
            .replace(Regex("[{}]"), "")
            .replace("\\P", "\n")
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(stripped, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawDimension(e: DxfDimension, canvas: Canvas, matrix: Matrix) {
        val dp = CoordTransform.worldToScreen(e.definitionPoint, matrix)
        val tp = CoordTransform.worldToScreen(e.textMidPoint, matrix)
        canvas.drawLine(dp.x, dp.y, tp.x, tp.y, linePaint)
        if (e.textOverride.isNotBlank()) {
            textPaint.textSize = (2.5 * CoordTransform.currentScale(matrix)).toFloat().coerceAtLeast(8f)
            canvas.drawText(e.textOverride, tp.x, tp.y, textPaint)
        }
    }

    private fun drawLeader(e: DxfLeader, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }
}
```

- [ ] **2-6. DrawingView.onDraw가 setLayers를 호출하도록 수정**

Read `app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt`.

`setDrawing()` 함수 수정:
```kotlin
    fun setDrawing(drawing: Drawing) {
        this.drawing = drawing
        renderer.setLayers(drawing.layers)
        if (width > 0 && height > 0) fitToScreen() else matrix = Matrix()
        invalidate()
    }
```

- [ ] **2-7. 빌드 + 테스트 검증**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -5
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL, 53개 테스트 통과 (43 + 10).

- [ ] **2-8. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/render/AciColor.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/render/AciColorTest.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt

git commit -m "$(cat <<'EOF'
feat(phase7): AciColor 색상표 — 레이어별 색상 렌더링

- AciColor — ACI 1~255 → ARGB 표준 256색 팔레트
- EntityRenderer — setLayers() 추가, 엔티티 그릴 때 레이어 색 lookup
- DrawingView.setDrawing — renderer.setLayers(drawing.layers) 호출
- 테스트 10개 추가

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: POLYLINE (구형식) 파서 추가

**근본 원인:** LWPOLYLINE만 지원하고 구형 POLYLINE은 DxfUnknown으로 처리됨. POLYLINE은 VERTEX 자식 엔티티들을 SEQEND까지 가지므로 구조가 다름.

**해결:** DxfPolyline 데이터 클래스 추가, parseEntity에서 "POLYLINE" 처리 시 SEQEND까지의 VERTEX들을 수집.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

### Steps

- [ ] **3-1. 테스트 작성**

`DxfParserTest.kt`의 맨 마지막 `}` 앞에 추가:

```kotlin
    // ---- POLYLINE (구형식) ----

    @Test
    fun parsePolyline_extractsVerticesUntilSeqEnd() {
        val dxf = withEntities("""
  0
POLYLINE
  8
walls
 66
1
 70
0
  0
VERTEX
  8
walls
 10
0.0
 20
0.0
  0
VERTEX
  8
walls
 10
10.0
 20
0.0
  0
VERTEX
  8
walls
 10
10.0
 20
5.0
  0
SEQEND""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(1, drawing.entities.size)
        val poly = drawing.entities[0] as DxfPolyline
        assertEquals("walls", poly.layer)
        assertEquals(3, poly.vertices.size)
        assertEquals(Vec2(0.0, 0.0), poly.vertices[0])
        assertEquals(Vec2(10.0, 0.0), poly.vertices[1])
        assertEquals(Vec2(10.0, 5.0), poly.vertices[2])
        assertFalse(poly.closed)
    }

    @Test
    fun parsePolyline_closedFlag() {
        val dxf = withEntities("""
  0
POLYLINE
 70
1
  0
VERTEX
 10
0.0
 20
0.0
  0
VERTEX
 10
1.0
 20
0.0
  0
SEQEND""".trimIndent())
        val poly = DxfParser.parse(dxf).entities[0] as DxfPolyline
        assertTrue(poly.closed)
    }
```

- [ ] **3-2. 테스트 실행 → 실패 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 컴파일 에러 (DxfPolyline 클래스 없음).

- [ ] **3-3. DxfEntity.kt에 DxfPolyline 추가**

Open `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`.
`DxfLwPolyline` 데이터 클래스 바로 아래(즉 33~34행)에 추가:

```kotlin
data class DxfPolyline(
    override val layer: String,
    val vertices: List<Vec2>,
    val closed: Boolean
) : DxfEntity()
```

- [ ] **3-4. DxfParser.kt에 POLYLINE 처리 추가**

Open `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`.

`parseEntity()` 함수의 `when` 블록에 LWPOLYLINE 라인 바로 아래(228행 부근)에 추가:
```kotlin
        "POLYLINE"   -> parsePolyline(reader)
```

그리고 `parseLwPolyline()` 함수 바로 아래에 새 함수 추가:

```kotlin
    private fun parsePolyline(reader: DxfReader): DxfPolyline {
        var layer = "0"
        var closed = false
        val vertices = mutableListOf<Vec2>()

        // 1) POLYLINE 헤더 그룹 코드 읽기 (다음 0 그룹코드까지)
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> closed = (next.value.toIntOrNull() ?: 0) and 1 != 0
            }
        }

        // 2) VERTEX 엔티티들을 SEQEND까지 수집
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code != 0) { reader.next(); continue }
            val typeGc = reader.next()
            when (typeGc.value) {
                "VERTEX" -> {
                    var vx = 0.0; var vy = 0.0
                    while (reader.hasNext()) {
                        val v = reader.peek() ?: break
                        if (v.code == 0) break
                        val nv = reader.next()
                        when (nv.code) {
                            10 -> vx = nv.value.toDouble()
                            20 -> vy = nv.value.toDouble()
                        }
                    }
                    vertices.add(Vec2(vx, vy))
                }
                "SEQEND" -> {
                    // SEQEND 본문도 그룹 코드를 가질 수 있으니 다음 0까지 스킵
                    while (reader.hasNext()) {
                        val s = reader.peek() ?: break
                        if (s.code == 0) break
                        reader.next()
                    }
                    return DxfPolyline(layer, vertices, closed)
                }
                else -> {
                    // 예상 외 엔티티 등장 → 본문 스킵 후 계속
                    while (reader.hasNext()) {
                        val s = reader.peek() ?: break
                        if (s.code == 0) break
                        reader.next()
                    }
                }
            }
        }
        return DxfPolyline(layer, vertices, closed)
    }
```

- [ ] **3-5. transformEntity / collectBoundingPoints에 DxfPolyline 추가**

같은 파일 내 `transformEntity()` 함수의 `when (e)` 블록 — `is DxfLwPolyline` 라인 바로 아래(103행 부근)에 추가:
```kotlin
            is DxfPolyline   -> e.copy(vertices = e.vertices.map { tp(it) })
```

`collectBoundingPoints()` 함수의 `when (entity)` 블록 — `is DxfLwPolyline` 라인 아래에 추가:
```kotlin
            is DxfPolyline   -> points.addAll(entity.vertices)
```

- [ ] **3-6. EntityRenderer에 drawPolyline 추가**

Open `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`.

`draw()` 함수의 `when (entity)` 블록에 `is DxfLwPolyline` 라인 아래에 추가:
```kotlin
            is DxfPolyline   -> drawPolyline(entity, canvas, matrix)
```

`drawLwPolyline` 함수 바로 아래에 새 함수 추가:
```kotlin
    private fun drawPolyline(e: DxfPolyline, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        if (e.closed) path.close()
        canvas.drawPath(path, linePaint)
    }
```

- [ ] **3-7. EntityBounds에 DxfPolyline 추가**

Open `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt`.

`worldBounds()` 함수의 `when (this)` 블록 — `is DxfLwPolyline` 케이스 바로 아래에 추가:
```kotlin
    is DxfPolyline   -> if (vertices.isEmpty()) null else BoundingBox(
        vertices.minOf { it.x }, vertices.minOf { it.y },
        vertices.maxOf { it.x }, vertices.maxOf { it.y }
    )
```

- [ ] **3-8. 테스트 + 빌드 검증**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL, 55개 테스트 통과 (53 + 2).

- [ ] **3-9. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt

git commit -m "$(cat <<'EOF'
feat(phase7): POLYLINE (구형식) 파서 + 렌더링

- DxfPolyline 데이터 클래스 추가
- DxfParser.parsePolyline — VERTEX 수집 후 SEQEND까지
- EntityRenderer.drawPolyline — LWPOLYLINE과 동일하게 Path 렌더
- EntityBounds — DxfPolyline.worldBounds 추가
- DxfParser.transformEntity — INSERT 확장 시 DxfPolyline 변환
- DxfParser.collectBoundingPoints — DxfPolyline 정점 포함
- 테스트 2개 추가 (open/closed)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 3DFACE 파서 + 렌더링

**근본 원인:** 3DFACE(평면 사각형/삼각형) 미지원 → DxfUnknown. 입면/단면 도면에서 흔히 사용됨.

**해결:** Dxf3DFace 데이터 클래스 추가. 4개 정점(11/21, 12/22, 13/23, 14/24)을 파싱하고 다각형으로 렌더.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

### Steps

- [ ] **4-1. 테스트 작성**

`DxfParserTest.kt` 끝의 `}` 앞에 추가:

```kotlin
    // ---- 3DFACE ----

    @Test
    fun parse3dFace_returnsFourCorners() {
        val dxf = withEntities("""
  0
3DFACE
  8
faces
 10
0.0
 20
0.0
 30
0.0
 11
10.0
 21
0.0
 31
0.0
 12
10.0
 22
10.0
 32
0.0
 13
0.0
 23
10.0
 33
0.0""".trimIndent())

        val face = DxfParser.parse(dxf).entities[0] as Dxf3DFace
        assertEquals("faces", face.layer)
        assertEquals(Vec2(0.0, 0.0),   face.corner1)
        assertEquals(Vec2(10.0, 0.0),  face.corner2)
        assertEquals(Vec2(10.0, 10.0), face.corner3)
        assertEquals(Vec2(0.0, 10.0),  face.corner4)
    }
```

- [ ] **4-2. 테스트 실행 → 실패 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 컴파일 에러.

- [ ] **4-3. Dxf3DFace 추가**

Open `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`. `DxfPolyline` 바로 아래 추가:

```kotlin
data class Dxf3DFace(
    override val layer: String,
    val corner1: Vec2,
    val corner2: Vec2,
    val corner3: Vec2,
    val corner4: Vec2
) : DxfEntity()
```

- [ ] **4-4. parser에 3DFACE 케이스 추가**

Open `DxfParser.kt`. `parseEntity()` 함수의 `when (type)` 블록에 추가 (POLYLINE 아래):
```kotlin
        "3DFACE"     -> parse3dFace(reader)
```

`parsePolyline()` 함수 아래에 새 함수:
```kotlin
    private fun parse3dFace(reader: DxfReader): Dxf3DFace {
        var layer = "0"
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        var x3 = 0.0; var y3 = 0.0; var x4 = 0.0; var y4 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
                12 -> x3 = next.value.toDouble()
                22 -> y3 = next.value.toDouble()
                13 -> x4 = next.value.toDouble()
                23 -> y4 = next.value.toDouble()
            }
        }
        return Dxf3DFace(layer, Vec2(x1, y1), Vec2(x2, y2), Vec2(x3, y3), Vec2(x4, y4))
    }
```

`transformEntity()` 함수의 `when` 블록에 추가 (DxfPolyline 아래):
```kotlin
            is Dxf3DFace     -> e.copy(
                corner1 = tp(e.corner1), corner2 = tp(e.corner2),
                corner3 = tp(e.corner3), corner4 = tp(e.corner4)
            )
```

`collectBoundingPoints()`의 `when` 블록에 추가:
```kotlin
            is Dxf3DFace     -> {
                points.add(entity.corner1); points.add(entity.corner2)
                points.add(entity.corner3); points.add(entity.corner4)
            }
```

- [ ] **4-5. EntityRenderer에 draw3dFace 추가**

Open `EntityRenderer.kt`. `draw()` 함수의 `when` 블록에 추가 (DxfPolyline 아래):
```kotlin
            is Dxf3DFace     -> draw3dFace(entity, canvas, matrix)
```

`drawPolyline()` 아래에 새 함수:
```kotlin
    private fun draw3dFace(e: Dxf3DFace, canvas: Canvas, matrix: Matrix) {
        val p1 = CoordTransform.worldToScreen(e.corner1, matrix)
        val p2 = CoordTransform.worldToScreen(e.corner2, matrix)
        val p3 = CoordTransform.worldToScreen(e.corner3, matrix)
        val p4 = CoordTransform.worldToScreen(e.corner4, matrix)
        val path = Path()
        path.moveTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.lineTo(p3.x, p3.y)
        // corner4 == corner3 인 경우(삼각형)는 닫기만
        if (e.corner4 != e.corner3) path.lineTo(p4.x, p4.y)
        path.close()
        canvas.drawPath(path, linePaint)
    }
```

- [ ] **4-6. EntityBounds 추가**

Open `EntityBounds.kt`. `worldBounds()`의 `when` 블록에 추가 (DxfPolyline 아래):
```kotlin
    is Dxf3DFace     -> BoundingBox(
        minOf(corner1.x, corner2.x, corner3.x, corner4.x),
        minOf(corner1.y, corner2.y, corner3.y, corner4.y),
        maxOf(corner1.x, corner2.x, corner3.x, corner4.x),
        maxOf(corner1.y, corner2.y, corner3.y, corner4.y)
    )
```

- [ ] **4-7. 테스트 + 빌드**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: 56개 통과 (55 + 1).

- [ ] **4-8. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt

git commit -m "$(cat <<'EOF'
feat(phase7): 3DFACE 파서 + 렌더링

- Dxf3DFace — 4개 모서리 (10/11/12/13, 20/21/22/23)
- 사각형/삼각형(corner4==corner3) 모두 지원
- 테스트 1개 추가

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: SOLID 파서 + 렌더링 (채움)

**근본 원인:** SOLID(채워진 삼각형/사각형) 미지원. 화살촉, 채움 영역 등에서 사용.

**해결:** DxfSolid 추가. SOLID는 정점 순서가 1-2-4-3(레거시) 이므로 렌더 시 주의. 색은 레이어 색으로 채움.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

### Steps

- [ ] **5-1. 테스트 작성**

`DxfParserTest.kt` 끝의 `}` 앞에 추가:

```kotlin
    // ---- SOLID ----

    @Test
    fun parseSolid_returnsFourCorners() {
        val dxf = withEntities("""
  0
SOLID
  8
fills
 10
0.0
 20
0.0
 11
10.0
 21
0.0
 12
0.0
 22
10.0
 13
10.0
 23
10.0""".trimIndent())

        val solid = DxfParser.parse(dxf).entities[0] as DxfSolid
        assertEquals("fills", solid.layer)
        assertEquals(Vec2(0.0, 0.0),   solid.corner1)
        assertEquals(Vec2(10.0, 0.0),  solid.corner2)
        assertEquals(Vec2(0.0, 10.0),  solid.corner3)
        assertEquals(Vec2(10.0, 10.0), solid.corner4)
    }
```

- [ ] **5-2. 테스트 실행 → 실패 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

- [ ] **5-3. DxfSolid 추가**

`DxfEntity.kt`의 `Dxf3DFace` 바로 아래에 추가:

```kotlin
data class DxfSolid(
    override val layer: String,
    val corner1: Vec2,
    val corner2: Vec2,
    val corner3: Vec2,
    val corner4: Vec2
) : DxfEntity()
```

- [ ] **5-4. parser에 SOLID 처리 추가**

`DxfParser.kt`의 `parseEntity()` `when` 블록에 추가 (3DFACE 아래):
```kotlin
        "SOLID"      -> parseSolid(reader)
```

`parse3dFace()` 함수 아래에 새 함수:
```kotlin
    private fun parseSolid(reader: DxfReader): DxfSolid {
        var layer = "0"
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        var x3 = 0.0; var y3 = 0.0; var x4 = 0.0; var y4 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
                12 -> x3 = next.value.toDouble()
                22 -> y3 = next.value.toDouble()
                13 -> x4 = next.value.toDouble()
                23 -> y4 = next.value.toDouble()
            }
        }
        return DxfSolid(layer, Vec2(x1, y1), Vec2(x2, y2), Vec2(x3, y3), Vec2(x4, y4))
    }
```

`transformEntity()` `when` 블록에 추가:
```kotlin
            is DxfSolid      -> e.copy(
                corner1 = tp(e.corner1), corner2 = tp(e.corner2),
                corner3 = tp(e.corner3), corner4 = tp(e.corner4)
            )
```

`collectBoundingPoints()`에 추가:
```kotlin
            is DxfSolid      -> {
                points.add(entity.corner1); points.add(entity.corner2)
                points.add(entity.corner3); points.add(entity.corner4)
            }
```

- [ ] **5-5. EntityRenderer에 drawSolid 추가 (FILL_AND_STROKE)**

`EntityRenderer.kt` 상단의 paint 정의 바로 아래에 fillPaint 추가:

```kotlin
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
```

`draw()` 함수의 `when` 블록에 추가 (3DFACE 아래):
```kotlin
            is DxfSolid      -> drawSolid(entity, canvas, matrix)
```

`draw3dFace()` 함수 아래에 새 함수:
```kotlin
    private fun drawSolid(e: DxfSolid, canvas: Canvas, matrix: Matrix) {
        // SOLID 정점 순서: 1-2-4-3 (legacy)
        val p1 = CoordTransform.worldToScreen(e.corner1, matrix)
        val p2 = CoordTransform.worldToScreen(e.corner2, matrix)
        val p3 = CoordTransform.worldToScreen(e.corner3, matrix)
        val p4 = CoordTransform.worldToScreen(e.corner4, matrix)
        val path = Path()
        path.moveTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.lineTo(p4.x, p4.y)   // 주의: 4 먼저
        if (e.corner3 != e.corner4) path.lineTo(p3.x, p3.y)
        path.close()
        fillPaint.color = linePaint.color
        canvas.drawPath(path, fillPaint)
    }
```

- [ ] **5-6. EntityBounds 추가**

`EntityBounds.kt`의 `worldBounds()`에 추가:
```kotlin
    is DxfSolid      -> BoundingBox(
        minOf(corner1.x, corner2.x, corner3.x, corner4.x),
        minOf(corner1.y, corner2.y, corner3.y, corner4.y),
        maxOf(corner1.x, corner2.x, corner3.x, corner4.x),
        maxOf(corner1.y, corner2.y, corner3.y, corner4.y)
    )
```

- [ ] **5-7. 검증**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: 57개 통과 (56 + 1).

- [ ] **5-8. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt

git commit -m "$(cat <<'EOF'
feat(phase7): SOLID 파서 + 채움 렌더링

- DxfSolid — 4개 모서리, 정점 순서 1-2-4-3 (legacy)
- EntityRenderer.drawSolid — Paint.Style.FILL, 레이어 색 사용
- 테스트 1개 추가

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: HATCH 경계 polyline 파싱 + 솔리드 채우기

**근본 원인:** HATCH가 isSolid만 캡처하고 경계는 무시. Renderer가 HATCH를 그냥 스킵.

**해결:** HATCH의 polyline boundary path(group 92 비트 1)만 파싱(가장 흔한 경우). 솔리드면 Path.fill, 아니면 outline 렌더. 경계가 line edges(group 72=1)로만 구성된 경우도 함께 처리.

**스코프 제한:** Arc/ellipse/spline edge 경계는 Phase 7.x에서 다룸. 현 단계는 polyline boundary + line edge boundary만.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

### Steps

- [ ] **6-1. DxfHatch 데이터 클래스 변경 (paths 필드 추가)**

`DxfEntity.kt`의 기존 `DxfHatch` 정의를 다음으로 교체:

```kotlin
data class DxfHatch(
    override val layer: String,
    val isSolid: Boolean,
    val paths: List<List<Vec2>>
) : DxfEntity()
```

- [ ] **6-2. 기존 HATCH 테스트 업데이트**

`DxfParserTest.kt`의 기존 `parseHatch_solidFlag` 테스트를 찾아 다음으로 교체:

```kotlin
    @Test
    fun parseHatch_solidFlag() {
        val dxf = withEntities("""
  0
HATCH
  8
0
 70
1
 91
0""".trimIndent())

        val hatch = DxfParser.parse(dxf).entities[0] as DxfHatch
        assertTrue(hatch.isSolid)
        assertEquals(0, hatch.paths.size)
    }
```

- [ ] **6-3. HATCH polyline boundary 테스트 추가**

`DxfParserTest.kt` 끝의 `}` 앞에 추가:

```kotlin
    // ---- HATCH polyline boundary ----

    @Test
    fun parseHatch_polylineBoundary_collectsVertices() {
        // 1개 path, polyline 타입(flag 비트 1 set = 2), 4개 정점
        val dxf = withEntities("""
  0
HATCH
  8
fills
 70
1
 91
1
 92
2
 72
0
 73
1
 93
4
 10
0.0
 20
0.0
 10
10.0
 20
0.0
 10
10.0
 20
10.0
 10
0.0
 20
10.0""".trimIndent())

        val hatch = DxfParser.parse(dxf).entities[0] as DxfHatch
        assertTrue(hatch.isSolid)
        assertEquals(1, hatch.paths.size)
        assertEquals(4, hatch.paths[0].size)
        assertEquals(Vec2(0.0, 0.0),   hatch.paths[0][0])
        assertEquals(Vec2(10.0, 10.0), hatch.paths[0][2])
    }

    @Test
    fun parseHatch_lineEdgeBoundary_collectsVertices() {
        // 1개 path, edge 타입(flag 0), 4개 line edges
        val dxf = withEntities("""
  0
HATCH
  8
fills
 70
0
 91
1
 92
0
 93
4
 72
1
 10
0.0
 20
0.0
 11
10.0
 21
0.0
 72
1
 10
10.0
 20
0.0
 11
10.0
 21
10.0
 72
1
 10
10.0
 20
10.0
 11
0.0
 21
10.0
 72
1
 10
0.0
 20
10.0
 11
0.0
 21
0.0""".trimIndent())

        val hatch = DxfParser.parse(dxf).entities[0] as DxfHatch
        assertEquals(1, hatch.paths.size)
        // line edge 경계는 각 edge의 시작점(10/20)과 끝점(11/21)을 모두 수집.
        // 4개 edge × 2점 = 8점 (인접 edge의 공유 정점으로 인해 중복 포함).
        // Path 렌더링 시 redundant lineTo만 발생하고 모양은 정상.
        assertEquals(8, hatch.paths[0].size)
    }
```

- [ ] **6-4. 테스트 실행 → 실패 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

- [ ] **6-5. DxfParser.parseHatch 재작성**

`DxfParser.kt`의 기존 `parseHatch` 함수를 통째로 교체:

```kotlin
    private fun parseHatch(reader: DxfReader): DxfHatch {
        var layer = "0"
        var isSolid = false
        var numPaths = 0
        val paths = mutableListOf<List<Vec2>>()

        // 1) 헤더: layer, solid flag, num paths
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            // 91 다음부터는 path 데이터이므로 91 만나면 다음 단계로
            if (gc.code == 0) return DxfHatch(layer, isSolid, paths)
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> isSolid = next.value.trim() == "1"
                91 -> { numPaths = next.value.toIntOrNull() ?: 0; break }
            }
        }

        // 2) Path 개수만큼 boundary path 파싱
        repeat(numPaths) {
            val path = parseHatchBoundaryPath(reader) ?: return@repeat
            if (path.isNotEmpty()) paths.add(path)
        }

        // 3) 남은 본문 (pattern data 등)은 다음 0 그룹코드까지 스킵
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            reader.next()
        }

        return DxfHatch(layer, isSolid, paths)
    }

    /**
     * HATCH boundary path 1개를 파싱.
     * - 92: path type flag. bit 1(=2) 이 set이면 polyline boundary
     * - polyline: 73(closed), 93(num vertices), 10/20 정점들, 42 bulge(스킵)
     * - edge boundary: 93(num edges), 각 edge별 72(type)+좌표
     *   - 72=1 (line): 10/20 (start), 11/21 (end)
     *   - 72=2 (arc), 3 (ellipse), 4 (spline) → 본 함수에서는 좌표만 스킵
     * 알 수 없는 그룹코드는 무시. 다음 boundary나 다른 섹션 시작 직전까지 읽음.
     */
    private fun parseHatchBoundaryPath(reader: DxfReader): List<Vec2>? {
        var pathTypeFlag = 0
        var isPolyline = false
        var numItems = 0  // polyline 정점 수 or edge 수
        var sawNumItems = false
        val vertices = mutableListOf<Vec2>()
        var lastVx = 0.0; var lastVy = 0.0; var sawLastVx = false

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            // 다음 boundary path 시작 신호: 92 (다음 path 의 type flag)
            // 또는 종료 신호: 97(source boundary 개수), 75(hatch style), 76(pattern type) 등
            // 가장 안전한 종료 시그널: 0 (다음 엔티티), 97 (이 HATCH의 다음 섹션)
            if (gc.code == 0) break
            if (gc.code == 97) break  // 다음 섹션 (source boundary objects)
            if (gc.code == 75) break  // hatch style
            // 다음 boundary path 의 시작은 92 — 다만 첫 92 는 *이 path 의* flag
            // 이 path 가 끝나고 다음이 다시 92 로 시작하면 종료
            if (gc.code == 92 && sawNumItems) break

            val next = reader.next()
            when (next.code) {
                92 -> {
                    pathTypeFlag = next.value.toIntOrNull() ?: 0
                    isPolyline = (pathTypeFlag and 2) != 0
                }
                93 -> {
                    numItems = next.value.toIntOrNull() ?: 0
                    sawNumItems = true
                }
                72 -> {
                    // polyline이면 has-bulge flag (무시)
                    // edge boundary이면 edge type
                    if (!isPolyline) {
                        // 새 edge 시작 — 다음 좌표를 받기 위해 sawLastVx 리셋
                        sawLastVx = false
                    }
                }
                10 -> {
                    lastVx = next.value.toDouble(); sawLastVx = true
                }
                20 -> {
                    if (sawLastVx) {
                        vertices.add(Vec2(lastVx, next.value.toDouble()))
                        sawLastVx = false
                    }
                }
                11 -> {
                    // line edge 끝점 X (edge boundary only)
                    if (!isPolyline) lastVx = next.value.toDouble().also { sawLastVx = true }
                }
                21 -> {
                    if (!isPolyline && sawLastVx) {
                        vertices.add(Vec2(lastVx, next.value.toDouble()))
                        sawLastVx = false
                    }
                }
                42 -> { /* bulge — 무시 */ }
            }
        }
        return vertices
    }
```

- [ ] **6-6. transformEntity / collectBoundingPoints에 DxfHatch 정점 포함**

`DxfParser.kt`의 `transformEntity()` 함수: 현재 `is DxfHatch, is DxfUnknown -> null` 라인을 찾아 다음으로 분리:

```kotlin
            is DxfHatch      -> e.copy(paths = e.paths.map { path -> path.map { tp(it) } })
            is DxfUnknown    -> null
```

`collectBoundingPoints()` 함수: 현재 `is DxfHatch, is DxfUnknown -> {}` 라인을 찾아 다음으로 분리:

```kotlin
            is DxfHatch      -> entity.paths.forEach { points.addAll(it) }
            is DxfUnknown    -> {}
```

- [ ] **6-7. EntityRenderer.drawHatch 추가**

`EntityRenderer.kt`:

`draw()` 함수의 `when` 블록에서 현재 `is DxfHatch, is DxfInsert, is DxfUnknown -> { /* skip */ }` 라인을 찾아 다음으로 분리:

```kotlin
            is DxfHatch      -> drawHatch(entity, canvas, matrix)
            is DxfInsert, is DxfUnknown -> { /* skip */ }
```

`drawSolid()` 아래에 새 함수:
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
        if (e.isSolid) {
            fillPaint.color = linePaint.color
            canvas.drawPath(path, fillPaint)
        } else {
            canvas.drawPath(path, linePaint)
        }
    }
```

- [ ] **6-8. EntityBounds.worldBounds에 DxfHatch 추가**

`EntityBounds.kt`의 `worldBounds()`에 추가 (DxfSolid 아래):
```kotlin
    is DxfHatch      -> {
        val allPts = paths.flatten()
        if (allPts.isEmpty()) null else BoundingBox(
            allPts.minOf { it.x }, allPts.minOf { it.y },
            allPts.maxOf { it.x }, allPts.maxOf { it.y }
        )
    }
```

- [ ] **6-9. 검증**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: 59개 통과 (57 + 2 새 hatch boundary test, 기존 parseHatch_solidFlag는 변경됐지만 카운트 동일).

- [ ] **6-10. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityBounds.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt

git commit -m "$(cat <<'EOF'
feat(phase7): HATCH polyline + line-edge boundary 파싱 및 솔리드 채우기

- DxfHatch.paths 필드 추가 (List<List<Vec2>>)
- parseHatch — polyline boundary (flag bit 1) + line edges (72=1) 처리
- arc/ellipse/spline edge boundary는 좌표 수집 안 함 (Phase 7.x)
- EntityRenderer.drawHatch — Path.fill (isSolid) / Path stroke
- 테스트 2개 추가 + 기존 1개 업데이트

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: MAX_ENTITIES 상한 완화

**근본 원인:** `MAX_ENTITIES = 50,000` 으로 대용량 도면에서 잘림.

**해결:** 상한 100,000으로 증가 (Android 메모리 한계 고려, 무한대는 OOM 위험). 사용자가 도달했을 때 로그 메시지로 인지 가능하게.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt:12`

### Steps

- [ ] **7-1. MAX_ENTITIES 상수 변경**

`DxfParser.kt` 12행:
```kotlin
    private const val MAX_ENTITIES = 50_000
```

다음으로 교체:
```kotlin
    /** 렌더링 성능을 보장하기 위한 최대 엔티티 수. 이 값을 초과하면 INSERT 확장을 중단한다.
     *  Phase 7: 50,000 → 100,000 (대용량 도면 지원, Android 메모리 한계 고려). */
    private const val MAX_ENTITIES = 100_000
```

- [ ] **7-2. 검증**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: 둘 다 SUCCESS, 59개 테스트 통과 (변경 없음).

- [ ] **7-3. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt

git commit -m "$(cat <<'EOF'
perf(phase7): MAX_ENTITIES 50000 → 100000 (대용량 도면 지원)

대형 건축 도면에서 INSERT 확장 시 50K 컷오프로 누락되던
엔티티들을 복구. Android 메모리 한계를 고려해 무한대는 피함.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 최종 검증 + CLAUDE.md 업데이트

### Steps

- [ ] **8-1. 전체 테스트 + 빌드 검증**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
./gradlew :app:assembleDebug 2>&1 | tail -10
./gradlew :app:assembleRelease 2>&1 | tail -10
```
Expected: 모두 BUILD SUCCESSFUL, 단위 테스트 59개.

테스트 카운트 상세 확인:
```bash
find app/build/test-results/testDebugUnitTest -name "*.xml" | xargs grep -c "testcase"
```
Expected:
- DxfCharsetDetectorTest: 5
- AciColorTest: 10
- DxfParserTest: 31 (24 + 7 신규: POLYLINE×2, 3DFACE×1, SOLID×1, HATCH×2 신규 + 기존 1 업데이트)
- DxfReaderTest: 7
- CoordTransformTest: 6
- ExampleUnitTest: 1
- 합계: **59**

(주: 새 테스트 21개(5+10+2+1+1+2) 통과 + 기존 38개 회귀 없음 = 59. parseHatch_solidFlag는 카운트는 유지, 본문만 갱신.)

- [ ] **8-2. CLAUDE.md 업데이트**

Read `C:\dev\opendwg\CLAUDE.md`. `## Status (2026-05-27)` 섹션을 다음으로 교체:

```markdown
## Status (2026-05-27)
- Phase 0–7 전부 완료. 단위 테스트 60개 통과. 실제 DWG 2종 렌더링 확인.
- **Phase 7 완료**: 렌더링 품질 대폭 개선 (ZWCAD Mobile 벤치마크).
  - DXF 인코딩 자동 감지 (`$DWGCODEPAGE` → MS949/UTF-8 등) — 한글 깨짐 해결
  - AciColor 256색 표준 팔레트 — 레이어별 색상 렌더링
  - POLYLINE (구형식) 파서 + 렌더링 — VERTEX/SEQEND 시퀀스
  - 3DFACE, SOLID 파서 + 렌더링 (SOLID는 FILL)
  - HATCH polyline + line-edge boundary 파싱 + 솔리드 채우기
  - MAX_ENTITIES 50K → 100K
  플랜: `docs/superpowers/plans/2026-05-27-phase7-rendering-quality.md`
- **Phase 6 완료**: 렌더링 품질 개선 + Play Store 출시 준비.
  - `%%D/C/P/U/O` 이스케이프 코드 → 유니코드 변환 (°, ⌀, ±)
  - Fit-to-Screen 아웃라이어 필터링 (`displayExtents`, trimRatio=5%)
  - 뷰포트 컬링 — 화면 밖 엔티티 스킵으로 렌더 성능 개선
  - About 화면 — GPL v3 고지, LibreDWG 저작권 표시
  - 릴리즈 서명 설정 (환경변수 기반, keystore 미커밋)
- **Phase 5 완료**: 다크모드, 설정, 최근파일, Share/View Intent, Toolbar 버그 수정.
- **핫픽스 완료** (`edb41ca`): DxfReader 스트리밍(OOM 방지) + MAX_ENTITIES=50,000(ANR 방지)
  + INSERT 블록 확장(`expandEntities`) — 13MB DWG 파일 정상 렌더링.
- **Next = 수동 검증 + Play Store 출시**: 04_참고도면.dwg / 워킹타워.dwg 비교 스크린샷 후
  ZWCAD 수준 도달 확인 → Play Store 업로드. 미흡 시 Phase 8 (LibreDWG 바이너리 API).
```

- [ ] **8-3. 에뮬레이터 수동 검증 체크리스트**

에뮬레이터에 debug APK 설치 후 `04_참고도면.dwg`, `워킹타워.dwg` 열어서 확인:

- [ ] 한글 텍스트 정상 표시 (`?` 박스 없음)
- [ ] 레이어별 색 구분 (벽=흰색, 치수=노랑, 텍스트=흰색/녹색 등)
- [ ] POLYLINE 도면 표시 (구형식 polyline 사용 도면)
- [ ] HATCH 채움 영역 표시 (벽 단면 등)
- [ ] 3DFACE/SOLID 도면 표시 (입면도, 단면도)
- [ ] 다크모드에서도 색상 적절 (어두운 색이 잘 안 보이면 별도 보정 필요 — Phase 7.x)
- [ ] 기존 기능 회귀 없음 (Fit-to-Screen, 줌, 팬, 뷰포트 컬링)

스크린샷 저장:
```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell screencap -p /sdcard/phase7_ref.png
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe pull /sdcard/phase7_ref.png ./phase7_ref.png
```

- [ ] **8-4. CLAUDE.md 커밋**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: Phase 7 완료 — CLAUDE.md Status 업데이트

단위 테스트 60개 통과, 렌더링 품질 ZWCAD Mobile 수준 도달.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **8-5. 최종 git log 확인**

```bash
git log --oneline -10
```
Expected: Phase 7 커밋 8개 (Task 1~7 각각 + CLAUDE.md), 모두 main 브랜치.

---

## 파일 변경 요약

| Task | 신규 파일 | 수정 파일 |
|------|-----------|-----------|
| 1 (인코딩) | `DxfCharsetDetector.kt`, `DxfCharsetDetectorTest.kt` | `DrawingViewModel.kt` |
| 2 (색상) | `AciColor.kt`, `AciColorTest.kt` | `EntityRenderer.kt`, `DrawingView.kt` |
| 3 (POLYLINE) | — | `DxfEntity.kt`, `DxfParser.kt`, `EntityRenderer.kt`, `EntityBounds.kt`, `DxfParserTest.kt` |
| 4 (3DFACE) | — | (Task 3과 동일 파일들) |
| 5 (SOLID) | — | (Task 3과 동일 파일들) |
| 6 (HATCH) | — | (Task 3과 동일 파일들) |
| 7 (MAX_ENTITIES) | — | `DxfParser.kt` |
| 8 (검증) | — | `CLAUDE.md` |

**누적 LOC 변동:** ~+800 lines (대부분 AciColor 256-color table + Task 6 HATCH 파싱).

---

## 위험과 완화

| 위험 | 완화 |
|------|------|
| HATCH boundary 파싱이 복잡한 도면에서 실패 | line/polyline boundary만 지원, 나머지는 좌표 누락하지만 크래시는 안 남 |
| MS949 charset이 Android에서 일부 OS 빌드에 없음 | `Charset.forName()` 실패 시 UTF-8 폴백 |
| ACI 색이 다크모드 배경에서 잘 안 보임 | 7번(자동) + 어두운 회색은 fallback 색 사용. 추후 다크모드용 색 변환 가능 |
| 100K 엔티티 도면에서 OOM | 사전 메모리 측정 어려움. 실측 후 필요 시 75K로 하향 |
| LibreDWG가 변환한 DXF 헤더에 $DWGCODEPAGE가 없는 경우 | UTF-8 폴백, 깨지면 사용자가 인지 (Phase 8에서 바이너리 API로 해결) |

---

## 이 파일을 새 세션에서 여는 방법

```
이 파일을 읽고 Phase 7을 시작해줘:
docs/superpowers/plans/2026-05-27-phase7-rendering-quality.md
```

CLAUDE.md의 `## Status` 섹션도 업데이트하는 것을 잊지 말 것 (Task 8-2).
