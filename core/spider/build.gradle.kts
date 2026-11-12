import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.core.spider"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        // engine:pyramid 定义了 abi flavor dimension，library 模块需声明 fallback，
        // 最终 ABI 由 app 模块的 productFlavors 决定并覆盖此默认值
        missingDimensionStrategy("abi", "arm64")
    }
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
    api(project(":core:network"))
    api(project(":engine:quickjs"))
    api(project(":engine:pyramid"))

    implementation(libs.bundles.coroutines)

    implementation(libs.gson)
    implementation(libs.jsoup)
    implementation(libs.xstream)
    implementation(libs.simple.xml)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
