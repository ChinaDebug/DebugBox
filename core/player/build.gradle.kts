import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
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
    buildFeatures { buildConfig = true }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":core:common"))

    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)

    // 弹幕视图（GSY 封装已移除，播放内核为 Media3 直控）
    api(libs.danmaku.flame.master)
    
    implementation(libs.media3.datasource.okhttp)
    // Media3 直控内核：exoplayer 以 api 暴露，业务层（轨道选择/字幕）直接使用 ExoPlayer 类型
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    // 画面增强（Anime4K 基于 Media3 GlEffect 注入视频渲染管线）
    implementation(libs.media3.effect)
    implementation(libs.gson)
    implementation(libs.zxing)
    implementation(libs.hilt.android)

    // 编译期注解依赖（媒体库源码引用）
    compileOnly(libs.checker.qual)

    ksp(libs.hilt.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
