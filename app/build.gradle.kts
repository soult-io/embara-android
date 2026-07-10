plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.soult.embara"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.soult.embara"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // T3 E2E config injection. A host-only secret file (self-hosted FRAME-DESK runner) holds the
        // test TREK server + seeded test-account credentials as KEY=VALUE lines:
        //     SERVER_URL=https://trek-test.stabpablo.eu
        //     TREK_USER=gplay-test-acc@soult.io
        //     TREK_PASS=...
        // Read it at configure time and forward to `am instrument` as -e args — NOT baked into the APK,
        // never committed. Absent on PR / dev machines -> args unset -> E2E journeys skip (ServerHealthCheck).
        val e2eCredsFile = System.getenv("EMBARA_E2E_CREDS_FILE") ?: "/srv/android/secrets/trek-test.creds"
        val credsPath = file(e2eCredsFile)
        if (credsPath.isFile && credsPath.canRead()) {
            val creds = credsPath.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            // Diagnostic — key NAMES only, never values — so the container's view of the secret is
            // observable in the build log without leaking anything.
            project.logger.lifecycle("E2E creds ${credsPath.path}: readable, keys=${creds.keys.sorted()}")
            creds["SERVER_URL"]?.let { testInstrumentationRunnerArguments["e2eServerUrl"] = it }
            creds["TREK_USER"]?.let { testInstrumentationRunnerArguments["e2eUserEmail"] = it }
            creds["TREK_PASS"]?.let { testInstrumentationRunnerArguments["e2ePassword"] = it }
        } else {
            project.logger.lifecycle(
                "E2E creds ${credsPath.path}: not visible to the build " +
                    "(exists=${credsPath.exists()}, readable=${credsPath.canRead()}) — E2E journeys will skip.",
            )
        }
    }

    signingConfigs {
        val keystoreFile = System.getenv("EMBARA_KEYSTORE_FILE")
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("EMBARA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EMBARA_KEY_ALIAS")
                keyPassword = System.getenv("EMBARA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreFile = System.getenv("EMBARA_KEYSTORE_FILE")
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Gradle Managed Device for headless instrumented tests. aosp_atd (Automated Test Device) is
    // test-keys/userdebug so it auto-authorizes adb headless. Task: :app:aospAtdDebugAndroidTest.
    // (Local-only pipeline exercise — NOT committed to the embara remote without approval.)
    testOptions {
        managedDevices {
            localDevices {
                create("aospAtd") {
                    device = "Pixel 5"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.web)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
}
