import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.chaquo.python) apply false
}

// 全项目统一构建时间：只在根项目配置阶段计算一次，
// 各模块通过 rootProject.extra["buildTime"] 共享
val buildTime = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
    .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
    .format(Date())
extra["buildTime"] = buildTime

// engine:pyramid 定义了 abi flavor dimension（arm32/arm64），
// 所有未声明该 dimension 的 library 模块需统一声明 fallback，
// 最终 ABI 由 app 模块的 productFlavors 决定并覆盖此默认值
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            defaultConfig {
                missingDimensionStrategy("abi", "arm64")
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
