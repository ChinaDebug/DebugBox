import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tvbox.kotlin.engine.quickjs"
    compileSdk = 37
    buildFeatures { buildConfig = false }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
}

dependencies {
    // QuickJS 引擎
    api("wang.harlon.quickjs:wrapper-android:3.2.3")
    api("wang.harlon.quickjs:wrapper-java:3.2.3")
    implementation(project(":core:common"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
