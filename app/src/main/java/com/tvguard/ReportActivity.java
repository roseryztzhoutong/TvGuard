package com.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 电视上的报告页。
 *
 * 三种交付方式，覆盖不同家庭条件：
 *  1. 电视上直接看（纯文本，遥控器滚动）；
 *  2. 手机浏览器看（电视开个局域网网页，扫码都不用，输个地址即可）；
 *  3. 导出成 HTML 文件，用 U 盘拷走或者发微信。
 */
public class ReportActivity extends Activity {

    private Prefs prefs;
    private Stats stats;
    private QuestionBank bank;
    private boolean monthMode = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        stats = new Stats(this);
        setContentView(R.layout.activity_report);

        findViewById(R.id.btnWeek).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                monthMode = false;
                render();
            }
        });
        findViewById(R.id.btnMonth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                monthMode = true;
                render();
            }
        });
        findViewById(R.id.btnPhone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPhoneView();
            }
        });
        findViewById(R.id.btnExport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });
        findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        render();
        findViewById(R.id.btnWeek).requestFocus();
    }

    private Stats.Agg current() {
        return monthMode ? stats.thisMonth() : stats.thisWeek();
    }

    private String kind() {
        return monthMode ? "本月报告" : "本周报告";
    }

    private void render() {
        final TextView tv = findViewById(R.id.tvBody);
        tv.setText("正在统计…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (bank == null) {
                    QuestionBank qb = new QuestionBank();
                    int g = prefs.getGrade();
                    qb.load(ReportActivity.this.getApplicationContext(), g < 1 ? 1 : g, prefs.getTerm());
                    bank = qb;
                }
                final String text = Report.text(prefs, stats, bank, current(), kind());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        TextView t = findViewById(R.id.tvBody);
                        if (t != null) t.setText(text);
                    }
                });
            }
        }, "report-render").start();
    }

    private void startPhoneView() {
        final TextView tvUrl = findViewById(R.id.tvUrl);
        tvUrl.setText("正在启动局域网服务…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int port = ReportHub.startServer(getApplicationContext());
                final String ip = LanReportServer.localIp();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        TextView t = findViewById(R.id.tvUrl);
                        if (port <= 0) {
                            t.setText("服务启动失败，端口被占用。可以改用「导出」把文件拷到手机。");
                        } else if (ip == null) {
                            t.setText("电视没连上 WiFi，手机看不到。请先把电视连到家里的 WiFi。");
                        } else {
                            t.setText("手机连同一个 WiFi，浏览器打开 " + ip + ":" + port);
                            toast("手机浏览器输入 " + ip + ":" + port);
                        }
                    }
                });
            }
        }, "lan-start").start();
    }

    private void export() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            final File f = new File(dir, Report.fileName(monthMode ? "月报" : "周报"));
            String html = Report.html(this, prefs, stats, bank, current(), kind());
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(html);
            w.close();
            fos.close();
            toast("已导出：" + f.getAbsolutePath());
            TextView tvUrl = findViewById(R.id.tvUrl);
            tvUrl.setText("已导出到 " + f.getAbsolutePath());
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 服务可以继续开，家长手机刷新还能看；退出 App 时由系统回收
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
