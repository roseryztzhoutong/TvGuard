package com.tvguard;

import android.content.Context;

/**
 * 报告的公共入口。
 *
 * 手机网页服务和电视端界面都从这里拿数据，保证两边看到的是同一份内容。
 * 服务在独立线程里被访问，所以这里对报告生成做了同步。
 */
public class ReportHub {

    private static final LanReportServer SERVER = new LanReportServer();
    private static QuestionBank reportBank;

    public static synchronized LanReportServer server() { return SERVER; }

    public static Stats stats(Context c) {
        return new Stats(c);
    }

    /** 手机访问时用本周数据，家长想看月报可以在电视上切换后导出 */
    public static String html(Context c) {
        Prefs p = new Prefs(c);
        Stats s = new Stats(c);
        QuestionBank bank = bank(c);
        Stats.Agg a = s.thisWeek();
        return Report.html(c, p, s, bank, a, "本周报告");
    }

    public static synchronized QuestionBank bank(Context c) {
        if (reportBank == null) {
            reportBank = new QuestionBank();
        }
        Prefs p = new Prefs(c);
        int g = p.getGrade();
        String t = p.getTerm();
        if (reportBank.size() == 0 || reportBank.getLoadedGrade() != g
                || !t.equals(reportBank.getLoadedTerm())) {
            reportBank.load(c.getApplicationContext(), g < 1 ? 1 : g, t);
        }
        return reportBank;
    }

    /** 启动局域网服务，返回可访问端口，-1 表示失败 */
    public static int startServer(final Context c) {
        return SERVER.start(new LanReportServer.Provider() {
            @Override
            public String html() {
                try {
                    return ReportHub.html(c);
                } catch (Throwable t) {
                    return "<h1>报告生成失败</h1><p>" + t.getMessage() + "</p>";
                }
            }
        });
    }

    public static void stopServer() {
        SERVER.stop();
    }
}
