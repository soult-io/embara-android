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
        versionCode = 8
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
}
