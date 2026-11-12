# Box-Kotlin

基于 Kotlin 的 Android TV / 手机影视聚合客户端，采用多模块 MVVM 架构，支持订阅配置、爬虫（Jar/JS/Py）、直播、弹幕与网盘播放。

- 包名：`com.tvbox.kotlin`
- 最低版本：Android 7.0（API 24），targetSdk 36
- 内置播放内核：Exo（Media3），外置支持 MX / Reex / Kodi

## 技术栈

Kotlin、Coroutines、Hilt、OkHttp、XStream、Room、Media3 ExoPlayer、QuickJS（JS 爬虫）、Pyramid（Python 爬虫）。

## 项目结构

```
app                     应用壳与依赖注入实现
engine/quickjs          JS 爬虫运行时
engine/pyramid          Python 爬虫运行时
core/common             通用数据模型、工具、偏好设置
core/ui                 通用 UI 组件
core/network            网络封装（OkHttp、DoH、TLS 兼容、Hosts 映射）
core/data               数据持久化（Room / 配置快照）
core/spider             订阅配置解析与爬虫调度
core/player             播放内核装配与管理
core/server             本地 HTTP 服务（clan 协议、代理、超级解析）
feature/splash          启动页
feature/home            首页（分类聚合、筛选、推荐）
feature/detail          详情页
feature/play            播放页（全屏 / 小窗）
feature/search          搜索（聚合 / 快速搜索）
feature/live            直播与 EPG
feature/setting         设置
feature/collect         收藏
feature/history         历史
feature/drive           网盘（WebDAV 等）
feature/push            推送
```

模块依赖方向：`feature:* → core:* → engine:*`，feature 之间禁止相互依赖。

## 构建

环境要求：JDK 21、Android SDK。`gradle.properties` 中的 `org.gradle.java.home` 请按本机 JDK 路径修改。

```bash
# 64 位 Release（arm64-v8a）
./gradlew.bat assembleArm64GenericRelease

# 32 位 Release（armeabi-v7a）
./gradlew.bat assembleArm32GenericRelease

# 同时构建 32/64 位
./gradlew.bat assembleRelease
```

产物位于 `app/build/outputs/apk/<flavor>/release/`。

签名配置：复制 `keystore.properties.example` 为 `keystore.properties`（已被 `.gitignore` 忽略），填入 keystore 路径与密码。

## 订阅配置格式

订阅地址返回 JSON；本地配置支持 `clan://localhost/<路径>`（经 App 内嵌 HTTP 服务读取），局域网可用 `clan://<局域网IP>/<路径>`。

### 完整示例

```json
{
  "spider": "https://example.com/spider.jar;md5;xxxxx",
  "wallpaper": "https://example.com/wallpaper.jpg",
  "jarCache": "true",
  "livePlayHeaders": [],
  "sites": [
    {
      "key": "csp_Demo",
      "name": "演示源",
      "type": 3,
      "api": "csp_Demo",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1,
      "hide": 0,
      "playUrl": "",
      "ext": "",
      "jar": "",
      "playerType": -1,
      "categories": ["电影", "电视剧", "综艺", "动漫"],
      "click": "",
      "style": ""
    },
    {
      "key": "cms_Demo",
      "name": "采集源",
      "type": 1,
      "api": "https://example.com/api.php/provide/vod/"
    }
  ],
  "parses": [
    { "name": "解析A", "type": 0, "url": "https://example.com/parse?url=" },
    { "name": "解析B", "type": 1, "url": "https://example.com/jsonparse",
      "ext": { "flag": ["qq", "iqiyi", "youku"] } },
    { "name": "超级解析", "url": "SuperParse" }
  ],
  "flags": ["qq", "iqiyi", "qiyi", "youku", "mgtv"],
  "lives": [
    { "type": "0", "url": "http://example.com/live.txt",
      "epg": "http://example.com/epg.xml",
      "logo": "https://logo.example.com/{name}.png" }
  ],
  "rules": [
    { "host": "example.com", "rule": ["mp4", "m3u8"], "filter": ["直链"] },
    { "hosts": ["ad1.com", "ad2.com"], "regex": ["ad", "advertisement"] },
    { "hosts": ["example3.com"], "script": ["document.querySelector('.play').click()"] }
  ],
  "ads": ["ad.example.com"],
  "hosts": ["example.com=1.2.3.4"]
}
```

### 全局字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `spider` | string | 爬虫 Jar 地址，可附加 `;md5;校验值` |
| `wallpaper` | string | 背景壁纸地址 |
| `jarCache` | string | 是否复用 Jar 缓存，`"true"` / `"false"` |
| `livePlayHeaders` | array | 直播播放附加请求头 |
| `sites`（或 `video.sites`） | array | 站点列表 |
| `parses` | array | 解析器列表 |
| `flags` | array | 需要 VIP 解析的播放标识 |
| `lives` | array | 直播源配置 |
| `rules` | array | 嗅探与广告规则 |
| `ads` | array | 广告域名黑名单 |
| `hosts` | array | 域名→IP 直连映射，格式 `"域名=IP"` |

### 站点（sites）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `key` | string | 是 | 站点唯一标识 |
| `name` | string | 是 | 站点显示名称 |
| `type` | int | 是 | `0`=XML 采集站，`1`=JSON 采集站，`3`=爬虫（Jar/JS/Py），`4`=JSON 采集站（支持筛选扩展） |
| `api` | string | 是 | 采集站接口地址或爬虫类名 |
| `searchable` | int | 否 | 是否参与搜索，默认 1 |
| `quickSearch` | int | 否 | 是否参与快速搜索，默认 1 |
| `filterable` | int | 否 | 是否支持筛选，默认 1 |
| `hide` | int | 否 | 是否在列表隐藏，默认 0 |
| `playUrl` | string | 否 | 播放解析地址 |
| `ext` | string | 否 | 站点扩展参数 |
| `jar` | string | 否 | 站点独立 Jar 地址 |
| `playerType` | int | 否 | `-1` 跟随全局；`0/1/2` 统一归为内置 Exo；`3`=MX，`4`=Reex，`5`=Kodi |
| `categories` | array | 否 | 首页分类排序，未列出的分类追加到末尾 |
| `click` | string | 否 | 嗅探点击选择器 |
| `style` | string | 否 | 展示风格 |

采集站（type 0/1/4）的首页分类由 App 直连获取：优先请求苹果CMS 的 `at/xml/?ac=list` 解析 `<class>` 节点，接口不可用时回退为 JSON 列表中的分类去重。

### 解析器（parses）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 解析器名称 |
| `type` | int | 否 | `0`=普通嗅探，`1`=JSON 解析，`2`=JSON 扩展，`3`=聚合 |
| `url` | string | 是 | 解析地址；填 `SuperParse` 表示启用超级解析，本身不出现在解析列表 |
| `ext` | object | 否 | type=1 时需包含 `flag` 数组声明支持的播放源 |

超级解析：在 `parses` 中加入 `{"name": "超级解析", "url": "SuperParse"}`，App 会聚合所有可用解析器并行竞速。

### 直播（lives）

支持两种格式。

格式一：详细频道列表

```json
[
  {
    "group": "央视",
    "channels": [
      { "name": "CCTV-1", "urls": ["http://example.com/cctv1.m3u8$高清"] }
    ]
  }
]
```

格式二：FongMi 格式

```json
[
  {
    "type": "0",
    "url": "http://example.com/live.txt",
    "epg": "http://example.com/epg.xml",
    "logo": "https://logo.example.com/{name}.png",
    "playerType": 1
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `group` | string | 分组名称，可附加密码 `分组名_密码` |
| `channels[].name` | string | 频道名称 |
| `channels[].urls` | array | 播放地址列表，格式 `url$源名称` |
| `type` | string | FongMi 格式固定 `"0"`（txt 直播源） |
| `url` | string | 直播源地址 |
| `epg` | string | EPG 节目单地址 |
| `logo` | string | 台标地址，占位符 `{name}`（频道名）或 `{epgid}`（EPG ID），二者不可混用 |

直播内置播放内核恒为 Exo，配置中的 `playerType` 会被忽略。

### 嗅探规则（rules）

| 字段 | 类型 | 说明 |
|------|------|------|
| `host` | string | 单个域名（与 `rule`/`filter` 搭配） |
| `hosts` | array | 多个域名（与 `regex`/`script` 搭配） |
| `rule` | array | 嗅探规则 |
| `filter` | array | 结果过滤规则 |
| `regex` | array | 广告匹配规则，含 `ad` 的规则按广告处理 |
| `script` | array | 注入脚本（自动点击等） |

### 其它

- `ads`：广告域名黑名单，命中后拦截请求。
- `flags`：需要 VIP 解析的播放标识（如 `qq`、`iqiyi`、`youku`）。
- `hosts`：DNS 直连映射，格式 `"域名=IP"`，用于绕过 DNS 污染。

## 免责声明

本项目仅用于技术学习与交流，自身不提供任何影视内容。订阅配置与媒体资源均由用户自行提供，请遵守当地法律法规。
