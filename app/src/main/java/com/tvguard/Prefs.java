package com.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 配置与运行状态的持久化。
 * 所有状态写进 SharedPreferences，断电重启也不会清零，这是防绕过的基础。
 */
@SuppressWarnings("unchecked")
public class Prefs {

    private static final String FILE = "tvguard_prefs";

    // 家长可配置项
    private static final String K_INTERVAL = "interval_min";      // 每看多久弹一次题
    private static final String K_DAILY_LIMIT = "daily_limit_min"; // 每日总时长上限
    private static final String K_MAX_WRONG = "max_wrong";        // 连错几次锁定
    private static final String K_LOCK_MIN = "lock_min";          // 锁定多少分钟
    private static final String K_STREAK = "streak_required";      // 需连续答对几题才放行
    private static final String K_PIN = "parent_pin";
    private static final String K_PIN_FAIL = "pin_fail_count";    // 连续输错次数
    private static final String K_PIN_LOCK = "pin_lock_until";    // 锁到什么时候（毫秒时间戳）
    private static final String K_ENABLED = "enabled";
    private static final String K_SUBJECTS = "subjects";          // 启用的科目，逗号分隔
    // 科目表升级标记。v3.7 把科学并入题库，老用户升级后要补一次科学，
    // 补过就不再动，家长手动关掉科学不会被反复加回来。
    private static final String K_SUBJ_MERGED = "subjects_science_merged";
    private static final String K_GRADE = "child_grade";           // 孩子年级 1-6，0 = 还没选
    private static final String K_TERM = "bank_term";              // a 上册 / b 下册 / ab 全学年
    private static final String K_UI_SCALE = "ui_scale";           // 界面缩放 0.9 / 1.0 / 1.2
    private static final String K_REPORT_DONE = "report_last_key"; // 上次自动生成报告的周期标识

    // 运行状态
    private static final String S_DATE = "today_date";
    private static final String S_USED = "today_used_sec";
    private static final String S_SINCE = "since_last_sec";
    private static final String S_WRONG = "wrong_count";
    private static final String S_STREAK_CNT = "streak_count";
    private static final String S_LOCK_UNTIL = "lock_until_ms";
    private static final String S_LAST_BEAT = "last_beat_ms";
    private static final String S_TOTAL_QUIZ = "total_quiz_count";
    private static final String S_TOTAL_RIGHT = "total_right_count";
    // 这台电视上「全屏答题页」到底能不能拉起来。拉不起来一次就永久改走悬浮窗，
    // 免得每次到点都先白等一秒半。换设备清数据会重测。
    private static final String S_FULLSCREEN_OK = "fullscreen_ok";
    // 最近一次弹题的结果，一句话。诊断页和设置页都显示它，
    // 家长不用猜「到底弹没弹」，看一眼就知道走的是哪条路、成没成。
    private static final String S_LAST_POP = "last_pop_result";

    // 逐题答题历史：每道固定题答对/答错各几次，答对满 2 次就退役
    private static final String H_HIST = "quiz_hist";
    private static final String K_BANK_ROUND = "bank_round";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        // attachBaseContext 阶段 getApplicationContext() 可能还是 null，必须兜一下，
        // 否则界面缩放读不到、Activity 直接崩在启动第一步。
        Context app = c.getApplicationContext();
        sp = (app != null ? app : c).getSharedPreferences(FILE, Context.MODE_PRIVATE);
        mergeScienceOnce();
    }

    /**
     * 把科学并进已有科目表，只做一次。
     *
     * <p>升级安装时 SharedPreferences 里存的是旧的三科，新加的科目不写进去，
     * 家长打开设置看到的还是语数英，会以为科学题库没生效。
     */
    private void mergeScienceOnce() {
        if (sp.getBoolean(K_SUBJ_MERGED, false)) return;
        String cur = sp.getString(K_SUBJECTS, null);
        if (cur != null && !cur.contains("science")) {
            sp.edit().putString(K_SUBJECTS, cur.isEmpty() ? "science" : cur + ",science").apply();
        }
        sp.edit().putBoolean(K_SUBJ_MERGED, true).apply();
    }

    // ---------- 配置 ----------

    public int getIntervalMin() { return sp.getInt(K_INTERVAL, 10); }
    public void setIntervalMin(int v) { sp.edit().putInt(K_INTERVAL, clamp(v, 1, 120)).apply(); }

    public int getDailyLimitMin() { return sp.getInt(K_DAILY_LIMIT, 60); }
    public void setDailyLimitMin(int v) { sp.edit().putInt(K_DAILY_LIMIT, clamp(v, 10, 600)).apply(); }

    public int getMaxWrong() { return sp.getInt(K_MAX_WRONG, 3); }
    public void setMaxWrong(int v) { sp.edit().putInt(K_MAX_WRONG, clamp(v, 1, 10)).apply(); }

    public int getLockMin() { return sp.getInt(K_LOCK_MIN, 10); }
    public void setLockMin(int v) { sp.edit().putInt(K_LOCK_MIN, clamp(v, 1, 120)).apply(); }

    /**
     * 需连续答对几题才放行。
     * 这是防"瞎蒙"的关键：4 选 1 单题通过率 25%，
     * 要求连对 3 题则通过率为 0.25^3 ≈ 1.6%，蒙不过去。
     */
    public int getStreakRequired() { return sp.getInt(K_STREAK, 3); }
    public void setStreakRequired(int v) { sp.edit().putInt(K_STREAK, clamp(v, 1, 10)).apply(); }

    public String getPin() { return sp.getString(K_PIN, ""); }
    public void setPin(String p) { sp.edit().putString(K_PIN, p).apply(); }

    /**
     * 出厂不带密码。第一次装必须家长自己设一个，
     * 否则「默认 1234」等于没设防，孩子第一次就试出来了。
     */
    public boolean hasPin() {
        String p = getPin();
        return p != null && p.length() >= 4;
    }

    public boolean checkPin(String p) { return hasPin() && getPin().equals(p); }

    // ---------- 防试密码 ----------

    public int getPinFail() { return sp.getInt(K_PIN_FAIL, 0); }
    public void setPinFail(int n) { sp.edit().putInt(K_PIN_FAIL, clamp(n, 0, 999)).apply(); }

    public long getPinLockUntil() { return sp.getLong(K_PIN_LOCK, 0L); }
    public void setPinLockUntil(long ms) { sp.edit().putLong(K_PIN_LOCK, ms).apply(); }

    public void clearPinFail() {
        sp.edit().putInt(K_PIN_FAIL, 0).putLong(K_PIN_LOCK, 0L).apply();
    }

    public boolean isEnabled() { return sp.getBoolean(K_ENABLED, true); }
    public void setEnabled(boolean b) { sp.edit().putBoolean(K_ENABLED, b).apply(); }

    /** 科目：chinese,math,english,science 的子集 */
    public String getSubjects() { return sp.getString(K_SUBJECTS, "chinese,math,english,science"); }
    public void setSubjects(String s) { sp.edit().putString(K_SUBJECTS, s).apply(); }

    // ---------- 年级与题库范围 ----------

    public int getGrade() { return sp.getInt(K_GRADE, 0); }
    public void setGrade(int g) { sp.edit().putInt(K_GRADE, clamp(g, 1, 6)).apply(); }
    public boolean hasGrade() { return getGrade() >= 1 && getGrade() <= 6; }

    /** 学期范围：a=上册，b=下册，ab=全学年 */
    public String getTerm() {
        String t = sp.getString(K_TERM, "");
        if ("a".equals(t) || "b".equals(t) || "ab".equals(t)) return t;
        return autoTerm();
    }

    public void setTerm(String t) {
        if ("a".equals(t) || "b".equals(t) || "ab".equals(t)) sp.edit().putString(K_TERM, t).apply();
    }

    /** 没设过就按当前月份猜：9月到次年1月是上册，2到6月是下册，寒暑假给全学年 */
    public static String autoTerm() {
        int m = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1;
        if (m >= 9 || m == 1) return "a";
        if (m >= 2 && m <= 6) return "b";
        return "ab";
    }

    public float getUiScale() { return sp.getFloat(K_UI_SCALE, 1.0f); }
    public void setUiScale(float s) { sp.edit().putFloat(K_UI_SCALE, s).apply(); }

    public String getReportKey() { return sp.getString(K_REPORT_DONE, ""); }
    public void setReportKey(String k) { sp.edit().putString(K_REPORT_DONE, k).apply(); }

    // ---------- 运行状态 ----------

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    /** 跨天自动清零。每次读写状态前都调用 */
    public void rolloverIfNewDay() {
        String t = today();
        if (!t.equals(sp.getString(S_DATE, ""))) {
            sp.edit()
                    .putString(S_DATE, t)
                    .putInt(S_USED, 0)
                    .putInt(S_SINCE, 0)
                    .putInt(S_WRONG, 0)
                    .putInt(S_STREAK_CNT, 0)
                    .putLong(S_LOCK_UNTIL, 0)
                    .apply();
            cUsed = 0;
            cSince = 0;
        }
    }

    // ---------- 高频计时的内存缓存 ----------
    // 守护服务每秒要 +1 秒观看时长、+1 秒距上次答题。原来是每秒两次 apply()，
    // 等于电视的闪存整天处在写入状态，久了会越来越卡，也白白磨寿命。
    // 这里改成内存累加，每 15 次（约 15 秒）落盘一次。服务被杀最多丢 15 秒，可以接受。
    private static Integer cUsed;
    private static Integer cSince;
    private static int tickN = 0;

    /** 服务每秒调一次，别在外面再单独调 addUsedSec / addSinceLastSec */
    public void tickAddSec() {
        tickAddSec(1);
    }

    /**
     * 一次补 n 秒。
     *
     * 为什么要支持 n 而不是死磕 1 秒：电视系统会把后台进程「冻结」，
     * 冻结期间 handler 一秒都不跑。解冻后如果还按「每跑一次 +1」算，
     * 那段时间就永远丢了，家长看到的现象就是「只有打开答题锁这个界面才计时」。
     * 现在服务每次醒来都按真实经过的秒数补，冻结多久补多久。
     */
    public void tickAddSec(int n) {
        rolloverIfNewDay();
        if (n < 1) n = 1;
        if (cUsed == null) cUsed = sp.getInt(S_USED, 0);
        if (cSince == null) cSince = sp.getInt(S_SINCE, 0);
        cUsed += n;
        cSince += n;
        tickN += n;
        if (tickN >= 15) {
            tickN = 0;
            flushCounters();
        }
    }

    // ---------- 心跳 ----------
    // 服务每次 tick 都会写一次「最后一次活着的时刻」（只在内存里，10 秒才落盘一次）。
    // 家长打开诊断页就能看到「上次心跳几秒前」：小于 5 秒说明服务正常，
    // 几十秒甚至几分钟说明这台电视把它冻结或者杀了。这是唯一能直接看见的事实。

    private long lastBeatMem = 0;
    private long lastBeatWrite = 0;

    public void beat() {
        long now = System.currentTimeMillis();
        lastBeatMem = now;
        if (now - lastBeatWrite > 10000) {
            lastBeatWrite = now;
            sp.edit().putLong(S_LAST_BEAT, now).apply();
        }
    }

    /** 上次心跳距今多少秒。0 表示这个服务从未心跳过 */
    public long beatGapSec() {
        long t = lastBeatMem > 0 ? lastBeatMem : sp.getLong(S_LAST_BEAT, 0);
        if (t <= 0) return -1;
        return Math.max(0, (System.currentTimeMillis() - t) / 1000);
    }

    /** 把内存里的计数落盘 */
    public void flushCounters() {
        if (cUsed == null && cSince == null) return;
        SharedPreferences.Editor e = sp.edit();
        if (cUsed != null) e.putInt(S_USED, cUsed);
        if (cSince != null) e.putInt(S_SINCE, cSince);
        e.apply();
    }

    public int getTodayUsedSec() {
        rolloverIfNewDay();
        return cUsed != null ? cUsed : sp.getInt(S_USED, 0);
    }

    public void addUsedSec(int s) {
        rolloverIfNewDay();
        if (cUsed == null) cUsed = sp.getInt(S_USED, 0);
        cUsed += s;
        flushCounters();
    }

    /**
     * 家长临时放行：把今日已用时长往回退一段。
     *
     * 今天的时间已经用完时，光解开锁定没用，下一秒又会因为「已用 >= 上限」重新锁上，
     * 家长看到的就是「输了密码还在锁屏」。这里退一段回去当缓冲，让家长能真的用上电视。
     * 退到最后也不会变成负数。
     */
    public void relieveDailySec(int s) {
        rolloverIfNewDay();
        if (cUsed == null) cUsed = sp.getInt(S_USED, 0);
        cUsed = Math.max(0, cUsed - Math.max(0, s));
        flushCounters();
    }

    public int getSinceLastSec() {
        rolloverIfNewDay();
        return cSince != null ? cSince : sp.getInt(S_SINCE, 0);
    }

    public void setSinceLastSec(int s) {
        cSince = s;
        sp.edit().putInt(S_SINCE, s).apply();
    }

    public void addSinceLastSec(int s) {
        rolloverIfNewDay();
        if (cSince == null) cSince = sp.getInt(S_SINCE, 0);
        cSince += s;
        flushCounters();
    }

    public int getWrongCount() { return sp.getInt(S_WRONG, 0); }
    public void setWrongCount(int v) { sp.edit().putInt(S_WRONG, v).apply(); }

    /** 当前已连续答对的题数。持久化保存，断电重启不会白答 */
    public int getStreakCount() { return sp.getInt(S_STREAK_CNT, 0); }
    public void setStreakCount(int v) { sp.edit().putInt(S_STREAK_CNT, v).apply(); }

    public long getLockUntil() { return sp.getLong(S_LOCK_UNTIL, 0); }
    public void setLockUntil(long v) { sp.edit().putLong(S_LOCK_UNTIL, v).apply(); }
    public boolean isLocked() { return System.currentTimeMillis() < getLockUntil(); }
    public long lockRemainSec() { return Math.max(0, (getLockUntil() - System.currentTimeMillis()) / 1000); }

    public void incQuiz() { sp.edit().putInt(S_TOTAL_QUIZ, sp.getInt(S_TOTAL_QUIZ, 0) + 1).apply(); }
    public void incRight() { sp.edit().putInt(S_TOTAL_RIGHT, sp.getInt(S_TOTAL_RIGHT, 0) + 1).apply(); }
    public int getTotalQuiz() { return sp.getInt(S_TOTAL_QUIZ, 0); }
    public int getTotalRight() { return sp.getInt(S_TOTAL_RIGHT, 0); }

    // ---------- 弹题方式与结果 ----------

    /**
     * 这台电视上全屏答题页能不能拉起来。
     *
     * 默认 true（这台机器还没试过，先按能起来试一次）。
     * 试失败一次就落成 false，之后一律走悬浮窗，不再每次白等一秒半。
     * 家长在设置页点「重新检测弹题方式」会把这条清掉重新试。
     */
    public boolean getFullscreenOk() { return sp.getBoolean(S_FULLSCREEN_OK, true); }
    public void setFullscreenOk(boolean b) { sp.edit().putBoolean(S_FULLSCREEN_OK, b).apply(); }
    public void resetFullscreenOk() { sp.edit().remove(S_FULLSCREEN_OK).apply(); }

    /** 最近一次弹题的一句话结果，给家长和诊断页看 */
    public String getLastPop() { return sp.getString(S_LAST_POP, ""); }
    public void setLastPop(String s) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date());
        sp.edit().putString(S_LAST_POP, stamp + " " + s).apply();
    }

    /** 家长侧：把今天已用时长清零 */
    public void resetToday() {
        sp.edit().putInt(S_USED, 0).putInt(S_SINCE, 0).putInt(S_WRONG, 0)
                .putInt(S_STREAK_CNT, 0).putLong(S_LOCK_UNTIL, 0).apply();
    }

    // ---------- 逐题答题历史 ----------
    // 存成一整条字符串：「id:对次:错次;id:对次:错次」。
    // 题库一千多题，每次出题都要把全库扫一遍问「这题答对过几次」，
    // 所以解析结果必须缓存在内存里，否则每题要解析上千次字符串，电视上会卡住。

    private java.util.LinkedHashMap<String, int[]> histCache;
    private String histCacheRaw;

    private java.util.LinkedHashMap<String, int[]> readHist() {
        String s = sp.getString(H_HIST, "");
        if (histCache != null && s.equals(histCacheRaw)) return histCache;
        java.util.LinkedHashMap<String, int[]> m = new java.util.LinkedHashMap<>();
        histCache = m;
        histCacheRaw = s;
        if (s.isEmpty()) return m;
        for (String part : s.split(";")) {
            if (part.isEmpty()) continue;
            String[] p = part.split(":");
            if (p.length != 3) continue;
            try {
                m.put(p[0], new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private void writeHist(java.util.Map<String, int[]> m) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, int[]> e : m.entrySet()) {
            int[] v = e.getValue();
            if (v[0] == 0 && v[1] == 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(v[0]).append(':').append(v[1]);
        }
        String s = sb.toString();
        if (m instanceof java.util.LinkedHashMap) {
            histCache = (java.util.LinkedHashMap<String, int[]>) m;
            histCacheRaw = s;
        } else {
            histCache = null;
            histCacheRaw = null;
        }
        sp.edit().putString(H_HIST, s).apply();
    }

    /** 全部答题历史（只读快照），报表用来统计最常错的题 */
    public java.util.Map<String, int[]> histSnapshot() {
        return new java.util.LinkedHashMap<>(readHist());
    }

    /** 这道题已经答对过几次 */
    public int getRight(String id) {
        if (id == null || id.isEmpty()) return 0;
        int[] v = readHist().get(id);
        return v == null ? 0 : v[0];
    }

    /** 这道题答错过几次 */
    public int getWrong(String id) {
        if (id == null || id.isEmpty()) return 0;
        int[] v = readHist().get(id);
        return v == null ? 0 : v[1];
    }

    public void markRight(String id) {
        if (id == null || id.isEmpty()) return;
        java.util.LinkedHashMap<String, int[]> m = readHist();
        int[] v = m.get(id);
        if (v == null) v = new int[]{0, 0};
        v[0] = Math.min(v[0] + 1, 99);
        m.put(id, v);
        writeHist(m);
    }

    public void markWrong(String id) {
        if (id == null || id.isEmpty()) return;
        java.util.LinkedHashMap<String, int[]> m = readHist();
        int[] v = m.get(id);
        if (v == null) v = new int[]{0, 0};
        v[1] = Math.min(v[1] + 1, 99);
        m.put(id, v);
        writeHist(m);
    }

    /**
     * 开启新一轮题库：这些题的答对次数清零，题目重新变得可出。
     * 答错次数保留一半，这样下一轮难住过他的题还是会优先出现。
     */
    public void rotateRound(java.util.List<String> ids) {
        java.util.LinkedHashMap<String, int[]> m = readHist();
        for (String id : ids) {
            int[] v = m.get(id);
            if (v == null) continue;
            v[0] = 0;
            v[1] = v[1] / 2;
        }
        writeHist(m);
    }

    /** 家长手动清空全部答题记录（相当于把题库彻底重置） */
    public void clearHist() {
        histCache = null;
        histCacheRaw = null;
        sp.edit().putString(H_HIST, "").putInt(K_BANK_ROUND, 1).apply();
    }

    public int getBankRound() { return sp.getInt(K_BANK_ROUND, 1); }
    public void setBankRound(int v) { sp.edit().putInt(K_BANK_ROUND, Math.max(1, v)).apply(); }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
