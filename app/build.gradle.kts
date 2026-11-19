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
        versionCode = 3
        versionName = "2.0.0-vision"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.opencv:opencv:4.10.0")
}
