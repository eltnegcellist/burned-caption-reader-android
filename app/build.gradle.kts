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
        versionCode = 32
        versionName = "1.0.1-preview"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    if (System.getenv("CAPTION_REQUIRE_PERSISTENT_SIGNING") == "true"
        && System.getenv("CAPTION_DEBUG_KEYSTORE").isNullOrBlank()) {
        error("Persistent signing is required; configure CAPTION_SIGNING_KEYSTORE_BASE64 in CI")
    }
    // Keep the private key outside the repository; CI restores it from Secrets.
    System.getenv("CAPTION_DEBUG_KEYSTORE")?.let { keyPath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keyPath)
            storePassword = System.getenv("CAPTION_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("CAPTION_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("CAPTION_KEY_PASSWORD") ?: "android"
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
