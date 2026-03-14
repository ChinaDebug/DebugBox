package com.undcover.freedom.pyramid;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.chaquo.python.PyObject;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PythonSpider extends Spider {
    PyObject app;
    PyObject pySpider;
    boolean loadSuccess = false;
    private String cachePath;
    private String name;

    public PythonSpider() {
        this("");
    }

    public PythonSpider(String cache) {
        this("", cache);
    }

    public PythonSpider(String name, String cache) {
        this.cachePath = cache;
        this.name = name;
    }

    @Override
    public void init(Context context) {
        app.callAttr("init", pySpider);
    }

    public void init(Context context, String url) {
        app = PythonLoader.getInstance().pyApp;
        PyObject retValue = app.callAttr("downloadPlugin", cachePath, url);
        Uri uri = Uri.parse(url);
        String extInfo = uri.getQueryParameter("extend");
        if (null == extInfo) extInfo = "";
        String path = retValue.toString();
        File file = new File(path);
        if (file.exists()) {
            pySpider = app.callAttr("loadFromDisk", path);

            List<PyObject> poList = app.callAttr("getDependence", pySpider).asList();
            for (PyObject po : poList) {
                String api = po.toString();
                String depUrl = PythonLoader.getInstance().getUrlByApi(api);
                if (!depUrl.isEmpty()) {
                    String tmpPath = app.callAttr("downloadPlugin", cachePath, depUrl).toString();
                    if (!new File(tmpPath).exists()) {
                        return;
                    }
                }
            }
            app.callAttr("init", pySpider, extInfo);
            loadSuccess = true;
        }
    }

    public String getName() {
        if (name.isEmpty()) {
            PyObject po = app.callAttr("getName", pySpider);
            return po.toString();
        } else {
            return name;
        }
    }

    public JSONObject map2json(HashMap<String, String> extend) {
        JSONObject jo = new JSONObject();
        try {
            if (extend != null) {
                for (String key : extend.keySet()) {
                    jo.put(key, extend.get(key));
                }
            }
        } catch (JSONException ignored) {
        }
        return jo;
    }

    public JSONObject map2json(Map extend) {
        JSONObject jo = new JSONObject();
        try {
            if (extend != null) {
                for (Object key : extend.keySet()) {
                    jo.put(key.toString(), extend.get(key));
                }
            }
        } catch (JSONException ignored) {
        }
        return jo;
    }

    public JSONArray list2json(List<String> array) {
        JSONArray ja = new JSONArray();
        if (array != null) {
            for (String str : array) {
                ja.put(str);
            }
        }
        return ja;
    }

    public Object[] proxyLocal(Map<String,String> params) {
        List<PyObject> list = app.callAttr("localProxy", pySpider, map2json(params).toString()).asList();
        boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
        boolean headerAvailable = list.size() > 3 && list.get(3) != null;
        Object[] result = new Object[4];
        result[0] = list.get(0).toInt();
        result[1] = list.get(1).toString();
        result[2] = getStream(list.get(2), base64);
        result[3] = headerAvailable ? getHeader(list.get(3)) : null;
        return result;
    }


    private Map<String, String> getHeader(PyObject headerObj) {
        if (headerObj == null) {
            return null;
        }
        Map<String, String> headerMap = new HashMap<>();
        for (PyObject key : headerObj.asMap().keySet()) {
            headerMap.put(key.toString(), Objects.requireNonNull(headerObj.asMap().get(key)).toString());
        }
        return headerMap;
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return new ByteArrayInputStream(new byte[0]);
        String typeStr = o.type().toString();
        if (typeStr.contains("bytes")) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) {
            content = content.split("base64,")[1];
        }
        return new ByteArrayInputStream(base64 ? decode(content) : content.getBytes());
    }

    public String replaceLocalUrl(String content) {
        return content.replace("http://127.0.0.1:UndCover/proxy", PythonLoader.getInstance().localProxyUrl());
    }

    public String homeContent(boolean filter) {
        PyObject po = app.callAttr("homeContent", pySpider, filter);
        return po.toString();
    }

    public String homeVideoContent() {
        PyObject po = app.callAttr("homeVideoContent", pySpider);
        return po.toString();
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        PyObject po = app.callAttr("categoryContent", pySpider, tid, pg, filter, map2json(extend).toString());
        return po.toString();
    }

    public String detailContent(List<String> ids) {
        PyObject po = app.callAttr("detailContent", pySpider, list2json(ids).toString());
        return po.toString();
    }

    public String searchContent(String key, boolean quick) {
        PyObject po = app.callAttr("searchContent", pySpider, key, quick);
        return po.toString();
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        PyObject po = app.callAttr("playerContent", pySpider, flag, id, list2json(vipFlags).toString());
        return replaceLocalUrl(po.toString());
    }

    public String liveContent(String url) {
        PyObject po = app.callAttr("liveContent", pySpider,url);
        return po.toString();
    }

    public boolean isVideoFormat(String url) {
        return false;
    }

    public boolean manualVideoCheck() {
        return false;
    }

    public static byte[] decode(String s) {
        return decode(s, Base64.DEFAULT | Base64.NO_WRAP);
    }

    public static byte[] decode(String s, int flags) {
        return Base64.decode(s, flags);
    }
}
