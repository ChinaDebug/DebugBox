import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquo.python)
}

android {
    namespace = "com.tvbox.kotlin.engine.pyramid"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm32") { dimension = "abi"; ndk { abiFilters += "armeabi-v7a" } }
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        getByName("main") {
            // Chaquopy 将 python 源码目录注册为 sourceSet 的 "python" 扩展（默认 src/main/python），
            // 本项目沿用 src/python 目录结构，需显式声明
            (this as org.gradle.api.plugins.ExtensionAware).extensions
                .getByName("python")
                .let { it as org.gradle.api.file.SourceDirectorySet }
                .srcDir("src/python")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        buildPython = listOf("py", "-3.11")
        pip {
            install("lxml")
            install("pyquery")
            install("requests")
            install("jsonpath-ng")
            install("cachetools")
            install("pycryptodome")
            install("beautifulsoup4")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
