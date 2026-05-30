import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 서명 비밀번호는 git 에 안 올라가는 keystore.properties(repo 루트)에서 읽는다.
// 없으면 환경변수 → 기본값 순. (keystore.properties 는 .gitignore 됨)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.june690602_blip.cleancad"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.june690602_blip.cleancad"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // CMake 4.x rejects projects requiring CMake < 3.5 (LibreDWG uses 2.8...).
                arguments += "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                keystoreProps.getProperty("storeFile")
                    ?: System.getenv("KEYSTORE_PATH") ?: "../cleancad-release.jks"
            )
            storePassword = keystoreProps.getProperty("storePassword")
                ?: System.getenv("KEYSTORE_PASS") ?: ""
            keyAlias = keystoreProps.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS") ?: "cleancad"
            keyPassword = keystoreProps.getProperty("keyPassword")
                ?: System.getenv("KEY_PASS") ?: ""
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    ndkVersion = "30.0.14904198"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}