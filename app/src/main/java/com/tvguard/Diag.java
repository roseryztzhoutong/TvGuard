package com.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行诊断日志。
 *
 * 电视上没有 adb 也没有 Logcat，出问题时家长只能描述「不弹题」，
 * 而「不弹」有五六种原因，光靠猜永远猜不准。这里把关键节点记在本地，
 * 家长进设置页就能看到最近发生了什么，一行一条。
 *
 * 只记事件，不记任何隐私内容（不记题目、不记看了什么）。
 */
public final class Diag {

    private static final String FILE = "tvguard_diag";
    private static final String K_LOG = "log";
    /** 留最近 150 条，够看清一整天的节奏，又不会把 SP 撑大 */
    private static final int MAX_LINES = 150;

    private Diag() {
    }

    private static SharedPreferences sp(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 记一条。tag 用短词，比如 弹题 / 浮窗 / 计时 */
    public static synchronized void log(Context c, String tag, String msg) {
        if (c == null) return;
        try {
            String t = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = t + " [" + tag + "] " + msg;

            SharedPreferences p = sp(c);
            String old = p.getString(K_LOG, "");
            String merged = old.isEmpty() ? line : old + "\n" + line;

            // 超长就砍掉最老的几条，避免无限增长
            String[] arr = merged.split("\n");
            if (arr.length > MAX_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = arr.length - MAX_LINES; i < arr.length; i++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(arr[i]);
                }
                merged = sb.toString();
            }
            p.edit().putString(K_LOG, merged).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 倒序返回，最新的一行在最上面，家长不用滑到底 */
    public static synchronized List<String> recent(Context c, int max) {
        List<String> out = new ArrayList<>();
        try {
            String s = sp(c).getString(K_LOG, "");
            if (s == null || s.isEmpty()) return out;
            String[] arr = s.split("\n");
            for (int i = arr.length - 1; i >= 0 && out.size() < max; i--) {
                if (!arr[i].trim().isEmpty()) out.add(arr[i]);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void clear(Context c) {
        try {
            sp(c).edit().remove(K_LOG).apply();
        } catch (Throwable ignored) {
        }
    }
}
