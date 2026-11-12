import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tvbox.kotlin.core.ui"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
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

    api(project(":core:common"))

    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)

    api(libs.coil)
    api(libs.coil.network.okhttp)
    api(libs.coil.svg)
    api(libs.loadsir)
    api(libs.autosize)
    api(libs.flow.layout)
    api(libs.tv.recyclerview)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.zxing)
}
