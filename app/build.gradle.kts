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
        // R26 gates the debug intent extras behind BuildConfig.DEBUG.
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // The keystore lives in ~/.gradle, never in the repo; its password
            // comes from ~/.gradle/gradle.properties (wearbookReleaseStorePassword).
            storeFile = file(System.getProperty("user.home") + "/.gradle/wearbook-release.jks")
            storePassword = (project.findProperty("wearbookReleaseStorePassword") as? String).orEmpty()
            keyAlias = "wearbook"
            keyPassword = (project.findProperty("wearbookReleaseStorePassword") as? String).orEmpty()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Keep dead natives and licence spam out of a watch APK.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
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
    // Ktor drags in org.fusesource.jansi for its console colours - Windows
    // DLLs and macOS dylibs shipped inside a watch APK. None of it is used.
    implementation(libs.ktor.server.core) {
        exclude(group = "org.fusesource.jansi")
    }
    implementation(libs.ktor.server.cio)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}
