package com.github.tvbox.osc.viewmodel.drive;

import com.github.tvbox.osc.bean.DriveFolderFile;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.urlhttp.OkHttpUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.PostRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AlistDriveViewModel extends AbstractDriveViewModel {

    private Thread loadThread;

    private void setRequestHeader(PostRequest request, String origin) {
        request.headers("User-Agent", UA.random());
        if (origin != null && !origin.isEmpty()) {
            if (origin.endsWith("/"))
                origin = origin.substring(0, origin.length() - 1);
            request.headers("origin", origin);
            request.headers("Referer", origin);
        }
        request.headers("accept", "application/json, text/plain, */*");
        request.headers("content-type", "application/json;charset=UTF-8");
    }

    public final String getUrl(String str) {
        String str2;
        if (str != null) {
            try {
                URL url = new URL(str);
                if (url.getPort() > 0) {
                    str2 = ":" + url.getPort();
                } else {
                    str2 = "";
                }
                return url.getProtocol() + "://" + url.getHost() + str2;

            } catch (Exception e) {
                LOG.e(e);
            }
        }
        return "";
    }

    @Override
    public String loadData(LoadDataCallback callback) {
        JsonObject config = currentDrive.getConfig();
        if (currentDriveNote == null) {
            currentDriveNote = new DriveFolderFile(null,
                    config.has("initPath") ? config.get("initPath").getAsString() : "", 0, false, null, null);
        }
        String targetPath = currentDriveNote.getAccessingPathStr() + currentDriveNote.name;

        if (currentDriveNote.getChildren() == null) {
            if (loadThread != null && loadThread.isAlive()) {
                loadThread.interrupt();
            }
            loadThread = new Thread(new LoadDataRunnable(this, config, targetPath, callback));
            loadThread.start();
            return targetPath;
        } else {
            sortData(currentDriveNote.getChildren());
            if (callback != null)
                callback.callback(currentDriveNote.getChildren(), true);
        }
        return targetPath;
    }

    private static class LoadDataRunnable implements Runnable {
        private final WeakReference<AlistDriveViewModel> vmRef;
        private final JsonObject config;
        private final String targetPath;
        private final LoadDataCallback callback;

        LoadDataRunnable(AlistDriveViewModel vm, JsonObject config, String targetPath, LoadDataCallback callback) {
            this.vmRef = new WeakReference<>(vm);
            this.config = config;
            this.targetPath = targetPath;
            this.callback = callback;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) return;
            
            AlistDriveViewModel vm = vmRef.get();
            if (vm == null) return;
            
            String webLink = vm.getUrl(config.get("url").getAsString());
            try {
                if (vm.currentDrive.version == 0) {
                    String result = OkHttpUtil.get(webLink + "/api/public/settings");
                    JSONObject opt = new JSONObject(result);
                    Object obj = new JSONTokener(opt.optString("data")).nextValue();
                    if (obj instanceof JSONObject) {
                        vm.currentDrive.version = 3;
                    } else if (obj instanceof JSONArray) {
                        vm.currentDrive.version = 2;
                    }
                }

                if (Thread.currentThread().isInterrupted()) return;

                if (vm.currentDrive.version == 2) {
                    PostRequest<String> request = OkGo.<String>post(webLink + "/api/public/path").tag("drive");
                    JSONObject requestBody = new JSONObject();
                    requestBody.put("path", targetPath.isEmpty() ? "/" : targetPath);
                    requestBody.put("password", vm.currentDrive.getConfig().get("password").getAsString());
                    requestBody.put("page_num", 1);
                    requestBody.put("page_size", 200);
                    request.upJson(requestBody);
                    vm.setRequestHeader(request, webLink);
                    request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AlistDriveViewModel vm = vmRef.get();
                            if (vm == null) return;
                            
                            String respBody = response.body();
                            try {
                                JsonObject respData = JsonParser.parseString(respBody).getAsJsonObject();
                                List<DriveFolderFile> items = new ArrayList<>();
                                if (respData.get("code").getAsInt() == 200) {
                                    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                                    for (JsonElement file : respData.get("data").getAsJsonObject().get("files").getAsJsonArray()) {
                                        JsonObject fileObj = file.getAsJsonObject();
                                        String fileName = fileObj.get("name").getAsString();
                                        int extNameStartIndex = fileName.lastIndexOf(".");
                                        boolean isFile = fileObj.get("type").getAsInt() != 1;
                                        String fileUrl = null;
                                        if (fileObj.has("url") && !fileObj.get("url").getAsString().isEmpty())
                                            fileUrl = fileObj.get("url").getAsString();
                                        try {
                                            DriveFolderFile driveFile = new DriveFolderFile(vm.currentDriveNote, fileName, vm.currentDrive.version, isFile,
                                                    isFile && extNameStartIndex >= 0 && extNameStartIndex < fileName.length() ?
                                                            fileName.substring(extNameStartIndex + 1) : null,
                                                    dateFormat.parse(fileObj.get("updated_at").getAsString()).getTime());
                                            if (fileUrl != null)
                                                driveFile.fileUrl = fileUrl;
                                            items.add(driveFile);
                                        } catch (ParseException e) {
                                            LOG.e(e);
                                        }
                                    }
                                }
                                vm.sortData(items);
                                DriveFolderFile backItem = new DriveFolderFile(null, null, 0, false, null, null);
                                backItem.parentFolder = backItem;
                                items.add(0, backItem);
                                vm.currentDriveNote.setChildren(items);
                                if (callback != null)
                                    callback.callback(vm.currentDriveNote.getChildren(), false);
                            } catch (Exception ex) {
                                if (callback != null)
                                    callback.fail("无法访问，请注意地址格式");
                            }
                        }
                    });
                } else if (vm.currentDrive.version == 3) {
                    PostRequest<String> request = OkGo.<String>post(webLink + "/api/fs/list").tag("drive");
                    JSONObject requestBody = new JSONObject();
                    requestBody.put("path", targetPath.isEmpty() ? "/" : targetPath);
                    requestBody.put("password", vm.currentDrive.getConfig().get("password").getAsString());
                    requestBody.put("page", 1);
                    requestBody.put("per_page", 200);
                    requestBody.put("refresh", false);

                    request.upJson(requestBody);
                    vm.setRequestHeader(request, webLink);
                    request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AlistDriveViewModel vm = vmRef.get();
                            if (vm == null) return;
                            
                            String respBody = response.body();
                            try {
                                JsonObject respData = JsonParser.parseString(respBody).getAsJsonObject();
                                List<DriveFolderFile> items = new ArrayList<>();
                                if (respData.get("code").getAsInt() == 200) {
                                    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                                    for (JsonElement file : respData.get("data").getAsJsonObject().get("content").getAsJsonArray()) {
                                        JsonObject fileObj = file.getAsJsonObject();
                                        String fileName = fileObj.get("name").getAsString();
                                        int extNameStartIndex = fileName.lastIndexOf(".");
                                        boolean isFile = !fileObj.get("is_dir").getAsBoolean();

                                        try {
                                            DriveFolderFile driveFile = new DriveFolderFile(vm.currentDriveNote, fileName, vm.currentDrive.version, isFile,
                                                    isFile && extNameStartIndex >= 0 && extNameStartIndex < fileName.length() ?
                                                            fileName.substring(extNameStartIndex + 1) : null,
                                                    dateFormat.parse(fileObj.get("modified").getAsString()).getTime());
                                            items.add(driveFile);
                                        } catch (ParseException e) {
                                            LOG.e(e);
                                        }
                                    }
                                }
                                vm.sortData(items);
                                DriveFolderFile backItem = new DriveFolderFile(null, null, 0, false, null, null);
                                backItem.parentFolder = backItem;
                                items.add(0, backItem);
                                vm.currentDriveNote.setChildren(items);
                                if (callback != null)
                                    callback.callback(vm.currentDriveNote.getChildren(), false);
                            } catch (Exception ex) {
                                if (callback != null)
                                    callback.fail("无法访问，请注意地址格式");
                            }
                        }
                    });
                }
            } catch (Exception ex) {
                LOG.e(ex);
            }
        }
    }
    
    @Override
    protected void onCleared() {
        super.onCleared();
        if (loadThread != null && loadThread.isAlive()) {
            loadThread.interrupt();
        }
        OkGo.getInstance().cancelTag("drive");
    }

    public void loadFile(DriveFolderFile targetFile, LoadFileCallback callback) {
        JsonObject config = currentDrive.getConfig();
        String webLink = getUrl(config.get("url").getAsString());
        String targetPath = targetFile.getAccessingPathStr() + targetFile.name;
        try {
            if (callback != null) {
                if (targetFile.fileUrl != null && !targetFile.fileUrl.isEmpty()) {
                    callback.callback(targetFile.fileUrl);
                } else {
                    callback.callback(URLDecoder.decode(webLink + "/d" + targetPath, "UTF-8"));
                }
            }
        } catch (UnsupportedEncodingException e) {
            LOG.e(e);
            callback.fail(e.getMessage());
        }

    }

    public interface LoadFileCallback {
        void callback(String fileUrl);

        void fail(String msg);
    }
}