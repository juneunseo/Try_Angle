plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id ("kotlin-kapt")
}

android {
    namespace = "com.example.camera2app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.camera2app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ✅✅✅ S25 Ultra (arm64-v8a) 설치 필수 조건 ✅✅✅
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ✅✅✅ 네이티브 라이브러리 충돌 방지 (AGP 8.x 방식) ✅✅✅
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/**/libonnxruntime.so"
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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
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
}


dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    implementation ("com.github.bumptech.glide:glide:4.16.0")
    kapt ("com.github.bumptech.glide:compiler:4.16.0")

    // ✅ ONNX Runtime (정상 버전, 하나만!)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")


}
