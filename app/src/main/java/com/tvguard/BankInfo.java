package com.tvguard;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 题库索引：每个年级学期有多少道题。
 *
 * 由 tools/build_bank.py 在打包前生成 assets/bank/index.json，
 * 界面上用来告诉家长「选这个年级会有多少题」，避免加载后才能看到。
 */
public class BankInfo {

    private static JSONObject cache;

    private static void ensure(Context c) {
        if (cache != null) return;
        InputStream is = null;
        try {
            is = c.getAssets().open("bank/index.json");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            cache = new JSONObject(sb.toString());
        } catch (Exception e) {
            cache = new JSONObject();
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** 某个年级 + 学期范围的题目总数，0 表示没有 */
    public static int count(Context c, int grade, String term) {
        ensure(c);
        int n = 0;
        if ("ab".equals(term)) {
            n += one(grade + "a");
            n += one(grade + "b");
        } else {
            n = one(grade + term);
        }
        return n;
    }

    private static int one(String key) {
        return cache == null ? 0 : cache.optInt(key, 0);
    }

    public static int total(Context c) {
        ensure(c);
        int n = 0;
        if (cache == null) return 0;
        for (int g = 1; g <= 6; g++) {
            n += one(g + "a");
            n += one(g + "b");
        }
        return n;
    }
}
