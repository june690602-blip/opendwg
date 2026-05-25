# Phase 2: LibreDWG NDK 통합 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LibreDWG를 Android 네이티브 라이브러리로 컴파일하고, JNI를 통해 Kotlin에서 LibreDWG 버전 문자열을 읽어와 "크로스컴파일 + 링크 + 로드"가 동작함을 검증한다.

**Architecture:** Android `externalNativeBuild`(CMake)로 우리 JNI 래퍼 `.so`(`libdwgjni.so`)를 빌드하고, 그 래퍼가 LibreDWG 라이브러리 타깃에 링크된다. LibreDWG는 git submodule로 고정 버전을 vendoring한다. 위험을 줄이기 위해 먼저 `x86_64` 한 ABI로 에뮬레이터에서 검증한 뒤, `arm64-v8a`/`armeabi-v7a`로 확장한다.

**Tech Stack:** Android NDK, CMake, JNI(C), Kotlin, LibreDWG(GPL v3, CMake 빌드), AndroidJUnit4 instrumented test.

**전제(이미 완료됨):**
- 프로젝트: `C:\dev\opendwg`, 패키지 `io.github.june690602_blip.cleancad`, minSdk 24, compileSdk 36, Views 기반.
- `./gradlew :app:assembleDebug` 가 성공함(Phase 1 확인됨).

**검증 환경 메모:**
- 표준 PC 에뮬레이터는 `x86_64` ABI로 실행된다. 따라서 instrumented 테스트는 `x86_64` 빌드를 검증한다(그래서 `x86_64`를 abiFilters에 포함). 실제 폰(arm64) 검증은 USB 연결 기기 또는 Phase 1.0 단계에서 수행.
- bash 셸에서 `./gradlew` 사용(Java 17 PATH 확인됨). 또는 Android Studio의 Build 메뉴.

---

## File Structure

이 Phase에서 생성/수정하는 파일:

- **Create** `app/src/main/cpp/CMakeLists.txt` — 우리 JNI 래퍼(`libdwgjni.so`) 빌드 정의. LibreDWG submodule을 `add_subdirectory`로 포함하고 래퍼를 거기에 링크.
- **Create** `app/src/main/cpp/dwgjni.c` — JNI 래퍼. LibreDWG 헤더를 include하고 버전 문자열을 반환하는 함수 하나.
- **Create** `app/src/main/cpp/libredwg/` — LibreDWG 소스 (git submodule, 고정 태그).
- **Create** `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt` — `System.loadLibrary` + `external fun` 선언.
- **Modify** `app/build.gradle.kts` — `ndkVersion`, `externalNativeBuild { cmake { ... } }`, `defaultConfig.ndk { abiFilters }`.
- **Create** `app/src/androidTest/java/io/github/june690602_blip/cleancad/NativeDwgTest.kt` — 버전 문자열이 비어있지 않음을 검증하는 instrumented 테스트.
- **Modify** `.gitignore` — 필요 시 native 빌드 산출물 무시(보통 `app/.cxx/`는 템플릿 gitignore에 이미 포함).

---

## Task 1: NDK/CMake 골격 + 빈 JNI 래퍼 빌드

LibreDWG를 붙이기 전에, NDK/CMake 파이프라인 자체가 동작하는지 먼저 검증한다(LibreDWG 없이 빈 `.so` 빌드).

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/dwgjni.c`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 최소 CMakeLists.txt 작성 (LibreDWG 아직 없음)**

`app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(cleancad LANGUAGES C)

add_library(dwgjni SHARED dwgjni.c)

find_library(log-lib log)
target_link_libraries(dwgjni ${log-lib})
```

- [ ] **Step 2: 최소 JNI 래퍼 작성 (하드코딩 문자열 반환)**

`app/src/main/cpp/dwgjni.c`:
```c
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeLibredwgVersion(
        JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "stub-not-linked-yet");
}
```
> 주의: JNI 함수명에서 패키지의 밑줄 `_`은 `_1`로 인코딩된다(`june690602_blip` → `june690602_1blip`).

- [ ] **Step 3: Kotlin 바인딩 작성**

`app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt`:
```kotlin
package io.github.june690602_blip.cleancad

object NativeDwg {
    init {
        System.loadLibrary("dwgjni")
    }

    external fun nativeLibredwgVersion(): String
}
```

- [ ] **Step 4: build.gradle.kts에 NDK/CMake 연결**

`app/build.gradle.kts`의 `android { ... }` 블록 안 `defaultConfig`에 추가:
```kotlin
        ndk {
            abiFilters += listOf("x86_64")
        }
```
`android { ... }` 블록(최상위, `buildTypes`와 같은 레벨)에 추가:
```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.1.12297006"
```
> `ndkVersion`은 설치된 NDK에 맞춘다. 확인: `ls $ANDROID_HOME/ndk` (또는 Android Studio > SDK Manager > SDK Tools > NDK). 설치된 버전 문자열로 교체.

- [ ] **Step 5: 빌드해서 .so가 생성되는지 확인**

Run: `cd /c/dev/opendwg && ./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. 그리고 `.so` 생성 확인:
Run: `find app/build -name 'libdwgjni.so'`
Expected: `app/build/.../x86_64/libdwgjni.so` 경로가 출력됨.

- [ ] **Step 6: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/cpp app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt app/build.gradle.kts
git commit -m "feat: add NDK/CMake pipeline with stub JNI wrapper"
```

---

## Task 2: 빈 래퍼가 기기에서 로드되는지 instrumented 테스트로 검증 (RED→GREEN)

`.so`가 빌드될 뿐 아니라 에뮬레이터에서 실제 로드·호출되는지 확인한다.

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleancad/NativeDwgTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/androidTest/java/io/github/june690602_blip/cleancad/NativeDwgTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDwgTest {
    @Test
    fun libredwgVersion_isNotBlank() {
        val version = NativeDwg.nativeLibredwgVersion()
        assertTrue("version was blank: '$version'", version.isNotBlank())
    }
}
```

- [ ] **Step 2: 에뮬레이터 실행 후 테스트 실행 (이 단계에서는 통과 예상 — 스텁이 문자열 반환)**

먼저 x86_64 에뮬레이터(AVD)가 실행 중이어야 한다(Android Studio > Device Manager에서 시작, 또는 `emulator -avd <name>`).
Run: `cd /c/dev/opendwg && ./gradlew :app:connectedDebugAndroidTest --console=plain`
Expected: `BUILD SUCCESSFUL`, `NativeDwgTest > libredwgVersion_isNotBlank PASSED`.
> 이 시점에서는 스텁이 `"stub-not-linked-yet"`을 반환하므로 통과한다. 이 테스트는 Task 3에서 **실제 LibreDWG 버전**을 반환하도록 만든 뒤에도 계속 통과해야 하는 회귀 테스트 역할을 한다.

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/androidTest/java/io/github/june690602_blip/cleancad/NativeDwgTest.kt
git commit -m "test: verify JNI library loads on device"
```

---

## Task 3: LibreDWG 소스를 submodule로 고정

**Files:**
- Create: `app/src/main/cpp/libredwg/` (submodule)
- Modify: `.gitmodules` (git이 자동 생성)

- [ ] **Step 1: LibreDWG를 고정 태그로 submodule 추가**

최신 안정 릴리스 태그를 확인한다:
Run: `git ls-remote --tags https://github.com/LibreDWG/libredwg.git | grep -o 'refs/tags/[0-9.]*$' | sort -V | tail -5`
Expected: `refs/tags/0.13.3` 형태의 태그 목록(최신 안정판 선택).

submodule 추가(예시 태그 `0.13.3` — 위에서 확인한 최신 안정판으로 교체):
```bash
cd /c/dev/opendwg
git submodule add https://github.com/LibreDWG/libredwg.git app/src/main/cpp/libredwg
cd app/src/main/cpp/libredwg
git checkout 0.13.3
cd /c/dev/opendwg
git submodule update --init --recursive
```

- [ ] **Step 2: LibreDWG의 CMake 라이브러리 타깃 이름 확인**

Run: `grep -rn "add_library" app/src/main/cpp/libredwg/CMakeLists.txt`
Expected: `add_library(libredwg ...)` 또는 유사한 라이브러리 타깃 이름이 출력됨. **이 타깃 이름을 기록**한다(Task 4에서 링크 대상으로 사용). 본 계획에서는 `libredwg`로 가정한다.

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/opendwg
git add .gitmodules app/src/main/cpp/libredwg
git commit -m "chore: vendor LibreDWG as submodule (pinned tag)"
```

---

## Task 4: LibreDWG를 x86_64로 빌드 + 실제 버전 반환 (디리스킹 스파이크)

이 Task가 Phase 2의 핵심 위험 구간이다. 목표는 명확하다: **LibreDWG가 x86_64 Android로 컴파일되고, 우리 래퍼가 거기 링크되어, LibreDWG 헤더의 버전 문자열을 반환한다.** config.h 생성/생성 소스/host-tool 실행 등 빌드 이슈가 여기서 드러난다.

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/cpp/dwgjni.c`

- [ ] **Step 1: CMakeLists.txt에서 LibreDWG를 서브디렉토리로 포함**

LibreDWG는 기본적으로 프로그램·테스트까지 빌드하려 하므로, 라이브러리만 빌드하도록 옵션을 끈다. 사용 가능한 옵션을 먼저 확인:
Run: `grep -n "option(" app/src/main/cpp/libredwg/CMakeLists.txt`
Expected: `LIBREDWG_LIBONLY`, `BUILD_TESTING`, `ENABLE_*` 등 옵션 목록. 라이브러리만 빌드/테스트 끄기 관련 옵션 이름을 기록.

`app/src/main/cpp/CMakeLists.txt`를 다음으로 교체(옵션명은 위에서 확인한 실제 이름으로 맞춤):
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(cleancad LANGUAGES C)

# LibreDWG: 라이브러리만 빌드, 프로그램/테스트 비활성
set(LIBREDWG_LIBONLY ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(libredwg)

add_library(dwgjni SHARED dwgjni.c)

find_library(log-lib log)
# 'libredwg'는 Task 3 Step 2에서 확인한 실제 타깃 이름으로 교체
target_link_libraries(dwgjni libredwg ${log-lib})
```

- [ ] **Step 2: JNI 래퍼가 LibreDWG 헤더의 버전을 반환하도록 수정**

LibreDWG의 공개 버전 매크로를 확인:
Run: `grep -rn "PACKAGE_VERSION\|LIBREDWG_VERSION" app/src/main/cpp/libredwg/include/dwg.h app/src/main/cpp/libredwg/configure.ac 2>/dev/null | head`
Expected: 버전 매크로/문자열의 위치. (`dwg.h`에 버전이 없으면 LibreDWG가 CMake로 생성하는 헤더에 있다.)

`app/src/main/cpp/dwgjni.c`를 수정(실제 헤더/매크로명으로 맞춤):
```c
#include <jni.h>
#include <dwg.h>

#ifndef PACKAGE_VERSION
#define PACKAGE_VERSION "unknown"
#endif

JNIEXPORT jstring JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeLibredwgVersion(
        JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, PACKAGE_VERSION);
}
```
> `dwg.h` 경로가 자동으로 잡히지 않으면, CMakeLists의 `target_link_libraries` 다음 줄에 인클루드 경로를 추가:
> ```cmake
> target_include_directories(dwgjni PRIVATE
>     ${CMAKE_CURRENT_SOURCE_DIR}/libredwg/include
>     ${CMAKE_CURRENT_BINARY_DIR}/libredwg)
> ```

- [ ] **Step 3: x86_64로 빌드**

Run: `cd /c/dev/opendwg && ./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.
빌드가 실패하면 흔한 원인과 대응:
- `config.h not found` → LibreDWG CMake가 config 헤더를 `${CMAKE_CURRENT_BINARY_DIR}/libredwg`에 생성한다. Step 2의 `target_include_directories`에 그 경로가 포함됐는지 확인.
- `undefined reference` (링크 에러) → `target_link_libraries`의 LibreDWG 타깃 이름이 Task 3 Step 2에서 확인한 것과 일치하는지 확인.
- host 도구 실행 실패(크로스컴파일 중 타깃 바이너리 실행 시도) → 해당 옵션(예: 코드 생성 단계)을 끄는 CMake 옵션을 Step 1 옵션 목록에서 찾아 비활성.

- [ ] **Step 4: 실제 버전 반환을 instrumented 테스트로 검증 (회귀)**

에뮬레이터(x86_64) 실행 중인 상태에서:
Run: `cd /c/dev/opendwg && ./gradlew :app:connectedDebugAndroidTest --console=plain`
Expected: `NativeDwgTest > libredwgVersion_isNotBlank PASSED`. 그리고 로그캣으로 실제 값 확인(선택):
Run: `adb logcat -d | grep -i libredwg` (또는 테스트에 `println(version)` 추가 후 `--info`로 확인)
Expected: `0.13.3` 같은 실제 LibreDWG 버전 문자열(스텁 문자열이 아님).

- [ ] **Step 5: Red-Green 회귀 확인 (테스트가 진짜 동작하는지)**

`dwgjni.c`의 반환을 일시적으로 `""`(빈 문자열)로 바꿔 테스트가 FAIL하는지 확인 → 다시 `PACKAGE_VERSION`으로 되돌려 PASS 확인.
Run(빈 문자열로 바꾼 뒤): `./gradlew :app:connectedDebugAndroidTest --console=plain`
Expected: `libredwgVersion_isNotBlank FAILED` (테스트가 실제로 값을 검사함을 증명).
되돌린 뒤 Run 다시: Expected `PASSED`.

- [ ] **Step 6: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/cpp/CMakeLists.txt app/src/main/cpp/dwgjni.c
git commit -m "feat: link LibreDWG and return real version via JNI (x86_64)"
```

---

## Task 5: 3개 ABI 전체로 확장

x86_64에서 검증됐으니 실제 폰용 ABI를 추가한다.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: abiFilters에 arm64-v8a, armeabi-v7a 추가**

`app/build.gradle.kts`의 `defaultConfig.ndk` 블록을 수정:
```kotlin
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
```

- [ ] **Step 2: 3개 ABI 전체 빌드**

Run: `cd /c/dev/opendwg && ./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. 3개 ABI의 `.so` 생성 확인:
Run: `find app/build -name 'libdwgjni.so'`
Expected: `arm64-v8a`, `armeabi-v7a`, `x86_64` 세 경로 모두 출력.
> 특정 ABI에서만 실패하면(예: armeabi-v7a의 32비트 관련 경고/에러), 에러 메시지를 기록하고 해당 ABI의 컴파일 플래그를 조정. LibreDWG는 64/32비트 모두 지원하므로 대개 추가 작업 없이 통과한다.

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/opendwg
git add app/build.gradle.kts
git commit -m "feat: build LibreDWG JNI for all 3 ABIs"
```

---

## Task 6: APK에 .so가 포함되는지 최종 확인 + 문서화

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 디버그 APK 안에 3개 ABI .so가 들어갔는지 확인**

Run: `cd /c/dev/opendwg && find app/build -name '*-debug.apk'`
그 APK 경로로:
Run: `unzip -l <apk경로> | grep libdwgjni.so`
Expected: `lib/arm64-v8a/libdwgjni.so`, `lib/armeabi-v7a/libdwgjni.so`, `lib/x86_64/libdwgjni.so` 세 항목.

- [ ] **Step 2: README에 빌드 전제 한 줄 추가 (submodule 초기화)**

`README.md`에 추가(클론 후 submodule 초기화가 필요함을 명시):
```markdown

## Build

This project vendors LibreDWG as a git submodule. After cloning:

    git submodule update --init --recursive

Then open in Android Studio or run `./gradlew :app:assembleDebug`.
Requires Android NDK (see `ndkVersion` in `app/build.gradle.kts`).
```

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/opendwg
git add README.md
git commit -m "docs: document submodule init and NDK build requirement"
```

---

## Phase 2 완료 기준 (Definition of Done)

- [ ] `./gradlew :app:assembleDebug` 가 3개 ABI 전부 성공.
- [ ] `./gradlew :app:connectedDebugAndroidTest` 의 `NativeDwgTest`가 PASS하고, 반환 버전이 실제 LibreDWG 버전(스텁 아님).
- [ ] 디버그 APK 안에 3개 ABI의 `libdwgjni.so` 존재.
- [ ] LibreDWG가 고정 태그 submodule로 박혀 재현 가능.
- [ ] 모든 변경이 커밋됨.

다음 단계(별도 계획): **Phase 3** — `dwgToDxf(inPath, outPath)` JNI 함수 + Kotlin DXF 파서 + Drawing 모델.
