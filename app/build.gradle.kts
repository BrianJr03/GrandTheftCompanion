import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Release signing is wired via environment variables so the keystore
// never lands in source control. CI (.github/workflows/build.yml)
// decodes KEYSTORE_B64 to a temp file and sets KEYSTORE_PATH before
// invoking :app:assembleRelease. When any of these are missing the
// release signingConfig stays unconfigured and assembleRelease will
// produce an unsigned APK — fine for a local sanity-check, not for a
// GitHub Release.
val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val releaseKeystorePwd  = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias     = System.getenv("KEY_ALIAS") ?: "gtc-release"
val releaseKeyPwd       = System.getenv("KEY_PASSWORD") ?: releaseKeystorePwd
val releaseSigningReady = !releaseKeystorePath.isNullOrBlank() &&
                          !releaseKeystorePwd.isNullOrBlank()

android {
    namespace = "jr.brian.thorhud"
    compileSdk = 36

    defaultConfig {
        applicationId = "jr.brian.thorhud"
        minSdk = 33
        targetSdk = 36
        versionCode = 16
        versionName = "0.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePwd
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPwd
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "GTC Debug")
        }
        release {
            // R8 full-mode shrink + resource shrink. Hilt/Compose/Material3
            // ship consumer rules, MirrorService is kept via the manifest,
            // and kotlinx-serialization @Serializable classes (all under
            // jr.brian.thorhud.model) are covered by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "GTC")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.junit)
}
