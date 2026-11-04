package com.tvguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Build;

/**
 * 答题/锁定期间的按键封闭（kiosk）。
 *
 * 为什么必须走这条路：
 * 遥控器上的「桌面」键（HOME）和「最近任务」键是系统自己处理的，
 * 根本不会送到应用里来。不管是悬浮窗还是普通全屏页，onKeyDown 都收不到 HOME，
 * 所以孩子一按就回桌面了，锁了个寂寞。
 *
 * 唯一能吃掉 HOME 的办法是进「锁任务」模式，而锁任务有两种完全不同的强度：
 *   1. 应用是设备所有者（Device Owner），并且本包已经写进白名单：
 *      startLockTask() 之后 HOME、返回、最近任务、状态栏全部失效，
 *      系统连「屏幕固定」的提示条都不显示，只有代码能退出。这是真锁。
 *   2. 不是设备所有者：startLockTask() 退化成系统的「屏幕固定」，
 *      个别 ROM 上 HOME 仍然能回桌面（华为智慧屏实测就是这样），
 *      外层只能靠「失焦就拉回」兜底。
 *
 * 所以两个版本的差别不在按键白名单，而在有没有拿到设备所有者这个身份。
 * 见 OwnerGuard。
 */
public final class Kiosk {

    private Kiosk() {
    }

    private static DevicePolicyManager dpm(Context c) {
        try {
            return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 现在是真锁还是只锁了一半。界面和日志都用这个判断，别各写一套 */
    public static boolean isStrict(Context c) {
        return OwnerGuard.isDeviceOwner(c);
    }

    /**
     * 把自己写进锁任务白名单。
     * 只有设备所有者能调，普通安装会直接抛异常，这里静默失败。
     */
    public static boolean prepare(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        return OwnerGuard.setLockWhitelist(c);
    }

    /** 进锁任务。返回是否真的进去了 */
    public static boolean enter(Activity a) {
        if (a == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            if (isOn(a)) return true;
            prepare(a.getApplicationContext());

            // 设备所有者还要把锁任务界面收拾干净：
            // 默认值在大多数 ROM 上已经是「什么都不给」，但个别固件会留着电源菜单，
            // 孩子长按电源键能直接关机，第二天开机重来。这里显式写死。
            if (isStrict(a) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    DevicePolicyManager d = dpm(a);
                    if (d != null) {
                        // 只保留系统信息（时间/电量），HOME、概览、通知、电源菜单一律不给
                        d.setLockTaskFeatures(new android.content.ComponentName(a, AdminReceiver.class),
                                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO);
                    }
                } catch (Throwable ignored) {
                }
            }
            a.startLockTask();
        } catch (Throwable t) {
            Diag.log(a, "锁键", "进锁任务失败：" + t.getClass().getSimpleName());
        }
        return isOn(a);
    }

    /** 出锁任务。必须在同一个 Activity 里调 */
    public static void exit(Activity a) {
        if (a == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            if (isOn(a)) a.stopLockTask();
        } catch (Throwable ignored) {
        }
    }

    /** 当前是否处在锁任务 / 屏幕固定状态 */
    public static boolean isOn(Context c) {
        if (c == null) return false;
        try {
            ActivityManager am =
                    (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            }
            //noinspection deprecation
            return am.isInLockTaskMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给设置页 / 诊断页看的一句话状态 */
    public static String describe(Context c) {
        if (isStrict(c)) {
            return "完全锁死（设备所有者）：答题时桌面键、返回键、最近任务键都按不动";
        }
        return "只锁一半：桌面键可能挡不住，需要用电脑跑一次「一键配置最终权限」";
    }
}
