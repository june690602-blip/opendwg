# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- CleanCAD: JNI 보존 (R8 minify 시 필수) ----
# 네이티브 바인딩은 이름 기반(Java_io_github_..._NativeDwg_nativeDwgToBuffer)이라,
# 선언 클래스명/네이티브 메서드명이 난독화되면 런타임 UnsatisfiedLinkError 가 난다.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# 네이티브 진입점 클래스는 통째로 보존 (System.loadLibrary + external fun)
-keep class io.github.june690602_blip.cleancad.NativeDwg { *; }