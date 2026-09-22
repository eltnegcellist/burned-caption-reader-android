plugins {
    id("com.android.application")
}

android {
    namespace = "jp.hidemaru.burnedcaptionreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.hidemaru.burnedcaptionreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.3.15"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    // Optional persistent test key. Keep the key outside the repository.
    System.getenv("CAPTION_DEBUG_KEYSTORE")?.let { keyPath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keyPath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
