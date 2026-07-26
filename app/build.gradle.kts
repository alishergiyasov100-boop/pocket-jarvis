plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.musornibak.pocketjarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musornibak.pocketjarvis"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "FISH_KEY_DEFAULT",
            "\"${System.getenv("FISH_KEY") ?: "c43ad30cb9b442d8b5068f80e47d5132"}\"",
        )
        buildConfigField(
            "String",
            "FISH_VOICE_DEFAULT",
            "\"${System.getenv("FISH_VOICE") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "OSA_URL_DEFAULT",
            "\"${System.getenv("OSA_URL") ?: "http://127.0.0.1:8770/v1"}\"",
        )
        buildConfigField(
            "String",
            "OSA_TOKEN_DEFAULT",
            "\"${System.getenv("OSA_TOKEN") ?: "sk-osa-9zP2pTw5JqzdUloHA0XLDVQSH8NZkvgI"}\"",
        )
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
