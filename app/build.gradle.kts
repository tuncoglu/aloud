plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.emre.wearbook"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.emre.wearbook"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Mp4ChapterParser logs through android.util.Log; JVM unit tests get
        // stubs rather than "not mocked" exceptions.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.datastore.preferences)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}
