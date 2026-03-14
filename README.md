
# Bug：首次安装首次播放首次开启弹幕，会出现弹幕的URL端口为127.0.0.1:-1的情况，开启弹幕的状态下重新启动会恢复正常，切换API配置地址又会复现，暂无能力修复


# Box API 配置模板
## 完整配置示例

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
    }
  ],
  
  "parses": [
    {
      "name": "解析A",
      "type": 0,
      "url": "https://example.com/parse?url=",
      "ext": {}
    },
    {
      "name": "解析B",
      "type": 1,
      "url": "https://example.com/jsonparse",
      "ext": {
        "flag": ["qq", "iqiyi", "qiyi", "youku", "mgtv"]
      }
    },
    {
      "name": "解析C",
      "url": "SuperParse"
    }
  ],
  
  "flags": ["qq", "iqiyi", "qiyi", "youku", "mgtv", "letv", "sohu", "pptv"],
  
  "lives": [
    {
      "group": "直播",
      "channels": [
        {
          "name": "CCTV-1",
          "urls": ["http://example.com/cctv1.m3u8$高清"]
        }
      ]
    },
    {
      "type": "0",
      "url": "http://example.com/live.txt",
      "epg": "http://example.com/epg.xml",
      "logo": "https://logo.example.com/{name}.png",
      "playerType": 1
    }
  ],
  
  "rules": [
    {
      "host": "example.com",
      "rule": ["mp4", "m3u8"]
    },
    {
      "hosts": ["example1.com", "example2.com"],
      "regex": ["advertisement", "ad"]
    },
    {
      "hosts": ["example3.com"],
      "script": ["document.querySelector('.play').click()"]
    }
  ],
  
  "ads": ["ad.example.com", "ads.example.com"],
  
  "ijk": [
    {
      "group": "软解码",
      "options": [
        {"category": 4, "name": "opensles", "value": "0"},
        {"category": 4, "name": "overlay-format", "value": "842225234"},
        {"category": 4, "name": "framedrop", "value": "1"},
        {"category": 4, "name": "soundtouch", "value": "1"},
        {"category": 4, "name": "start-on-prepared", "value": "1"},
        {"category": 1, "name": "http-detect-rangeupport", "value": "0"},
        {"category": 1, "name": "fflags", "value": "fastseek"},
        {"category": 2, "name": "skip_loop_filter", "value": "48"},
        {"category": 4, "name": "reconnect", "value": "1"},
        {"category": 4, "name": "enable-accurate-seek", "value": "0"},
        {"category": 4, "name": "mediacodec", "value": "0"},
        {"category": 4, "name": "mediacodec-auto-rotate", "value": "0"},
        {"category": 4, "name": "mediacodec-handle-resolution-change", "value": "0"},
        {"category": 4, "name": "mediacodec-hevc", "value": "0"},
        {"category": 1, "name": "dns_cache_timeout", "value": "600000000"},
        {"category": 1, "name": "max-buffer-size", "value": "15728640"},
        {"category": 4, "name": "min-frames", "value": "5"},
        {"category": 4, "name": "max_cached_duration", "value": "5000"},
        {"category": 1, "name": "analyzeduration", "value": "1000000"},
        {"category": 1, "name": "probesize", "value": "16384"}
      ]
    },
    {
      "group": "硬解码",
      "options": [
        {"category": 4, "name": "opensles", "value": "0"},
        {"category": 4, "name": "overlay-format", "value": "842225234"},
        {"category": 4, "name": "framedrop", "value": "1"},
        {"category": 4, "name": "soundtouch", "value": "1"},
        {"category": 4, "name": "start-on-prepared", "value": "1"},
        {"category": 1, "name": "http-detect-rangeupport", "value": "0"},
        {"category": 1, "name": "fflags", "value": "fastseek"},
        {"category": 2, "name": "skip_loop_filter", "value": "48"},
        {"category": 4, "name": "reconnect", "value": "1"},
        {"category": 4, "name": "enable-accurate-seek", "value": "0"},
        {"category": 4, "name": "mediacodec", "value": "1"},
        {"category": 4, "name": "mediacodec-auto-rotate", "value": "1"},
        {"category": 4, "name": "mediacodec-handle-resolution-change", "value": "1"},
        {"category": 4, "name": "mediacodec-hevc", "value": "1"},
        {"category": 1, "name": "dns_cache_timeout", "value": "600000000"},
        {"category": 1, "name": "max-buffer-size", "value": "15728640"},
        {"category": 4, "name": "min-frames", "value": "5"},
        {"category": 4, "name": "max_cached_duration", "value": "5000"},
        {"category": 1, "name": "analyzeduration", "value": "1000000"},
        {"category": 1, "name": "probesize", "value": "16384"}
      ]
    }
  ]
}
```

## 字段说明

### 全局配置

| 字段 | 类型 | 说明 |
|------|------|------|
| `spider` | string | JAR爬虫文件地址，可附加md5校验 `;md5;xxxxx` |
| `wallpaper` | string | 背景壁纸URL |
| `jarCache` | string | 是否启用JAR缓存，`"true"` 或 `"false"` |
| `livePlayHeaders` | array | 直播播放请求头配置 |

### 站点配置 (sites)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `key` | string | 是 | 站点唯一标识 |
| `name` | string | 是 | 站点显示名称 |
| `type` | int | 是 | 类型：0=xml, 1=json, 3=Spider |
| `api` | string | 是 | 站点API地址或爬虫名称 |
| `searchable` | int | 否 | 是否可搜索：0=否, 1=是（默认1） |
| `quickSearch` | int | 否 | 是否快速搜索：0=否, 1=是（默认1） |
| `filterable` | int | 否 | 是否可筛选：0=否, 1=是（默认1） |
| `hide` | int | 否 | 是否在列表隐藏：0=显示, 1=隐藏（默认0） |
| `playUrl` | string | 否 | 站点解析URL |
| `ext` | string | 否 | 扩展数据 |
| `jar` | string | 否 | 自定义JAR文件地址 |
| `playerType` | int | 否 | 播放器类型：-1=默认, 0=系统, 1=IJK, 2=Exo, 10=MXPlayer |
| `categories` | array | 否 | 分类列表，如 `["电影", "电视剧"]` |
| `click` | string | 否 | 嗅探点击选择器，如 `ddrk.me;#id` |
| `style` | string | 否 | 展示风格 |

### 解析配置 (parses)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 解析名称 |
| `type` | int | 是 | 类型：0=普通嗅探, 1=json, 2=Json扩展, 3=聚合 |
| `url` | string | 是 | 解析地址，设为 `"SuperParse"` 直接启用超级解析且不会出现在解析列表中，不区分type类型 |
| `ext` | object | 否 | 扩展配置，type=1时需要包含 `flag` 数组指定支持的源 |

**超级解析说明：**
- 在 `parses` 中添加 `{"name": "超级解析", "url": "SuperParse"}` 可启用超级解析功能
- 超级解析会自动聚合所有 type=0 和 type=1 的解析进行并行解析
- type=1 的解析需要在 `ext` 中配置 `{"flag": ["qq", "iqiyi", "youku"]}` 指定支持的播放源标识

### 直播配置 (lives)

支持两种格式：

**格式1 - 详细频道列表：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `group` | string | 分组名称，可附加密码 `分组名_密码` |
| `channels` | array | 频道列表 |
| `channels[].name` | string | 频道名称 |
| `channels[].urls` | array | 播放地址列表，格式 `url$源名称` |

**格式2 - FongMi格式：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 类型："0"=txt格式 |
| `url` | string | 直播源地址 |
| `epg` | string | EPG节目单地址 |
| `logo` | string | 台标地址，支持占位符 `{name}`(频道名) 和 `{epgid}`(EPG ID) ，占位符仅能使用其一不能同时使用|
| `playerType` | int | 播放器类型 |

### 嗅探规则 (rules)

| 字段 | 类型 | 说明 |
|------|------|------|
| `host` | string | 单个域名 |
| `hosts` | array | 多个域名 |
| `rule` | array | 嗅探规则列表 |
| `filter` | array | 过滤规则列表 |
| `regex` | array | 正则规则列表（广告过滤） |
| `script` | array | 脚本规则列表（自动点击等） |

### IJK解码配置 (ijk)

| 字段 | 类型 | 说明 |
|------|------|------|
| `group` | string | 配置组名称，如 `"软解码"`、`"硬解码"` |
| `options` | array | 解码选项列表 |
| `options[].category` | int | 选项分类 |
| `options[].name` | string | 选项名称 |
| `options[].value` | string | 选项值 |

### 广告拦截 (ads)

广告域名列表，数组格式：

```json
"ads": ["ad.example.com", "ads.example.com"]
```

### VIP解析标识 (flags)

需要VIP解析的播放源标识列表：

```json
"flags": ["qq", "iqiyi", "qiyi", "youku", "mgtv", "letv", "sohu", "pptv"]
```
