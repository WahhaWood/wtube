plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "dev.wahha.wtube"
    compileSdk = 37
    // byedpi for the blocking bypass (app/src/main/cpp).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.wahha.wtube"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "0.24.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Shown in Settings → О приложении.
        buildConfigField("String", "EXTRACTOR_VERSION", "\"${libs.versions.newpipeExtractor.get().take(7)}\"")
    }

    // The release key lives outside the repo; its path and password come from ~/.gradle/gradle.properties.
    val keystore = providers.gradleProperty("wtube.keystore").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("wtube.keystore.password").get()
            keyAlias = "wtube"
            keyPassword = providers.gradleProperty("wtube.keystore.password").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // The phone and the x86_64 emulator.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ML Kit translation ships 12-17 MB of native code per ABI; phones are arm64.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    compileOptions {
        // NewPipeExtractor uses JDK APIs newer than minSdk 26 ships.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // OkHttp probes android.util.Log on the JVM; stubs returning defaults keep the smoke tests quiet.
        unitTests.isReturnDefaultValues = true
        // Robolectric: the few JVM tests that need a real framework (Uri, Compose UI).
        unitTests.isIncludeAndroidResources = true
    }

    // Findings that existed when lint joined CI; new ones fail the build.
    lint {
        baseline = file("lint-baseline.xml")
    }

    // The license texts live in docs/licenses; the app shows them under Settings > About.
    sourceSets["main"].assets.srcDir("../docs/licenses")
    // The bypass presets (dpi/strategies.json): bundled, and fetched from the repository when newer.
    sourceSets["main"].assets.srcDir("../dpi")

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// The profile is collected on the phone next to the installed release: same id with another key would not install,
// and the run uninstalls the app afterwards along with its history and login.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.applicationId.set("dev.wahha.wtube.bp") }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.mlkit.translate)
    implementation(libs.kyant.backdrop)
    implementation(libs.material.kolor)

    implementation(libs.newpipe.extractor)
    implementation(libs.newpipe.nanojson)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Installs the baseline profile on sideloaded builds too (Play would do it otherwise).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
}

// `-Poffline` runs only the tests that need no internet (the smoke tests talk to YouTube).
// GitHub's runners get YouTube's bot checks, so CI uses it too; a nightly job runs the smoke tests on their own.
if (providers.gradleProperty("offline").isPresent) {
    tasks.withType<Test>().configureEach { exclude("**/*SmokeTest*") }
}
