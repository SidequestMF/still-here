plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.stillhere.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.stillhere"          // must match the phone app for the Data Layer to connect
        minSdk = 30                               // Wear OS 3: Galaxy Watch 4 and newer
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "17" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
}
