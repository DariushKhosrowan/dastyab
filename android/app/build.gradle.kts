plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dastyab.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dastyab.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Package the CLI as an extracted native executable: Android 10+ denies
    // exec() from writable app data for apps targeting API 29+.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libdastyab.so"
        }
    }

    signingConfigs {
        create("release") {
            // Stable, in-repo signing config (project is public; key is a
            // dedicated Dastyab release key, not shared with other apps).
            storeFile = rootProject.file("dastyab-release.keystore")
            storePassword = "dastyab-release"
            keyAlias = "dastyab"
            keyPassword = "dastyab-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Configure your release signing in android/signing.properties or
            // via Gradle properties: android.injected.signing.store.file etc.
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            res.srcDirs("src/main/res")
            java.srcDirs("src/main/java")
        }
    }

    testOptions {
        // JVM unit tests call android.util.Log and android.os.Build stubs;
        // without this they throw "not mocked" and logger tests fail.
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")

    // --- Test dependencies ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    testImplementation("io.mockk:mockk:1.13.9")

    // Android instrumentation tests (require Android SDK)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}