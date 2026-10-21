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

    defaultConfig {
        applicationId = "dev.wahha.wtube"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.13.0"
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
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// GitHub's runners get YouTube's bot checks, so CI builds skip the network smoke tests (-PofflineTests);
// a nightly job runs them on their own.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("offlineTests").isPresent) filter.excludeTestsMatching("*SmokeTest")
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

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
}
