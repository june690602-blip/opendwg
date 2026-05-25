# opendwg
Open-source ad-free DWG viewer for Android (app name: CleanCAD Viewer).

## Build

This project vendors LibreDWG as a git submodule. After cloning:

    git submodule update --init --recursive

Then open in Android Studio, or build from the command line:

    ./gradlew :app:assembleDebug

Requirements:
- Android SDK with NDK and CMake (install via Android Studio > SDK Manager > SDK Tools).
  The NDK version is pinned in `app/build.gradle.kts` (`ndkVersion`).
- Perl on PATH (bundled with Git for Windows). Used by LibreDWG's build.

License: GPL v3 (LibreDWG is GPL v3; linking it makes the whole app GPL v3).
