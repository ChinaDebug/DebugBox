import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.core.network"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    buildFeatures { buildConfig = true }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":core:common"))

    api(libs.okhttp)
    api(libs.okio)

    implementation(libs.conscrypt)
    implementation(libs.brotli)
    implementation(libs.jsoup)

    implementation(libs.bundles.coroutines)

    implementation(libs.gson)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
