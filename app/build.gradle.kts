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

    // Coil for image loading in Compose（图片加载）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Material Components for XML themes (Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // 高德官方 3D 地图 SDK（中国大陆）
    implementation("com.amap.api:3dmap:latest.integration")
    // 说明：3dmap 已内含定位相关依赖，避免重复引入导致类冲突，故不再单独依赖 location。
}