package com.tvguard;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 设备所有者（Device Owner）支持。
 *
 * 这是整台电视能不能真正管住孩子的分水岭。
 *
 * 普通设备管理器（DeviceAdmin）只是「提醒」，孩子在设置里点几下就能取消激活再卸载。
 * 普通安装的 App 也拿不到两样最关键的权力：
 *   一、悬浮窗权限。华为智慧屏的设置里根本没有这一项，家长想给也给不了。
 *   二、锁任务真正生效。不是设备所有者时 startLockTask 只是系统的「屏幕固定」，
 *      按 HOME 照样回桌面，实测在智慧屏上连桌面都挡不住。
 *
 * 成为 Device Owner 之后，这两样都由系统直接交到我们手里：
 *   - setPermissionGrantState 可以给自己授予 SYSTEM_ALERT_WINDOW，
 *     不需要电视上有那个授权入口。这一条直接决定「孩子看 B 站时能不能弹题」。
 *   - setLockTaskPackages 写进白名单后，startLockTask 变成真正的锁任务，
 *     HOME、最近任务、状态栏全部失效，只有代码能退出。
 *   - 后台启动界面也豁免限制，题目能从服务里直接压到最前面。
 *
 * 代价是需要一次性用电脑执行 ADB 命令，见交付目录里的「一键配置最终权限.bat」。
 * 命令本身不需要 root，只是把系统里本来就有的一个身份交给本应用。
 *
 * 注意：set-device-owner 要求设备上还没有任何账号、且未设置过其他 Device Owner。
 * 华为智慧屏如果登过华为账号，这一步会被系统拒绝，那种情况只能退到无障碍浮窗，
 * 详见 A11y 的说明。做之前先看脚本的检测结果，别自己瞎试。
 */
public final class OwnerGuard {

    private OwnerGuard() {
    }

    private static DevicePolicyManager dpm(Context c) {
        try {
            return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    /** 当前是否已经是设备所有者 */
    public static boolean isDeviceOwner(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return d.isDeviceOwnerApp(c.getPackageName());
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设备管理器是否已激活 */
    public static boolean isAdminActive(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return false;
            return d.isAdminActive(admin(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统有没有真的给了悬浮窗权限 */
    public static boolean canOverlay(Context c) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 让系统把悬浮窗权限直接授给我们。
     *
     * 只有设备所有者能调，普通安装时会静默失败，返回 false。
     * 这是「电视设置里没有悬浮窗这一项」的正解：不是去求家长找入口，
     * 而是让系统按设备所有者的身份把权限批下来。
     *
     * 注意部分定制 ROM 会「返回授予成功但实际不生效」，
     * 所以最后一定要回读 Settings.canDrawOverlays，不能只看返回值。
     */
    public static boolean grantOverlay(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (canOverlay(c)) return true;
        if (!isDeviceOwner(c)) return false;
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return false;
            // 返回值只代表「请求有没有被受理」，不代表权限真的批下来了，
            // 有些定制 ROM 会先返回 true 再悄悄拒掉。所以下面还要回读一次。
            boolean accepted = d.setPermissionGrantState(admin(c), c.getPackageName(),
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            Diag.log(c, "权限", "请求授予悬浮窗权限，系统受理=" + (accepted ? "是" : "否"));
        } catch (Throwable t) {
            Diag.log(c, "权限", "授予悬浮窗失败：" + t.getClass().getSimpleName());
        }
        return canOverlay(c);
    }

    /**
     * 把自己写进锁任务白名单。
     *
     * 白名单里只放自己，不放任何视频应用。
     * 放进去就等于在锁任务期间给孩子留了一扇门，而锁任务的整套逻辑
     * 就是「除答题外什么都打不开」，多放一个包就多一个洞。
     * 孩子正常看电视的时间段本来就不在锁任务里，不需要白名单。
     */
    public static boolean setLockWhitelist(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        if (!isDeviceOwner(c)) return false;
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return false;
            d.setLockTaskPackages(admin(c), new String[]{c.getPackageName()});
            return true;
        } catch (Throwable t) {
            Diag.log(c, "权限", "写锁任务白名单失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 已经是 Device Owner 的话，把自己标记为不可卸载。
     * 不是 Device Owner 时静默失败，不影响正常使用。
     */
    public static void blockSelfUninstall(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (!isDeviceOwner(c)) return;
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return;
            d.setUninstallBlocked(admin(c), c.getPackageName(), true);
        } catch (Throwable ignored) {
        }
    }

    /** 解除不可卸载（家长要卸载时先调这个） */
    public static void unblockSelfUninstall(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (!isDeviceOwner(c)) return;
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return;
            d.setUninstallBlocked(admin(c), c.getPackageName(), false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一次性把设备所有者能给的好处全部落地。
     *
     * 每次服务启动、每次打开设置页都调一遍，看着啰嗦，其实是为了自愈：
     * 电视恢复出厂、系统升级、家长误操作，都可能让其中某一项掉回未授予状态。
     * 这些调用本身很便宜，重复执行没有副作用。
     */
    public static void applyPolicies(Context c) {
        if (c == null) return;
        if (!isDeviceOwner(c)) return;
        blockSelfUninstall(c);
        grantOverlay(c);
        setLockWhitelist(c);
    }

    /**
     * 彻底松开：解除不可卸载 → 放弃设备所有者 → 取消设备管理器激活。
     *
     * 顺序不能反。已经是 Device Owner 时，光 removeActiveAdmin 会抛 SecurityException，
     * 必须先用 clearDeviceOwnerApp 把身份交出去，系统才允许卸载。
     * 家长要关掉或者卸载时，必须先把这条路走通，否则连自己都删不掉。
     */
    public static void releaseAll(Context c) {
        unblockSelfUninstall(c);
        try {
            if (isDeviceOwner(c) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                DevicePolicyManager d = dpm(c);
                if (d != null) d.clearDeviceOwnerApp(c.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        try {
            DevicePolicyManager d = dpm(c);
            if (d != null && d.isAdminActive(admin(c))) {
                d.removeActiveAdmin(admin(c));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 界面用的状态描述 */
    public static String statusText(Context c) {
        if (isDeviceOwner(c)) {
            return canOverlay(c)
                    ? "已拿到最终权限（设备所有者 + 悬浮窗）"
                    : "设备所有者（悬浮窗还没批下来）";
        }
        if (isAdminActive(c)) return "一般（可在设置里取消激活后卸载）";
        return "未开启";
    }

    /**
     * 跨应用管住孩子的能力，到底有几成。
     *
     * 只用来给家长一句人话，判断逻辑别的地方不要复用这个字符串。
     */
    public static String powerText(Context c) {
        if (isDeviceOwner(c) && canOverlay(c)) {
            return "完全生效：切到任何应用都能弹题，答题时按桌面键也出不去";
        }
        if (isDeviceOwner(c)) {
            return "锁屏生效，但悬浮窗没批下来：答题会全屏弹出";
        }
        if (A11y.ready()) {
            return "一半：靠着无障碍浮窗能盖在别的应用上，但按桌面键挡不住";
        }
        return "只在答题锁自己里面生效，切到别的应用就管不住了";
    }
}
