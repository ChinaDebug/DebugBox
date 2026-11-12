import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val buildTime = rootProject.extra["buildTime"] as String

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun signingProps(): Map<String, String?> {
    val env = mapOf(
        "storeFile" to System.getenv("SIGNING_KEYSTORE_FILE"),
        "storePassword" to System.getenv("SIGNING_STORE_PASSWORD"),
        "keyAlias" to System.getenv("SIGNING_KEY_ALIAS"),
        "keyPassword" to System.getenv("SIGNING_KEY_PASSWORD")
    )
    if (env["storeFile"].isNullOrEmpty()) {
        val propFile = listOf(project.file("keystore.properties"), rootProject.file("keystore.properties"))
            .firstOrNull { it.exists() } ?: return env
        val props = Properties().apply { load(propFile.inputStream()) }
        return mapOf(
            "storeFile" to props.getProperty("storeFile"),
            "storePassword" to props.getProperty("storePassword"),
            "keyAlias" to props.getProperty("keyAlias"),
            "keyPassword" to props.getProperty("keyPassword")
        )
    }
    return env
}

android {
    namespace = "com.tvbox.kotlin.debug"
    compileSdk = 37

    signingConfigs {
        create("release") {
            val cfg = signingProps()
            val storePath = cfg["storeFile"]
            if (storePath.isNullOrEmpty()) {
                throw GradleException(
                    "缺少 Release 签名配置"
                )
            }
            storeFile = file(storePath)
            storePassword = cfg["storePassword"]
            keyAlias = cfg["keyAlias"]
            keyPassword = cfg["keyPassword"]
        }
    }

    defaultConfig {
        applicationId = "com.tvbox.kotlin.debug"
        targetSdk = 36
        versionCode = 1
        versionName = buildTime
        minSdk = 24
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += listOf("abi", "brand")
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("arm32") { dimension = "abi"; ndk { abiFilters += "armeabi-v7a" } }
        create("generic") { dimension = "brand" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        dataBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += "UnsafeOptInUsageError"
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            var abi = variant.flavorName?.lowercase() ?: ""
            abi = abi.replace("generic", "")
            abi = abi.trim('-')
            val fileName = "TVBox_CloudDebug-$buildTime-${abi}_beta.apk"
            variant.outputs.forEach { output ->
                output.outputFileName.set(fileName)
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.lifecycle)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.fragment)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)

    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:spider"))
    implementation(project(":engine:pyramid"))
    implementation(project(":core:player"))
    implementation(project(":core:server"))

    implementation(project(":feature:splash"))
    implementation(project(":feature:home"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:play"))
    implementation(project(":feature:search"))
    implementation(project(":feature:live"))
    implementation(project(":feature:setting"))
    implementation(project(":feature:collect"))
    implementation(project(":feature:history"))
    implementation(project(":feature:drive"))
    implementation(project(":feature:push"))

    implementation(libs.xxpermissions)
    implementation(libs.autosize)
    implementation(libs.loadsir)
    implementation(libs.flow.layout)
    implementation(libs.jsoup)
    // BT 引擎本地 HTTP 边下边播服务
    implementation(libs.nanohttpd)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    configurations.configureEach {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "com.android.support")
    }
}
