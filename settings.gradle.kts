pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "TVBoxKotlin"

include(":app")

// 爬虫运行时引擎组（最底层，被 core:spider 单向依赖）
include(":engine:quickjs")
include(":engine:pyramid")

// 核心模块组（业务基础能力，被 feature 单向依赖，模块间通过契约接口通信）
include(":core:common")
include(":core:ui")
include(":core:network")
include(":core:data")
include(":core:spider")
include(":core:player")
include(":core:server")

// 功能模块组（界面层，单向依赖 core，feature 间禁止相互依赖）
include(":feature:splash")
include(":feature:home")
include(":feature:detail")
include(":feature:play")
include(":feature:search")
include(":feature:live")
include(":feature:setting")
include(":feature:collect")
include(":feature:history")
include(":feature:drive")
include(":feature:push")
