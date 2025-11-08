import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.xiamuguizhi.parking"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiamuguizhi.parking"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables.useSupportLibrary = true

        // 从 local.properties 中读取 AMAP_API_KEY 并注入到 Manifest 占位符
        val propsFile = rootProject.file("local.properties")
        val props = Properties()
        if (propsFile.exists()) {
            props.load(FileInputStream(propsFile))
        }
        val amapKey = props.getProperty("AMAP_API_KEY") ?: ""
        manifestPlaceholders["amap_api_key"] = amapKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // DataStore for persistent storage
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Google Play Services Location for fused location provider
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Coil for image loading in Compose (optional but helpful)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Material Components for XML themes (Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines Tasks await() for Play Services
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // OpenStreetMap (osmdroid) 地图，无需 API Key
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // 高德官方 3D 地图 SDK（绝对可靠的国内地图），使用 Maven 依赖
    // 如无 Key 会自动回退到 osmdroid
    implementation("com.amap.api:3dmap:latest.integration")

    // CameraX 用于 AR 摄像头预览
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.0")
}