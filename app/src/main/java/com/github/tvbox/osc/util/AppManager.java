package com.github.tvbox.osc.util;

import android.app.Activity;

import com.github.tvbox.osc.util.LOG;

import java.util.Stack;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class AppManager {
    private static Stack<Activity> activityStack;

    private AppManager() {
    }

    private static class SingleHolder {
        private static AppManager instance = new AppManager();
    }

    public static AppManager getInstance() {
        return SingleHolder.instance;
    }

    /**
     * 添加Activity到堆栈
     */
    public void addActivity(Activity activity) {
        if (activityStack == null) {
            activityStack = new Stack<Activity>();
        }
        activityStack.add(activity);
    }

    /**
     * 是否有activity
     */
    public boolean isActivity() {
        if (activityStack != null) {
            return !activityStack.isEmpty();
        }
        return false;
    }

    /**
     * 获取当前Activity（堆栈中最后一个压入的）
     */
    public Activity currentActivity() {
        if (activityStack != null && !activityStack.isEmpty()) {
            return activityStack.lastElement();
        }
        return null;
    }

    /**
     * 结束当前Activity（堆栈中最后一个压入的）
     */
    public void finishActivity() {
        if (activityStack != null && !activityStack.isEmpty()) {
            Activity activity = activityStack.lastElement();
            if (activity != null && !activity.isFinishing()) {
                activity.finish();
            }
        }
    }

    public void finishActivity(Activity activity) {
        if (activity != null) {
            // 只在 Activity 没有正在销毁时才调用 finish
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
            activityStack.remove(activity);
        }
    }


    /**
     * 结束指定类名的Activity
     */
    public void finishActivity(Class<?> cls) {
        if (activityStack != null) {
            Activity targetActivity = null;
            for (Activity activity : activityStack) {
                if (activity != null && activity.getClass().equals(cls)) {
                    targetActivity = activity;
                    break;
                }
            }
            if (targetActivity != null) {
                if (!targetActivity.isFinishing()) {
                    targetActivity.finish();
                }
                activityStack.remove(targetActivity);
            }
        }
    }

    public void backActivity(Class<?> cls) {
        if (activityStack != null) {
            while (!activityStack.empty()) {
                Activity activity = activityStack.pop();
                if (activity != null && activity.getClass().equals(cls)) {
                    activityStack.push(activity);
                    break;
                } else if (activity != null) {
                    activity.finish();
                }
            }
        }
    }

    /**
     * 结束所有Activity
     */
    public void finishAllActivity() {
        if (activityStack != null && activityStack.size() > 0) {
            for (int i = 0, size = activityStack.size(); i < size; i++) {
                Activity activity = activityStack.get(i);
                if (null != activityStack.get(i)) {
                    if (!activity.isFinishing()) {
                        activity.finish();
                    }
                }
            }
            activityStack.clear();
        }
    }

    /**
     * 获取指定的Activity
     */
    public Activity getActivity(Class<?> cls) {
        if (activityStack != null) {
            for (Activity activity : activityStack) {
                if (activity != null && activity.getClass().equals(cls)) {
                    return activity;
                }
            }
        }
        return null;
    }

    /**
     * 获取上一个Activity（当前Activity的前一个）
     */
    public Activity getPreviousActivity() {
        if (activityStack != null && activityStack.size() >= 2) {
            // 获取倒数第二个Activity（当前Activity的前一个）
            return activityStack.get(activityStack.size() - 2);
        }
        return null;
    }

    public void appExit(int code) {
        try {
            com.github.tvbox.osc.base.App.cleanup();
            finishAllActivity();
            // 关键修复：延迟一点时间确保资源释放，避免UI缩放异常
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(code);
                } catch (Exception e) {
                }
            }, 500);
        } catch (Exception e) {
            activityStack.clear();
        }
    }
}