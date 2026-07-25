package com.github.catvod.crawler;

import android.content.Context;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.js.Connect;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Dns;

public class Spider {

    /** 站点 key，由 JarLoader 在创建 Spider 实例时回填，便于 jar 内爬虫识别自身站点 */
    public String siteKey;

    public void init(Context context) throws Exception {}

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    /** 由 JarLoader 注入 SpiderApi，jar 内爬虫可重写以使用主进程提供的服务地址、并发请求等能力 */
    public void initApi(SpiderApi api) {}

    /**
     * 首页数据内容
     *
     * @param filter 是否开启筛选
     * @return
     */
    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    /**
     * 首页最近更新数据 如果上面的homeContent中不包含首页最近更新视频的数据 可以使用这个接口返回
     *
     * @return
     */
    public String homeVideoContent() throws Exception {
        return "";
    }

    /**
     * 分类数据
     *
     * @param tid
     * @param pg
     * @param filter
     * @param extend
     * @return
     */
    public String categoryContent(String tid, String pg, boolean filter, HashMap < String, String > extend) throws Exception {
        return "";
    }

    /**
     * 详情数据
     *
     * @param ids
     * @return
     */
    public String detailContent(List < String > ids) throws Exception {
        return "";
    }

    /**
     * 搜索数据内容
     *
     * @param key
     * @param quick
     * @return
     */
    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    /**
     * 播放信息
     *
     * @param flag
     * @param id
     * @return
     */
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List < String > vipFlags) throws Exception {
        return "";
    }

    /**
     * webview解析时使用 可自定义判断当前加载的 url 是否是视频
     *
     * @param url
     * @return
     */
    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    /**
     * 是否手动检测webview中加载的url
     *
     * @return
     */
    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxyLocal(Map < String, String > params) throws Exception {
        return null;
    }

    /**
     * 卡片 action 回调，jar 内爬虫可重写以实现配置中心等带分类菜单的数据源。
     * 主进程在 SourceViewModel.action 中调用此方法，将结果通过 actionResult 回调到界面。
     * 返回 JSON 字符串，包含 msg 字段时主进程会显示 Toast 并刷新列表。
     */
    public String action(String action) throws Exception {
        return null;
    }

    public void cancelByTag() {

    }

    public void destroy() {}

    /**
     * 直播list
     * @return
     */
    public String liveContent(String url) {
        return "";
    }

    public static Dns safeDns() {
        return OkGoHelper.dnsOverHttps;
    }
}
