package com.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * 年级选择向导。
 *
 * 首次启动必走一遍，之后从设置页进来可随时改。
 * 只做一件事：确定「几年级 + 哪一学期」，App 据此加载对应题库。
 */
public class SetupActivity extends Activity {

    private static final int[] GRADE_BTN = {R.id.g1, R.id.g2, R.id.g3, R.id.g4, R.id.g5, R.id.g6};
    private static final int[] TERM_BTN = {R.id.termA, R.id.termB, R.id.termAB};
    private static final String[] TERMS = {"a", "b", "ab"};

    private Prefs prefs;
    private int grade = 3;
    private String term = "a";
    private boolean firstRun = true;

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        firstRun = !prefs.hasGrade();
        grade = firstRun ? guessGrade() : prefs.getGrade();
        term = prefs.getTerm();

        setContentView(R.layout.activity_setup);

        for (int i = 0; i < GRADE_BTN.length; i++) {
            final int g = i + 1;
            findViewById(GRADE_BTN[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    grade = g;
                    render();
                }
            });
        }
        for (int i = 0; i < TERM_BTN.length; i++) {
            final String t = TERMS[i];
            findViewById(TERM_BTN[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    term = t;
                    render();
                }
            });
        }

        findViewById(R.id.btnDone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setGrade(grade);
                prefs.setTerm(term);
                // 一年级两年级没有英语教材，别让英语开关空转
                if (grade <= 2) {
                    String s = prefs.getSubjects();
                    if (s.contains("english")) {
                        String ns = s.replace(",english", "").replace("english,", "").replace("english", "");
                        if (!ns.isEmpty()) prefs.setSubjects(ns);
                    }
                }
                if (firstRun) {
                    // 刚设完密码选完年级，别再拦一道密码门
                    MainActivity.skipGateOnce = true;
                    startActivity(new Intent(SetupActivity.this, MainActivity.class));
                }
                finish();
            }
        });

        render();
        findViewById(R.id.g5).post(new Runnable() {
            @Override
            public void run() {
                findViewById(GRADE_BTN[grade - 1]).requestFocus();
            }
        });
    }

    /** 默认落在三年级，比瞎猜年份靠谱，家长点一下就行 */
    private int guessGrade() {
        return 3;
    }

    private void render() {
        for (int i = 0; i < GRADE_BTN.length; i++) {
            Button btn = findViewById(GRADE_BTN[i]);
            boolean on = (i + 1) == grade;
            btn.setBackgroundResource(on ? R.drawable.btn_selected : R.drawable.btn_option);
            btn.setTextColor(getResources().getColor(on ? R.color.gold_text : R.color.normal_text));
        }
        for (int i = 0; i < TERM_BTN.length; i++) {
            Button btn = findViewById(TERM_BTN[i]);
            boolean on = TERMS[i].equals(term);
            btn.setBackgroundResource(on ? R.drawable.btn_selected : R.drawable.btn_option);
            btn.setTextColor(getResources().getColor(on ? R.color.gold_text : R.color.normal_text));
        }

        TextView tv = findViewById(R.id.tvCount);
        int count = BankInfo.count(this, grade, term);
        if (count > 0) {
            String termText = "a".equals(term) ? "上册" : "b".equals(term) ? "下册" : "上下册";
            tv.setText("将加载 " + gradeName(grade) + termText + " 题库，共 " + count + " 道题");
        } else {
            tv.setText("这个年级的题库正在准备中");
        }
    }

    static String gradeName(int g) {
        String[] cn = {"", "一", "二", "三", "四", "五", "六"};
        return (g >= 1 && g <= 6 ? cn[g] : "") + "年级";
    }
}
