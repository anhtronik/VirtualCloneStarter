plugins {
    id("com.android.application")
}

android {
    namespace = "com.digitalizha.apkcloner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitalizha.apkcloner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.android.tools.build:apksig:8.6.1")
}
