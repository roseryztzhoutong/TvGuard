package com.tvguard;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * 答题逻辑的唯一实现。
 *
 * 为什么单独抽一个类：答题界面有两种承载方式。
 * 一、悬浮窗（GuardService），能盖在视频上面，答完无缝接着看，但需要系统给悬浮窗权限。
 * 二、全屏页面（QuizActivity），不需要任何特殊权限，一定能弹出来，代价是视频会被打断。
 *
 * 两条路必须用同一套判题、计分、连对、连错锁定的代码，否则改一处漏一处，
 * 两个入口的行为会慢慢走偏。
 */
public class QuizController {

    /** 宿主环境：浮窗和 Activity 各自提供自己的线程调度方式 */
    public interface Host {
        /** 连续答对达标，可以放行 */
        void onPassed();

        /** 连错达到上限，已经写入锁定时间，宿主该收摊了 */
        void onLocked();

        /** 延迟执行 */
        void post(Runnable r, long delayMs);
    }

    private final Prefs prefs;
    private final QuestionBank bank;
    private final Stats stats;
    private final Host host;

    private TextView tvStem;
    private TextView tvMeta;
    private TextView tvFeedback;
    private Button btnNext;
    private final Button[] btns = new Button[4];

    /** 选项的原始文字颜色（带按下态的选择器），答错染色之后要能还原 */
    private ColorStateList optionColors;

    private QuestionBank.Q current;
    private boolean answering;

    /**
     * 本组还要出哪些科目，按顺序取。
     *
     * <p>「连对 3 题才放行」这个规则早就有，但题是混着抽的，三道全是数学完全合法，
     * 家长看到的观感就是「只会出数学题」。这里改成一组固定把语文、数学、英语
     * 各排一道，顺序每次洗牌，孩子不知道下一道是什么，但一定三科都碰到。
     *
     * <p>一二年级没有英语，{@link #refillQueue()} 会按题库实际有哪些科来排，
     * 不会强行塞一道英语出来。
     */
    private final java.util.List<String> queue = new java.util.ArrayList<>();

    /** 本轮排好的科目顺序，只用于界面提示「本组 语文/数学/英语/科学」 */
    private final java.util.List<String> groupOrder = new java.util.ArrayList<>();

    /**
     * 本组一共出几道题。
     *
     * <p>以前固定是家长设的「连对 3 题」，但科目一多就出问题：开了四科，
     * 排了四道，第三道就放行了，科学永远轮不到。现在取
     * 「启用科目数」和「连对题数」中的较大值，开四科就是四道、四科各碰一次，
     * 家长把连对调到 6 就排两轮。关掉科学自动回到三道。
     */
    private int groupNeed = 0;

    /**
     * 现在停在「答错了，等孩子按我知道了」这一屏。
     *
     * 以前答错是 1.6 秒后自动换题，家长反馈根本看不清错在哪、正确答案是什么。
     * 现在改成必须手动确认，孩子不按就一直停在这里。
     */
    private boolean waitingNext = false;

    public QuizController(Prefs prefs, QuestionBank bank, Stats stats, Host host) {
        this.prefs = prefs;
        this.bank = bank;
        this.stats = stats;
        this.host = host;
    }

    /** 绑到一棵已经 inflate 好的视图上，绑完立刻出第一道题 */
    public void bind(View root) {
        tvStem = root.findViewById(R.id.tvStem);
        tvMeta = root.findViewById(R.id.tvMeta);
        tvFeedback = root.findViewById(R.id.tvFeedback);
        btnNext = root.findViewById(R.id.btnNext);
        btns[0] = root.findViewById(R.id.opt0);
        btns[1] = root.findViewById(R.id.opt1);
        btns[2] = root.findViewById(R.id.opt2);
        btns[3] = root.findViewById(R.id.opt3);

        if (btns[0] != null) optionColors = btns[0].getTextColors();

        // 按钮自己也要拦返回键，否则焦点在按钮上时按键不会冒泡到根布局
        for (Button b : btns) {
            if (b == null) continue;
            b.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
                }
            });
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmNext();
                }
            });
            btnNext.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
                }
            });
            btnNext.setVisibility(View.GONE);
        }
        newQuestion();
    }

    public QuestionBank.Q current() {
        return current;
    }

    /** 是不是停在「答错了，等你点下一题」这一屏。无障碍转接要认这个状态 */
    public boolean isWaitingNext() {
        return waitingNext;
    }

    /** 从无障碍 / 遥控器确认键直接进下一题 */
    public void confirmNext() {
        if (!waitingNext) return;
        waitingNext = false;
        newQuestion();
    }

    public void newQuestion() {
        answering = false;
        waitingNext = false;
        if (tvFeedback != null) {
            tvFeedback.setText("");
            tvFeedback.setTextColor(Color.parseColor("#F87171"));
            tvFeedback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        }
        if (btnNext != null) btnNext.setVisibility(View.GONE);

        // 上一题答错时把选项染过色、锁过焦点，这里必须全部还原，
        // 否则下一题一进来就是红的绿的，遥控器也选不动
        for (Button b : btns) {
            if (b == null) continue;
            if (optionColors != null) b.setTextColor(optionColors);
            b.setFocusable(true);
        }

        // 连对进度归零就是新的一组，重排科目；还有进度说明在同一组里，接着往下走。
        if (prefs.getStreakCount() <= 0) queue.clear();
        if (queue.isEmpty()) refillQueue();

        String want = queue.isEmpty() ? "" : queue.remove(0);
        current = bank == null ? null : bank.pickSubject(want, prefs);
        if (current == null && bank != null) {
            // 这一科在这个年级根本没有题（比如二年级没有英语），退回自由抽，不能把孩子卡死
            current = bank.next(prefs.getSubjects(), prefs);
        }
        if (current == null) {
            // 题库空 / 科目全关，不能把孩子卡死，直接放行
            if (tvStem != null) tvStem.setText("题库还没准备好，先看吧");
            host.post(new Runnable() {
                @Override
                public void run() {
                    host.onPassed();
                }
            }, 1200);
            return;
        }

        if (tvStem != null) {
            tvStem.setText(current.stem);
            // 题干长就缩字号，避免把下面的选项挤出屏幕
            int n = current.stem.length();
            tvStem.setTextSize(TypedValue.COMPLEX_UNIT_SP, n <= 40 ? 18 : n <= 80 ? 16 : 15);
        }
        if (tvMeta != null) {
            int need = groupSize();
            int streak = prefs.getStreakCount();
            String plan = planText();
            tvMeta.setText(subjectName(current.subject)
                    + " · 第 " + Math.min(streak + 1, need) + "/" + need + " 题"
                    + (plan.isEmpty() ? "" : " · 本组 " + plan));
        }

        final String[] prefix = {"A  ", "B  ", "C  ", "D  "};
        for (int i = 0; i < 4; i++) {
            if (btns[i] == null) continue;
            if (i < current.options.length) {
                String text = current.options[i];
                btns[i].setVisibility(View.VISIBLE);
                btns[i].setText(prefix[i] + text);
                int len = text.length();
                btns[i].setTextSize(TypedValue.COMPLEX_UNIT_SP,
                        len <= 16 ? 15 : len <= 30 ? 14 : len <= 44 ? 13 : 12);
                final int idx = i;
                btns[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onAnswer(idx);
                    }
                });
            } else {
                btns[i].setVisibility(View.GONE);
            }
        }
        if (btns[0] != null) btns[0].requestFocus();
    }

    /**
     * 排一组的科目顺序。
     *
     * <p>只排「题库真有、家长也开着」的科目。一二年级的题库里没有英语，
     * 排出来就是语文、数学（外加科学）几科，不会硬塞一道英语结果取不到题。
     * 顺序每次洗牌，孩子猜不到下一道是什么。
     *
     * <p>题数取「启用科目数」和「连对题数」的较大值，再按科目循环填满队列，
     * 这样无论家长怎么调，都不会出现「排了科目却轮不到」的情况。
     */
    private void refillQueue() {
        queue.clear();
        groupOrder.clear();
        groupNeed = 0;
        if (bank == null) return;

        java.util.Set<String> have = new java.util.HashSet<>();
        for (String s : bank.availableSubjects().split(",")) {
            if (s != null && !s.isEmpty()) have.add(s);
        }
        String on = prefs.getSubjects() == null ? "" : prefs.getSubjects();

        java.util.List<String> subs = new java.util.ArrayList<>();
        for (String s : QuestionBank.SUBJECT_ORDER) {
            if (have.contains(s) && on.contains(s)) subs.add(s);
        }
        if (subs.isEmpty()) return;   // 一科都没有，交给 newQuestion 的兜底逻辑

        int need = Math.max(subs.size(), Math.max(1, prefs.getStreakRequired()));
        groupNeed = need;

        java.util.Collections.shuffle(subs);
        groupOrder.addAll(subs);

        // 第一轮用洗好的顺序，第二轮起每轮重新洗，避免每轮顺序完全一样
        while (queue.size() < need) {
            java.util.List<String> round = new java.util.ArrayList<>(subs);
            if (!queue.isEmpty()) java.util.Collections.shuffle(round);
            for (String s : round) {
                if (queue.size() >= need) break;
                queue.add(s);
            }
        }
    }

    /** 本组题数。队列还没排出来时退回家长设的值，保证不出现 0 题放行 */
    private int groupSize() {
        if (groupNeed > 0) return groupNeed;
        return Math.max(1, prefs.getStreakRequired());
    }

    /** 「本组 语文/数学/英语」这行提示。只有一科时不必显示，反而显啰嗦 */
    private String planText() {
        int need = groupSize();
        int n = Math.min(groupOrder.size(), need);
        if (n < 2) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (sb.length() > 0) sb.append("/");
            sb.append(subjectName(groupOrder.get(i)));
        }
        return sb.toString();
    }

    private static String subjectName(String s) {
        if ("chinese".equals(s)) return "语文";
        if ("math".equals(s)) return "数学";
        if ("english".equals(s)) return "英语";
        if ("science".equals(s)) return "科学";
        return "";
    }

    public void onAnswer(int idx) {
        if (answering || current == null) return;
        answering = true;
        prefs.incQuiz();

        final int need = groupSize();
        final boolean correct = (idx == current.answer);
        if (stats != null) stats.recordQuiz(current.subject, correct);

        if (correct) {
            prefs.incRight();                 // 累计答对总数（统计用）
            prefs.markRight(current.id);      // 这道题答对一次，满 2 次退役
            prefs.setWrongCount(0);
            final int streak = prefs.getStreakCount() + 1;
            prefs.setStreakCount(streak);

            // 答对是好事，不用孩子再点一下，900 毫秒后自己走
            markOption(idx, "#4ADE80", "✓ ");
            if (streak >= need) {
                prefs.setStreakCount(0);
                prefs.setSinceLastSec(0);
                if (tvFeedback != null) {
                    tvFeedback.setTextColor(Color.parseColor("#4ADE80"));
                    tvFeedback.setText(need <= 1
                            ? "答对了，继续看吧"
                            : "连对 " + streak + " 题，继续看吧");
                }
                host.post(new Runnable() {
                    @Override
                    public void run() {
                        host.onPassed();
                    }
                }, 900);
            } else {
                if (tvFeedback != null) {
                    tvFeedback.setTextColor(Color.parseColor("#4ADE80"));
                    tvFeedback.setText("答对了 " + streak + "/" + need
                            + "，还差 " + (need - streak) + " 题");
                }
                host.post(new Runnable() {
                    @Override
                    public void run() {
                        newQuestion();
                    }
                }, 900);
            }
            return;
        }

        // ---- 答错 ----
        int wrong = prefs.getWrongCount() + 1;
        prefs.setWrongCount(wrong);
        prefs.markWrong(current.id);      // 记录这道题难住过他，下一轮优先出
        prefs.setStreakCount(0);

        final String tip = current.tip == null ? "" : current.tip.trim();
        markOption(idx, "#F87171", "✗ ");
        markOption(current.answer, "#4ADE80", "✓ ");

        if (wrong >= prefs.getMaxWrong()) {
            // 连错到上限：这一屏也停住，让孩子看清为什么被锁，再进锁定页
            prefs.setLockUntil(System.currentTimeMillis() + prefs.getLockMin() * 60000L);
            if (tvFeedback != null) {
                tvFeedback.setTextColor(Color.parseColor("#F87171"));
                tvFeedback.setText("连错 " + wrong + " 次，锁定 " + prefs.getLockMin() + " 分钟\n"
                        + answerLine());
            }
            if (btnNext != null) {
                btnNext.setText("我知道了");
                btnNext.setVisibility(View.VISIBLE);
                btnNext.requestFocus();
            }
            waitingNext = true;
            // 这里不再等按钮：锁定已经开始计时，让孩子停在题上也一样看不到视频，
            // 但锁定页会把倒计时摆出来，家长更清楚现在是什么情况。
            host.post(new Runnable() {
                @Override
                public void run() {
                    host.onLocked();
                }
            }, 2600);
            return;
        }

        // 答错之后选项就锁死了：还能看见，但焦点拿不过去。
        // 不然孩子用方向键会把焦点从「我知道了」拨回选项上，按 OK 又没反应，
        // 看上去就像软件卡死了。
        for (Button b : btns) {
            if (b != null) b.setFocusable(false);
        }

        if (tvFeedback != null) {
            tvFeedback.setTextColor(Color.parseColor("#F87171"));
            String msg = "答错了，连对进度清零\n" + answerLine();
            if (!tip.isEmpty()) msg += "\n" + tip;
            tvFeedback.setText(msg);
            // 答案和解析可能有点长，这里给个小字号，避免把按钮挤出屏幕
            tvFeedback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        }
        if (btnNext != null) {
            btnNext.setText("我知道了，下一题");
            btnNext.setVisibility(View.VISIBLE);
            btnNext.requestFocus();
        }
        waitingNext = true;
    }

    /** 把正确答案所在选项写成人话，放在反馈里 */
    private String answerLine() {
        if (current == null) return "";
        int a = current.answer;
        if (a < 0 || a >= current.options.length) return "";
        String letter = String.valueOf((char) ('A' + a));
        return "正确答案是 " + letter + "：" + current.options[a];
    }

    /** 给某一个选项打勾或打叉，只改文字颜色和前缀，不动背景（背景是状态选择器） */
    private void markOption(int idx, String color, String mark) {
        if (idx < 0 || idx >= btns.length) return;
        Button b = btns[idx];
        if (b == null || b.getVisibility() != View.VISIBLE) return;
        try {
            b.setTextColor(Color.parseColor(color));
        } catch (Throwable ignored) {
        }
        String text = b.getText().toString();
        String prefix = String.valueOf((char) ('A' + idx)) + "  ";
        String rest = text.startsWith(prefix) ? text.substring(prefix.length()) : text;
        b.setText(mark + prefix + rest);
    }
}
