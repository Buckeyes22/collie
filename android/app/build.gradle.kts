plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lateapex.collie"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.1.12297006"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.lateapex.collie"
        minSdk = 37
        targetSdk = 37
        versionCode = 4
        versionName = "1.6.0"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_PLATFORM=latest", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Public builds start unconfigured; the operator supplies their own HTTPS server.
        buildConfigField("String", "DEFAULT_ORIGIN", "\"\"")
        buildConfigField("boolean", "SIDELOAD_INSTALL", "true")
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (providers.gradleProperty("collieCompletionAcceptance").orNull == "true") ".completiontest" else ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    androidResources {
        localeFilters += "en"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    // UnifiedPush connector 3.3.5 released August 22, 2026; enrollment UI is native.
    implementation("org.unifiedpush.android:connector:3.3.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("androidx.work:work-testing:2.12.0")
    // Keep application Kotlin libraries on the same release as the compiler plugins.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.20"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // Parsing only: rendered HTML is mapped to native TextViews after an explicit Safelist clean.
    implementation("org.jsoup:jsoup:1.23.2")
    // Pinned AndroidSVG 1.4; input is preflighted against active/external constructs before parsing.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.withType<Test>().configureEach {
    // Robolectric's documented JDK 17+ access requirements are test-only.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
    // Robolectric retains Android resource state across classes; bound each worker so the complete
    // native parity suite is reproducible instead of exhausting Gradle's 512 MiB test default.
    // Raised from 1g to 2g 2026-09-18: the diagnostics-capture test suite added enough Robolectric
    // classes (DiagnosticsWriterTest, DiagnosticsInterceptorTest, CollieApplicationTest, and the
    // now-larger PaneActivityTest) that a full testDebugUnitTest run OOMs at 1g even though any
    // single test class still passes there.
    maxHeapSize = "2g"
    // The expanded audit suite exhausts 2g when 100 classes share one resource sandbox.
    // Bound retained Robolectric/HTTP state to one test class without increasing its heap.
    forkEvery = 1
}

dependencyLocking {
    lockAllConfigurations()
}
