plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // NOTE: 暫定のプレースホルダ。恒久的なパッケージ名は未確定（README「パッケージ名」参照）。
    namespace = "com.example.arcorefetcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.arcorefetcher"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.ar:core:1.47.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // 使い方のページ送り（ManualActivity）。material 経由でも入るが、
    // 直接 import するので明示しておく。
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
