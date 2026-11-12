package com.tvbox.kotlin.app.di

import android.util.Base64
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.server.ServerHost
import com.tvbox.kotlin.core.server.ServerResources
import com.tvbox.kotlin.core.spider.ApiConfig
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServerHost] 实现，聚合 ApiConfig/Spider 等上层组件供 RemoteServer 使用。
 * - proxyLocal 处理 `do=live` 直播源代理：解码 ext 为真实直播地址，
 *   拉取 txt 直播源并返回标准分组 JSON
 */
@Singleton
class ServerHostImpl @Inject constructor(
    override val resources: ServerResources,
    private val apiConfig: ApiConfig
) : ServerHost {

    /** 是否存在指定 key 的源（如 push_agent 推送解析源），直接查站点配置 */
    override fun hasSource(key: String): Boolean = apiConfig.getSource(key) != null

    override fun proxyLocal(params: Map<String, String>): Array<Any?>? {
        val doAction = params["do"] ?: return null
        if (doAction == "live") return handleLiveProxy(params)
        // 弹幕等其余 do 请求统一分发到 ApiConfig，由 jar/js/py 爬虫的 proxyLocal 处理
        return try {
            apiConfig.proxyLocal(params)
        } catch (t: Throwable) {
            LOG.e("proxyLocal error do=$doAction", t)
            null
        }
    }

    override fun proxy(params: Map<String, String>): Array<Any?>? {
        return when (params["go"]) {
            "SuperParse" -> handleSuperParse(params)
            else -> null
        }
    }

    /** 处理 go=SuperParse：返回聚合嗅探 iframe 页面，逐帧加载各解析站 */
    private fun handleSuperParse(params: Map<String, String>): Array<Any?>? {
        val encodedUrl = params["url"] ?: return null
        val videoUrl = try {
            String(
                Base64.decode(encodedUrl, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
        } catch (e: Throwable) {
            LOG.e(e)
            return null
        }
        val jxUrls = apiConfig.getParseBeanList()
            .filter { it.type == ParseBean.TYPE_SNIFF && it.getUrl().isNotEmpty() }
            .map { it.getUrl() }
        if (jxUrls.isEmpty()) return null
        val html = buildSuperParseHtml(jxUrls, videoUrl)
        return arrayOf(
            200,
            "text/html; charset=\"UTF-8\"",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }

    /** 生成超级解析嗅探页：iframe 错峰加载各解析站地址 + 视频地址 */
    private fun buildSuperParseHtml(jxUrls: List<String>, videoUrl: String): String {
        val jxs = jxUrls.joinToString(",") { "\"$it\"" }
        return "\n" +
            "<!doctype html>\n" +
            "<html>\n" +
            "<head>\n" +
            "<title>解析</title>\n" +
            "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
            "</head>\n" +
            "<body>\n" +
            "<script>\n" +
            "var apiArray=[$jxs];\n" +
            "var urlPs=\"$videoUrl\";\n" +
            "var iframes=[];\n" +
            "var loadIndex=0;\n" +
            "var loadTimer=null;\n" +
            "for(var i=0;i<apiArray.length;i++){\n" +
            "var f=document.createElement('iframe');\n" +
            "f.setAttribute('sandbox','allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-top-navigation allow-pointer-lock');\n" +
            "f.setAttribute('frameborder','0');\n" +
            "f.style.width='100%';\n" +
            "f.style.height='100%';\n" +
            "f.style.border='none';\n" +
            "document.body.appendChild(f);\n" +
            "iframes.push(f);\n" +
            "}\n" +
            "function loadNextIframe(){\n" +
            "if(loadIndex>=iframes.length){\n" +
            "clearInterval(loadTimer);\n" +
            "loadTimer=null;\n" +
            "return;\n" +
            "}\n" +
            "iframes[loadIndex].src=apiArray[loadIndex]+urlPs;\n" +
            "loadIndex++;\n" +
            "}\n" +
            "loadTimer=setInterval(loadNextIframe,50);\n" +
            "loadNextIframe();\n" +
            "</script>\n" +
            "</body>\n" +
            "</html>"
    }

    override fun resolveDns(name: String?): ByteArray = ByteArray(0)

    override fun dashData(): String? = null

    override fun onServerStarted(port: Int, dohEnabled: Boolean) {
        // 同步实际监听端口（可能因占用 +1）到爬虫代理，保证 jar/弹幕等拿到的端口正确
        com.github.catvod.Proxy.set(port)
    }

    /** 处理 do=live&type=txt&ext=xxx，返回 [200, mime, InputStream] 或 null */
    private fun handleLiveProxy(params: Map<String, String>): Array<Any?>? {
        val ext = params["ext"] ?: return null
        val url = try {
            String(
                Base64.decode(ext, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
        } catch (e: Throwable) {
            LOG.e(e)
            return null
        }
        val json = try {
            runBlocking { apiConfig.fetchLiveTxtJson(url) }
        } catch (e: Throwable) {
            LOG.e(e)
            null
        } ?: return null
        return arrayOf(
            200,
            "application/json;charset=utf-8",
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        )
    }
}
