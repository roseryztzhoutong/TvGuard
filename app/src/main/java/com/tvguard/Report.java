package com.tvguard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学习报告生成。
 *
 * 同一份数据出两种形态：
 *  - text()：电视上直接看的纯文本，遥控器只需上下滚动；
 *  - html()：手机浏览器打开的精排版，由内置局域网服务提供。
 */
public class Report {

    public static final String AUTHOR = "AI漫剧花大叔";
    public static final String COPYRIGHT = "© 2026 " + AUTHOR + "　保留所有权利";

    private static String subjectName(String s) {
        if ("chinese".equals(s)) return "语文";
        if ("math".equals(s)) return "数学";
        if ("english".equals(s)) return "英语";
        if ("science".equals(s)) return "科学";
        return s;
    }

    public static String rangeTitle(Stats.Agg a) {
        return a.from.equals(a.to) ? a.from : (a.from + " 至 " + a.to);
    }

    /** 找出这段时间里错得最多的几道题 */
    private static List<String[]> topWrong(Prefs prefs, QuestionBank bank, int n) {
        List<String[]> out = new ArrayList<>();
        if (prefs == null || bank == null) return out;
        List<Map.Entry<String, int[]>> list = new ArrayList<>(prefs.histSnapshot().entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, int[]>>() {
            @Override
            public int compare(Map.Entry<String, int[]> x, Map.Entry<String, int[]> y) {
                return y.getValue()[1] - x.getValue()[1];
            }
        });
        for (Map.Entry<String, int[]> e : list) {
            if (out.size() >= n) break;
            if (e.getValue()[1] <= 0) break;
            QuestionBank.Q q = bank.findById(e.getKey());
            if (q == null) continue;
            String right = (q.answer >= 0 && q.answer < q.options.length) ? q.options[q.answer] : "";
            out.add(new String[]{
                    q.stem.length() > 60 ? q.stem.substring(0, 60) + "…" : q.stem,
                    right,
                    String.valueOf(e.getValue()[1])
            });
        }
        return out;
    }

    /** 根据数据给建议，家长看得懂的那种 */
    private static List<String> advice(Stats.Agg a, Prefs prefs) {
        List<String> tips = new ArrayList<>();
        if (a.quiz >= 10) {
            if (a.rate() < 60) {
                tips.add("正确率 " + a.rate() + "%，题目对这个孩子偏难。建议把「连续答对」从 "
                        + prefs.getStreakRequired() + " 题降到 2 题，先让他有成就感。");
            } else if (a.rate() > 90) {
                tips.add("正确率 " + a.rate() + "%，题目偏容易，孩子可能已经开始背答案。建议把弹题间隔从 "
                        + prefs.getIntervalMin() + " 分钟缩到 " + Math.max(1, prefs.getIntervalMin() - 3) + " 分钟。");
            } else {
                tips.add("正确率 " + a.rate() + "%，难度卡得正好，保持现在的设置就行。");
            }
        }
        String weak = a.weakSubject();
        if (weak != null) {
            tips.add(subjectName(weak) + "是这阶段最弱的科目（" + a.subjectRate(weak) + "%），可以只开这一科练几天。");
        }
        if (a.activeDays >= 3 && prefs.getDailyLimitMin() > 0 && a.avgMin() >= prefs.getDailyLimitMin() * 8 / 10) {
            tips.add("日均 " + a.avgMin() + " 分钟，已经接近上限 " + prefs.getDailyLimitMin()
                    + " 分钟，说明每天都看到顶了。可以考虑把上限收到 " + Math.max(20, prefs.getDailyLimitMin() - 15) + " 分钟。");
        }
        if (a.bestDay != null && prefs.getDailyLimitMin() > 0 && a.bestDayMin > prefs.getDailyLimitMin() + 10) {
            tips.add(a.bestDay + " 单日看了 " + a.bestDayMin + " 分钟，超过上限不少，留意是不是被临时加时了。");
        }
        if (a.activeDays == 0) {
            tips.add("这段时间没有观看记录，可能电视没开，或者守护服务被系统停掉了。");
        }
        return tips;
    }

    // ==================== 纯文本（电视端） ====================

    public static String text(Prefs prefs, Stats stats, QuestionBank bank, Stats.Agg a, String kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("答题锁 · ").append(kind).append("\n");
        sb.append(rangeTitle(a)).append("\n");
        sb.append("────────────────\n\n");

        sb.append("观看时间\n");
        sb.append("  合计 ").append(a.watchMin()).append(" 分钟，有效观看 ").append(a.activeDays).append(" 天\n");
        if (a.activeDays > 0) {
            sb.append("  日均 ").append(a.avgMin()).append(" 分钟");
            if (a.bestDay != null) sb.append("，最长 ").append(a.bestDay).append(" 看了 ").append(a.bestDayMin).append(" 分钟");
            sb.append("\n");
        }
        sb.append("  今日上限 ").append(prefs.getDailyLimitMin()).append(" 分钟\n\n");

        sb.append("答题情况\n");
        if (a.quiz == 0) {
            sb.append("  这段时间没有答题记录\n\n");
        } else {
            sb.append("  共答 ").append(a.quiz).append(" 题，答对 ").append(a.right)
                    .append(" 题，答错 ").append(a.wrong()).append(" 题，正确率 ").append(a.rate()).append("%\n\n");

            sb.append("各科表现\n");
            appendSubjectText(sb, "语文", a.cQuiz, a.cRight);
            appendSubjectText(sb, "数学", a.mQuiz, a.mRight);
            appendSubjectText(sb, "英语", a.eQuiz, a.eRight);
            appendSubjectText(sb, "科学", a.sQuiz, a.sRight);
            sb.append("\n");
        }

        List<String[]> wrong = topWrong(prefs, bank, 5);
        if (!wrong.isEmpty()) {
            sb.append("最常错的题\n");
            for (int i = 0; i < wrong.size(); i++) {
                String[] w = wrong.get(i);
                sb.append("  ").append(i + 1).append(". ").append(w[0]).append("\n");
                sb.append("     正确答案：").append(w[1]).append("（错 ").append(w[2]).append(" 次）\n");
            }
            sb.append("\n");
        }

        List<String> tips = advice(a, prefs);
        if (!tips.isEmpty()) {
            sb.append("建议\n");
            for (String t : tips) sb.append("  · ").append(t).append("\n");
            sb.append("\n");
        }

        sb.append("────────────────\n");
        sb.append(COPYRIGHT);
        return sb.toString();
    }

    private static void appendSubjectText(StringBuilder sb, String name, int q, int r) {
        if (q == 0) {
            sb.append("  ").append(name).append("：这段时间没出题\n");
            return;
        }
        int rate = r * 100 / q;
        String level = rate >= 85 ? "扎实" : rate >= 70 ? "正常" : rate >= 55 ? "偏弱" : "需要补";
        sb.append("  ").append(name).append("：").append(q).append(" 题，答对 ").append(r)
                .append("，正确率 ").append(rate).append("%（").append(level).append("）\n");
    }

    // ==================== HTML（手机端） ====================

    private static final String[] SUB_KEYS = {"chinese", "math", "english", "science"};

    public static String html(android.content.Context ctx, Prefs prefs, Stats stats,
                              QuestionBank bank, Stats.Agg a, String kind) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>答题锁 ").append(kind).append("</title><style>")
                .append(":root{--bg:#0B1220;--card:#151E31;--line:#22304A;--gold:#FACC15;--txt:#E8EEF7;--dim:#94A3B8}")
                .append("*{box-sizing:border-box;margin:0;padding:0}")
                .append("body{background:var(--bg);color:var(--txt);font:15px/1.7 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif;padding:18px 14px 40px}")
                .append(".wrap{max-width:520px;margin:0 auto}")
                .append(".tag{display:inline-block;background:rgba(250,204,21,.14);color:var(--gold);font-size:12px;padding:4px 10px;border-radius:99px;letter-spacing:.06em}")
                .append("h1{font-size:22px;margin:10px 0 4px;letter-spacing:.02em}")
                .append(".range{color:var(--dim);font-size:13px;margin-bottom:18px}")
                .append(".kpi{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:16px}")
                .append(".kpi div{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}")
                .append(".kpi b{display:block;font-size:26px;color:var(--gold);font-weight:700;line-height:1.2}")
                .append(".kpi span{font-size:12px;color:var(--dim)}")
                .append("h2{font-size:15px;margin:22px 0 10px;color:var(--gold);letter-spacing:.06em}")
                .append(".card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}")
                .append(".row{display:flex;justify-content:space-between;padding:7px 0;border-bottom:1px dashed rgba(148,163,184,.18);font-size:14px}")
                .append(".row:last-child{border:0}")
                .append(".bar{height:8px;background:#1E2A42;border-radius:99px;overflow:hidden;margin-top:6px}")
                .append(".bar i{display:block;height:100%;background:linear-gradient(90deg,#FACC15,#F59E0B)}")
                .append(".sub{margin-bottom:12px}")
                .append(".sub .t{display:flex;justify-content:space-between;font-size:14px}")
                .append(".muted{color:var(--dim);font-size:13px}")
                .append(".q{font-size:14px;padding:10px 0;border-bottom:1px dashed rgba(148,163,184,.18)}")
                .append(".q:last-child{border:0}")
                .append(".q b{color:#4ADE80;font-weight:600}")
                .append("ul{padding-left:18px}li{font-size:14px;margin:6px 0;color:#CBD5E1}")
                .append("footer{margin-top:26px;padding-top:14px;border-top:1px solid var(--line);color:#64748B;font-size:12px;text-align:center;line-height:1.8}")
                .append("</style></head><body><div class=\"wrap\">");

        b.append("<span class=\"tag\">答题锁 · ").append(kind).append("</span>");
        b.append("<h1>").append(gradeTitle(prefs)).append(" 学习报告</h1>");
        b.append("<div class=\"range\">").append(rangeTitle(a)).append(" · 共 ").append(a.days).append(" 天</div>");

        b.append("<div class=\"kpi\">");
        kpi(b, String.valueOf(a.watchMin()), "观看分钟");
        kpi(b, String.valueOf(a.quiz), "答题次数");
        kpi(b, a.quiz == 0 ? "-" : a.rate() + "%", "正确率");
        kpi(b, String.valueOf(a.activeDays), "有效天数");
        b.append("</div>");

        b.append("<h2>观看时间</h2><div class=\"card\">");
        row(b, "日均", a.avgMin() + " 分钟");
        row(b, "最长一天", a.bestDay == null ? "-" : a.bestDay + "　" + a.bestDayMin + " 分钟");
        row(b, "每日上限", prefs.getDailyLimitMin() + " 分钟");
        b.append("</div>");

        if (a.quiz > 0) {
            b.append("<h2>各科表现</h2><div class=\"card\">");
            for (String s : SUB_KEYS) {
                int q, r;
                if ("chinese".equals(s)) { q = a.cQuiz; r = a.cRight; }
                else if ("math".equals(s)) { q = a.mQuiz; r = a.mRight; }
                else if ("english".equals(s)) { q = a.eQuiz; r = a.eRight; }
                else { q = a.sQuiz; r = a.sRight; }
                b.append("<div class=\"sub\"><div class=\"t\"><span>").append(subjectName(s)).append("</span>");
                if (q == 0) {
                    b.append("<span class=\"muted\">未出题</span></div>");
                } else {
                    int rate = r * 100 / q;
                    b.append("<span>").append(q).append(" 题 · 对 ").append(r).append(" · ").append(rate).append("%</span></div>");
                    b.append("<div class=\"bar\"><i style=\"width:").append(rate).append("%\"></i></div>");
                }
                b.append("</div>");
            }
            row(b, "答对 / 答错", a.right + " / " + a.wrong());
            b.append("</div>");
        }

        // 每日分布
        if (a.daily.size() > 1) {
            int max = 1;
            for (Stats.Day d : a.daily) max = Math.max(max, d.watchSec / 60);
            b.append("<h2>每天看了多久</h2><div class=\"card\">");
            for (Stats.Day d : a.daily) {
                int m = d.watchSec / 60;
                b.append("<div class=\"sub\"><div class=\"t\"><span>").append(d.date.substring(5))
                        .append("</span><span class=\"muted\">").append(m).append(" 分钟</span></div>")
                        .append("<div class=\"bar\"><i style=\"width:").append(m * 100 / max).append("%\"></i></div></div>");
            }
            b.append("</div>");
        }

        List<String[]> wrong = topWrong(prefs, bank, 6);
        if (!wrong.isEmpty()) {
            b.append("<h2>最常错的题</h2><div class=\"card\">");
            for (String[] w : wrong) {
                b.append("<div class=\"q\">").append(esc(w[0]))
                        .append("<br><b>正确答案：").append(esc(w[1]))
                        .append("</b> <span class=\"muted\">错 ").append(w[2]).append(" 次</span></div>");
            }
            b.append("</div>");
        }

        List<String> tips = advice(a, prefs);
        if (!tips.isEmpty()) {
            b.append("<h2>建议</h2><div class=\"card\"><ul>");
            for (String t : tips) b.append("<li>").append(esc(t)).append("</li>");
            b.append("</ul></div>");
        }

        b.append("<footer>答题锁 ").append(ver(ctx)).append(" · 著作权归 ").append(AUTHOR).append(" 所有<br>")
                .append(COPYRIGHT).append("<br>本页由电视在局域网内直接提供，数据不出家庭网络</footer>");
        b.append("</div></body></html>");
        return b.toString();
    }

    private static void kpi(StringBuilder b, String num, String label) {
        b.append("<div><b>").append(num).append("</b><span>").append(label).append("</span></div>");
    }

    private static void row(StringBuilder b, String k, String v) {
        b.append("<div class=\"row\"><span class=\"muted\">").append(esc(k)).append("</span><span>")
                .append(esc(v)).append("</span></div>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String gradeTitle(Prefs p) {
        int g = p.getGrade();
        String[] cn = {"", "一年级", "二年级", "三年级", "四年级", "五年级", "六年级"};
        if (g < 1 || g > 6) return "孩子";
        String term = p.getTerm();
        String t = "a".equals(term) ? "上册" : "b".equals(term) ? "下册" : "全学年";
        return cn[g] + "（" + t + "）";
    }

    /**
     * 版本号兜底值。
     *
     * 这里曾经硬编码成 "2.4"，然后连着五个版本忘了改，家长在报告页脚看到的
     * 永远是 2.4，于是以为「怎么装都是老版本」。这个教训值一条注释：
     * 版本号只允许从安装包本身读，不许在代码里写死第二份。
     */
    public static String Build_VER = "3.0";

    /** 从安装包读真实版本，格式 v2.8 (13)。读不到才退回上面的兜底值 */
    public static String ver(android.content.Context c) {
        if (c == null) return "v" + Build_VER;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            String name = pi.versionName == null ? Build_VER : pi.versionName;
            int code = android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
            return "v" + name + " (" + code + ")";
        } catch (Throwable t) {
            return "v" + Build_VER;
        }
    }

    /** 文件名用时间戳，导出多次不覆盖 */
    public static String fileName(String kind) {
        return "答题锁_" + kind + "_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Calendar.getInstance().getTime()) + ".html";
    }
}
