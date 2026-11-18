import java.io.File
import java.io.FileInputStream
import java.util.Properties

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
        versionCode = 14
        versionName = "0.3.2"
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

    if (project.hasProperty("abiSplits")) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                isUniversalApk = true
            }
        }
    }

    signingConfigs {
        create("release") {
            val signingPropsFile = rootProject.file("signing.properties")
            if (signingPropsFile.isFile) {
                val props = Properties()
                FileInputStream(signingPropsFile).use { stream: FileInputStream ->
                    props.load(stream)
                }
                storeFile = rootProject.file(props.getProperty("storeFile", "dastyab-release.keystore"))
                storePassword = props.getProperty("storePassword", "dastyab-release")
                keyAlias = props.getProperty("keyAlias", "dastyab")
                keyPassword = props.getProperty("keyPassword", "dastyab-release")
            } else {
                // Stable, in-repo signing config (project is public; key is a
                // dedicated Dastyab release key, not shared with other apps).
                storeFile = rootProject.file("dastyab-release.keystore")
                storePassword = "dastyab-release"
                keyAlias = "dastyab"
                keyPassword = "dastyab-release"
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
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
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}

val verifyNativeJniLibs by tasks.registering {
    group = "verification"
    description = "Verify all 4 Android ABIs have valid ELF libdastyab.so binaries before APK packaging."
    val skipCheck = project.hasProperty("skipNativeCheck")
    val jniDir: File = layout.projectDirectory.dir("src/main/jniLibs").asFile
    doLast {
        if (skipCheck) return@doLast
        val expected = mapOf(
            "arm64-v8a" to (2 to 183),
            "armeabi-v7a" to (1 to 40),
            "x86_64" to (2 to 62),
            "x86" to (1 to 3)
        )
        for ((abi, spec) in expected) {
            val so = File(jniDir, "$abi/libdastyab.so")
            if (!so.isFile || so.length() < 4096L) {
                throw GradleException(
                    "Missing or invalid native binary at ${so.path}. " +
                        "Run: bash scripts/android-build-native.sh android/app/src/main/jniLibs"
                )
            }
            val header = ByteArray(20)
            FileInputStream(so).use { ins: FileInputStream ->
                if (ins.read(header) != 20) {
                    throw GradleException("Truncated ELF header in ${so.path}")
                }
            }
            if (header[0] != 0x7f.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte() ||
                header[5] != 1.toByte()
            ) {
                throw GradleException("Invalid little-endian ELF magic in ${so.path}")
            }
            val elfClass = header[4].toInt() and 0xFF
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            if (elfClass != spec.first || machine != spec.second) {
                throw GradleException(
                    "ABI mismatch in ${so.path}: got class=$elfClass machine=$machine, " +
                        "expected ${spec.first}/${spec.second}"
                )
            }
        }
    }
}

tasks.matching {
    it.name == "mergeReleaseJniLibFolders" || it.name == "mergeDebugJniLibFolders"
}.configureEach {
    dependsOn(verifyNativeJniLibs)
}