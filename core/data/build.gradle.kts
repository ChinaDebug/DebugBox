import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tvbox.kotlin.core.data"
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

hilt {
    enableAggregatingTask = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":core:common"))

    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    api(libs.datastore.preferences)

    implementation(libs.gson)
    implementation(libs.xstream)
    implementation(libs.simple.xml)
    implementation(libs.jsoup)

    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
