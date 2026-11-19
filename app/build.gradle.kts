plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.poolvisionpro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poolvisionpro"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0-opencv"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Native OpenCV image processing for table contours and circular ball candidates.
    implementation("org.opencv:opencv:4.10.0")
}
