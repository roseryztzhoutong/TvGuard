package com.tvguard;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 兼容性自检。
 *
 * 做通用版最大的问题不是「哪个品牌能不能装 APK」，而是同样的 APK 装到不同电视上，
 * 下面这几项系统能力有的被放开、有的被裁掉：
 *
 *   1. 悬浮窗权限（没有就自动改用全屏答题页，功能不受影响，只是体验差一点）
 *   2. 悬浮窗能不能拿到遥控器焦点（拿不到就选不了选项，这时也该走全屏页）
 *   3. 通知权限（周报提醒）
 *   4. 后台存活（电池优化白名单）
 *   5. 开机自启（多数电视默认拦）
 *
 * 与其给每个品牌写一份说明书，不如让软件自己测出来、自己告诉家长缺什么。
 */
public class CompatActivity extends Activity {

    private static final int OK = 0;
    private static final int WARN = 1;
    private static final int BAD = 2;
    private static final int INFO = 3;

    /** 遥控器测试结果：0 未测，1 通过，2 浮窗拿不到焦点，3 没收到按键 */
    private int keyTest = 0;

    private LinearLayout rowBox;
    private ScrollView scrollBox;
    private TextView tvDevice;

    private WindowManager wm;
    private View testOverlay;
    private int overlayKeys;
    private int activityKeys;
    private boolean servicePaused;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_compat);

        rowBox = findViewById(R.id.rowBox);
        scrollBox = findViewById(R.id.scrollBox);
        tvDevice = findViewById(R.id.tvDevice);

        findViewById(R.id.btnRecheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollBox.smoothScrollTo(0, 0);
                render();
                toast("已重新检测");
            }
        });
        findViewById(R.id.btnKeyTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startKeyTest();
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        render();

        // 焦点先落在滚动区，遥控器上下键就能翻；翻到底再按下会自然跳到下面按钮
        scrollBox.post(new Runnable() {
            @Override
            public void run() {
                scrollBox.requestFocus();
            }
        });

        // 从设置页直接点「遥控器按键测试」进来的，跳过后直接开测
        if (getIntent() != null && getIntent().getBooleanExtra("key_test", false)) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startKeyTest();
                }
            }, 400L);
        }
    }

    // ==================== 检测与渲染 ====================

    private void render() {
        rowBox.removeAllViews();
        final String brand = brandKey();

        tvDevice.setText(Build.MANUFACTURER + " " + Build.MODEL
                + "　Android " + Build.VERSION.RELEASE
                + "（API " + Build.VERSION.SDK_INT + "）"
                + (isTv() ? "　电视系统" : "　非电视系统"));

        // 1. 悬浮窗权限
        if (hasOverlay()) {
            addRow(OK, "悬浮窗权限已开启",
                    "答题界面会盖在视频上层，答完无缝接着看。");
        } else {
            addRow(WARN, "悬浮窗权限没开，到点改用全屏答题页",
                    "功能不受影响：到点会直接跳一个全屏答题页，答完按一下继续播就行。\n"
                            + "只是视频会被打断一下，不如盖在画面上那么顺。\n"
                            + "想开的话路径：" + overlayPath(brand)
                            + "\n电视上实在找不到入口，用电脑 adb 一条命令直接给：\n"
                            + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        }

        // 2. 遥控器焦点（最要命的一项）
        if (keyTest == 1) {
            addRow(OK, "遥控器能操作答题浮窗",
                    "实测浮窗收到了 " + overlayKeys + " 次按键，方向键和确定键都正常，答题可以选。");
        } else if (keyTest == 2) {
            addRow(WARN, "遥控器按键到不了答题浮窗",
                    "按键被下面的界面吃掉了，浮窗拿不到焦点。\n"
                            + "把悬浮窗权限关掉，或者直接不管，软件会自动改用全屏答题页，那边按键一定正常。");
        } else if (keyTest == 3) {
            addRow(WARN, "刚才没收到任何按键",
                    "可能是没按，也可能是遥控器走蓝牙/红外没走系统按键通道。再点一次「遥控器按键测试」，按几下方向键试试。");
        } else if (!hasOverlay()) {
            addRow(WARN, "遥控器焦点：不用测",
                    "这台电视不走悬浮窗，到点是全屏答题页。全屏页的按键是系统标准通道，不会出问题。");
        } else {
            addRow(WARN, "遥控器焦点：还没测",
                    "点下面「遥控器按键测试」，按几下方向键。这一项决定孩子能不能选中选项，务必测一次。");
        }

        // 3. 通知
        if (notifyEnabled()) {
            addRow(OK, "通知已开启", "每周一、每月 1 号会弹一条提醒，让你去看学习报告。");
        } else {
            addRow(WARN, "通知被关了",
                    "周报提醒弹不出来，报告还是能自己进去看。\n开启路径：设置 → 应用 → 答题锁 → 通知。");
        }

        // 4. 后台存活
        if (batteryOk()) {
            addRow(OK, "不受电池优化限制", "后台计时不会被系统掐断。");
        } else {
            addRow(WARN, "还在电池优化名单里",
                    "部分电视息屏或切后台后会掐掉后台进程，导致计时中断或开机不自启。\n"
                            + "开启路径：设置 → 应用 → 答题锁 → 电池 → 不受限制。");
        }

        // 5. 防卸载 + 跨应用能力
        // 这两件事其实是同一件事：有没有拿到设备所有者。
        // 没拿到时应用在自己界面里一切正常，一出门就管不住，这是家长最直接的感受。
        String owner = OwnerGuard.statusText(this);
        boolean isOwner = OwnerGuard.isDeviceOwner(this);
        addRow(isOwner ? OK : WARN, "防卸载：" + owner,
                OwnerGuard.powerText(this)
                        + (isOwner ? "\n\n已锁死，孩子在任何界面都卸不掉。"
                        : "\n\n没拿到最终权限时：题目只能在本应用里弹，孩子一按桌面键回主界面就管不住了。"
                        + "要彻底修好，用电脑跑一次交付目录里的「一键配置最终权限.bat」。"));

        // 6. 开机自启（无法可靠检测，只能说明）
        addRow(INFO, "开机自启：需装好后重启电视验证一次",
                "多数电视默认拦自启，但本应用用了前台服务 + 开机广播，多数机型能起来。\n"
                        + "验证方法：重启电视，等半分钟，看主屏右上角有没有「守护运行中」的通知。没有就进 "
                        + brandName(brand) + " 的「自启动管理」里把答题锁加白名单。");

        // 7. 系统底座说明
        addRow(INFO, "系统底座：" + systemKind(),
                systemKindNote(brand));
    }

    private void addRow(int level, String title, String detail) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, dp(6));

        TextView head = new TextView(this);
        head.setText("● " + title);
        head.setTextColor(colorOf(level));
        head.setTextSize(14);
        wrap.addView(head);

        if (detail != null && detail.length() > 0) {
            TextView d = new TextView(this);
            d.setText(detail);
            d.setTextColor(Color.parseColor("#94A3B8"));
            d.setTextSize(12);
            d.setLineSpacing(dp(3), 1f);
            d.setPadding(dp(16), dp(2), 0, 0);
            wrap.addView(d);
        }
        rowBox.addView(wrap);
    }

    private int colorOf(int level) {
        switch (level) {
            case OK:
                return Color.parseColor("#4ADE80");
            case WARN:
                return Color.parseColor("#FACC15");
            case BAD:
                return Color.parseColor("#F87171");
            default:
                return Color.parseColor("#CBD5E1");
        }
    }

    // ==================== 遥控器浮窗按键实测 ====================

    private void startKeyTest() {
        if (!hasOverlay()) {
            toast("这台电视走全屏答题页，不用测浮窗按键。方向键一定正常");
            return;
        }
        if (testOverlay != null) {
            toast("测试正在进行，按几下方向键");
            return;
        }
        // 暂停守护，免得测试中途弹出一道真题抢走焦点
        Prefs p = new Prefs(this);
        if (p.isEnabled()) {
            servicePaused = true;
            stopService(new Intent(this, GuardService.class));
        }
        overlayKeys = 0;
        activityKeys = 0;

        if (wm == null) wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        final TextView label = new TextView(this);
        label.setTextColor(Color.parseColor("#F1F5F9"));
        label.setTextSize(18);
        label.setGravity(Gravity.CENTER);
        label.setText("遥控器按键测试\n\n请按几下方向键，再按一次确定键\n\n浮窗收到按键：0");

        LinearLayout box = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    overlayKeys++;
                    label.setText("遥控器按键测试\n\n请按几下方向键，再按一次确定键\n\n浮窗收到按键：" + overlayKeys);
                }
                return true;   // 吞掉，不往下传
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#F20B1220"));
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                0,                       // 注意：不能加 FLAG_NOT_FOCUSABLE，加了就收不到遥控器
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;

        try {
            wm.addView(box, lp);
            testOverlay = box;
            box.setFocusableInTouchMode(true);
            box.requestFocus();
        } catch (Throwable t) {
            testOverlay = null;
            resumeService();
            toast("浮窗加不上：" + t.getMessage());
            return;
        }

        // 让 Activity 自己的窗口也持有焦点作为对照：如果浮窗抢到焦点，这里就收不到按键了
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.setFocusableInTouchMode(true);
            content.requestFocus();
        }

        toast("开始测试，请在 10 秒内按方向键");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                endKeyTest();
            }
        }, 10000L);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (testOverlay != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            // 走到这里说明浮窗没拿到焦点，按键漏到底下的 Activity 了
            activityKeys++;
        }
        return super.dispatchKeyEvent(e);
    }

    private void endKeyTest() {
        if (testOverlay != null) {
            try {
                wm.removeViewImmediate(testOverlay);
            } catch (Throwable ignored) {
            }
            testOverlay = null;
        }
        resumeService();

        if (overlayKeys > 0) {
            keyTest = 1;
        } else if (activityKeys > 0) {
            keyTest = 2;
        } else {
            keyTest = 3;
        }
        render();
    }

    private void resumeService() {
        if (servicePaused) {
            servicePaused = false;
            Prefs p = new Prefs(this);
            if (p.isEnabled()) {
                try {
                    Intent i = new Intent(this, GuardService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (testOverlay != null && wm != null) {
            try {
                wm.removeViewImmediate(testOverlay);
            } catch (Throwable ignored) {
            }
            testOverlay = null;
        }
        resumeService();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ==================== 系统能力查询 ====================

    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean notifyEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isTv() {
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature("android.hardware.type.television");
    }

    // ==================== 品牌识别与路径 ====================

    private String brandKey() {
        String s = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL
                + " " + Build.DEVICE).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("小米") || s.contains("mi tv")) return "xiaomi";
        if (s.contains("tcl") || s.contains("ffalcon") || s.contains("雷鸟")) return "tcl";
        if (s.contains("hisense") || s.contains("vidda") || s.contains("海信")) return "hisense";
        if (s.contains("skyworth") || s.contains("coocaa") || s.contains("创维") || s.contains("酷开")) return "skyworth";
        if (s.contains("huawei") || s.contains("honor") || s.contains("华为") || s.contains("荣耀")) return "huawei";
        if (s.contains("sony") || s.contains("philips") || s.contains("sharp") || s.contains("panasonic")) return "native";
        if (s.contains("changhong") || s.contains("长虹")) return "changhong";
        if (s.contains("konka") || s.contains("康佳")) return "konka";
        if (s.contains("dangbei") || s.contains("当贝")) return "dangbei";
        if (s.contains("google") || s.contains("adt-3")) return "native";
        return "other";
    }

    private String brandName(String key) {
        switch (key) {
            case "xiaomi":   return "小米 / Redmi";
            case "tcl":      return "TCL / 雷鸟";
            case "hisense":  return "海信 / Vidda";
            case "skyworth": return "创维 / 酷开";
            case "huawei":   return "华为 / 荣耀";
            case "native":   return "索尼 / 飞利浦等原生安卓电视";
            case "changhong":return "长虹";
            case "konka":    return "康佳";
            case "dangbei":  return "当贝";
            default:         return "这台电视";
        }
    }

    private String overlayPath(String brand) {
        switch (brand) {
            case "xiaomi":
                return "小米：设置 → 应用 → 管理应用 → 答题锁 → 权限 → 显示在其他应用上层。\n"
                        + "部分新款 PatchWall 把这一项藏了，直接看下面的 adb 命令。";
            case "tcl":
                return "TCL / 雷鸟：设置 → 系统与权限 → 应用权限 → 答题锁 → 悬浮窗。";
            case "hisense":
                return "海信 / Vidda：设置 → 更多设置 → 应用管理 → 答题锁 → 权限 → 显示在其他应用上层。";
            case "skyworth":
                return "创维 / 酷开：设置 → 隐私与安全 → 应用权限 → 答题锁 → 悬浮窗。";
            case "huawei":
                return "华为 / 荣耀：设置 → 应用 → 应用管理 → 答题锁 → 权限 → 悬浮窗。\n"
                        + "鸿蒙系统若提示「不兼容」，说明这台是纯鸿蒙（HarmonyOS NEXT），装不了安卓应用。";
            case "native":
                return "原生安卓电视：设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → 答题锁。";
            default:
                return "通用路径：设置 → 应用 → 答题锁 → 权限 → 显示在其他应用上层（或叫「悬浮窗」）。";
        }
    }

    private String systemKind() {
        String brand = brandKey();
        switch (brand) {
            case "xiaomi":   return "安卓深度定制（PatchWall / MIUI for TV）";
            case "tcl":      return "安卓定制（灵控系统）";
            case "hisense":  return "安卓定制（VIDAA）";
            case "skyworth": return "安卓定制（酷开）";
            case "huawei":   return "鸿蒙 + 安卓兼容层，能装 APK；纯鸿蒙 NEXT 不行";
            case "native":   return "原生 Android TV / Google TV，兼容性最好";
            default:         return isTv() ? "安卓电视定制系统" : "标准安卓";
        }
    }

    private String systemKindNote(String brand) {
        if ("huawei".equals(brand)) {
            return "华为智慧屏 2024 年前的鸿蒙 2/3/4 带安卓兼容层，能装 APK；"
                    + "之后的纯鸿蒙只认 HAP，装不了。装不上就换外接安卓盒子。";
        }
        if ("hisense".equals(brand)) {
            return "海信有一部分机型跑的是非安卓的 VIDAA，装不了 APK。装的时候报「解析包错误」基本就是这原因，换盒子。";
        }
        return "本应用只用安卓系统自带接口，不依赖任何厂商 SDK，只要是安卓电视都同一套逻辑。";
    }

    // ==================== 工具 ====================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
