package com.tvguard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 全屏答题页。
 *
 * 只有 Activity 能进锁任务模式，把遥控器的桌面键、返回键、最近任务键一起吃掉，
 * 悬浮窗做不到这件事，所以答题的默认主场是全屏页。
 * 但全屏页有个硬前提：系统得允许后台拉起 Activity。个别电视（尤其鸿蒙底层的）会拦，
 * 拦了就是「什么都没弹」，所以守护服务那边会回查有没有真的显示，
 * 没显示就永久改走悬浮窗，见 GuardService.attemptQuiz。
 *
 * 按键白名单、家长密码、失焦拉回都在 BaseLockActivity 里，这里只管出题判题。
 */
public class QuizActivity extends BaseLockActivity {

    /** 供 GuardService 判断是否已经有一道题正在这里等着答，避免重复拉起 */
    public static volatile boolean alive = false;

    /**
     * 最近一次「确实在前台」的时刻（onResume 或拿到焦点）。
     *
     * 光有 alive 不够：Activity 在 onCreate 里崩掉时不会走 onDestroy，alive 会永久留在 true，
     * 守护服务看到它就以为题已经弹出来了，于是计时停住、题再也不弹。
     * 这个时间戳就是给 alive 加的有效期。
     */
    private static volatile long lastFrontAt = 0;

    private QuestionBank bank;
    private Stats stats;
    private QuizController ctrl;

    /** 题库还没解完时，等它一下，别直接放行。等到这个次数就别等了，让孩子继续看 */
    private int waitTicks = 0;

    /** 界面是不是真的在屏幕上等着答题 */
    public static boolean isShowing() {
        if (!alive) return false;
        long t = lastFrontAt;
        return t > 0 && android.os.SystemClock.elapsedRealtime() - t < 4000;
    }

    @Override
    protected void onAliveSignal() {
        lastFrontAt = android.os.SystemClock.elapsedRealtime();
    }

    @Override
    protected int contentLayout() {
        return R.layout.quiz_overlay;
    }

    @Override
    protected String logTag() {
        return "答题";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        // 注意这里不写 lastFrontAt。
        // onCreate 跑过不等于界面出来了：它可能被系统挡在 B 站后面，永远是后台页。
        // 只有 onResume / 拿到焦点才算真的到了前台，见 onAliveSignal。

        stats = new Stats(this);
        // 优先复用守护服务已经解析好的题库，避免主线程再解一次 JSON
        bank = GuardService.sharedBank;

        if (bankReady()) {
            startQuiz();
        } else {
            hintStem("正在准备题目…");
            waitForBank();
        }
    }

    private boolean bankReady() {
        return bank != null && bank.size() > 0;
    }

    /**
     * 题库没准备好就等，不要按「空题库直接放行」处理。
     *
     * 以前的状态是：题没解完 → next() 返回 null → 判成「题库空」→ 1.2 秒后自动放行。
     * 结果就是刚开机那一次弹题会被自己秒过，家长看到的是「弹了一下就没了」甚至「根本没弹」。
     * 这里改成每 300 毫秒看一次，最多等 8 秒；实在不行再放行，不把孩子卡死在黑屏上。
     */
    private void waitForBank() {
        if (isFinishing()) return;
        if (waitTicks++ > 26) {
            Diag.log(this, "答题", "题库 8 秒还没准备好，本次放行");
            prefs.setLastPop("跳过：题库没准备好");
            finish();
            return;
        }
        // 服务没在跑（被系统杀掉之类）时，自己兜底加载一次
        if (bank == null) {
            bank = GuardService.sharedBank;
            if (bank == null) {
                final QuestionBank tmp = new QuestionBank();
                bank = tmp;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        tmp.load(QuizActivity.this,
                                prefs.hasGrade() ? prefs.getGrade() : 1, prefs.getTerm());
                    }
                }, "quiz-bank").start();
            }
        }
        if (bankReady()) {
            startQuiz();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitForBank();
            }
        }, 300);
    }

    private void hintStem(String s) {
        View root = getWindow().getDecorView();
        TextView tv = root.findViewById(R.id.tvStem);
        if (tv != null) tv.setText(s);
    }

    private void startQuiz() {
        if (isFinishing() || ctrl != null) return;
        if (!bankReady()) {
            // 极端情况下还在加载，回到等待逻辑
            waitForBank();
            return;
        }
        ctrl = new QuizController(prefs, bank, stats, new QuizController.Host() {
            @Override
            public void onPassed() {
                leaveQuiz(true);
            }

            @Override
            public void onLocked() {
                leaveQuiz(false);
            }

            @Override
            public void post(Runnable r, long delayMs) {
                handler.postDelayed(r, delayMs);
            }
        });
        View root = getWindow().getDecorView();
        ctrl.bind(root);
    }

    /**
     * 收摊：从答题页撤离，把孩子送回他原来在看的东西。
     *
     * <p>这是「答对了却卡在答题页」的正解。原来的写法只有一句 finish()，
     * 在没拿到设备所有者时装作能跑，因为那时系统只是屏幕固定，
     * finish 之后还能自己退回上一个应用。拿到设备所有者以后答题页进了真锁任务，
     * 系统只允许白名单里的应用在前台，我们一 finish 就没有下一个可以去的任务，
     * 屏幕停在最后一帧，遥控器按什么都没用，看上去就是卡死。
     *
     * <p>所以顺序必须是：掐掉失焦拉回、退出锁任务、把目标应用拉到前台、最后关自己。
     * 少任何一步都会卡住或者被自己抢回来。
     *
     * @param passed true 表示答对放行，回到原来那个应用；false 表示连错锁定，
     *               这一页交给锁定页接管，不要把人送回视频
     */
    private void leaveQuiz(final boolean passed) {
        if (isFinishing()) return;

        // 一、先掐拉回。不然下面 finish 的瞬间 watchdog 会把这一页重新抢到前台，
        //     表现就是「答对了，页面闪一下又回来了」。
        parentPassing = true;

        // 二、退出锁任务。不退出的话下面那步启动外部应用会被系统静默丢掉。
        Kiosk.exit(this);

        final String back = GuardService.pkgBeforeQuiz;
        Diag.log(this, "答题", (passed ? "答对放行" : "连错锁定，交给锁定页")
                + "，目标应用 " + (back == null || back.isEmpty() ? "（没记到）" : back));

        // 三、关自己。关闭之后再启动目标应用，避免两页互相压。
        finish();

        if (passed && back != null && !back.isEmpty() && !back.equals(getPackageName())) {
            // 四、留一点空档再启动。
            //     退出锁任务和启动外部应用紧挨着调用时，部分固件会把启动当成
            //     「锁任务期间拉起白名单外的应用」而直接丢弃，日志里干干净净，很难查。
            //     350 毫秒是实测能稳过的最小值，再短就时灵时不灵。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            launchApp(back);
                        }
                    }, 350);
        }
    }

    /**
     * 把某个应用拉到前台。
     *
     * <p>用 launch intent + NEW_TASK 而不是新建任务：这个应用如果还在后台栈里，
     * 系统会把原来那个任务整个提到前台，视频接着播，不会从头开始。
     * 只有它真的被系统回收了，才会冷启动，那时候也只能从头开始。
     */
    private void launchApp(final String pkg) {
        // 一、系统如果已经自己把目标应用送回来了，就什么都别做。
        //    这时候再 startActivity 一次，等于从桌面图标重新拉起，
        //    会把它整个任务重置回首页，看到的就是「视频没了，像被杀了一样」。
        if (A11y.ready() && pkg.equals(A11y.frontPkg)) {
            Diag.log(this, "答题", pkg + " 已经在前台，不再重复拉起");
            return;
        }
        if (!startInto(pkg)) return;

        // 二、回头确认一次。华为从锁任务退出的转场比原生慢，第一次启动偶尔被吞掉。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!A11y.ready() || !pkg.equals(A11y.frontPkg)) {
                            Diag.log(getApplicationContext(), "答题", "第一次拉起没生效，补一次");
                            startInto(pkg);
                        }
                    }
                }, 1500);
    }

    /**
     * 真正启动目标应用。
     *
     * <p>关键是**清掉** launcher intent 自带的 {@link Intent#FLAG_ACTIVITY_RESET_TASK_IF_NEEDED}。
     * getLaunchIntentForPackage 返回的是「桌面图标」那个意图，默认带重置标志；
     * 原样 start 过去，系统会把该应用的任务栈重置到根 Activity，
     * 正在播的视频被换成首页，看上去就像进程被杀、从头再来。
     *
     * <p>只留 NEW_TASK：任务还在后台时，系统把它整体提到前台，栈顶仍是原来那一页，
     * 视频接着播。只有它真被系统回收了，才会冷启动。
     */
    private boolean startInto(String pkg) {
        try {
            android.content.Context c = getApplicationContext();
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Diag.log(this, "答题", "找不到 " + pkg + " 的启动入口");
                return false;
            }
            int f = i.getFlags();
            f &= ~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
            f &= ~Intent.FLAG_ACTIVITY_CLEAR_TOP;
            f &= ~Intent.FLAG_ACTIVITY_CLEAR_TASK;
            f |= Intent.FLAG_ACTIVITY_NEW_TASK;
            i.setFlags(f);
            c.startActivity(i);
            Diag.log(this, "答题", "已把 " + pkg + " 提回前台（不重置任务栈）");
            return true;
        } catch (Throwable t) {
            Diag.log(this, "答题", "拉回应用失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 家长输对密码：这次不答了，直接放行。
     * 只清本轮连对，不清连错，家长不该顺手把孩子答错的记录抹掉。
     */
    @Override
    protected void onParentPass() {
        // 只清本轮连对，不清连错，家长不该顺手把孩子答错的记录抹掉。
        prefs.setSinceLastSec(0);
        prefs.setStreakCount(0);
        // 等一秒再撤，让「已放行」的提示看得见。
        // 撤的路径和孩子答对完全一样：退锁任务、回原来那个应用，不然家长输完密码照样卡在这儿。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                leaveQuiz(true);
            }
        }, 400);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        lastFrontAt = 0;
        super.onDestroy();
    }

    /** GuardService 拉起答题页用的 Intent */
    public static Intent makeIntent(android.content.Context c) {
        Intent i = new Intent(c, QuizActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }
}
