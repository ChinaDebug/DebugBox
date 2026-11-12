import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.core.server"
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

    api(libs.nanohttpd)
    api(libs.nanohttpd.websocket)
    api(libs.sardine.android)

    // 投屏服务端与设备发现扫描依赖 OkHttp
    api(libs.okhttp)

    // 前台服务通知与权限检查依赖 androidx.core（common 模块 appcompat 为 implementation 不传递）
    implementation(libs.androidx.appcompat)

    // 后台音频播放接管：退出详情页/切后台后由服务内 ExoPlayer 续播（听歌核心体验）
    implementation(libs.media3.exoplayer)

    implementation(libs.gson)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
