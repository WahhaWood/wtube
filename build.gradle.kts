buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin pinned to an older KGP; this lifts it to our Kotlin version.
        // https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin-upgrade
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
