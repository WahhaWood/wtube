plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    jvmToolchain(21)
}

// Generates app/src/release/generated/baselineProfiles by driving the app on a connected device:
// ./gradlew :app:generateBaselineProfile
android {
    namespace = "dev.wahha.wtube.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
