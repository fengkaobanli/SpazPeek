plugins {
    id("com.android.application")
}

android {
    namespace = "com.spazpeek"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spazpeek"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-m3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Shizuku 本体（api = 客户端调用，provider = 权限握手）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
