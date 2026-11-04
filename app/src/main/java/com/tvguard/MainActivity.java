package com.tvguard;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int MODE_VERIFY = 0;
    private static final int MODE_SET_PIN = 1;
    /** 退出前的二次确认。能进设置页不代表能拆防护，拆之前再验一次。 */
    private static final int MODE_CONFIRM_EXIT = 2;

    /** 输错几次才开始罚等 */
    private static final int FREE_TRIES = 3;

    /**
     * 一次性跳过密码门的令牌，只活在进程内存里。
     * 不能用 Intent extra：主界面是 exported，别的 App 塞个参数就能绕过密码。
     */
    static boolean skipGateOnce = false;

    private static final int[] INTERVAL_STEPS = {1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120};
    private static final int[] DAILY_STEPS = {30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600};
    private static final int[] STREAK_STEPS = {1, 2, 3, 4, 5};
    private static final int[] WRONG_STEPS = {1, 2, 3, 4, 5, 6, 8, 10};
    private static final int[] LOCK_STEPS = {3, 5, 10, 15, 20, 30, 45, 60, 90, 120};

    private Prefs prefs;
    private QuestionBank bank;   // 只用于显示进度和手动换题，出题在服务里
    private int gateMode = MODE_VERIFY;
    private String pinBuf = "";
    private String pendingPin;   // 设密码时第一次输入的值，等二次确认

    private TextView tvDots, tvHint;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable lockTicker = new Runnable() {
        @Override
        public void run() {
            tickLock();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        // 一、出厂不带密码。第一次装必须先逼家长设一个自己的密码，
        //    不然「默认 1234」孩子第一次就试出来了，等于没设防。
        if (!prefs.hasPin()) {
            showGate(MODE_SET_PIN);
            return;
        }
        // 二、密码有了但还没选年级，先选年级
        if (!prefs.hasGrade()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        // 三、刚走完首次引导（设密码 → 选年级），别再拦一道。
        //    令牌只在内存里，用一次就作废，外部 App 伪造不了。
        if (skipGateOnce && prefs.hasGrade()) {
            skipGateOnce = false;
            showMain();
            return;
        }
        showGate(MODE_VERIFY);
    }

    // ================= 密码门 =================

    private void showGate(int mode) {
        gateMode = mode;
        pinBuf = "";
        pendingPin = null;
        setContentView(R.layout.activity_gate);
        tvDots = findViewById(R.id.tvPinDots);
        tvHint = findViewById(R.id.tvPinHint);
        updateHint();
        updateDots();

        int[] numIds = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
                R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9};
        for (int i = 0; i < numIds.length; i++) {
            final String digit = String.valueOf(i);
            findViewById(numIds[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onDigit(digit);
                }
            });
        }

        findViewById(R.id.kDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pinBuf.length() > 0) {
                    pinBuf = pinBuf.substring(0, pinBuf.length() - 1);
                    updateDots();
                }
            }
        });

        findViewById(R.id.kOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onOk();
            }
        });

        // 退出确认时才给「取消」，家长中途改主意有明确出口，不用瞎按返回键
        View cancel = findViewById(R.id.btnGateCancel);
        if (cancel != null) {
            if (mode == MODE_CONFIRM_EXIT) {
                cancel.setVisibility(View.VISIBLE);
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMain();
                    }
                });
            } else {
                cancel.setVisibility(View.GONE);
            }
        }

        findViewById(R.id.k1).requestFocus();
    }

    private void onDigit(String d) {
        // 设密码时不用罚等（家长本人就在设）；进设置页和退出确认都要，防止孩子接手键盘硬试
        if (gateMode != MODE_SET_PIN && isLockedNow()) {
            tickLock();
            return;
        }
        if (pinBuf.length() >= 8) return;
        pinBuf += d;
        updateDots();
    }

    private void onOk() {
        if (gateMode == MODE_VERIFY || gateMode == MODE_CONFIRM_EXIT) {
            if (isLockedNow()) {
                tickLock();
                return;
            }
            if (prefs.checkPin(pinBuf)) {
                prefs.clearPinFail();
                if (gateMode == MODE_CONFIRM_EXIT) {
                    // 先起退出页再回填设置页，避免设置页在退出页底下闪一下
                    startActivity(new Intent(this, ExitActivity.class));
                    showMain();
                } else {
                    showMain();
                }
            } else {
                onPinWrong();
            }
            return;
        }

        // ---- 首次设置密码 / 修改密码：两步确认 ----
        if (pinBuf.length() < 4) {
            toast("最少 4 位");
            return;
        }
        if (pendingPin == null) {
            if (isWeakPin(pinBuf)) {
                hintRed("这个太好猜了，换一个");
                pinBuf = "";
                updateDots();
                return;
            }
            pendingPin = pinBuf;
            pinBuf = "";
            hintGold("再输一遍确认");
            updateDots();
            return;
        }
        if (!pendingPin.equals(pinBuf)) {
            hintRed("两次不一样，重新设");
            pendingPin = null;
            pinBuf = "";
            updateDots();
            return;
        }

        prefs.setPin(pinBuf);
        prefs.clearPinFail();
        pendingPin = null;
        toast("密码已设好，记牢，忘了只能重装");
        if (!prefs.hasGrade()) {
            // 首次引导第二步：选年级
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        } else {
            showMain();
        }
    }

    /** 连续输错就罚等，越错越久。重启、退出都清不掉，写在本地。 */
    private void onPinWrong() {
        int f = prefs.getPinFail() + 1;
        prefs.setPinFail(f);
        pinBuf = "";
        updateDots();

        int sec = lockSeconds(f);
        if (sec <= 0) {
            int left = FREE_TRIES - f;
            hintRed(left > 0
                    ? "密码不对，还能试 " + left + " 次"
                    : "密码不对，再错就要等 30 秒");
            return;
        }
        prefs.setPinLockUntil(System.currentTimeMillis() + sec * 1000L);
        tickLock();
    }

    /**
     * 前 3 次不罚（家长手滑）。第 4 次起每次错等 30 秒，
     * 错到 9 次变 1 分钟，14 次 2 分钟，19 次以上 5 分钟封顶。
     *
     * 为什么不封得更狠：孩子乱按会把家长一起锁在外面。5 分钟封顶，
     * 家长最多等一下就能进；孩子要爆破 4 位数得试 10000 次，
     * 按 5 分钟一次算是 34 天，划不来。
     */
    private int lockSeconds(int fails) {
        if (fails <= FREE_TRIES) return 0;
        int step = (fails - FREE_TRIES - 1) / 5;
        if (step <= 0) return 30;
        if (step == 1) return 60;
        if (step == 2) return 120;
        return 300;
    }

    private boolean isLockedNow() {
        return prefs.getPinLockUntil() > System.currentTimeMillis();
    }

    private void tickLock() {
        ui.removeCallbacks(lockTicker);
        long left = prefs.getPinLockUntil() - System.currentTimeMillis();
        if (left <= 0) {
            hintGold("可以再试了，输家长密码");
            return;
        }
        long sec = left / 1000;
        String t = sec >= 60
                ? (sec / 60) + " 分 " + (sec % 60) + " 秒"
                : sec + " 秒";
        hintRed("错太多次，还得等 " + t);
        ui.postDelayed(lockTicker, 1000L);
    }

    private void updateHint() {
        if (tvHint == null) return;
        if (gateMode == MODE_SET_PIN) {
            if (prefs.hasPin()) {
                hintGold("输 4 到 8 位新密码，按「确定」");
            } else {
                hintGold("先设一个家长密码，4 到 8 位，按「确定」");
            }
            return;
        }
        if (isLockedNow()) {
            tickLock();
        } else if (gateMode == MODE_CONFIRM_EXIT) {
            hintGold("要关掉或卸载，再输一次家长密码");
        } else {
            hintGold("输家长密码，按「确定」");
        }
    }

    private void hintGold(String s) {
        if (tvHint == null) return;
        tvHint.setTextColor(Color.parseColor("#FACC15"));
        tvHint.setText(s);
    }

    private void hintRed(String s) {
        if (tvHint == null) return;
        tvHint.setTextColor(Color.parseColor("#F87171"));
        tvHint.setText(s);
    }

    /** 纯重复（1111）、顺增（1234）、顺减（4321）一律不收 */
    private boolean isWeakPin(String p) {
        if (p.length() < 4) return true;
        boolean same = true, asc = true, desc = true;
        for (int i = 1; i < p.length(); i++) {
            int d = p.charAt(i) - p.charAt(i - 1);
            if (d != 0) same = false;
            if (d != 1) asc = false;
            if (d != -1) desc = false;
        }
        return same || asc || desc;
    }

    private void updateDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pinBuf.length(); i++) sb.append("● ");
        tvDots.setText(sb.toString());
    }

    // ================= 设置页 =================

    private void showMain() {
        setContentView(R.layout.activity_main);

        bindStep(R.id.btnIntervalDec, R.id.btnIntervalInc, R.id.tvInterval,
                INTERVAL_STEPS, prefs.getIntervalMin(), new Apply() {
                    @Override
                    public void on(int v) {
                        prefs.setIntervalMin(v);
                        restartServiceIfRunning();
                    }
                }, "分钟");

        bindStep(R.id.btnDailyDec, R.id.btnDailyInc, R.id.tvDaily,
                DAILY_STEPS, prefs.getDailyLimitMin(), new Apply() {
                    @Override
                    public void on(int v) {
                        prefs.setDailyLimitMin(v);
                    }
                }, "分钟");

        bindStep(R.id.btnStreakDec, R.id.btnStreakInc, R.id.tvStreak,
                STREAK_STEPS, prefs.getStreakRequired(), new Apply() {
                    @Override
                    public void on(int v) {
                        prefs.setStreakRequired(v);
                    }
                }, "题");

        bindStep(R.id.btnWrongDec, R.id.btnWrongInc, R.id.tvWrong,
                WRONG_STEPS, prefs.getMaxWrong(), new Apply() {
                    @Override
                    public void on(int v) {
                        prefs.setMaxWrong(v);
                    }
                }, "次");

        bindStep(R.id.btnLockDec, R.id.btnLockInc, R.id.tvLock,
                LOCK_STEPS, prefs.getLockMin(), new Apply() {
                    @Override
                    public void on(int v) {
                        prefs.setLockMin(v);
                    }
                }, "分钟");

        // 科目
        findViewById(R.id.btnSubChinese).setOnClickListener(v -> toggleSubject("chinese"));
        findViewById(R.id.btnSubMath).setOnClickListener(v -> toggleSubject("math"));
        findViewById(R.id.btnSubEnglish).setOnClickListener(v -> toggleSubject("english"));
        findViewById(R.id.btnSubScience).setOnClickListener(v -> toggleSubject("science"));

        // 权限
        findViewById(R.id.btnOverlay).setOnClickListener(v -> requestOverlay());
        findViewById(R.id.btnAdmin).setOnClickListener(v -> requestAdmin());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestIgnoreBattery());

        // 最终权限。这是唯一能让管理能力伸出本应用的办法：
        // 应用自己连本机的调试服务，家长只要在电视上点一次「允许」，
        // 剩下的 dpm 命令由软件自己跑，不需要电脑也不需要手机。
        findViewById(R.id.btnProbeAdb).setOnClickListener(v -> probeAdbChannel());
        findViewById(R.id.btnSelfGrant).setOnClickListener(v -> startSelfGrant());

        // 设成默认桌面：整条免电脑方案的主开关。
        // 家长在电视设置里点两下就行，不用电脑、不用数据线、不用开发者模式。
        findViewById(R.id.btnSetHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (HomeActivity.isDefaultHome(MainActivity.this)) {
                    toast("已经是电视桌面了，孩子按桌面键回到的就是答题锁");
                    return;
                }
                HomeActivity.openHomeSettings(MainActivity.this);
                toast("在「默认应用 / 主屏幕」里选「答题锁」。电视上没有这一项就说明换不了桌面，"
                        + "那就用上面的「一键获取最终权限」");
            }
        });

        findViewById(R.id.btnPreviewHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, HomeActivity.class));
                } catch (Throwable t) {
                    toast("桌面打不开：" + t.getClass().getSimpleName());
                }
            }
        });

        // 换成答题锁桌面之后，电视自己的主页就没了，系统设置的入口也一起消失。
        // 没有这个按钮，家长想装个应用、改个分辨率都会卡死，最后只能恢复出厂。
        findViewById(R.id.btnSysSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] acts = {
                        android.provider.Settings.ACTION_SETTINGS,
                        "android.settings.SETTINGS",
                        "com.android.settings.Settings"
                };
                for (String a : acts) {
                    try {
                        Intent i = new Intent(a);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                toast("这台电视把系统设置页藏起来了，只能从遥控器上的设置键进");
            }
        });

        // 无障碍：这是这台电视唯一能挂出浮窗的通道，比悬浮窗权限还重要。
        findViewById(R.id.btnA11y).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (A11y.enabled(MainActivity.this)) {
                    toast("无障碍已经开着了，答题浮窗可以正常盖在视频上");
                    return;
                }
                boolean ok = A11y.openSettings(MainActivity.this);
                toast(ok
                        ? "在「无障碍」里找到答题锁，打开它。开着的是「无障碍」，不是悬浮窗"
                        : "这台电视没找到无障碍设置页，只能靠电脑跑一次强锁脚本");
            }
        });

        // 维护
        findViewById(R.id.btnAddTime).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setDailyLimitMin(prefs.getDailyLimitMin() + 30);
                toast("今天上限已加到 " + prefs.getDailyLimitMin() + " 分钟");
                refresh();
            }
        });

        findViewById(R.id.btnReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetToday();
                toast("今日时长已清零");
                refresh();
            }
        });

        // 换一批题：把当前科目所有题的答对次数清零，题目重新可用
        findViewById(R.id.btnNewBatch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bank == null) {
                    toast("题库还在加载，稍后再点一次");
                    return;
                }
                bank.resetRound(prefs, prefs.getSubjects());
                toast("换到第 " + prefs.getBankRound() + " 轮，题目全部重新可用");
                refresh();
            }
        });

        findViewById(R.id.btnChangePin).setOnClickListener(v -> showGate(MODE_SET_PIN));

        findViewById(R.id.btnToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = prefs.isEnabled();
                if (now) {
                    prefs.setEnabled(false);
                    stopService(new Intent(MainActivity.this, GuardService.class));
                } else {
                    prefs.setEnabled(true);
                    startGuard();
                    // 之前从退出页解除过防护的话，设备管理员已经被摘掉了，提醒一句
                    if (!OwnerGuard.isAdminActive(MainActivity.this)) {
                        toast("已恢复。防卸载之前解除了，要重开请点「开启防卸载」");
                    }
                }
                refresh();
            }
        });

        // 年级
        findViewById(R.id.btnGrade).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SetupActivity.class));
            }
        });

        // 学习报告
        findViewById(R.id.btnReport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ReportActivity.class));
            }
        });

        // 界面大小三档
        findViewById(R.id.btnScaleS).setOnClickListener(v -> setScale(0.9f));
        findViewById(R.id.btnScaleM).setOnClickListener(v -> setScale(1.0f));
        findViewById(R.id.btnScaleL).setOnClickListener(v -> setScale(1.2f));

        // 兼容性自检：换一台电视先跑这个，能不能用一眼看出来
        findViewById(R.id.btnCompat).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CompatActivity.class)));
        findViewById(R.id.btnKeyTest).setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, CompatActivity.class);
            i.putExtra("key_test", true);
            startActivity(i);
        });

        // 不用真等十分钟，立刻弹一道题看看效果
        findViewById(R.id.btnTryQuiz).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean overlay;
                try {
                    overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                            || Settings.canDrawOverlays(MainActivity.this);
                } catch (Throwable t) {
                    overlay = false;
                }
                try {
                    // 点这个按钮顺便把「这台电视该走哪条路」重新检测一遍。
                    // 之前判定全屏页不行就一直走悬浮窗，换了电视或升了系统要能重来。
                    prefs.resetFullscreenOk();
                    Intent i = new Intent(MainActivity.this, GuardService.class);
                    i.setAction(GuardService.ACTION_TRY);
                    startService(i);
                    toast(overlay
                            ? "3 秒内会盖一道题上来，试试遥控器能不能选中"
                            : "3 秒内会跳出全屏答题页（这台没给悬浮窗权限）");

                    // 五秒后回来看结果。
                    // 以前点完就没下文，家长不知道是没到点、被系统拦了、还是服务没跑，
                    // 只能靠猜。现在把服务记录的那句话直接念出来。
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            String r = prefs.getLastPop();
                            toast(r == null || r.isEmpty() ? "没有回音，进「计时诊断」看第一屏" : r);
                        }
                    }, 5000);
                } catch (Throwable t) {
                    toast("守护服务没在跑，先点最下面的「恢复守护」");
                }
            }
        });

        // 计时和弹题到底在做什么，摊开给家长看
        findViewById(R.id.btnDiag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });

        // 退出 / 关闭：拆防护是大动作，必须再过一次密码，不能因为已经站在设置页就放行
        findViewById(R.id.btnExit).setOnClickListener(v -> showGate(MODE_CONFIRM_EXIT));

        // 家长改完参数需要一个明确的下庄动作。这个按钮同时兼一道自检：
        // 缺密码、缺年级、守护没开，都会说清楚缺哪一样，而不是闷声返回。
        findViewById(R.id.btnFinish).setOnClickListener(v -> onFinishSetup());

        ensureServiceRunning();
        refresh();
    }

    private void onFinishSetup() {
        StringBuilder miss = new StringBuilder();
        if (!prefs.hasPin()) miss.append("家长密码还没设。");
        if (!prefs.hasGrade()) miss.append("还没选年级。");
        if (!prefs.isEnabled()) miss.append("守护现在是暂停状态。");

        if (miss.length() > 0) {
            toast(miss.toString());
            return;
        }

        try {
            startService(new Intent(this, GuardService.class));
        } catch (Throwable ignored) {
        }

        StringBuilder ok = new StringBuilder();
        ok.append("设置完成，守护运行中。每 ")
                .append(prefs.getIntervalMin())
                .append(" 分钟弹")
                .append(prefs.getStreakRequired())
                .append(" 题，每天上限 ")
                .append(prefs.getDailyLimitMin())
                .append(" 分钟");
        if (prefs.hasGrade()) {
            ok.append("，当前 ").append(SetupActivity.gradeName(prefs.getGrade()));
        }

        // 悬浮窗权限不拦着完成，但必须说清楚。
        // 它有两个作用：一是有权限的应用才能从后台把答题页拉起来，
        // 二是它本身就是最后一道兜底。没开的时候，孩子开着视频也很有可能弹不出来。
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        if (!overlay && !A11y.enabled(this)) {
            ok.append("。注意：悬浮窗权限和无障碍都没开，孩子看视频时弹不出来，"
                    + "请先点「开启无障碍」");
        } else if (!overlay) {
            ok.append("。浮窗通道：无障碍（这台电视没有悬浮窗权限项，正常）");
        }
        toast(ok.toString());
        finish();
    }

    private void setScale(float s) {
        prefs.setUiScale(s);
        toast("界面已切换，正在刷新");
        // 重建 Activity 让新的 density 生效，同时重启服务让遮罩也用新尺寸
        restartServiceIfRunning();
        recreate();
    }

    private interface Apply {
        void on(int v);
    }

    private void bindStep(int decId, int incId, int labelId, final int[] steps,
                          int current, final Apply app, final String unit) {
        final TextView label = findViewById(labelId);
        label.setText(current + " " + unit);

        View.OnClickListener l = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = label.getText().toString().replace(" " + unit, "").trim();
                int cur;
                try {
                    cur = Integer.parseInt(txt);
                } catch (Exception e) {
                    cur = steps[0];
                }
                int idx = indexOf(steps, cur);
                if (v.getId() == decId) idx = Math.max(0, idx - 1);
                else idx = Math.min(steps.length - 1, idx + 1);
                int nv = steps[idx];
                label.setText(nv + " " + unit);
                app.on(nv);
            }
        };
        findViewById(decId).setOnClickListener(l);
        findViewById(incId).setOnClickListener(l);
    }

    private int indexOf(int[] arr, int v) {
        int best = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] <= v) best = i;
        }
        return best;
    }

    private void toggleSubject(String key) {
        if ("english".equals(key) && prefs.getGrade() <= 2) {
            toast("一二年级还没有英语教材，三年级再开");
            return;
        }
        String cur = prefs.getSubjects();
        String[] parts = cur.split(",");
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) list.add(p);

        if (list.contains(key)) {
            if (list.size() == 1) {
                toast("至少保留一科");
                return;
            }
            list.remove(key);
        } else {
            list.add(key);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        prefs.setSubjects(sb.toString());
        refreshSubjects();
    }

    private void refreshSubjects() {
        String s = prefs.getSubjects();
        styleSubject(R.id.btnSubChinese, s.contains("chinese"));
        styleSubject(R.id.btnSubMath, s.contains("math"));
        styleSubject(R.id.btnSubEnglish, s.contains("english"));
        styleSubject(R.id.btnSubScience, s.contains("science"));
    }

    private void styleSubject(int id, boolean on) {
        Button b = findViewById(id);
        if (b == null) return;
        b.setTextColor(on ? Color.parseColor("#FACC15") : Color.parseColor("#64748B"));
    }

    // ================= 状态刷新 =================

    private void refresh() {
        if (prefs == null) return;
        prefs.rolloverIfNewDay();

        int used = prefs.getTodayUsedSec() / 60;
        int limit = prefs.getDailyLimitMin();
        TextView tvToday = findViewById(R.id.tvToday);
        if (tvToday != null) {
            tvToday.setText("今日已看 " + used + " 分钟 / 上限 " + limit + " 分钟");
        }
        ProgressBar pb = findViewById(R.id.pbToday);
        if (pb != null) {
            pb.setProgress(limit <= 0 ? 0 : Math.min(100, used * 100 / limit));
        }
        TextView tvStats = findViewById(R.id.tvStats);
        if (tvStats != null) {
            Stats.Agg w = new Stats(this).lastDays(7);
            int q = prefs.getTotalQuiz();
            int r = prefs.getTotalRight();
            String rate = q == 0 ? "-" : (r * 100 / q) + "%";
            tvStats.setText("近 7 天答题 " + w.quiz + " 次，正确率 "
                    + (w.quiz == 0 ? "-" : w.rate() + "%")
                    + "　｜　累计答题 " + q + " 次，正确率 " + rate);
        }

        TextView tvOwner = findViewById(R.id.tvOwner);
        if (tvOwner != null) {
            boolean owner = OwnerGuard.isDeviceOwner(this);
            if (owner) {
                // 每次打开设置页都重新落一遍权限。系统升级、家长误点都可能让它掉回去，
                // 而家长只会看到「又不弹了」。重复执行没有副作用。
                OwnerGuard.applyPolicies(this);
            }
            tvOwner.setText("跨应用能力：" + OwnerGuard.powerText(this)
                    + "\n防卸载强度：" + OwnerGuard.statusText(this)
                    + "\n答题锁屏：" + Kiosk.describe(this));
            tvOwner.setTextColor(Color.parseColor(owner ? "#4ADE80" : "#F87171"));
        }

        // 最终权限的默认提示。只在还没被授权流程写过的时候填，
        // 否则会把「正在等电视上点允许」这种实时进度冲掉。
        TextView tvGrantTip = findViewById(R.id.tvGrantTip);
        if (tvGrantTip != null && tvGrantTip.getText().length() == 0) {
            if (OwnerGuard.isDeviceOwner(this)) {
                tvGrantTip.setTextColor(Color.parseColor("#4ADE80"));
                tvGrantTip.setText("已拿到最终权限。切到任何应用都会弹题，答题时按桌面键也出不去。");
            } else {
                tvGrantTip.setTextColor(Color.parseColor("#94A3B8"));
                tvGrantTip.setText("没拿到之前，软件只能在自己界面里生效。"
                        + "先点「只检查调试通道」，通了再点「一键获取最终权限」。");
            }
        }

        // 桌面接管状态。这一行才是现在最该看的东西：
        // 它决定「孩子切到别的应用还管不管得住」，而且不需要电脑就能变成绿的。
        TextView tvHomeTip = findViewById(R.id.tvHomeTip);
        Button btnSetHome = findViewById(R.id.btnSetHome);
        if (tvHomeTip != null) {
            boolean isHome = HomeActivity.isDefaultHome(this);
            boolean canSwitch = HomeActivity.homeSwitchable(this);
            if (isHome) {
                tvHomeTip.setText("已接管桌面。孩子按桌面键回到的是答题锁，"
                        + "想开任何应用都得先过答题这一关。"
                        + (A11y.ready()
                        ? "在应用里看久了，会自动把他送回来重新答题。"
                        : "再把下面的无障碍打开，在应用里看久了就能自动送回来。"));
                tvHomeTip.setTextColor(Color.parseColor("#4ADE80"));
                if (btnSetHome != null) btnSetHome.setText("已经是电视桌面");
            } else if (canSwitch) {
                tvHomeTip.setText("电视允许换桌面，只是还没换成答题锁。"
                        + "点「去设成默认桌面」，在列表里选「答题锁」，有「始终」就选「始终」。"
                        + "设好之后不用电脑，孩子按桌面键就跑不掉了。");
                tvHomeTip.setTextColor(Color.parseColor("#FACC15"));
                if (btnSetHome != null) btnSetHome.setText("去设成默认桌面");
            } else {
                tvHomeTip.setText("这台电视没给换桌面的入口，这一条路走不通。"
                        + "退回老办法：把无障碍打开，到点用浮窗或按桌面键把人拦回来，"
                        + "或者用电脑/安卓手机跑一次配置脚本。");
                tvHomeTip.setTextColor(Color.parseColor("#F87171"));
                if (btnSetHome != null) btnSetHome.setText("没有换桌面入口");
            }
        }
        // 版本号必须显眼。以前它在报告页脚里，还是硬编码的 2.4，
        // 家长装到 v2.7 了还以为自己一直在用 v2.4。
        TextView verLine = findViewById(R.id.tvVerLine);
        if (verLine != null) {
            verLine.setText("当前版本 " + Report.ver(this)
                    + "　改动点了就生效，改完滚到最下面点「设置完成」");
        }

        TextView tvState = findViewById(R.id.tvRunState);
        if (tvState != null) {
            if (prefs.isEnabled()) {
                // 光看「已启用」不够：电视把后台冻住时，这个开关也一样是开着的，
                // 实际计时和弹题全停。心跳超过 70 秒就说明服务没在跑，直接说出来。
                long beat = prefs.beatGapSec();
                if (beat > 70) {
                    tvState.setText("守护被系统冻结，" + beat + " 秒没响应");
                    tvState.setTextColor(Color.parseColor("#F87171"));
                } else {
                    tvState.setText("守护运行中");
                    tvState.setTextColor(Color.parseColor("#4ADE80"));
                }
            } else {
                // 只是暂停的话防护还在；从退出页关掉的话设备管理员已经摘了，说清楚区别
                tvState.setText(OwnerGuard.isAdminActive(this) ? "已暂停" : "已关闭");
                tvState.setTextColor(Color.parseColor("#F87171"));
            }
        }
        Button tg = findViewById(R.id.btnToggle);
        if (tg != null) tg.setText(prefs.isEnabled() ? "暂停守护" : "恢复守护");

        // 无障碍按钮的文字跟着状态变，家长不用去别处查。
        Button btnA11y = findViewById(R.id.btnA11y);
        if (btnA11y != null) {
            boolean a11yOn = A11y.enabled(this);
            btnA11y.setText(a11yOn ? "无障碍：已开启" : "开启无障碍（挂答题浮窗）");
        }

        TextView tip = findViewById(R.id.tvTip);
        if (tip != null) {
            if (hasOverlay()) {
                // 悬浮窗权限有了，浮窗一定能挂，不用再提醒
                tip.setText("");
                tip.setVisibility(View.GONE);
            } else if (A11y.enabled(this)) {
                // 权限没有但无障碍开着：这就是能用的状态，说清楚免得他继续找悬浮窗
                tip.setText("这台电视没有「悬浮窗」这一项，现在靠无障碍挂答题浮窗，已经能用了。"
                        + "看到题目盖在视频上就是正常的。");
                tip.setTextColor(Color.parseColor("#4ADE80"));
                tip.setVisibility(View.VISIBLE);
            } else {
                // 两条通道都没有：这才是真的弹不出来
                tip.setText("还差最后一步：这台电视没有「悬浮窗」权限可开，"
                        + "必须打开「无障碍」才能把题目盖到视频上。点上面的「开启无障碍」，"
                        + "在列表里找到答题锁，打开它。");
                tip.setTextColor(Color.parseColor("#F87171"));
                tip.setVisibility(View.VISIBLE);
            }
        }

        TextView tvGrade = findViewById(R.id.tvGrade);
        if (tvGrade != null) {
            String term = "a".equals(prefs.getTerm()) ? "上册" : "b".equals(prefs.getTerm()) ? "下册" : "上下册";
            tvGrade.setText(SetupActivity.gradeName(prefs.getGrade()) + " · " + term + "题库");
        }
        float sc = prefs.getUiScale();
        styleScale(R.id.btnScaleS, Math.abs(sc - 0.9f) < 0.01f);
        styleScale(R.id.btnScaleM, Math.abs(sc - 1.0f) < 0.01f);
        styleScale(R.id.btnScaleL, Math.abs(sc - 1.2f) < 0.01f);

        refreshSubjects();
        refreshBankInfo();
    }

    private void styleScale(int id, boolean on) {
        Button b = findViewById(id);
        if (b == null) return;
        b.setTextColor(on ? Color.parseColor("#FACC15") : Color.parseColor("#94A3B8"));
    }

    // ================= 题库进度 =================

    private void refreshBankInfo() {
        final TextView tvBank = findViewById(R.id.tvBank);
        if (tvBank == null) return;
        if (bank != null) {
            bank.refreshStats(prefs);
            renderBank(tvBank);
            return;
        }
        tvBank.setText("题库加载中…");
        // 题库文件一百多 KB，放后台线程解析，别卡住遥控器
        new Thread(new Runnable() {
            @Override
            public void run() {
                final QuestionBank b = new QuestionBank();
                b.load(MainActivity.this, prefs.getGrade(), prefs.getTerm());
                b.restoreRound(prefs);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bank = b;
                        bank.refreshStats(prefs);
                        TextView tv = findViewById(R.id.tvBank);
                        if (tv != null) renderBank(tv);
                    }
                });
            }
        }).start();
    }

    private void renderBank(TextView tv) {
        if (bank == null) return;
        int avail = bank.getAvailableCount(prefs);
        String term = "a".equals(prefs.getTerm()) ? "上册" : "b".equals(prefs.getTerm()) ? "下册" : "上下册";
        tv.setText(SetupActivity.gradeName(prefs.getGrade()) + term + " · 固定题 "
                + bank.getTotalFixed() + " 道，还剩 " + avail + " 道没掌握 · 第 "
                + prefs.getBankRound() + " 轮\n数学口算由程序现场生成，答不完");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null || findViewById(R.id.tvToday) == null) return;

        // 从年级选择页回来，题库要换成新年级的
        if (bank != null && (bank.getLoadedGrade() != prefs.getGrade()
                || !prefs.getTerm().equals(bank.getLoadedTerm()))) {
            bank = null;
            restartServiceIfRunning();
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================= 权限 =================

    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 电视系统普遍裁剪了设置项，ACTION_MANAGE_OVERLAY_PERMISSION
            // 很可能不存在。一级一级往后退，别直接失败。
            String[] actions = {
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Settings.ACTION_SETTINGS
            };
            for (String action : actions) {
                try {
                    Intent i = new Intent(action);
                    if (action.equals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) {
                        i.setData(Uri.parse("package:" + getPackageName()));
                    } else if (!action.equals(Settings.ACTION_SETTINGS)) {
                        i.setData(Uri.parse("package:" + getPackageName()));
                    }
                    startActivity(i);
                    toast("找到「悬浮窗」或「显示在其他应用上层」打开。找不到也没关系，软件会自动改成全屏弹题");
                    return;
                } catch (Exception ignored) {
                    // 换下一个入口
                }
            }
            toast("这台电视没有悬浮窗权限入口。不影响使用：到点会自动弹全屏答题页，只是答完要手动继续播");
        } else {
            toast("悬浮窗权限已开启");
        }
    }

    private void requestAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName cn = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(cn)) {
            toast("防卸载已开启");
            return;
        }
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "开启后孩子无法直接卸载本应用，防止绕过答题。");
            startActivity(i);
        } catch (Exception e) {
            toast("这台设备不支持设备管理器");
        }
    }

    private void requestIgnoreBattery() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    toast("已经不受电池优化限制");
                    return;
                }
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) {
            toast("请在系统设置的电池选项里手动关闭限制");
        }
    }

    // ================= 服务 =================

    private void ensureServiceRunning() {
        if (!prefs.isEnabled()) return;
        startGuard();
    }

    private void startGuard() {
        try {
            Intent i = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
        }
    }

    private void restartServiceIfRunning() {
        stopService(new Intent(this, GuardService.class));
        if (prefs.isEnabled()) startGuard();
    }

    // ------------------------------------------------------------ 最终权限

    /**
     * 只检查调试通道，不做任何授权。
     *
     * <p>单独拎出来是因为它是整条路的第一道门，而家长通常不知道该去哪儿看、
     * 也不知道自己打开的那个开关到底算不算数。这里一次性把三种情况分开报：
     * 口没开、口开了但换了协议、口开了且是安卓协议。
     * 三种情况对应三套处置办法，混在一起说等于没说。
     */
    private void probeAdbChannel() {
        final TextView tip = findViewById(R.id.tvGrantTip);
        if (tip != null) {
            tip.setTextColor(Color.parseColor("#94A3B8"));
            tip.setText("正在检查电视的调试通道，几秒钟…");
        }
        new Thread(() -> {
            final LocalAdb.Probe p = LocalAdb.probe();
            final boolean owner = OwnerGuard.isDeviceOwner(this);
            Diag.log(this, "权限", "探测 state=" + p.state + " host=" + p.host
                    + " tried=" + p.tried + " raw=" + p.raw + " hdc8710=" + p.hdcOpen);
            runOnUiThread(() -> {
                TextView t = findViewById(R.id.tvGrantTip);
                if (t == null) return;
                if (owner) {
                    t.setTextColor(Color.parseColor("#4ADE80"));
                    t.setText("这台电视已经是「设备所有者」，能力已经全部拿到，不用再检查通道。");
                    return;
                }
                t.setTextColor(Color.parseColor(p.usable() ? "#4ADE80" : "#F87171"));
                t.setText(LocalAdb.advice(p));
            });
        }).start();
    }

    /**
     * 一键拿到最终权限。整个过程在工作线程里跑，因为要等家长在电视上点授权框。
     */
    private void startSelfGrant() {
        final TextView tip = findViewById(R.id.tvGrantTip);
        final Button btn = findViewById(R.id.btnSelfGrant);

        if (OwnerGuard.isDeviceOwner(this)) {
            if (tip != null) {
                tip.setTextColor(Color.parseColor("#4ADE80"));
                tip.setText("已经是「设备所有者」，能力全部生效，不用再做。");
            }
            return;
        }

        if (btn != null) btn.setEnabled(false);
        if (tip != null) {
            tip.setTextColor(Color.parseColor("#94A3B8"));
            tip.setText("准备中…");
        }

        new Thread(() -> SelfGrant.run(MainActivity.this, new SelfGrant.Cb() {
            @Override
            public void onStep(final String step) {
                runOnUiThread(() -> {
                    TextView t = findViewById(R.id.tvGrantTip);
                    if (t != null) {
                        t.setTextColor(Color.parseColor("#94A3B8"));
                        t.setText(step + "…");
                    }
                });
            }

            @Override
            public void onDone(final boolean ok, final String detail) {
                runOnUiThread(() -> {
                    Button b = findViewById(R.id.btnSelfGrant);
                    if (b != null) b.setEnabled(true);
                    TextView t = findViewById(R.id.tvGrantTip);
                    if (t != null) {
                        t.setTextColor(Color.parseColor(ok ? "#4ADE80" : "#F87171"));
                        t.setText(detail);
                    }
                    // 成功与否都要重刷一遍顶部状态，家长需要看到「跨应用能力」是不是变绿
                    refresh();
                });
            }
        })).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
