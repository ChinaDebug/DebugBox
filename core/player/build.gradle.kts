import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.core.player"
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

    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)

    // 播放内核仅依赖 GSY 两件套：
    // - gsyVideoPlayer-java：基础封装（IPlayerManager 抽象与播放器外壳）
    api(libs.gsyvideo.java)
    api(libs.gsyvideo.exo2)
    api(libs.danmaku.flame.master)
    
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.gson)
    implementation(libs.zxing)
    implementation(libs.hilt.android)

    // 编译期注解依赖（媒体库源码引用）
    compileOnly(libs.checker.qual)

    ksp(libs.hilt.compiler)
}
