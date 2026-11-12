package com.tvbox.kotlin.feature.play.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * B 站弹幕表情映射：将弹幕文本中的 [表情名] 映射为表情图片 URL。
 *
 * 词表来源（两层，保证稳定）：
 * 1. [builtin] 内置完整词表（B 站小黄脸 + 热词系列 + 中国绊爱共 320 个，官方 emote 图片地址），
 *    离线可用、链接稳定，是弹幕表情的主力覆盖；
 * 2. 运行时拉取 B 站表情包接口作为增量（新增表情即时补充到 [map]），拉取失败不影响内置表。
 *
 * 加载策略：进程内仅尝试拉取一次（成功或失败均置 [loaded]），失败时使用内置表。
 */
object DanmuEmote {

    private const val TAG = "DanmuEmote"
    /**
     * B 站表情包明细接口（无需 Cookie）：小黄脸(1) + 热词系列一(53) + 中国绊爱(105)，
     * 作为内置表的增量来源（内置表已覆盖这三个包，接口用于拉取后续新增表情）。
     */
    private const val EMOTE_API =
        "https://api.bilibili.com/x/emote/package?ids=1,53,105&business=reply"
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    /** 表情名（含方括号，如 [微笑]）→ 图片 URL，动态词表（增量） */
    private val map = ConcurrentHashMap<String, String>()

    /** 是否已完成词表加载（成功或失败均不再重复拉取） */
    @Volatile
    private var loaded = false

    private var loading = false

    /** 查询表情图片 URL：优先动态词表（增量），未命中回退内置 B 站表，再回退腾讯表，无映射返回 null */
    fun getUrl(text: String): String? = map[text] ?: builtin[text] ?: tencentBuiltin[text]

    /**
     * 确保表情词表已加载（进程内只拉取一次，作为内置表的增量补充）。
     * 在弹幕解析的 IO 线程调用；成功或失败均不再重复请求，内置表始终兜底。
     */
    suspend fun ensureLoaded() {
        if (loaded || loading) return
        synchronized(this) {
            if (loaded || loading) return
            loading = true
        }
        try {
            withContext(Dispatchers.IO) {
                val json = fetchEmoteJson() ?: return@withContext
                val packages = JSONObject(json).optJSONObject("data")?.optJSONArray("packages")
                    ?: return@withContext
                for (i in 0 until packages.length()) {
                    val emoteArr = packages.optJSONObject(i)?.optJSONArray("emote") ?: continue
                    for (j in 0 until emoteArr.length()) {
                        val emote = emoteArr.optJSONObject(j) ?: continue
                        val text = emote.optString("text")
                        val url = emote.optString("url")
                        if (text.isNotEmpty() && url.startsWith("http")) {
                            map[text] = url
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 增量拉取失败时使用内置词表，不影响弹幕加载
        } finally {
            loaded = true
            loading = false
        }
    }

    /** 拉取表情接口 JSON */
    private fun fetchEmoteJson(): String? {
        return try {
            val conn = (URL(EMOTE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://www.bilibili.com")
            }
            try {
                conn.connect()
                if (conn.responseCode != 200) return null
                conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 内置完整弹幕表情词表（B 站官方 emote 图片地址，320 个，覆盖小黄脸/热词系列/中国绊爱）。
     * 链接使用官方 CDN（i0.hdslb.com），稳定且不依赖登录。
     */
    private val builtin: Map<String, String> = mapOf(
        "[doge_金箍]" to "https://i0.hdslb.com/bfs/emote/aadaca1895e09c5596fc6365192ec93a23718cf0.png",
        "[笑哭]" to "https://i0.hdslb.com/bfs/emote/c3043ba94babf824dea03ce500d0e73763bf4f40.png",
        "[蹲蹲]" to "https://i0.hdslb.com/bfs/emote/878f1dfac79622050f730d1fee5f7b1a55951c74.png",
        "[星星眼]" to "https://i0.hdslb.com/bfs/emote/63c9d1a31c0da745b61cdb35e0ecb28635675db2.png",
        "[微笑]" to "https://i0.hdslb.com/bfs/emote/685612eadc33f6bc233776c6241813385844f182.png",
        "[吃瓜]" to "https://i0.hdslb.com/bfs/emote/4191ce3c44c2b3df8fd97c33f85d3ab15f4f3c84.png",
        "[打call]" to "https://i0.hdslb.com/bfs/emote/431432c43da3ee5aab5b0e4f8931953e649e9975.png",
        "[OK]" to "https://i0.hdslb.com/bfs/emote/4683fd9ffc925fa6423110979d7dcac5eda297f4.png",
        "[doge]" to "https://i0.hdslb.com/bfs/emote/3087d273a78ccaff4bb1e9972e2ba2a7583c9f11.png",
        "[大哭]" to "https://i0.hdslb.com/bfs/emote/2caafee2e5db4db72104650d87810cc2c123fc86.png",
        "[辣眼睛]" to "https://i0.hdslb.com/bfs/emote/35d62c496d1e4ea9e091243fa812866f5fecc101.png",
        "[滑稽]" to "https://i0.hdslb.com/bfs/emote/d15121545a99ac46774f1f4465b895fe2d1411c3.png",
        "[喜极而泣]" to "https://i0.hdslb.com/bfs/emote/485a7e0c01c2d70707daae53bee4a9e2e31ef1ed.png",
        "[呲牙]" to "https://i0.hdslb.com/bfs/emote/b5a5898491944a4268360f2e7a84623149672eb6.png",
        "[歪嘴]" to "https://i0.hdslb.com/bfs/emote/4384050fbab0586259acdd170b510fe262f08a17.png",
        "[调皮]" to "https://i0.hdslb.com/bfs/emote/8290b7308325e3179d2154327c85640af1528617.png",
        "[妙啊]" to "https://i0.hdslb.com/bfs/emote/b4cb77159d58614a9b787b91b1cd22a81f383535.png",
        "[嗑瓜子]" to "https://i0.hdslb.com/bfs/emote/28a91da1685d90124cfeead74622e1ebb417c0eb.png",
        "[藏狐]" to "https://i0.hdslb.com/bfs/emote/ba0937ef6f3ccca85e2e0047e6263f3b4da37201.png",
        "[脱单doge]" to "https://i0.hdslb.com/bfs/emote/bf7e00ecab02171f8461ee8cf439c73db9797748.png",
        "[笑]" to "https://i0.hdslb.com/bfs/emote/81edf17314cea3b48674312b4364df44d5c01f17.png",
        "[给心心]" to "https://i0.hdslb.com/bfs/emote/1597302b98827463f5b75c7cac1f29ea6ce572c4.png",
        "[脸红]" to "https://i0.hdslb.com/bfs/emote/0922c375da40e6b69002bd89b858572f424dcfca.png",
        "[嘟嘟]" to "https://i0.hdslb.com/bfs/emote/abd7404537d8162720ccbba9e0a8cdf75547e07a.png",
        "[哦呼]" to "https://i0.hdslb.com/bfs/emote/362bded07ea5434886271d23fa25f5d85d8af06c.png",
        "[喜欢]" to "https://i0.hdslb.com/bfs/emote/8a10a4d73a89f665feff3d46ca56e83dc68f9eb8.png",
        "[酸了]" to "https://i0.hdslb.com/bfs/emote/92b1c8cbceea3ae0e8e32253ea414783e8ba7806.png",
        "[害羞]" to "https://i0.hdslb.com/bfs/emote/9d2ec4e1fbd6cb1b4d12d2bbbdd124ccb83ddfda.png",
        "[嫌弃]" to "https://i0.hdslb.com/bfs/emote/de4c0783aaa60ec03de0a2b90858927bfad7154b.png",
        "[疑惑]" to "https://i0.hdslb.com/bfs/emote/b7840db4b1f9f4726b7cb23c0972720c1698d661.png",
        "[偷笑]" to "https://i0.hdslb.com/bfs/emote/6c49d226e76c42cd8002abc47b3112bc5a92f66a.png",
        "[惊讶]" to "https://i0.hdslb.com/bfs/emote/f8e9a59cad52ae1a19622805696a35f0a0d853f3.png",
        "[捂脸]" to "https://i0.hdslb.com/bfs/emote/6921bb43f0c634870b92f4a8ad41dada94a5296d.png",
        "[阴险]" to "https://i0.hdslb.com/bfs/emote/ba8d5f8e7d136d59aab52c40fd3b8a43419eb03c.png",
        "[呆]" to "https://i0.hdslb.com/bfs/emote/33ad6000d9f9f168a0976bc60937786f239e5d8c.png",
        "[抠鼻]" to "https://i0.hdslb.com/bfs/emote/cb89184c97e3f6d50acfd7961c313ce50360d70f.png",
        "[大笑]" to "https://i0.hdslb.com/bfs/emote/ca94ad1c7e6dac895eb5b33b7836b634c614d1c0.png",
        "[惊喜]" to "https://i0.hdslb.com/bfs/emote/0afecaf3a3499479af946f29749e1a6c285b6f65.png",
        "[点赞]" to "https://i0.hdslb.com/bfs/emote/1a67265993913f4c35d15a6028a30724e83e7d35.png",
        "[无语]" to "https://i0.hdslb.com/bfs/emote/44667b7d9349957e903b1b62cb91fb9b13720f04.png",
        "[鼓掌]" to "https://i0.hdslb.com/bfs/emote/895d1fc616b4b6c830cf96012880818c0e1de00d.png",
        "[尴尬]" to "https://i0.hdslb.com/bfs/emote/cb321684ed5ce6eacdc2699092ab8fe7679e4fda.png",
        "[灵魂出窍]" to "https://i0.hdslb.com/bfs/emote/43d3db7d97343c01b47e22cfabeca84b4251f35a.png",
        "[傲娇]" to "https://i0.hdslb.com/bfs/emote/010540d0f61220a0db4922e4a679a1d8eca94f4e.png",
        "[委屈]" to "https://i0.hdslb.com/bfs/emote/d2f26cbdd6c96960320af03f5514c5b524990840.png",
        "[疼]" to "https://i0.hdslb.com/bfs/emote/905fd9a99ec316e353b9bd4ecd49a5f0a301eabf.png",
        "[冷]" to "https://i0.hdslb.com/bfs/emote/cb0ebbd0668640f07ebfc0e03f7a18a8cd00b4ed.png",
        "[热]" to "https://i0.hdslb.com/bfs/emote/4e58a2a6f5f1580ac33df2d2cf7ecad7d9ab3635.png",
        "[生病]" to "https://i0.hdslb.com/bfs/emote/0f25ce04ae1d7baf98650986454c634f6612cb76.png",
        "[生气]" to "https://i0.hdslb.com/bfs/emote/3195714219c4b582a4fb02033dd1519913d0246d.png",
        "[捂眼]" to "https://i0.hdslb.com/bfs/emote/c5c6d6982e1e53e478daae554b239f2b227b172b.png",
        "[嘘声]" to "https://i0.hdslb.com/bfs/emote/e64af664d20716e090f10411496998095f62f844.png",
        "[思考]" to "https://i0.hdslb.com/bfs/emote/cfa9b7e89e4bfe04bbcd34ccb1b0df37f4fa905c.png",
        "[再见]" to "https://i0.hdslb.com/bfs/emote/fc510306bae26c9aec7e287cdf201ded27b065b9.png",
        "[翻白眼]" to "https://i0.hdslb.com/bfs/emote/eba54707c7168925b18f6f8b1f48d532fe08c2b1.png",
        "[抓狂]" to "https://i0.hdslb.com/bfs/emote/4c87afff88c22439c45b79e9d2035d21d5622eba.png",
        "[哈欠]" to "https://i0.hdslb.com/bfs/emote/888d877729cbec444ddbd1cf4c9af155a7a06086.png",
        "[奋斗]" to "https://i0.hdslb.com/bfs/emote/bb2060c15dba7d3fd731c35079d1617f1afe3376.png",
        "[墨镜]" to "https://i0.hdslb.com/bfs/emote/3a03aebfc06339d86a68c2d893303b46f4b85771.png",
        "[难过]" to "https://i0.hdslb.com/bfs/emote/a651db36701610aa70a781fa98c07c9789b11543.png",
        "[口罩]" to "https://i0.hdslb.com/bfs/emote/3ad2f66b151496d2a5fb0a8ea75f32265d778dd3.png",
        "[撇嘴]" to "https://i0.hdslb.com/bfs/emote/531863568e5668c5ac181d395508a0eeb1f0cda4.png",
        "[黑眼圈_金箍]" to "https://i0.hdslb.com/bfs/emote/3d8edacc6efa4bc397642ee2bdc688c2eb976b4f.png",
        "[猴哥]" to "https://i0.hdslb.com/bfs/emote/4dae851bd07b91418824ba3897659b7b9017114d.png",
        "[鸭科夫_流汗鸭]" to "https://i0.hdslb.com/bfs/emote/b49b325224cef1ef8313728022aa86e93e65bd08.png",
        "[鸭科夫_干嘛鸭]" to "https://i0.hdslb.com/bfs/emote/2e67293d703e6501d7a639870477cbcc16277a23.png",
        "[鸭科夫_红温鸭]" to "https://i0.hdslb.com/bfs/emote/3ff2737eb4d78fba74f37276d99755ee5df621b8.png",
        "[黑猫]" to "https://i0.hdslb.com/bfs/emote/6bd61d4f5c29c17098f31dfb6ce2140dd2b728a8.png",
        "[三花猫]" to "https://i0.hdslb.com/bfs/emote/3a36c1f88956996b31a835c13041f4d505f2e2e0.png",
        "[折耳猫]" to "https://i0.hdslb.com/bfs/emote/4a09dc958bdd82391ba97af0d09dab0925c5b46c.png",
        "[银魂_justaway]" to "https://i0.hdslb.com/bfs/emote/7ef3eecc6b69404524e86b5f48d94c02ab0a9b34.png",
        "[我独自升级_疑惑]" to "https://i0.hdslb.com/bfs/emote/6cac4bce69f120e04c44f09bcd624c8dcd5675fa.png",
        "[怪兽8号_奇可露]" to "https://i0.hdslb.com/bfs/emote/694a2bc638d2242e379d802715d29536357f9d36.png",
        "[颂乐人偶_眯眼笑]" to "https://i0.hdslb.com/bfs/emote/6087f6b3919b915c8922e608cfde147dd5ece43b.png",
        "[坂本_佛系]" to "https://i0.hdslb.com/bfs/emote/89c70d9044637a66fceaf99d89b257984117ce36.png",
        "[生气_金箍]" to "https://i0.hdslb.com/bfs/emote/fb3fbcf44157a5ba460b62341a86b38c11e7a664.png",
        "[龙年]" to "https://i0.hdslb.com/bfs/emote/7d0e1e48a56a6b739bcbb4f602fe555082d64ed7.png",
        "[豹富]" to "https://i0.hdslb.com/bfs/emote/3d1dbe52ea16e12ff7b1c371196f728a4097fb33.png",
        "[兔兔岛_开心]" to "https://i0.hdslb.com/bfs/emote/40dabdb3237a9d8c726f663360d7e967eac3ac01.png",
        "[兔兔岛_吃面]" to "https://i0.hdslb.com/bfs/emote/7277d90e321bf4407377ff18cc0bfcde106234d9.png",
        "[兔兔岛_睡觉]" to "https://i0.hdslb.com/bfs/emote/c272aa0e59e2e20432e54fa229b15652f2593308.png",
        "[重返未来1999_喂]" to "https://i0.hdslb.com/bfs/emote/4e224148584938588a0d500eea8127db21f06ebb.png",
        "[重返未来1999_赞]" to "https://i0.hdslb.com/bfs/emote/e250228e3ba06822496dc2ff81e49324298f8bc8.png",
        "[重返未来1999_谢谢老板]" to "https://i0.hdslb.com/bfs/emote/d87d32fd4c91f1045842362c328e8f359dab4fd6.png",
        "[重返未来1999_什么]" to "https://i0.hdslb.com/bfs/emote/8f1a9abbe5364a110aba1bde276d1f2712108edc.png",
        "[胆大党_微笑]" to "https://i0.hdslb.com/bfs/emote/24d13e3f1713d854f93416ac11b3c5ceb4f0120f.png",
        "[水稻]" to "https://i0.hdslb.com/bfs/emote/d530fcaa5100ba12a17a79b55bad342d530c54e3.png",
        "[奶茶干杯]" to "https://i0.hdslb.com/bfs/emote/d5a491990be551ce69f9660da948050df4eab331.png",
        "[汤圆]" to "https://i0.hdslb.com/bfs/emote/93609633a9d194cf336687eb19c01dca95bde719.png",
        "[锦鲤]" to "https://i0.hdslb.com/bfs/emote/643d6c19c8164ffd89e3e9cdf093cf5d773d979c.png",
        "[福到了]" to "https://i0.hdslb.com/bfs/emote/5de5373d354c373cf1617b6b836f3a8d53c5a655.png",
        "[鸡腿]" to "https://i0.hdslb.com/bfs/emote/c7860392815d345fa69c4f00ef18d67dccfbd574.png",
        "[雪花]" to "https://i0.hdslb.com/bfs/emote/a41813c4edf8782047e172c884ebd4507ce5e449.png",
        "[视频卫星]" to "https://i0.hdslb.com/bfs/emote/dce6fc7d6dfeafff01241924db60f8251cca5307.png",
        "[干杯]" to "https://i0.hdslb.com/bfs/emote/8da12d5f55a2c7e9778dcc05b40571979fe208e6.png",
        "[黑洞]" to "https://i0.hdslb.com/bfs/emote/c4e9f0e3f35961d5037cb071b16ddba2170b262c.png",
        "[爱心]" to "https://i0.hdslb.com/bfs/emote/ed04066ea7124106d17ffcaf75600700e5442f5c.png",
        "[胜利]" to "https://i0.hdslb.com/bfs/emote/b49fa9f4b1e7c3477918153b82c60b114d87347c.png",
        "[加油]" to "https://i0.hdslb.com/bfs/emote/c7aaeacb21e107292d3bb053e5abde4a4459ed30.png",
        "[抱拳]" to "https://i0.hdslb.com/bfs/emote/89516218158dbea18ab78e8873060bf95d33bbbe.png",
        "[响指]" to "https://i0.hdslb.com/bfs/emote/1b5c53cf14336903e1d2ae3527ca380a1256a077.png",
        "[保佑]" to "https://i0.hdslb.com/bfs/emote/fafe8d3de0dc139ebe995491d2dac458a865fb30.png",
        "[福]" to "https://i0.hdslb.com/bfs/emote/802429a301ac5b35a0480d9526a070ce67cd8097.png",
        "[支持]" to "https://i0.hdslb.com/bfs/emote/3c210366a5585706c09d4c686a9d942b39feeb50.png",
        "[拥抱]" to "https://i0.hdslb.com/bfs/emote/41780a4254750cdaaccb20735730a36044e98ef3.png",
        "[跪了]" to "https://i0.hdslb.com/bfs/emote/f2b3aee7e521de7799d4e3aa379b01be032698ac.png",
        "[怪我咯]" to "https://i0.hdslb.com/bfs/emote/07cc6077f7f7d75b8d2c722dd9d9828a9fb9e46d.png",
        "[香格里拉边境]" to "https://i0.hdslb.com/bfs/emote/80e53d32b4abd12232126f03c68de7c1a67a1e9f.png",
        "[老鼠]" to "https://i0.hdslb.com/bfs/emote/8e6fb491eb1bb0d5862e7ec8ccf9a3da12b6c155.png",
        "[牛年]" to "https://i0.hdslb.com/bfs/emote/9275275ff1f2659310648221107d20bc4970f106.png",
        "[兔年]" to "https://i0.hdslb.com/bfs/emote/9cb6ee2c42986c56ec361d21d5ccbd096aefab0a.png",
        "[三星堆]" to "https://i0.hdslb.com/bfs/emote/fc7dadaa6986e75b813aa26f3eff3281d5f1a6d1.png",
        "[桃源_给花花]" to "https://i0.hdslb.com/bfs/emote/085d41d0fa25453b58fdc87dc2df538183fea11e.png",
        "[桃源_缘分]" to "https://i0.hdslb.com/bfs/emote/cf1c441507689342713623965035f9bed72b1b17.png",
        "[桃源_傲娇]" to "https://i0.hdslb.com/bfs/emote/ce8314c9c2cfdbe235c239c28b819698e29a02d3.png",
        "[桃源_欢呼]" to "https://i0.hdslb.com/bfs/emote/1f439878d8160bd64a4464904064a10df880c921.png",
        "[桃源_乖巧]" to "https://i0.hdslb.com/bfs/emote/57d087b6437002c2ca9e225dcac0ef226d1f2654.png",
        "[鸣潮_好呀]" to "https://i0.hdslb.com/bfs/emote/b500f106c3be1d61e110e6f1cfaafc5c2311ed37.png",
        "[鸣潮_唉]" to "https://i0.hdslb.com/bfs/emote/8b1a0c279d23f9c1a947c96ffc658d848650a773.png",
        "[鸣潮_咦]" to "https://i0.hdslb.com/bfs/emote/866390294acb5e6cf6017e3f88acffa21d9130bb.png",
        "[鸣潮_嘿]" to "https://i0.hdslb.com/bfs/emote/0cf0a8c8e1f32b9d4144a25c43e972580a7a631f.png",
        "[鸣潮_哇]" to "https://i0.hdslb.com/bfs/emote/759651f8e616075c9494160d2541ed581c55550a.png",
        "[鸣潮_嗯]" to "https://i0.hdslb.com/bfs/emote/441b5da4fdf295728f859f9388d528a79f3367bc.png",
        "[鸣潮_嗨]" to "https://i0.hdslb.com/bfs/emote/2589293ba4ba4e60e65569a079819a8578c48378.png",
        "[鸣潮_祈愿]" to "https://i0.hdslb.com/bfs/emote/8ec500d5820f43852d272349cc32927084628376.png",
        "[鸣潮_你好]" to "https://i0.hdslb.com/bfs/emote/06a9869adc94651e62e1579527d051243dfabcf7.png",
        "[鸣潮_嘿嘿]" to "https://i0.hdslb.com/bfs/emote/177eeb8dc85f38ff3d150ab6bb09f431e4e394e5.png",
        "[洛天依]" to "https://i0.hdslb.com/bfs/emote/9fe06f3594d9afaf4ee2b74770f1c3086ae0ba11.png",
        "[少女乐团派对_派对]" to "https://i0.hdslb.com/bfs/emote/b3ea2c80fdd3b4db112c659e08416f987467d4b9.png",
        "[少女乐团派对_等一下]" to "https://i0.hdslb.com/bfs/emote/bebd886bed3c4d5ded63728eb1e06d0fce0cae8f.png",
        "[少女乐团派对_期待]" to "https://i0.hdslb.com/bfs/emote/da237793f08eab3acd7b811e33ae5aad672a8b0a.png",
        "[少女乐团派对_感想]" to "https://i0.hdslb.com/bfs/emote/6d40f35734f2cea4f081455b9a9560951aa88ee7.png",
        "[少女乐团派对_开心]" to "https://i0.hdslb.com/bfs/emote/1815b2e21a0379ba3d052942ae7314326e0d8272.png",
        "[少女乐团派对_哇哦]" to "https://i0.hdslb.com/bfs/emote/afc0f71bf9a65b1d929f5bd3ebd77cd725e3226e.png",
        "[少女乐团派对_害羞]" to "https://i0.hdslb.com/bfs/emote/3f30402c194d84be15014089d042e436efb83075.png",
        "[少女乐团派对_流汗]" to "https://i0.hdslb.com/bfs/emote/69467ee7811f506d883df698a55fa44757b8ffc8.png",
        "[怪兽8号_开心]" to "https://i0.hdslb.com/bfs/emote/10ff5a4eba58e8d99fa3d5f21c98f10c3b61cc5f.png",
        "[白荆回廊_欢呼]" to "https://i0.hdslb.com/bfs/emote/c326324f72047d4041760bc8cf3eb90003b36bd4.png",
        "[白荆回廊_期待]" to "https://i0.hdslb.com/bfs/emote/9581d1386ce9498e456b39268fdb7b51023bf76a.png",
        "[白荆回廊_啊？]" to "https://i0.hdslb.com/bfs/emote/275a1580ddc2f581d65d3cb647eb92d9abb7788b.png",
        "[白荆回廊_好好好]" to "https://i0.hdslb.com/bfs/emote/a1e2fac2355569c2c1348a69333f76e8f6dfbd6b.png",
        "[白荆回廊_无语]" to "https://i0.hdslb.com/bfs/emote/b85bca2e51f8ad03478c36222cdc6c266a04d9a5.png",
        "[白荆回廊_尴尬]" to "https://i0.hdslb.com/bfs/emote/5f42aa8c1f13126f9dd7554a3b91db9068c6b2fa.png",
        "[坎公骑冠剑_吃鸡]" to "https://i0.hdslb.com/bfs/emote/c4248a7b6ab326d66c83fd1fb58f1a50f99df332.png",
        "[坎公骑冠剑_钻石]" to "https://i0.hdslb.com/bfs/emote/0b97c7e50e0cc963370e62fbb9b55f51bbe7f8ab.png",
        "[坎公骑冠剑_无语]" to "https://i0.hdslb.com/bfs/emote/80eba0ce64c3fc1279b4daede2f1979cb2380e78.png",
        "[来古-疑问]" to "https://i0.hdslb.com/bfs/emote/032fdc0d9d9fe6334776f6c39518a959b73b98f4.png",
        "[来古-沉思]" to "https://i0.hdslb.com/bfs/emote/4ee07ff03266d62b246be0b950bebb2abf3d997c.png",
        "[来古-呆滞]" to "https://i0.hdslb.com/bfs/emote/9a70b365e523f2379f395031ceefcebb75a45903.png",
        "[来古-震撼]" to "https://i0.hdslb.com/bfs/emote/8b40f228675602a317d32007de6b795c101135ec.png",
        "[来古-注意]" to "https://i0.hdslb.com/bfs/emote/4b671ba32a2581cf40e5cd41c67b111eb8010de0.png",
        "[FGO_尴尬]" to "https://i0.hdslb.com/bfs/emote/1c6d649c507cc20f5cac2f630ba115b0da24f8d9.png",
        "[FGO_跪]" to "https://i0.hdslb.com/bfs/emote/d2fecbdcac237b801e9354d5f6671875ae0cce1b.png",
        "[FGO_好耶]" to "https://i0.hdslb.com/bfs/emote/bb2279baa660dd196fd944a2c7bd2f7f22e55f80.png",
        "[FGO_好棒]" to "https://i0.hdslb.com/bfs/emote/66d8b3d85fb6d816217badddcc5bf868bf750388.png",
        "[FGO_礼物]" to "https://i0.hdslb.com/bfs/emote/a132811673c8cc2743884c74f34ce7c48f8c04a8.png",
        "[FGO_无语]" to "https://i0.hdslb.com/bfs/emote/94360c282f8fc97dbcb7dae3fd3c0e92b24ffc4b.png",
        "[FGO_耶]" to "https://i0.hdslb.com/bfs/emote/cb57c18365601d277e5a5d52b0957616d034f04c.png",
        "[FGO_开心]" to "https://i0.hdslb.com/bfs/emote/300dadb9ec3cf14cf5ed55e2542947fe6e4f0295.png",
        "[FGO_汗]" to "https://i0.hdslb.com/bfs/emote/93d8a770a22914ade2354ca928bb1799b2260d00.png",
        "[FGO_怒]" to "https://i0.hdslb.com/bfs/emote/75ee235f331b3c714e1cfb264fff9e562b20944b.png",
        "[FGO_偷看]" to "https://i0.hdslb.com/bfs/emote/b2d752b135b72a93c13b26bb0e272070a906257b.png",
        "[初音未来_大笑]" to "https://i0.hdslb.com/bfs/emote/8e7f71cda83ce407b0684702983399f8ed982f17.png",
        "[原神_哇]" to "https://i0.hdslb.com/bfs/emote/8188ddf95bace929d382c7a83214afde79d83bfc.png",
        "[原神_哼]" to "https://i0.hdslb.com/bfs/emote/91ed33b74bc36873c3ac8b2648f70d7ab6d8ab78.png",
        "[原神_嗯]" to "https://i0.hdslb.com/bfs/emote/8b0a87e414f453a29730b6e0f45ca61f2f898688.png",
        "[原神_欸嘿]" to "https://i0.hdslb.com/bfs/emote/8fba438fcbe0550877b04efd768d857082307c5e.png",
        "[原神_喝茶]" to "https://i0.hdslb.com/bfs/emote/1de5789fbb3526ef7823c54db7081790a38e7044.png",
        "[原神_生气]" to "https://i0.hdslb.com/bfs/emote/90a38c34742899f8e84138ed55f56cad3ba611fb.png",
        "[保卫萝卜_白眼]" to "https://i0.hdslb.com/bfs/emote/9fce63f38288700bf7be84f3be336cf895ba0902.png",
        "[保卫萝卜_笔芯]" to "https://i0.hdslb.com/bfs/emote/5ff2ed5cb71b02010018cc5910ac7052a03769af.png",
        "[保卫萝卜_哭哭]" to "https://i0.hdslb.com/bfs/emote/7d249f7c990111d3e2982f7477af15b7eb29cbd9.png",
        "[保卫萝卜_哇]" to "https://i0.hdslb.com/bfs/emote/5f2370e561c32d841245f7b1aab2eef43aeb9544.png",
        "[保卫萝卜_问号]" to "https://i0.hdslb.com/bfs/emote/41eb93f09fc4a4d0692a310e8a1f85ba60e96060.png",
        "[无悔华夏_不愧是你]" to "https://i0.hdslb.com/bfs/emote/c58002c32ee78d45366e126f294cb3149dd64ac2.png",
        "[无悔华夏_吃瓜]" to "https://i0.hdslb.com/bfs/emote/273dcff577551bafff4f1eae18561f871e73a6ba.png",
        "[无悔华夏_达咩]" to "https://i0.hdslb.com/bfs/emote/cffab383f47bab7f6736ba9c8d6ac098113410d9.png",
        "[无悔华夏_点赞]" to "https://i0.hdslb.com/bfs/emote/b0f2e8db405ec667c3e6aaabd7c15155b6ea8710.png",
        "[无悔华夏_好耶]" to "https://i0.hdslb.com/bfs/emote/324cd79784aeb37dbf2f47f68bbe8ed5d01f975e.png",
        "[奥比岛_搬砖]" to "https://i0.hdslb.com/bfs/emote/1fab697214918d91087373a999cc7ef8040ddf85.png",
        "[奥比岛_点赞]" to "https://i0.hdslb.com/bfs/emote/fb0b476fe2ff30cd59385ea7d616627ac114161f.png",
        "[奥比岛_击爪]" to "https://i0.hdslb.com/bfs/emote/35bba1bb8f164c5e844155548438248e6eaa8382.png",
        "[奥比岛_委屈]" to "https://i0.hdslb.com/bfs/emote/fda155e7c33b40dbb94c24644e0635d47b6ef3cc.png",
        "[奥比岛_喜欢]" to "https://i0.hdslb.com/bfs/emote/ed64e0c81ee194138bd9df30c65077ed978fb88c.png",
        "[黎明觉醒_怒了鸦]" to "https://i0.hdslb.com/bfs/emote/078991ad7546f2fefb79c05894d5f0431736d1e7.png",
        "[黎明觉醒_石化鸦]" to "https://i0.hdslb.com/bfs/emote/40bc09683e3b93390995b9ea5dc64f982b34e347.png",
        "[黎明觉醒_摊手鸦]" to "https://i0.hdslb.com/bfs/emote/883e4caa8e1745f48de0671c6441810faa4a56ed.png",
        "[黎明觉醒_比心鸦]" to "https://i0.hdslb.com/bfs/emote/e25f5442c5bce2a54b9c757e3032f44398f1a7df.png",
        "[黎明觉醒_哼白眼鸦]" to "https://i0.hdslb.com/bfs/emote/36de90dbaffeb32477dffdac612ad69da4e19b76.png",
        "[以闪亮之名_哎？！]" to "https://i0.hdslb.com/bfs/emote/5fafe7d9992e12c756da909a545ddb27486987af.png",
        "[以闪亮之名_爱你哦]" to "https://i0.hdslb.com/bfs/emote/835c3bff4fc686a78f49db1eb68e4d04c0963195.png",
        "[以闪亮之名_吃瓜]" to "https://i0.hdslb.com/bfs/emote/ebfdda8501e48c2434297e4a9f15218aadf07747.png",
        "[以闪亮之名_好耶!]" to "https://i0.hdslb.com/bfs/emote/fd0c2bbb806265c468eca83b7a90b0d5fcaa4cec.png",
        "[以闪亮之名_星星眼]" to "https://i0.hdslb.com/bfs/emote/a44a291ffe9334712ba130aa3fca0b940f32908d.png",
        "[以闪亮之名_针不戳]" to "https://i0.hdslb.com/bfs/emote/cad39e954972bbd93f0a777978b2f77078b8cf4a.png",
        "[元梦_爱你]" to "https://i0.hdslb.com/bfs/emote/b80e056748199ad498af8e0a1ec91290bfabdae3.png",
        "[元梦_打call]" to "https://i0.hdslb.com/bfs/emote/c8d5dc8044143d2869546aaced5bc967eaa4eb28.png",
        "[元梦_大哭]" to "https://i0.hdslb.com/bfs/emote/4905fafba7283ab8dd0c6e5087b245392d7f4e27.png",
        "[元梦_点赞]" to "https://i0.hdslb.com/bfs/emote/dd0132e92cfdad3f4d69b016d430b387568651f3.png",
        "[元梦_哭哭]" to "https://i0.hdslb.com/bfs/emote/06c2245a0ce59e118da5af8f9b06a141b0cd43cc.png",
        "[无限暖暖_暖暖]" to "https://i0.hdslb.com/bfs/emote/71dfc073d05c1672031ad2b8c8404d4b97ae66aa.png",
        "[无限暖暖_大喵]" to "https://i0.hdslb.com/bfs/emote/4b924d0245e922834c76d9dcf2448a0c8240fe92.png",
        "[无限暖暖_单品灵]" to "https://i0.hdslb.com/bfs/emote/a234e5544a65d237749eed7370de577f6b42b47e.png",
        "[无限暖暖_心愿精灵]" to "https://i0.hdslb.com/bfs/emote/8d2c2d9ff36317cd71309bf18db82694f213d13a.png",
        "[无限暖暖_美食家蜜蜂]" to "https://i0.hdslb.com/bfs/emote/db6013fd4551a2b3f13ca5f5e1d5c9fd9a44f5eb.png",
        "[无限暖暖_袜袜羊]" to "https://i0.hdslb.com/bfs/emote/98c5c2bffb45e7e4f26b7f030afa7ea0e44b0d19.png",
        "[无限暖暖_围兜狸猫]" to "https://i0.hdslb.com/bfs/emote/4f7f0b66f202fd57150450df2ee95e10ee5ff3f6.png",
        "[无限暖暖_毛线汪汪]" to "https://i0.hdslb.com/bfs/emote/06c38ae541b44aa640e152fb97966ca35442a0d2.png",
        "[明日方舟_沉默]" to "https://i0.hdslb.com/bfs/emote/10394b10492cfb70a1dad40b65577194c4137d09.png",
        "[明日方舟_打call]" to "https://i0.hdslb.com/bfs/emote/af50d35c709657dcf8654020a07d19a0a4827bd2.png",
        "[明日方舟_扶额]" to "https://i0.hdslb.com/bfs/emote/2e15e0f19552dde2ad694bd0940780e11d63b8c9.png",
        "[明日方舟_惊]" to "https://i0.hdslb.com/bfs/emote/b4c8b2e4f46da4a34bbb27183c0e750d0d9eefe8.png",
        "[明日方舟_敬礼]" to "https://i0.hdslb.com/bfs/emote/523503796fdb28e8036c1fe339a63054a3dcc02d.png",
        "[明日方舟_哭哭]" to "https://i0.hdslb.com/bfs/emote/c1133cedc2a260fd9a28fef2795db46ccfebfc41.png",
        "[明日方舟_装傻]" to "https://i0.hdslb.com/bfs/emote/39396136192016bb3e4a7a60db2ba288a161a6a1.png",
        "[明日方舟终末地_比心]" to "https://i0.hdslb.com/bfs/emote/3f3fd5b1ebc083372dd56c3c9afd1cc5e63a4de9.png",
        "[明日方舟终末地_哈欠]" to "https://i0.hdslb.com/bfs/emote/df8855059206659a9b0993f871de87f38711f6c4.png",
        "[明日方舟终末地_喝茶]" to "https://i0.hdslb.com/bfs/emote/d5d8fa12679347563c5975598df14e79d6520c33.png",
        "[明日方舟终末地_愣住]" to "https://i0.hdslb.com/bfs/emote/06bf4097d56f77d95ae253efc994cf561af2723d.png",
        "[明日方舟终末地_什么]" to "https://i0.hdslb.com/bfs/emote/519246b9667a80422f8468d8684568afcd629faf.png",
        "[明日方舟终末地_笑]" to "https://i0.hdslb.com/bfs/emote/50b136e46c6b756a4cda2015b3dbfd249aac4db1.png",
        "[来自星尘_盯]" to "https://i0.hdslb.com/bfs/emote/dad717572ddafc6b194b74be1f24a06430e4d306.png",
        "[来自星尘_wink]" to "https://i0.hdslb.com/bfs/emote/04d1271a6b0df157c163d4acce717bdb65f6e21d.png",
        "[泡姆泡姆_棒]" to "https://i0.hdslb.com/bfs/emote/533035cdeb29815f1be5f05b1b7f97877cdffb92.png",
        "[泡姆泡姆_加油]" to "https://i0.hdslb.com/bfs/emote/47a13be55f08d6f62bf737347aef3579f71c1618.png",
        "[热词系列_健健康康]" to "https://i0.hdslb.com/bfs/emote/db3f8c8cc72bedd5000e74ca1b2bdb1d9162f5ca.png",
        "[热词系列_再给一集]" to "https://i0.hdslb.com/bfs/emote/9f5e7d6a2d6171f8a96b9d112cc97278eaf5acca.png",
        "[热词系列_啊?]" to "https://i0.hdslb.com/bfs/emote/3e2f1385777a3428f413dea75755abb5d4fd7b08.png",
        "[热词系列_发刀大队]" to "https://i0.hdslb.com/bfs/emote/4d2f556dea78a37f5dbb1d99a6576b2d38ce582f.png",
        "[热词系列_道友请了]" to "https://i0.hdslb.com/bfs/emote/94efa372b3a7388aad265b4678241f07081bffb3.png",
        "[热词系列_念头通达]" to "https://i0.hdslb.com/bfs/emote/6519460319141c9684e14b986d53fcf39b39347b.png",
        "[热词表情_世萌双冠]" to "https://i0.hdslb.com/bfs/emote/8d962fcf0b18761d5f4315a49b7ae428da08cf4a.png",
        "[热词系列_谢谢老师]" to "https://i0.hdslb.com/bfs/emote/197cee386925f87b2ddfe5281e1ea63eb5c4fb97.png",
        "[热词系列_六到无语]" to "https://i0.hdslb.com/bfs/emote/3cae536616fdd1a3c1399312381b563e54ac3271.png",
        "[热词系列_美貌惊人]" to "https://i0.hdslb.com/bfs/emote/e8eb4e8ae62d68ad7db176fe163a714379991a18.png",
        "[热词系列_干杯]" to "https://i0.hdslb.com/bfs/emote/5ce649d5f716566db41dbf68c2e319517516d321.png",
        "[热词系列_肥肠自信]" to "https://i0.hdslb.com/bfs/emote/0872ca59db047b916f58926d83ca4f35e49b87d6.png",
        "[热词系列_我故意的]" to "https://i0.hdslb.com/bfs/emote/cc7eba19b5f468523c5a2867c4561fd33b541029.png",
        "[热词系列_知识增加]" to "https://i0.hdslb.com/bfs/emote/7145cbd5464b14a3f5cf544125a9976e44a13517.png",
        "[热词系列_三连]" to "https://i0.hdslb.com/bfs/emote/21f15fe11b7a84d2f2121c16dec50a4e4556f865.png",
        "[热词系列_妙啊]" to "https://i0.hdslb.com/bfs/emote/3ed5b0b2e1f632884d93abe6ae2a387e6f4395fd.png",
        "[热词系列_哇酷哇酷]" to "https://i0.hdslb.com/bfs/emote/04dbba8080a3d2eeeb2b80f845ff6b4b5232643b.png",
        "[热词系列_呵呵]" to "https://i0.hdslb.com/bfs/emote/2b20ec5940137d61b4172747c349dcb105e94eff.png",
        "[热词系列_上任鹅城]" to "https://i0.hdslb.com/bfs/emote/2de0e94aeb749acf34441b0a2987135b62b16b8c.png",
        "[热词系列_优雅]" to "https://i0.hdslb.com/bfs/emote/d41bc3db2a0d5ac32eee0b454062c057e6198a21.png",
        "[热词表情_哎呦不错哦]" to "https://i0.hdslb.com/bfs/emote/6209c2cdcdeb014efd9eae4e9d55b9efb9c18263.png",
        "[热词系列_好耶]" to "https://i0.hdslb.com/bfs/emote/ee2d354d3e67e5d77bab31ad8af99cd5ac89c009.png",
        "[热词系列_你币有了]" to "https://i0.hdslb.com/bfs/emote/84820c2b147a8ca02f3c4006b63f76c6313cbfa0.png",
        "[热词系列_吹爆]" to "https://i0.hdslb.com/bfs/emote/9c78c14bd395d75afeecd20276ec71324e9b97df.png",
        "[念诗之王]" to "https://i0.hdslb.com/bfs/emote/8de880a58fada7e0e668f2f47dabf0c223de4e58.png",
        "[热词系列_对象]" to "https://i0.hdslb.com/bfs/emote/a41763740a90c2b11bf282bf44ccee78066c98ff.png",
        "[热词系列_不孤鸟]" to "https://i0.hdslb.com/bfs/emote/7b94a0c3fe95c603a85a13bacbaf726b8e5882dc.png",
        "[热词系列_洛水天依]" to "https://i0.hdslb.com/bfs/emote/ab3845edcdc3bb8852c502f472a8caa9e921ae3a.png",
        "[热词系列_秀]" to "https://i0.hdslb.com/bfs/emote/1f8ab5644a10c3a01cc32cfe8f4d000296b06fb4.png",
        "[热词系列_标准结局]" to "https://i0.hdslb.com/bfs/emote/6b812dc41844ae4bdb3d6b7dd66067db6b922a5d.png",
        "[热词系列_B站有房]" to "https://i0.hdslb.com/bfs/emote/e2aeda75690b72b49ebc82f12e340678f5fc47c0.png",
        "[热词系列_破防了]" to "https://i0.hdslb.com/bfs/emote/d2c5d5a131f9d247e4ab4eed875c752d6e89d69e.png",
        "[热词系列_多谢款待]" to "https://i0.hdslb.com/bfs/emote/a4789b0eceb8fb9719c3a214aa89180528369091.png",
        "[热词系列_燃起来了]" to "https://i0.hdslb.com/bfs/emote/5a6199d39ee57e6e00ee1db33a7d430f5a9ff045.png",
        "[热词系列_赛博考古]" to "https://i0.hdslb.com/bfs/emote/590a6fdc7f0af07363975cf2e17a5f9972968891.png",
        "[热词系列_饮茶先啦]" to "https://i0.hdslb.com/bfs/emote/f4782fa5a072c766fc2b289b067ea3f19086bdf4.png",
        "[热词系列_再来亿遍]" to "https://i0.hdslb.com/bfs/emote/011c8e452941c701c3f69810eaeaaed99d96b9ee.png",
        "[热词系列_热乎]" to "https://i0.hdslb.com/bfs/emote/79ba29bf44db3f05acbf0179f5969cc21cb1b0e6.png",
        "[热词系列_好活]" to "https://i0.hdslb.com/bfs/emote/7ec857e53ebaf37c59e96b5e56bcb0f05cd0b2d7.png",
        "[热词系列_好家伙]" to "https://i0.hdslb.com/bfs/emote/63ec80dea3066bd9f449ba999ba531fa61f7b4eb.png",
        "[热词系列_排面]" to "https://i0.hdslb.com/bfs/emote/a544563e68dec3f6fcb3200cb7ea71193a0afe61.png",
        "[热词系列_爷青回]" to "https://i0.hdslb.com/bfs/emote/a26189ff1e681bddef7f6533f9aabe7604731a3e.png",
        "[热词系列_芜湖起飞]" to "https://i0.hdslb.com/bfs/emote/78d04c6ce78a613c90d510cd45fe7e25c57ba00b.png",
        "[热词系列_夺笋呐]" to "https://i0.hdslb.com/bfs/emote/e763846a534e790d402ee4ef1640c9bc02aaab37.png",
        "[热词系列_两面包夹芝士]" to "https://i0.hdslb.com/bfs/emote/9903fefe88018d62fecad299f6af4eb2d588bbdc.png",
        "[热词系列_梦幻联动]" to "https://i0.hdslb.com/bfs/emote/4809416be5ca787c2ec3e897e4fd022a58da6e0e.png",
        "[热词系列_泪目]" to "https://i0.hdslb.com/bfs/emote/bba3703ab90b7d16fe9dbcb85ed949db687f8331.png",
        "[热词系列_保护]" to "https://i0.hdslb.com/bfs/emote/55f8f6445ca7c3170cdfc5b16036abf639ce9b57.png",
        "[热词系列_爱了爱了]" to "https://i0.hdslb.com/bfs/emote/2a165b555ba20391316366c664ed7891883dc5aa.png",
        "[热词系列_可以]" to "https://i0.hdslb.com/bfs/emote/e08543c71202b36c590094417fcfbb80c3506cd8.png",
        "[热词系列_希望没事]" to "https://i0.hdslb.com/bfs/emote/6c0d2e6c486d1ba5afd6204a96e102652464a01d.png",
        "[热词系列_打卡]" to "https://i0.hdslb.com/bfs/emote/a9cf77c78e1b9b40aa3ed4862402fba008ee2f51.png",
        "[热词系列_DNA]" to "https://i0.hdslb.com/bfs/emote/f6eb74f8230588f61a298af89061a7d75c5762e5.png",
        "[热词系列_这次一定]" to "https://i0.hdslb.com/bfs/emote/a01ca28923daa7cc896c42f27deb4914e20dd572.png",
        "[热词系列_AWSL]" to "https://i0.hdslb.com/bfs/emote/c37f88cf799f9badf9d84b7671dc3dd98c0fc0c2.png",
        "[热词系列_霸体在此]" to "https://i0.hdslb.com/bfs/emote/637d83d08e881d9a5116042d5e8f87f157d26d5e.png",
        "[热词系列_递话筒]" to "https://i0.hdslb.com/bfs/emote/98e6950e39fbb4dd1c576042063ca632074070ba.png",
        "[热词系列_你细品]" to "https://i0.hdslb.com/bfs/emote/535e00658e7e47966f154d3a167fa2365ebc4321.png",
        "[热词系列_咕咕]" to "https://i0.hdslb.com/bfs/emote/d8065c2e7ce48c929317a94553499a46fecc262a.png",
        "[热词系列_张三]" to "https://i0.hdslb.com/bfs/emote/255a938f39cea625032b6650036b31aa26c50a3c.png",
        "[热词系列_害]" to "https://i0.hdslb.com/bfs/emote/cbe798a194612958537c5282fcca7c3bcd2aa15c.png",
        "[热词系列_我裂开了]" to "https://i0.hdslb.com/bfs/emote/29bd57ec4e8952880fea6c9e47aee924e91f10c4.png",
        "[热词系列_有内味了]" to "https://i0.hdslb.com/bfs/emote/7ca61680a905b5b6e2e335c630e725b648b03b4d.png",
        "[热词系列_猛男必看]" to "https://i0.hdslb.com/bfs/emote/9ca4f723194ff9f6f5745591132c1d70fae5a4a8.png",
        "[热词系列_奥力给]" to "https://i0.hdslb.com/bfs/emote/c9b8683827ec6c00fea5327c9bec14f581cef2aa.png",
        "[热词系列_神仙UP]" to "https://i0.hdslb.com/bfs/emote/a49e0d0db1e7d35a0f7411be13208951ab448f03.png",
        "[热词系列_问号]" to "https://i0.hdslb.com/bfs/emote/c1d1e76c12180adc8558f47006fe0e7ded4154bb.png",
        "[热词系列_我哭了]" to "https://i0.hdslb.com/bfs/emote/9e0b3877d649aaf6538fbdd3f937e240a9d808e4.png",
        "[热词系列_不愧是你]" to "https://i0.hdslb.com/bfs/emote/9ff2e356797c57ee3b1675ade0883d2d2247be9b.png",
        "[热词系列_高产]" to "https://i0.hdslb.com/bfs/emote/9db817cba4a7f4a42398f3b2ec7c0a8e0c247c42.png",
        "[热词系列_真香]" to "https://i0.hdslb.com/bfs/emote/e68497c775feaac1c3b1a6cd63a50cfb11b767c4.png",
        "[热词系列_我全都要]" to "https://i0.hdslb.com/bfs/emote/d424d1ad8d14c1c9b8367842bc68c658b9229bc1.png",
        "[热词系列_爷关更]" to "https://i0.hdslb.com/bfs/emote/faad40c56447f1f8abcb4045c17ce159d113d1fd.png",
        "[热词系列_锤]" to "https://i0.hdslb.com/bfs/emote/35668cc12ae25b9545420e4a85bf21a0bfc03e5d.png",
        "[热词系列_我酸了]" to "https://i0.hdslb.com/bfs/emote/a8cbf3f6b8cd9377eeb15b9172f3cd683b2e4650.png",
        "[热词系列_有生之年]" to "https://i0.hdslb.com/bfs/emote/f41fdafe2d0fbb8e8bc1598d2cf37e355560103a.png",
        "[热词系列_镇站之宝]" to "https://i0.hdslb.com/bfs/emote/24e7a6a6e6383c987215fb905e3ee070aca259b5.png",
        "[热词系列_我太南了]" to "https://i0.hdslb.com/bfs/emote/a523f3e4c63e4db1232365765d0ec452f83be97e.png",
        "[热词系列_完结撒花]" to "https://i0.hdslb.com/bfs/emote/ea9db62ff5bca8e069cd70c4233353a802835422.png",
        "[热词系列_大师球]" to "https://i0.hdslb.com/bfs/emote/f30089248dd137c568edabcb07cf67e0f6e98cf3.png",
        "[热词系列_知识盲区]" to "https://i0.hdslb.com/bfs/emote/ccc94600b321a28116081e49ecedaa4ee8728312.png",
        "[热词系列_“狼火”]" to "https://i0.hdslb.com/bfs/emote/33ccd3617bfa89e9d1498b13b7542b63f163e5de.png",
        "[热词系列_你可真星]" to "https://i0.hdslb.com/bfs/emote/54c8ddff400abfe388060cabfbb579280fdea1be.png",
        "[凡人修仙传_给个交代]" to "https://i0.hdslb.com/bfs/emote/581a521d7dbfb6bd358e0df728f47c15d640d2f9.png",
        "[凡人修仙传_退至身后]" to "https://i0.hdslb.com/bfs/emote/6e578d2207b43b3fa3be99155dd4676e3f54e00b.png",
        "[中国绊爱_吃惊]" to "https://i0.hdslb.com/bfs/emote/c590a359bb2c9ec43f695dbe6a40ab03dddc0fcd.png",
        "[中国绊爱_大叫]" to "https://i0.hdslb.com/bfs/emote/3a3ead4ce90b4628213445e873a9aadbb21ef055.png",
        "[中国绊爱_点赞]" to "https://i0.hdslb.com/bfs/emote/9248de5d87737a34f88204156ad0401814cda8e0.png",
        "[中国绊爱_吃饺子]" to "https://i0.hdslb.com/bfs/emote/17f5d04e7891795e1320d27e74f7d85aeb2c42d5.png",
        "[中国绊爱_贴贴]" to "https://i0.hdslb.com/bfs/emote/7525b277cdcbabfe5e5b102fae8260480aaf7682.png",
        "[中国绊爱_无语]" to "https://i0.hdslb.com/bfs/emote/fc9a4bcff2c5f15d9d9926e4e54e701ebf63f46b.png",
        "[中国绊爱_笑哭]" to "https://i0.hdslb.com/bfs/emote/913bcdd8bf756d521dc5c4b89056c09358a1b349.png",
        "[中国绊爱_星星眼]" to "https://i0.hdslb.com/bfs/emote/93954c6b475ffb1e2886aee8f27825b406df2dd7.png",
        "[中国绊爱_一手好牌]" to "https://i0.hdslb.com/bfs/emote/ab3e15c12856fe33da12b88cc7e4f4bcf9c01025.png",
        "[中国绊爱_疑惑]" to "https://i0.hdslb.com/bfs/emote/e85f0568167fe5ecbc1c11911503ac9cd6f98339.png"
    )

    /**
     * 腾讯视频弹幕表情词表（官方 CDN：vc.qpic.cn / puui.qpic.cn / vfiles.gtimg.com，公开可访问）。
     * 覆盖腾讯风格弹幕表情（如 [打call了] [我裂开了] [AWSL] 等，B 站无同名）；多数为 GIF 动图，
     * 加载时取静态首帧显示。与 B 站表同名表情优先命中 B 站表（语义一致，图源更稳定）。
     */
    private val tencentBuiltin: Map<String, String> = mapOf(
        "[666啊]" to "https://vc.qpic.cn/tpic/mtvizoMZGRKa3.gif",
        "[6周年]" to "https://puui.qpic.cn/vupload/0/20181217_6b4cyc6ny46/0",
        "[AWSL]" to "https://vc.qpic.cn/tpic/mtvivRFxXzcpw.png",
        "[EMO了]" to "https://vc.qpic.cn/tpic/mtvizrneUe2EY.gif",
        "[KSWLA]" to "https://vc.qpic.cn/tpic/mtvivRBpFDAq1.png",
        "[N刷]" to "https://vfiles.gtimg.com/wupload/xy/starter/N5iXXqUl.gif",
        "[PTSD]" to "https://vc.qpic.cn/tpic/mtvivRBq2z41L.png",
        "[SOSA]" to "https://vc.qpic.cn/tpic/mtvivRBqccYm1.png",
        "[YYDSA]" to "https://vc.qpic.cn/tpic/mtvivRBqovCnw.png",
        "[一起冲]" to "https://vc.qpic.cn/tpic/mtvizoN2eUnLC.gif",
        "[上头-上]" to "https://vc.qpic.cn/tpic/mtvivXQvbx1L1.png",
        "[上头-头]" to "https://vc.qpic.cn/tpic/mtvivXQvr5LsL.png",
        "[互粉]" to "https://vc.qpic.cn/tpic/mtvivRBmG1i2S.png",
        "[亲]" to "https://vc.qpic.cn/tpic/mtvivMUQoqC2N.png",
        "[你有事吗]" to "https://vc.qpic.cn/tpic/mtvizrnm9kvfG.gif",
        "[再见]" to "https://vc.qpic.cn/tpic/mtvivMUSAWF2j.png",
        "[准奏]" to "https://vc.qpic.cn/tpic/mtvizoN2E63NC.gif",
        "[准时]" to "https://vc.qpic.cn/tpic/mtvizoN3uVgp7.gif",
        "[击退]" to "https://vc.qpic.cn/tpic/mtvivRaaJv8gE.png",
        "[加更-加]" to "https://vc.qpic.cn/tpic/mtvivRBpdFGef.png",
        "[加更-更]" to "https://vc.qpic.cn/tpic/mtvivRBnWQrfy.png",
        "[发呆]" to "https://vc.qpic.cn/tpic/mtvivNpanTk4f.png",
        "[吃瓜N]" to "https://vc.qpic.cn/tpic/mtvizrnc7avqU.gif",
        "[吐舌]" to "https://vc.qpic.cn/tpic/mtvivNpbaKz6b.png",
        "[咿呀]" to "https://vc.qpic.cn/tpic/mtvivMUSUD9Gy.png",
        "[啊啊啊]" to "https://vc.qpic.cn/tpic/mtviweLvbTPv3.png",
        "[喜]" to "https://vc.qpic.cn/tpic/mtvivMUW1uwjC.png",
        "[喜欢]" to "https://puui.qpic.cn/vupload/0/20181102_i2rijfwd7bl/0",
        "[大哭]" to "https://vc.qpic.cn/tpic/mtvivNpb2Mos5.png",
        "[委屈]" to "https://vc.qpic.cn/tpic/mtvivNpaSkdwu.png",
        "[寄刀片]" to "https://vc.qpic.cn/tpic/mtvivPmSaa7m1.png",
        "[富翁]" to "https://vc.qpic.cn/tpic/mtvizoN2PrR2b.gif",
        "[小哭]" to "https://vc.qpic.cn/tpic/mtvivMUR2uj5h.png",
        "[庆祝N]" to "https://vc.qpic.cn/tpic/mtvivMUTZsSLq.png",
        "[心]" to "https://vc.qpic.cn/tpic/mtvivPmLwZQm1.png",
        "[心碎N]" to "https://vc.qpic.cn/tpic/mtvivMUTgRRaJ.png",
        "[快逃]" to "https://vc.qpic.cn/tpic/mtvivPmT3iFyU.png",
        "[怒]" to "https://vc.qpic.cn/tpic/mtvivMUSMpXGu.png",
        "[意难平]" to "https://vc.qpic.cn/tpic/mtvizrnd8bTA1.gif",
        "[憨笑]" to "https://vc.qpic.cn/tpic/mtvivNpawv8CL.png",
        "[我不理解]" to "https://vc.qpic.cn/tpic/mtvizrngXhUK3.gif",
        "[我裂开了]" to "https://vc.qpic.cn/tpic/mtvizrngmPGrw.gif",
        "[打call了]" to "https://vc.qpic.cn/tpic/mtvizrnef3RaA.gif",
        "[打卡-卡]" to "https://vc.qpic.cn/tpic/mtvivPmSHA68E.png",
        "[打卡-打]" to "https://vc.qpic.cn/tpic/mtvivPmSuwjHo.png",
        "[折磨]" to "https://vc.qpic.cn/tpic/mtvivMUS1tsHE.png",
        "[抠鼻]" to "https://vc.qpic.cn/tpic/mtvivNpaGjNnN.png",
        "[抱抱]" to "https://vc.qpic.cn/tpic/mtvivMUT4Cs6o.png",
        "[拳头]" to "https://vc.qpic.cn/tpic/mtvivMUUYPweo.png",
        "[握手]" to "https://vc.qpic.cn/tpic/mtvivMUUCBWvW.png",
        "[握草]" to "https://vc.qpic.cn/tpic/mtvizoN2r7E67.gif",
        "[摊手]" to "https://vc.qpic.cn/tpic/mtvivMUSsvBRA.png",
        "[撒狗粮]" to "https://vc.qpic.cn/tpic/mtvivPmSjN2Ff.png",
        "[撒花N]" to "https://vc.qpic.cn/tpic/mtvizrni7tdX3.gif",
        "[擦汗]" to "https://vc.qpic.cn/tpic/mtvivMURcdB7y.png",
        "[放大镜]" to "https://vc.qpic.cn/tpic/mtvizrniV5sC1.gif",
        "[斜眼]" to "https://vc.qpic.cn/tpic/mtvivMUSeg72w.png",
        "[无羡]" to "https://puui.qpic.cn/vupload/0/20181102_hdplbuej5z4/0",
        "[柠檬精]" to "https://vc.qpic.cn/tpic/mtvizrnn4H4p7.gif",
        "[棒棒糖]" to "https://vc.qpic.cn/tpic/mtvivMUTymsFj.png",
        "[气到变形]" to "https://vc.qpic.cn/tpic/mtvizrnhjjRNy.gif"
    )
}
