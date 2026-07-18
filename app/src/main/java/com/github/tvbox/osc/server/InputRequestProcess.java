package com.github.tvbox.osc.server;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.UrlUtils;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author pj567
 * @date :2021/1/5
 * @description: 响应按键和输入
 */

public class InputRequestProcess implements RequestProcess {
    private RemoteServer remoteServer;

    public InputRequestProcess(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() == NanoHTTPD.Method.POST) {
            switch (fileName) {
                case "/action":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        DataReceiver mDataReceiver = remoteServer.getDataReceiver();
        switch (fileName) {
            case "/action":
                if (params.get("do") != null && mDataReceiver != null) {
                    String action = params.get("do");

                    switch (action) {
                        case "search": {
                            mDataReceiver.onTextReceived(params.get("word").trim());
                            break;
                        }
                        case "api": {
                            mDataReceiver.onApiReceived(params.get("url").trim());
                            break;
                        }
                        case "live": {
                            mDataReceiver.onLiveReceived(params.get("url").trim());
                            break;
                        }
                        case "epg": {
                            mDataReceiver.onEpgReceived(params.get("url").trim());
                            break;
                        }
                        case "proxys": {
                            mDataReceiver.onProxysReceived(params.get("url").trim());
                            break;
                        }
                        case "push": {
                            String rawUrl = params.get("url");
                            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                                JSONObject errResult = new JSONObject();
                                try {
                                    errResult.put("status", "error");
                                    errResult.put("message", "推送地址不能为空");
                                } catch (JSONException ignored) {
                                }
                                return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.BAD_REQUEST, errResult.toString());
                            }
                            String pushUrl = rawUrl.trim();
                            mDataReceiver.onPushReceived(pushUrl);

                            boolean isDirectPlayUrl = UrlUtils.isDirectPlayUrl(pushUrl);
                            boolean hasPushAgent = ApiConfig.get().getSource("push_agent") != null;
                            String pushType;
                            String message;
                            if (isDirectPlayUrl) {
                                pushType = "direct";
                                message = "已推送，直链地址将直接播放";
                            } else if (hasPushAgent) {
                                pushType = "detail";
                                message = "已推送给 push_agent 源解析";
                            } else {
                                pushType = "unsupported";
                                message = "检测到当前数据源中未配置含 push_agent 的源，无法推送该地址";
                            }

                            JSONObject result = new JSONObject();
                            try {
                                result.put("status", "ok");
                                result.put("hasPushAgent", hasPushAgent);
                                result.put("pushType", pushType);
                                result.put("message", message);
                            } catch (JSONException ignored) {
                            }
                            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, result.toString());
                        }
                        case "mirror": {
                            //推送当前电影、电视剧……
                            String id = params.get("id");
                            String sourceKey = params.get("sourceKey");
                            if (id == null || sourceKey == null) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "missing_params");
                            }
                            // 检查接收端是否有对应的源配置
                            SourceBean sourceBean = ApiConfig.get().getSource(sourceKey.trim());
                            if (sourceBean == null) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "source_not_found");
                            }
                            mDataReceiver.onMirrorReceived(id.trim(), sourceKey.trim());
                            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "mirrored");
                        }
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }
}
