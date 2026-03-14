package com.github.tvbox.osc.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hjq.permissions.Permission;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class DefaultConfig {

    public static List<MovieSort.SortData> adjustSort(String sourceKey, List<MovieSort.SortData> list, boolean withMy) {
        List<MovieSort.SortData> data = new ArrayList<>();
        if (sourceKey != null) {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb != null) {
                ArrayList<String> categories = sb.getCategories();
                if (categories != null && !categories.isEmpty()) {
                    for (String cate : categories) {
                        for (MovieSort.SortData sortData : list) {
                            if (sortData.name.equals(cate)) {
                                if (sortData.filters == null)
                                    sortData.filters = new ArrayList<>();
                                data.add(sortData);
                            }
                        }
                    }
                } else {
                    for (MovieSort.SortData sortData : list) {
                        if (sortData.filters == null)
                            sortData.filters = new ArrayList<>();
                        data.add(sortData);
                    }
                }
            }
        }
        if (withMy)
            data.add(0, new MovieSort.SortData("my0", HomeActivity.getRes().getString(R.string.app_home)));
        Collections.sort(data);
        return data;
    }

    public static int getAppVersionCode(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            LOG.e(e);
        }
        return -1;
    }

    public static boolean resetApp(Context mContext){
        if (!hasStoragePermission(mContext)) {
            return false;
        }
        clearPublic(mContext);
        clearPrivate(mContext);
        restartApp();
        return true;
    }

    private static boolean hasStoragePermission(Context context) {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static void restartApp() {
        Activity activity = AppManager.getInstance().getActivity(HomeActivity.class);
        final Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
        }
        //杀掉以前进程
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * 清空公有目录
     */
    public static void clearPublic(Context mContext) {
        File dir = new File(App.getInstance().getExternalFilesDir("").getParentFile().getAbsolutePath());
        File[] files = dir.listFiles();
        if (null != files) {
            for (File file : files) {
                FileUtils.recursiveDelete(file);
            }
        }
        String publicFilePath = Environment.getExternalStorageDirectory().getPath() + "/" + getPackageName(mContext);
        dir = new File(publicFilePath);
        files = dir.listFiles();
        if (null != files) {
            for (File file : files) {
                FileUtils.recursiveDelete(file);
            }
        }
    }

    /**
     * 清空私有目录
     */
    public static  void clearPrivate(Context mContext) {
        //清空文件夹
        File dir = new File(Objects.requireNonNull(mContext.getFilesDir().getParent()));
        File[] files = dir.listFiles();
        if (null != files) {
            for (File file : files) {
                if (!file.getName().contains("lib")) {
                    FileUtils.recursiveDelete(file);
                }
            }
        }
    }

    public static String getPackageName(Context mContext) {
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.packageName;
        } catch (PackageManager.NameNotFoundException e) {
            LOG.e(e);
        }
        return "";
    }
    public static String getAppVersionName(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            LOG.e(e);
        }
        return "";
    }

    /**
     * 后缀
     *
     * @param name
     * @return
     */
    public static String getFileSuffix(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int endP = name.lastIndexOf(".");
        return endP > -1 ? name.substring(endP) : "";
    }

    /**
     * 获取文件的前缀
     *
     * @param fileName
     * @return
     */
    public static String getFilePrefixName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        int start = fileName.lastIndexOf(".");
        return start > -1 ? fileName.substring(0, start) : fileName;
    }

    private static final Pattern snifferMatch = Pattern.compile(
            "https?((?!https?).){10,}?\\.(m3u8|mp4|flv|avi|mkv|rm|rmvb|wmv|mpg|mpeg|mov|ts|webm|f4v|3gp|asf|vob)(\\?|$|#)|" +
            "https?((?!https?).)*?video/tos[^\\?]*|" +
            "https?((?!https?).){10,}?/m3u8\\?pt=m3u8.*|" +
            "https?((?!https?).)*?default\\.ixigua\\.com/.*|" +
            "https?((?!https?).)*?dycdn-tos\\.pstatp[^\\?]*|" +
            "https?((?!https?).)*?bytecdn\\.cn/.*|" +
            "https?((?!https?).)*?bytednsdoc\\.com/.*|" +
            "https?.*?/player/m3u8play\\.php\\?url=.*|" +
            "https?.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
            "https?.*?/playlist/m3u8/\\?vid=.*|" +
            "https?.*?\\.php\\?type=m3u8&.*|" +
            "https?.*?/download\\.aspx\\?.*|" +
            "https?.*?/api/up_api\\.php\\?.*|" +
            "https?.*?\\.66yk\\.cn.*|" +
            "https?((?!https?).)*?netease\\.com/file/.*|" +
            "https?((?!https?).)*?\\.m3u8\\.cdn.*|" +
            "https?((?!https?).)*?/video/.*\\.m3u8.*|" +
            "https?((?!https?).)*?cdn.*?/.*\\.(m3u8|mp4|ts).*|" +
            "https?((?!https?).)*?/play/.*\\.(m3u8|mp4).*"
    );
    public static boolean isVideoFormat(String url) {
        if (url.contains("=http")) {
            return false;
        }
        if (snifferMatch.matcher(url).find()) {
            String lowerUrl = url.toLowerCase();
            return !lowerUrl.contains(".js")
                && !lowerUrl.contains(".css")
                && !lowerUrl.contains(".jpg")
                && !lowerUrl.contains(".jpeg")
                && !lowerUrl.contains(".png")
                && !lowerUrl.contains(".gif")
                && !lowerUrl.contains(".ico")
                && !lowerUrl.contains(".webp")
                && !lowerUrl.contains(".svg")
                && !lowerUrl.contains(".woff")
                && !lowerUrl.contains(".ttf")
                && !lowerUrl.contains("rl=")
                && !isHtmlFile(url);
        }
        return false;
    }

    private static boolean isHtmlFile(String url) {
        int queryStart = url.indexOf('?');
        int hashStart = url.indexOf('#');
        int pathEnd = url.length();
        if (queryStart > 0) pathEnd = Math.min(pathEnd, queryStart);
        if (hashStart > 0) pathEnd = Math.min(pathEnd, hashStart);
        String path = url.substring(0, pathEnd).toLowerCase();
        return path.endsWith(".html") || path.endsWith(".htm");
    }


    public static String safeJsonString(JsonObject obj, String key, String defaultVal) {
        try {
            if (obj.has(key)){
                return obj.get(key).isJsonObject() || obj.get(key).isJsonArray()?obj.get(key).toString().trim():obj.getAsJsonPrimitive(key).getAsString().trim();
            }
            else
                return defaultVal;
        } catch (Throwable th) {
        }
        return defaultVal;
    }

    public static int safeJsonInt(JsonObject obj, String key, int defaultVal) {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).getAsInt();
            else
                return defaultVal;
        } catch (Throwable th) {
        }
        return defaultVal;
    }

    public static ArrayList<String> safeJsonStringList(JsonObject obj, String key) {
        ArrayList<String> result = new ArrayList<>();
        try {
            if (obj.has(key)) {
                if (obj.get(key).isJsonObject()) {
                    result.add(obj.get(key).getAsString());
                } else {
                    for (JsonElement opt : obj.getAsJsonArray(key)) {
                        result.add(opt.getAsString());
                    }
                }
            }
        } catch (Throwable th) {
        }
        return result;
    }

    public static String checkReplaceProxy(String urlOri) {
        if (urlOri.startsWith("proxy://"))
            return urlOri.replace("proxy://", ControlManager.get().getAddress(true) + "proxy?");
        return urlOri;
    }

    public static String[] StoragePermissionGroup() {
        // Android 11+ (API 30+) 使用 MANAGE_EXTERNAL_STORAGE
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要申请此权限
        Context context = App.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            return new String[] {
                    Permission.MANAGE_EXTERNAL_STORAGE
            };
        }
        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        return new String[] {
                Permission.READ_EXTERNAL_STORAGE,
                Permission.WRITE_EXTERNAL_STORAGE
        };
    }

}
