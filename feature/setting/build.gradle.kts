import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val buildTime = rootProject.extra["buildTime"] as String

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.feature.setting"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    buildFeatures {
        buildConfig = true
        dataBinding = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:spider"))
    implementation(project(":core:server"))
    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.zxing)
}
