plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thienbach.dexmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thienbach.dexmonitor"
        minSdk = 26
        targetSdk = 33
        versionCode = 10
        versionName = "0.9.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
