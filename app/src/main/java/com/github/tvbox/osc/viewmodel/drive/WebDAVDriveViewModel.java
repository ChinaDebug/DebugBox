package com.github.tvbox.osc.viewmodel.drive;

import com.github.tvbox.osc.bean.DriveFolderFile;
import com.google.gson.JsonObject;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WebDAVDriveViewModel extends AbstractDriveViewModel {

    private Sardine webDAV;
    private Thread loadThread;

    private boolean initWebDav() {
        if (webDAV != null)
            return true;
        try {
            JsonObject config = currentDrive.getConfig();
            webDAV = new OkHttpSardine();
            if (config.has("username") && config.has("password")) {
                webDAV.setCredentials(config.get("username").getAsString(), config.get("password").getAsString());
            }
            return true;
        } catch (Exception ex) {
        }
        return false;
    }

    private Sardine getWebDAV() {
        if (initWebDav()) {
            return webDAV;
        }
        return null;
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
        private final WeakReference<WebDAVDriveViewModel> vmRef;
        private final JsonObject config;
        private final String targetPath;
        private final LoadDataCallback callback;

        LoadDataRunnable(WebDAVDriveViewModel vm, JsonObject config, String targetPath, LoadDataCallback callback) {
            this.vmRef = new WeakReference<>(vm);
            this.config = config;
            this.targetPath = targetPath;
            this.callback = callback;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) return;
            
            WebDAVDriveViewModel vm = vmRef.get();
            if (vm == null) return;
            
            Sardine webDAV = vm.getWebDAV();
            if (webDAV == null && callback != null) {
                callback.fail("无法访问该WebDAV地址");
                return;
            }
            List<DavResource> files = null;
            try {
                files = webDAV.list(config.get("url").getAsString() + targetPath);
            } catch (Exception ex) {
                if (callback != null)
                    callback.fail("无法访问该WebDAV地址");
                return;
            }

            if (Thread.currentThread().isInterrupted()) return;
            
            vm = vmRef.get();
            if (vm == null) return;

            List<DriveFolderFile> items = new ArrayList<>();
            if (files != null) {
                for (DavResource file : files) {
                    if (targetPath != "" && file.getPath().toUpperCase(Locale.ROOT).endsWith(targetPath.toUpperCase(Locale.ROOT) + "/"))
                        continue;
                    int extNameStartIndex = file.getName().lastIndexOf(".");
                    items.add(new DriveFolderFile(vm.currentDriveNote, file.getName(), 0, !file.isDirectory(),
                            !file.isDirectory() && extNameStartIndex >= 0 && extNameStartIndex < file.getName().length() ?
                                    file.getName().substring(extNameStartIndex + 1) : null,
                            file.getModified().getTime()));
                }
            }
            vm.sortData(items);
            DriveFolderFile backItem = new DriveFolderFile(null, null, 0, false, null, null);
            backItem.parentFolder = backItem;
            items.add(0, backItem);
            vm.currentDriveNote.setChildren(items);
            if (callback != null)
                callback.callback(vm.currentDriveNote.getChildren(), false);
        }
    }
    
    @Override
    protected void onCleared() {
        super.onCleared();
        if (loadThread != null && loadThread.isAlive()) {
            loadThread.interrupt();
        }
    }
}