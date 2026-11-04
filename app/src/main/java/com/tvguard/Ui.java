package com.tvguard;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 电视端自适应。
 *
 * 电视的 DPI 报得极其混乱：同样一块 1080p 屏，有的报 320dpi（等于 960x540dp），
 * 有的报 160dpi（等于 1920x1080dp），4K 机型更离谱。如果按 dp 写死尺寸，
 * 同一套界面在不同电视上会一半太大一半太小。
 *
 * 这里的办法是把短边强行换算成固定 dp 数：
 *   目标 dp = 540 * 缩放系数
 *   densityDpi = 160 * 短边像素 / 目标 dp
 * 于是 720p、1080p、4K 全都渲染成 960x540 的逻辑画布，
 * 布局只需要按 960x540 设计一次。
 */
public final class Ui {

    /** 设计稿的短边（dp），所有布局尺寸按这个基准写 */
    public static final float BASE_SHORT_DP = 540f;

    private Ui() {
    }

    /** 用户可选的三档界面缩放 */
    public static float normalizeScale(float s) {
        if (s < 0.7f) return 0.9f;
        if (s > 1.3f) return 1.2f;
        return s;
    }

    public static Context wrap(Context base, float userScale) {
        if (base == null) return null;
        DisplayMetrics dm = base.getResources().getDisplayMetrics();
        int shortPx = Math.min(dm.widthPixels, dm.heightPixels);
        if (shortPx <= 0) return base;

        float scale = normalizeScale(userScale);
        int targetDpi = Math.round(160f * shortPx / (BASE_SHORT_DP * scale));
        if (targetDpi < 100) targetDpi = 100;
        if (targetDpi > 640) targetDpi = 640;

        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        if (cfg.densityDpi == targetDpi && Math.abs(dm.density - (targetDpi / 160f)) < 0.01f) {
            return base;
        }
        cfg.densityDpi = targetDpi;
        try {
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }

    /** 屏幕短边像素，用来判断是不是极低分辨率设备 */
    public static int shortSidePx(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels);
    }

    /** 布局里拿到的逻辑宽高，用于个别需要按比例算尺寸的地方 */
    public static int widthDp(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.round(dm.widthPixels / dm.density);
    }

    public static int heightDp(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.round(dm.heightPixels / dm.density);
    }

    /** 少数超低分辨率机型（短边 < 480px）再压一档，避免四行选项撑出屏幕 */
    public static float autoShrink(Context c) {
        int p = shortSidePx(c);
        if (p > 0 && p < 480) return 0.85f;
        return 1f;
    }
}
