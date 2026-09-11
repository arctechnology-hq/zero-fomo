plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.arctechnology.zerofomo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arctechnology.zerofomo"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.8.0"

        // Live feed: GitHub Pages (arctechnology-hq/zero-fomo) on the custom domain 0fomo.app,
        // republished every 6 hours by
        // .github/workflows/scrape-and-publish.yml (directory that CONTAINS
        // events.json, trailing slash required).
        buildConfigField("String", "FEED_BASE_URL",
            "\"https://0fomo.app/\"")
    }

    // Release signing is injected by CI (.github/workflows/android-ci.yml).
    // Local release builds without the env vars stay unsigned.
    val ksPath = System.getenv("ZEROFOMO_KEYSTORE_PATH")
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ZEROFOMO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZEROFOMO_KEY_ALIAS")
                keyPassword = System.getenv("ZEROFOMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug rides the live feed too. For offline device testing
            // against a local scrape, temporarily switch back to
            // "http://localhost:8765/" + `adb reverse tcp:8765 tcp:8765`
            // + `python -m http.server 8765` in a dir holding events.json.
        }
        release {
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
