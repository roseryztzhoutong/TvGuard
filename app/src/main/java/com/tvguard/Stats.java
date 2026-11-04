package com.tvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按天记录使用与答题数据，供周报月报统计。
 *
 * 存储格式刻意做成一行一天的紧凑文本，一天一条：
 *   2026-11-04,12,9,3,540,c:8:6;m:10:7;e:4:3
 * 字段依次是：日期、答题数、答对数、答错数、观看秒数、分科目(题数:答对数)。
 * 只在内存里维护，写盘时整条覆盖，一天最多几百字节，不用担心体积。
 */
public class Stats {

    private static final String FILE = "tvguard_stats";
    private static final String KEY = "daily";
    private static final int KEEP_DAYS = 200;

    public static class Day {
        public String date;
        public int quiz, right, wrong, watchSec;
        public int cQuiz, cRight, mQuiz, mRight, eQuiz, eRight, sQuiz, sRight;

        int subjectQuiz(String s) {
            if ("chinese".equals(s)) return cQuiz;
            if ("math".equals(s)) return mQuiz;
            if ("english".equals(s)) return eQuiz;
            if ("science".equals(s)) return sQuiz;
            return 0;
        }

        int subjectRight(String s) {
            if ("chinese".equals(s)) return cRight;
            if ("math".equals(s)) return mRight;
            if ("english".equals(s)) return eRight;
            if ("science".equals(s)) return sRight;
            return 0;
        }

        void addSubject(String s, boolean ok) {
            if ("chinese".equals(s)) { cQuiz++; if (ok) cRight++; }
            else if ("math".equals(s)) { mQuiz++; if (ok) mRight++; }
            else if ("english".equals(s)) { eQuiz++; if (ok) eRight++; }
            else if ("science".equals(s)) { sQuiz++; if (ok) sRight++; }
        }
    }

    /** 一段时间内的汇总 */
    public static class Agg {
        public String from, to;
        public int days, activeDays;
        public int quiz, right, watchSec;
        public int cQuiz, cRight, mQuiz, mRight, eQuiz, eRight, sQuiz, sRight;
        public String bestDay;
        public int bestDayMin;
        public List<Day> daily = new ArrayList<>();

        public int wrong() { return Math.max(0, quiz - right); }
        public int rate() { return quiz == 0 ? 0 : right * 100 / quiz; }
        public int watchMin() { return watchSec / 60; }
        public int avgMin() { return activeDays == 0 ? 0 : watchMin() / activeDays; }
        public int avgQuiz() { return activeDays == 0 ? 0 : Math.round(quiz * 1f / activeDays); }

        public String bestSubject() {
            String best = null;
            int br = -1;
            if (cQuiz >= 3 && cRight * 100 / cQuiz > br) { br = cRight * 100 / cQuiz; best = "chinese"; }
            if (mQuiz >= 3 && mRight * 100 / mQuiz > br) { br = mRight * 100 / mQuiz; best = "math"; }
            if (eQuiz >= 3 && eRight * 100 / eQuiz > br) { br = eRight * 100 / eQuiz; best = "english"; }
            if (sQuiz >= 3 && sRight * 100 / sQuiz > br) { br = sRight * 100 / sQuiz; best = "science"; }
            return best;
        }

        public String weakSubject() {
            String weak = null;
            int wr = 101;
            if (cQuiz >= 3 && cRight * 100 / cQuiz < wr) { wr = cRight * 100 / cQuiz; weak = "chinese"; }
            if (mQuiz >= 3 && mRight * 100 / mQuiz < wr) { wr = mRight * 100 / mQuiz; weak = "math"; }
            if (eQuiz >= 3 && eRight * 100 / eQuiz < wr) { wr = eRight * 100 / eQuiz; weak = "english"; }
            if (sQuiz >= 3 && sRight * 100 / sQuiz < wr) { wr = sRight * 100 / sQuiz; weak = "science"; }
            return weak;
        }

        public int subjectRate(String s) {
            int q, r;
            if ("chinese".equals(s)) { q = cQuiz; r = cRight; }
            else if ("math".equals(s)) { q = mQuiz; r = mRight; }
            else if ("english".equals(s)) { q = eQuiz; r = eRight; }
            else if ("science".equals(s)) { q = sQuiz; r = sRight; }
            else return -1;
            return q == 0 ? -1 : r * 100 / q;
        }
    }

    private final SharedPreferences sp;
    private LinkedHashMap<String, Day> cache;
    private String cacheRaw;

    public Stats(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private LinkedHashMap<String, Day> all() {
        String raw = sp.getString(KEY, "");
        if (cache != null && raw.equals(cacheRaw)) return cache;

        LinkedHashMap<String, Day> m = new LinkedHashMap<>();
        cache = m;
        cacheRaw = raw;
        if (raw.isEmpty()) return m;

        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split(",", -1);
            if (p.length < 5) continue;
            Day d = new Day();
            d.date = p[0];
            try {
                d.quiz = Integer.parseInt(p[1]);
                d.right = Integer.parseInt(p[2]);
                d.wrong = Integer.parseInt(p[3]);
                d.watchSec = Integer.parseInt(p[4]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (p.length >= 6 && !p[5].isEmpty()) {
                for (String part : p[5].split(";")) {
                    String[] t = part.split(":");
                    if (t.length != 3) continue;
                    try {
                        int q = Integer.parseInt(t[1]);
                        int r = Integer.parseInt(t[2]);
                        if ("c".equals(t[0])) { d.cQuiz = q; d.cRight = r; }
                        else if ("m".equals(t[0])) { d.mQuiz = q; d.mRight = r; }
                        else if ("e".equals(t[0])) { d.eQuiz = q; d.eRight = r; }
                        else if ("s".equals(t[0])) { d.sQuiz = q; d.sRight = r; }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            m.put(d.date, d);
        }
        return m;
    }

    private void save(LinkedHashMap<String, Day> m) {
        prune(m);
        StringBuilder sb = new StringBuilder();
        for (Day d : m.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(d.date).append(',').append(d.quiz).append(',').append(d.right).append(',')
                    .append(d.wrong).append(',').append(d.watchSec).append(',')
                    .append("c:").append(d.cQuiz).append(':').append(d.cRight).append(';')
                    .append("m:").append(d.mQuiz).append(':').append(d.mRight).append(';')
                    .append("e:").append(d.eQuiz).append(':').append(d.eRight).append(';')
                    .append("s:").append(d.sQuiz).append(':').append(d.sRight);
        }
        String raw = sb.toString();
        cache = m;
        cacheRaw = raw;
        sp.edit().putString(KEY, raw).apply();
    }

    /** 只留最近 200 天，别让文件无限长 */
    private void prune(LinkedHashMap<String, Day> m) {
        if (m.size() <= KEEP_DAYS) return;
        List<String> keys = new ArrayList<>(m.keySet());
        for (int i = 0; i < keys.size() - KEEP_DAYS; i++) m.remove(keys.get(i));
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private Day today(LinkedHashMap<String, Day> m) {
        String t = today();
        Day d = m.get(t);
        if (d == null) {
            d = new Day();
            d.date = t;
            m.put(t, d);
        }
        return d;
    }

    /** 答了一道题。科目取 chinese/math/english/science */
    public void recordQuiz(String subject, boolean correct) {
        LinkedHashMap<String, Day> m = all();
        Day d = today(m);
        d.quiz++;
        if (correct) d.right++;
        else d.wrong++;
        d.addSubject(subject, correct);
        save(m);
    }

    /** 累加观看秒数，服务每秒调一次，攒够一分钟再落盘一次由调用方控制 */
    public void addWatch(int sec) {
        if (sec <= 0) return;
        LinkedHashMap<String, Day> m = all();
        Day d = today(m);
        d.watchSec += sec;
        save(m);
    }

    public Day todayStat() {
        Day d = all().get(today());
        if (d == null) {
            d = new Day();
            d.date = today();
        }
        return d;
    }

    /** 汇总 [from, to] 两个 yyyy-MM-dd 之间的数据 */
    public Agg range(String from, String to) {
        Agg a = new Agg();
        a.from = from;
        a.to = to;
        for (Map.Entry<String, Day> e : all().entrySet()) {
            String k = e.getKey();
            if (k.compareTo(from) < 0 || k.compareTo(to) > 0) continue;
            Day d = e.getValue();
            a.days++;
            if (d.watchSec > 0 || d.quiz > 0) a.activeDays++;
            a.quiz += d.quiz;
            a.right += d.right;
            a.watchSec += d.watchSec;
            a.cQuiz += d.cQuiz; a.cRight += d.cRight;
            a.mQuiz += d.mQuiz; a.mRight += d.mRight;
            a.eQuiz += d.eQuiz; a.eRight += d.eRight;
            a.sQuiz += d.sQuiz; a.sRight += d.sRight;
            a.daily.add(d);
            int mins = d.watchSec / 60;
            if (mins > a.bestDayMin) { a.bestDayMin = mins; a.bestDay = k; }
        }
        return a;
    }

    /** 本周（周一到今天） */
    public Agg thisWeek() {
        Calendar c = Calendar.getInstance();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int back = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        c.add(Calendar.DAY_OF_MONTH, -back);
        return range(fmt(c), today());
    }

    /** 上周（完整一周） */
    public Agg lastWeek() {
        Calendar c = Calendar.getInstance();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int back = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        c.add(Calendar.DAY_OF_MONTH, -back - 7);
        String from = fmt(c);
        c.add(Calendar.DAY_OF_MONTH, 6);
        return range(from, fmt(c));
    }

    /** 最近 7 天（滚动，不含今天之前的不满） */
    public Agg lastDays(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -(n - 1));
        return range(fmt(c), today());
    }

    /** 本月 1 号到今天 */
    public Agg thisMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        return range(fmt(c), today());
    }

    /** 上月整月 */
    public Agg lastMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, -1);
        String from = fmt(c);
        Calendar c2 = (Calendar) c.clone();
        c2.add(Calendar.MONTH, 1);
        c2.add(Calendar.DAY_OF_MONTH, -1);
        return range(from, fmt(c2));
    }

    public static String fmt(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime());
    }

    public void clearAll() {
        cache = null;
        cacheRaw = null;
        sp.edit().putString(KEY, "").apply();
    }
}
