package com.tvguard;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * 常驻守护服务。
 * 每秒检查一次：屏幕亮着就累加观看时长，满一个周期就盖全屏答题遮罩，答对才撤。
 * 不碰电视系统本身的任何设置，标准模式下正常工作，也不锁内容。
 */
public class GuardService extends Service {

    private static final String CH = "tvguard_ch";
    private static final int NOTI_ID = 1001;
    private static final String CH_REPORT = "tvguard_report";
    private static final int NOTI_REPORT_ID = 1002;
    /** 弹题被拦时的提醒通知 */
    private static final int NOTI_ALERT_ID = 1003;

    private static final int LOCK_NONE = 0;
    private static final int LOCK_WRONG = 1;   // 连错太多，短期锁定
    private static final int LOCK_DAILY = 2;   // 今天看完了

    /**
     * 当前解析好的题库，供全屏答题页（QuizActivity）复用。
     * 题库解析要几百毫秒，没必要为一次答题在 UI 线程再解一遍。
     */
    public static volatile QuestionBank sharedBank;

    /** 无障碍服务要把按键转进来，得先拿到活着的实例 */
    public static volatile GuardService inst;

    /**
     * 弹题之前孩子在看的那个应用。
     *
     * <p>这个字段是「答对了回不去」的解法。设备所有者模式下答题页进了锁任务，
     * 孩子的桌面键、返回键全部失效，Activity 一 finish 系统没有可去的任务，
     * 屏幕就停在最后那一帧上，看上去像卡死。
     * 所以必须在弹题之前记下去处，答对之后主动把人送回去。
     */
    public static volatile String pkgBeforeQuiz = "";

    /**
     * 这一次弹题是家长在设置页手动点「立即弹一道题」触发的。
     *
     * <p>只用来给心跳里那条「家长在设置页就什么都不做」开一个一次性的口子。
     * 没有它，家长想验一下效果的时候按下去毫无反应，会以为软件坏了。
     */
    private boolean manualTry = false;

    /** 上一次「按 HOME 把人送回桌面」的时刻，用来防抖，别一秒按十次 */
    private long lastKickMs = 0;

    private Prefs prefs;
    private QuestionBank bank;
    private WindowManager wm;
    /** 上一次挂浮窗用的是哪个 WindowManager。无障碍浮窗和普通悬浮窗不是同一个，摘的时候必须对上 */
    private WindowManager quizWm;
    private WindowManager lockWm;
    private Handler handler;
    private QuizController quizCtrl;

    private View quizView;
    private View lockView;
    private WindowManager.LayoutParams quizParams;
    private WindowManager.LayoutParams lockParams;

    private boolean quizShowing = false;
    private boolean lockShowing = false;
    /** 这一次弹题有没有因为「浮窗吃不到遥控器焦点」降级过一次，防止来回抖 */
    private boolean focusFallbackUsed = false;
    private int lockReason = LOCK_NONE;

    private Stats stats;
    private int watchBuffer = 0;
    private long lastReportCheckDay = 0;
    private int bankCheckTick = 0;
    private final Button[] btns = new Button[4];

    /** 上一次 tick 的时刻。用来算真实过去了多少秒，见 tickAddSec 的注释 */
    private long lastTickE = 0;
    private long lastTickU = 0;

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        stats = new Stats(this);
        bank = new QuestionBank();
        sharedBank = bank;
        // 一个年级一个学期的题库几百 KB，解析要几百毫秒，放后台加载，别卡住服务启动
        final int grade = prefs.hasGrade() ? prefs.getGrade() : 1;
        final String term = prefs.getTerm();
        new Thread(new Runnable() {
            @Override
            public void run() {
                bank.load(GuardService.this, grade, term);
                bank.restoreRound(prefs);
            }
        }, "bank-load").start();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        // 每次启动都把设备所有者能给的权限重新落一遍。
        // 系统升级、恢复出厂、家长误点，都可能让其中某一项掉回未授予状态，
        // 而家长不会知道，只会发现「又不弹了」。这些调用很便宜，重复做没有副作用。
        OwnerGuard.applyPolicies(this);
        inst = this;
        startAsForeground();
        handler.postDelayed(ticker, 1000);
        // 电视系统会把后台进程冻起来，冻结期间 handler 完全不走。
        // 光靠 handler 醒来时补时还不够，因为可能一冻就是半小时。
        // 这里再挂一个系统闹钟，每分钟把服务叫醒一次，醒了就按真实时间补上。
        armBeat();

        Diag.log(this, "服务", "守护服务启动，悬浮窗权限=" + (canOverlay() ? "有" : "没有")
                + "，设备所有者=" + (OwnerGuard.isDeviceOwner(this) ? "是" : "否")
                + "，间隔=" + prefs.getIntervalMin() + "分钟"
                + "，今日已用=" + prefs.getTodayUsedSec() / 60 + "分钟");
    }

    /** 设置页「立即弹一道题试试」用的 action，把计时器直接推到满格 */
    public static final String ACTION_TRY = "com.tvguard.TRY_QUIZ";

    /** 系统闹钟每分钟叫醒服务用的 action */
    public static final String ACTION_BEAT = "com.tvguard.BEAT";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_BEAT.equals(intent.getAction()) && handler != null) {
            // 被叫醒了就立刻补一次，不用等下一个 1 秒
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
        // 家长想看效果，不用真等十分钟
        if (intent != null && ACTION_TRY.equals(intent.getAction()) && prefs != null) {
            try {
                // 清掉失败节流，让测试按钮立刻生效，不用干等八秒
                lastAttemptMs = 0;
                attemptFail = 0;

                // 这个标记必须置上。下面心跳里有一条「家长在设置页就什么都不做」，
                // 而「立即弹一道题」恰恰就是家长在设置页点的，不开口子这个按钮会变成死的。
                manualTry = true;

                // 手动试弹的时候，答完应该回设置页继续调参数，
                // 而不是回上一次孩子看的那个视频。把自己的包名记成目标就行，
                // 答题页那边看到目标是本应用，就只关自己不乱跳。
                pkgBeforeQuiz = getPackageName();

                prefs.setSinceLastSec(prefs.getIntervalMin() * 60);
                Diag.log(this, "弹题", "家长点了「立即弹一道题」，权限=" + (canOverlay() ? "有" : "没有"));
            } catch (Throwable ignored) {
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Diag.log(this, "服务", "守护服务被销毁");
        // 把内存里还没落盘的计时刻度写下去，否则最多丢 15 秒
        try {
            if (prefs != null) prefs.flushCounters();
        } catch (Throwable ignored) {
        }
        if (inst == this) inst = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        disarmBeat();
        removeQuiz();
        removeLock();
        super.onDestroy();
    }

    // ================= 计时主循环 =================

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 1000);
            // 闹钟是一次性的，每次 tick 顺手续下一个
            armBeat();
        }
    };

    private void tick() {
        // 先算真实过去了多少秒，再决定这一切片该记多久。
        //
        // 电视系统会把后台进程冻起来，冻结期间这里一秒都不跑。
        // 以前按「跑一次 +1 秒」算，被冻住的时间就彻底丢了，
        // 家长看到的就是「只有在答题锁自己的界面上才会计时」。
        // 现在按墙钟差值补，冻了多久补多久。
        //
        // 关键细节：不能用 elapsedRealtime 单算。这个时钟连深度睡眠一起算，
        // 电视关了半小时再开，会一次性补上半小时。所以取 elapsedRealtime 与
        // uptimeMillis 的较小值：深度睡眠时 uptimeMillis 不走，自然就被扣掉了。
        long nowE = android.os.SystemClock.elapsedRealtime();
        long nowU = android.os.SystemClock.uptimeMillis();
        int delta = 1;
        if (lastTickE > 0) {
            long raw = nowE - lastTickE;
            long awake = nowU - lastTickU;
            long d = Math.min(raw, awake);
            delta = (int) (d / 1000);
            if (delta < 1) delta = 1;
            // 单片最多补 10 分钟，防止系统时钟异常时一把冲到天亮
            if (delta > 600) delta = 600;
        }
        lastTickE = nowE;
        lastTickU = nowU;
        prefs.beat();

        if (!prefs.isEnabled()) {
            removeQuiz();
            removeLock();
            lockReason = LOCK_NONE;
            return;
        }

        // 第一件事：核对遮罩是不是还挂在屏幕上。
        // 必须放在最前面，因为下面每条分支都会因为 quizShowing / lockShowing 提前 return，
        // 状态一旦卡死，后面的检查永远轮不到执行。
        // 切小窗、切信号源、改分辨率、进出画中画，都可能让 WindowManager 把窗口扔掉，
        // 而这两个布尔值还停在 true。老代码的后果：计时停住、题永远不再弹、锁也锁不住。
        verifyOverlayAlive();

        // 0. 家长正在操作自己的界面（设置、选年级、诊断、报告……）时，整个心跳什么都不做。
        //
        //    为什么排在最前面，连锁定检查都让路：家长调参数、改密码、看诊断，
        //    一待就是好几分钟，这段时间有两件坏事会发生。
        //    一、它被算进孩子的观看时间，家长改个设置就把额度耗掉一截；
        //    二、满一个周期后一道题压到设置页上面，家长被自己的软件锁在外面，
        //        还得先答一道三年级的题才能继续改参数。
        //    界面判断由 TvGuardApp 统一维护，答题页、锁定页、桌面都刻意不在其中，
        //    所以这条不会把守护逻辑本身关掉。家长一离开设置页，下一个心跳就恢复。
        //    唯一例外是家长在设置页点「立即弹一道题」，那一次要放行。
        if (!manualTry && TvGuardApp.parentUiShowing()) return;

        // 全屏锁定页同样可能被系统回收而不走 onDestroy，标志卡住就再也不拉了
        if (lockAsActivity && !LockActivity.isShowing()
                && android.os.SystemClock.elapsedRealtime() - lastLockLaunchMs > 5000) {
            lockAsActivity = false;
        }

        // 1. 连错锁定优先
        if (prefs.isLocked()) {
            if (deskMode()) {
                // 桌面接管模式：不用挂任何浮窗，把人送回桌面就行，桌面上会显示锁定告示
                if (!HomeActivity.alive) kickToHome();
                return;
            }
            if (!quizShowing) {
                lockReason = LOCK_WRONG;
                showLock("先休息一会", "连续答错次数太多，暂时不能继续看");
            }
            updateLockCountdown();
            reassertFocus(false);
            return;
        }
        if (lockShowing && lockReason == LOCK_WRONG) {
            removeLock();
            lockReason = LOCK_NONE;
        }

        // 2. 每日总量
        int used = prefs.getTodayUsedSec();
        int limit = prefs.getDailyLimitMin() * 60;
        if (used >= limit) {
            if (deskMode()) {
                if (!HomeActivity.alive) kickToHome();
                return;
            }
            if (!quizShowing) {
                lockReason = LOCK_DAILY;
                showLock("今天的看电视时间已用完", "连续看了 " + fmtMin(used) + "，明天再来吧");
            }
            updateLockCountdown();
            reassertFocus(false);
            return;
        }
        if (lockShowing && lockReason == LOCK_DAILY) {
            removeLock();
            lockReason = LOCK_NONE;
        }

        // 3. 熄屏不计时
        if (!isScreenOn()) return;

        // 4. 答题期间不计时。
        //    全屏答题页不走 quizShowing 这个标志（那是给悬浮窗用的），
        //    少了这一句，孩子答题的几十秒会照常累加，而且满一个周期会再压一道题上来。
        //
        //    注意用的是 isShowing() 而不是 alive：alive 是个静态标记，
        //    Activity 在 onCreate 里崩掉不会走 onDestroy，它会永久留在 true。
        //    v2.6 就是被这个假标记卡死的：服务以为题一直挂着，于是计时不走、题再也不弹。
        //    isShowing() 带 4 秒有效期，僵尸状态自己会过期。
        if (quizShowing || QuizActivity.isShowing()) {
            reassertFocus(true);
            return;
        }


        // 每 30 秒核对一次年级设置，家长中途改了年级要立刻生效
        if (++bankCheckTick >= 30) {
            bankCheckTick = 0;
            final int g = prefs.hasGrade() ? prefs.getGrade() : 1;
            final String t = prefs.getTerm();
            if (bank.getLoadedGrade() != g || !t.equals(bank.getLoadedTerm())) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        bank.load(GuardService.this, g, t);
                    }
                }, "bank-reload").start();
            }
        }

        prefs.tickAddSec(delta);
        watchBuffer += delta;
        if (watchBuffer >= 30) {
            if (stats != null) stats.addWatch(watchBuffer);
            watchBuffer = 0;
        }
        checkPeriodicReport();

        int intervalSec = prefs.getIntervalMin() * 60;
        if (prefs.getSinceLastSec() >= intervalSec) {
            // 接管了桌面的话，优先走「把人送回去」这条路。
            // 它比弹浮窗干净得多：不需要悬浮窗权限、不需要设备所有者、
            // 也不会出现「窗口画出来了但吃不到按键」的烂摊子。
            // 桌面在前台时什么都不用做，它自己的 onResume 会拦。
            if (deskMode()) {
                if (!HomeActivity.alive) kickToHome();
                return;
            }
            attemptQuiz(intervalSec);
        }
    }

    // ------------------------------------------------------------ 桌面接管模式

    /**
     * 是不是已经由答题锁接管了桌面，并且有能力把人送回去。
     *
     * 两个条件缺一不可：
     *   一、默认桌面是答题锁。不是的话按 HOME 会回到电视自己的主页，白按。
     *   二、无障碍服务在跑。它是唯一能在别的应用前台时按下 HOME 的角色。
     *
     * 这两样都可以在电视上点出来，不用电脑。这就是这一版的意义所在。
     */
    private boolean deskMode() {
        if (prefs == null) return false;
        return HomeActivity.isDefaultHome(this) && A11y.ready();
    }

    /**
     * 把正在看视频的孩子送回桌面。
     *
     * 桌面回去之后会自己发现「到点了」，于是弹题。答对才能再点开应用。
     * 整个过程孩子能做的最多是再点一次应用，而点一次就得答一次。
     */
    private void kickToHome() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastKickMs < 3000) return;
        lastKickMs = now;
        boolean ok = A11y.goHome();
        if (ok) {
            prefs.setLastPop("到点了，已把孩子送回桌面答题");
            Diag.log(this, "桌面", "到点，按 HOME 送回桌面（原来在 "
                    + emptyDash(A11y.frontPkg) + "）");
        } else {
            Diag.log(this, "桌面", "按 HOME 失败，无障碍服务可能刚断开");
        }
    }

    /**
     * 无障碍服务报告「前台换成了某个应用」。
     *
     * 这是补 tick 的漏：孩子可能刚好在两次心跳之间点开一个新应用，
     * 那一刻就该拦住，而不是等最多一秒。
     */
    public void onFrontApp(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            if (pkg.equals(getPackageName())) return;   // 自己在前台，正常

            // 孩子在用什么，随时记下来。答对之后要靠它把人送回去。
            // 放在 deskMode 判断之前，因为这台电视没有桌面接管能力，
            // 一旦放到后面，这个字段永远是空的，答完就回不去。
            rememberFront(pkg);

            if (!prefs.isEnabled()) return;
            if (!deskMode()) return;

            boolean over = prefs.getTodayUsedSec() >= prefs.getDailyLimitMin() * 60;
            boolean due = prefs.getSinceLastSec() >= prefs.getIntervalMin() * 60;
            if (prefs.isLocked() || over || due) {
                kickToHome();
            }
        } catch (Throwable ignored) {
        }
    }

    private String emptyDash(String s) {
        return (s == null || s.isEmpty()) ? "未知" : s;
    }

    /**
     * 记住「孩子在用的应用」。
     *
     * <p>只记非本应用、非桌面的包名。桌面记下来是有害的：
     * 答对之后会把人送回主页，孩子还得再点一次应用，等于白答一道题。
     * 碰到桌面时宁可保留上一次的视频应用，答完接着看。
     */
    private void rememberFront(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(getPackageName())) return;
        if (isHomePkg(pkg)) return;
        pkgBeforeQuiz = pkg;
    }

    /** 这个包是不是系统桌面。用 resolveActivity 问系统，不猜包名 */
    private boolean isHomePkg(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, 0);
            return r != null && r.activityInfo != null
                    && pkg.equals(r.activityInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return pm.isInteractive();
            }
            //noinspection deprecation
            return pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    // ================= 弹题尝试 =================

    /** 上次尝试弹题的时刻，用来给失败重试做节流，避免每秒 inflate 一次布局 */
    private long lastAttemptMs = 0;
    /** 连续失败次数。只用来写日志，不会再「放弃」 */
    private int attemptFail = 0;
    /** 遮罩挂上的时刻，用来给「是否被系统摘掉」的检查留出缓冲 */
    private long quizShownAt = 0;
    private long lockShownAt = 0;
    /** 这一轮弹题试过浮窗、试过全屏没有。两个都试完才认输，不然会来回循环 */
    private boolean overlayTried = false;
    private boolean fullscreenTried = false;

    /**
     * 到点了，该弹题了。
     *
     * v3.0 的顺序反过来了：**先试浮窗，浮窗不行才拉全屏页**。三条理由，都是真机上撞出来的：
     *
     * 一、挂窗不需要「从后台启动界面」的许可。全屏页靠 startActivity，
     *    Android 10 起系统会直接拦掉，既不抛异常也不打日志，静默失败。
     *    这就是「家长开着 B 站看视频，一次都不弹」的真正原因。
     * 二、浮窗是独立窗口，不跟着任务栈走。孩子按 HOME，桌面只是露在浮窗下面，
     *    题还盖在最上层，遥控器按什么都没用。这比全屏页闪一下再被拉回更硬。
     * 三、华为智慧屏的设置里根本没有「悬浮窗」这一项，普通悬浮窗挂不上去。
     *    但无障碍浮窗（TYPE_ACCESSIBILITY_OVERLAY）不需要那个权限，
     *    只要家长在无障碍里打开答题锁，这条路就通了。
     *
     * 浮窗挂上之后还要回查它到底有没有吃到遥控器焦点，吃不到就降级到全屏页。
     */
    private void attemptQuiz(final int intervalSec) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAttemptMs < 6000) return;
        lastAttemptMs = now;
        // 手动试弹的口子只开一次，用完就关
        manualTry = false;
        focusFallbackUsed = false;
        overlayTried = false;
        fullscreenTried = false;

        // 先记下孩子在看什么，再弹题。顺序不能反：题一旦压上去，
        // 无障碍报上来的前台包名就变成我们自己的了，那时再记等于没记。
        rememberFront(A11y.frontPkg);

        // 拿到设备所有者之后，顺序反过来：先拉全屏页，浮窗只当兜底。
        // 两个理由：
        //   一、设备所有者不受「后台不能起界面」的限制，这一页能真的压到 B 站上面；
        //   二、全屏页能进真锁任务，孩子按桌面键、返回键、最近任务键全部无效，
        //       而浮窗永远做不到这件事（它只是个窗口，挡不住按键）。
        // 没拿到这个身份时下面的浮窗优先逻辑不变。
        if (Kiosk.isStrict(this) && canOverlay()) {
            Diag.log(this, "弹题", "设备所有者：先拉全屏页，可进真锁任务");
            fullscreenTried = true;
            launchFullscreen(intervalSec);
            return;
        }

        final int type = overlayType();
        if (type != -1) {
            overlayTried = true;
            if (showOverlayQuiz()) {
                prefs.setSinceLastSec(0);
                attemptFail = 0;
                prefs.setLastPop("浮窗已盖在屏幕上，通道：" + channelName(type));
                Diag.log(this, "弹题", "浮窗已挂上，通道=" + channelName(type));
                checkOverlayFocusOnce(intervalSec);
                return;
            }
            Diag.log(this, "弹题", "浮窗没挂上（通道=" + channelName(type) + "），改走全屏页");
        } else {
            Diag.log(this, "弹题", "没有任何浮窗通道：既没悬浮窗权限，无障碍也没开");
        }
        fullscreenTried = true;
        launchFullscreen(intervalSec);
    }

    /**
     * 浮窗挂上之后，确认它真的吃到了遥控器焦点。
     *
     * 无障碍浮窗在个别电视上会「画得出来但吃不掉按键」，那种状态下孩子按 OK 没反应，
     * 题永远答不完，比不弹还糟。2.5 秒还没有焦点就改走全屏页，一次弹题只降级一次，不来回抖。
     */
    private void checkOverlayFocusOnce(final int intervalSec) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!quizShowing || quizView == null) return;
                if (quizView.hasWindowFocus()) return;

                // 无障碍服务在跑的话，不需要降级。
                // 它的按键过滤能把遥控器的方向键、确认键直接转给浮窗里的选项，
                // 这是专门为「浮窗画得出来、吃不掉按键」这种情况留的路。
                // 以前这里一律降级到全屏，结果全屏又被系统拦，两头都不成。
                if (A11y.ready()) {
                    Diag.log(GuardService.this, "弹题", "浮窗没焦点，遥控器改由无障碍转接");
                    return;
                }

                if (focusFallbackUsed || fullscreenTried) return;
                focusFallbackUsed = true;
                fullscreenTried = true;
                Diag.log(GuardService.this, "弹题", "浮窗在，但吃不到遥控器焦点，改全屏页");
                removeQuiz();
                launchFullscreen(intervalSec);
            }
        }, 2500);
    }

    /** 走全屏答题页，1.2 秒 + 1 秒两次回查它到底到没到前台 */
    private void launchFullscreen(final int intervalSec) {
        Diag.log(this, "弹题", "拉起全屏答题页");
        try {
            startActivity(QuizActivity.makeIntent(this));
        } catch (Throwable t) {
            onQuizAttemptFailed(intervalSec, "全屏页启动抛异常 " + t.getClass().getSimpleName());
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                onFullscreenChecked(intervalSec, 0);
            }
        }, 1200);
    }

    private void onFullscreenChecked(final int intervalSec, int round) {
        if (QuizActivity.isShowing()) {
            prefs.setSinceLastSec(0);
            prefs.setFullscreenOk(true);
            attemptFail = 0;
            prefs.setLastPop("全屏答题页，已显示");
            Diag.log(this, "弹题", "全屏答题页已显示");
            return;
        }
        // 页面可能只是慢，再给一秒
        if (round < 1) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    onFullscreenChecked(intervalSec, 1);
                }
            }, 1000);
            return;
        }
        // 页面可能在后台建起来了却永远到不了前台，这种僵尸页顺手收掉，
        // 免得以后家长打开答题锁时突然冒出一道陈年旧题。
        if (QuizActivity.alive) closeFullScreen(QuizActivity.class);
        prefs.setFullscreenOk(false);

        // 全屏这条路走不通，退到浮窗再试一次。
        // 以前这里直接记一次失败就去重试，等于把浮窗这条路丢了，
        // 而浮窗恰恰是很多电视上唯一能显示出来的方式。
        if (!overlayTried && canOverlay() && showOverlayQuiz()) {
            overlayTried = true;
            prefs.setSinceLastSec(0);
            attemptFail = 0;
            prefs.setLastPop("全屏被系统拦了，改用浮窗：" + channelName(overlayType()));
            Diag.log(this, "弹题", "全屏被拦，改用浮窗");
            checkOverlayFocusOnce(intervalSec);
            return;
        }
        onQuizAttemptFailed(intervalSec, "全屏页没到前台，后台起界面被系统拦了");
    }

    /**
     * 弹题连续失败时，在电视上留一条通知。
     *
     * 为什么要有这条：家长最难受的不是「弹不出来」，而是「弹不出来还不知道为什么」。
     * 服务在后台悄悄失败，电视上一点动静都没有，只能靠猜。
     * 这里把原因直接摆到电视的通知栏，点一下还能进设置页去开权限。
     */
    private long lastBlockedNotifyAt = 0;

    private void notifyPopupBlocked(String why) {
        long now = System.currentTimeMillis();
        if (now - lastBlockedNotifyAt < 3600_000L) return;   // 一小时最多烦一次
        lastBlockedNotifyAt = now;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            String chId = "tvguard_alert";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        chId, "答题锁提醒", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, chId);
            } else {
                b = new Notification.Builder(this);
            }
            PendingIntent pi = PendingIntent.getActivity(
                    this, 1, new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setContentTitle("答题锁弹不出题，需要处理")
                    .setContentText(why + "。点这里进设置，看看「兼容性自检」")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTI_ALERT_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private void onQuizAttemptFailed(int intervalSec, String why) {
        attemptFail++;
        // 只记前五次和之后每二十次，免得把日志刷爆
        if (attemptFail <= 5 || attemptFail % 20 == 0) {
            Diag.log(this, "弹题", "第 " + attemptFail + " 次弹题失败：" + why);
        }
        prefs.setLastPop("弹题失败：" + why);
        if (attemptFail >= 3) notifyPopupBlocked(why);
        // 六秒后重试，而不是硬等一整个周期。永远重试，不停手。
        prefs.setSinceLastSec(Math.max(0, intervalSec - 6));
    }

    /**
     * 核对遮罩是不是还挂在屏幕上。
     *
     * 切小窗、切信号源、改分辨率、进出画中画，都可能让 WindowManager 直接把我们的窗口扔掉，
     * 而 quizShowing 这个布尔值还停在 true。老代码的后果是：
     * tick 里看到 quizShowing 就 return，计时停住、题目也永远不再弹，孩子可以白看一整晚。
     * 这是最隐蔽的一个洞，必须每秒核对真实挂载状态。
     */
    private void verifyOverlayAlive() {
        if (quizShowing && quizView != null
                && android.os.SystemClock.elapsedRealtime() - quizShownAt > 2500
                && !quizView.isAttachedToWindow()) {
            Diag.log(this, "浮窗", "答题遮罩被系统摘掉了，重置状态");
            quizShowing = false;
            quizView = null;
            quizCtrl = null;
            // 立刻重新弹，别让孩子白看
            prefs.setSinceLastSec(prefs.getIntervalMin() * 60);
            lastAttemptMs = 0;
        }
        if (lockShowing && lockView != null
                && android.os.SystemClock.elapsedRealtime() - lockShownAt > 2500
                && !lockView.isAttachedToWindow()) {
            Diag.log(this, "浮窗", "锁定遮罩被系统摘掉了，重置状态");
            lockShowing = false;
            lockView = null;
            lockReason = LOCK_NONE;
        }
    }

    // ================= 答题遮罩 =================

    /** 只负责把浮窗挂上去。挂不上返回 false，由调用方决定怎么兜底 */
    private boolean showOverlayQuiz() {
        if (quizShowing) return true;
        final int type = overlayType();
        if (type == -1) return false;
        final WindowManager w = overlayWm(type);
        if (w == null) return false;
        removeLock();
        lockReason = LOCK_NONE;
        reassertCount = 0;
        focusLostCount = 0;
        a11ySel = 0;

        quizView = LayoutInflater.from(this).inflate(R.layout.quiz_overlay, null);
        btns[0] = quizView.findViewById(R.id.opt0);
        btns[1] = quizView.findViewById(R.id.opt1);
        btns[2] = quizView.findViewById(R.id.opt2);
        btns[3] = quizView.findViewById(R.id.opt3);

        // 吃掉返回键，孩子按返回退不掉
        quizView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
            }
        });

        clearSystemUi(quizView);

        quizCtrl = new QuizController(prefs, bank, stats, new QuizController.Host() {
            @Override
            public void onPassed() {
                removeQuiz();
            }

            @Override
            public void onLocked() {
                removeQuiz();
            }

            @Override
            public void post(Runnable r, long delayMs) {
                handler.postDelayed(r, delayMs);
            }
        });
        quizCtrl.bind(quizView);

        quizParams = buildParams();
        try {
            w.addView(quizView, quizParams);
            quizWm = w;
            quizShowing = true;
            quizShownAt = android.os.SystemClock.elapsedRealtime();
            quizView.requestFocus();
            if (btns[0] != null) btns[0].requestFocus();
        } catch (Throwable t) {
            quizShowing = false;
            quizView = null;
            quizCtrl = null;
            // 权限名头有、系统照样拒绝，是定制 ROM 的常见做法。交给调用方退到全屏页
            return false;
        }
        return quizShowing;
    }

    // ================= 无障碍按键转接 =================

    /** 无障碍转接模式下，当前选中的选项序号 */
    private int a11ySel = 0;

    /**
     * 无障碍服务把遥控器按键转进来。
     *
     * 只在「答题浮窗在，但窗口没拿到焦点」时接管，其余一律返回 false 放行。
     * 这是给个别电视留的后路：无障碍浮窗画得出来，却吃不掉遥控器，
     * 没有这一条，孩子会卡在一个按不动的题上，永远答不完。
     */
    public boolean onA11yKey(KeyEvent e) {
        if (!quizShowing || quizView == null) return false;
        if (quizView.hasWindowFocus()) return false;

        final int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            // 抬起事件只在返回键上拦，别的一律放行，免得多吃系统的按键
            return code == KeyEvent.KEYCODE_BACK;
        }

        // 答错之后停在「等孩子点下一题」这一屏。
        // 这时候遥控器只认一个动作：确认 = 下一题。方向键也一并吞掉，
        // 否则会在背后的播放器里乱跳，看起来像题目自己消失了。
        if (quizCtrl != null && quizCtrl.isWaitingNext()) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_UNKNOWN:
                    Diag.log(this, "无障碍", "答错提示由无障碍确认，进下一题");
                    quizCtrl.confirmNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_BACK:
                    return true;
                default:
                    return false;
            }
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveA11ySel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveA11ySel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_UNKNOWN:
                clickA11ySel();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void moveA11ySel(int step) {
        int n = btns.length;
        for (int k = 0; k < n; k++) {
            a11ySel = (a11ySel + step + n) % n;
            if (btns[a11ySel] != null && btns[a11ySel].getVisibility() == View.VISIBLE) break;
        }
        if (btns[a11ySel] != null) btns[a11ySel].requestFocus();
        Diag.log(this, "无障碍", "浮窗没焦点，按键由无障碍转接，选中第 " + (a11ySel + 1) + " 项");
    }

    private void clickA11ySel() {
        Button b = btns[a11ySel];
        if (b == null || b.getVisibility() != View.VISIBLE) b = btns[0];
        if (b == null) return;
        Diag.log(this, "无障碍", "选项由无障碍确认");
        b.performClick();
    }

    /**
     * 能不能挂浮窗。
     *
     * v3.0 起这个判断有两级，因为「悬浮窗权限」在部分电视上压根不存在：
     *   一、有 SYSTEM_ALERT_WINDOW 权限 → TYPE_APPLICATION_OVERLAY
     *   二、没有那个权限，但无障碍服务开着 → TYPE_ACCESSIBILITY_OVERLAY
     *       这一条是华为智慧屏这类电视唯一能走通的路，它不需要任何权限弹窗。
     */
    private boolean canOverlay() {
        return overlayType() != -1;
    }

    /** 返回该用哪种窗口类型，-1 表示两条通道都不通 */
    private int overlayType() {
        try {
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(this);
            if (granted) {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
            }
        } catch (Throwable ignored) {
        }
        if (A11y.ready()) return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        return -1;
    }

    /** 取对应通道的 WindowManager。无障碍那块必须用服务自己的，别处拿的会被系统拒 */
    private WindowManager overlayWm(int type) {
        if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            WindowManager a = A11y.wm();
            if (a != null) return a;
        }
        return wm;
    }

    /** 通道的中文名，只用来写日志和给家长看 */
    private String channelName(int type) {
        if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return "无障碍浮窗";
        if (type == -1) return "无";
        return "悬浮窗权限";
    }

    private void removeQuiz() {
        if (quizView != null && quizShowing) {
            try {
                (quizWm != null ? quizWm : wm).removeView(quizView);
            } catch (Throwable ignored) {
            }
        }
        quizWm = null;
        quizShowing = false;
        quizShownAt = 0;
        quizView = null;
        quizCtrl = null;
        if (QuizActivity.isShowing()) closeFullScreen(QuizActivity.class);
    }

    /**
     * 关掉可能还开着的全屏页。
     * 跨界面收摊只能靠再发一次 Intent 走 onNewIntent，Activity 自己认这个旗标。
     */
    private void closeFullScreen(Class<?> cls) {
        try {
            Intent i = new Intent(this, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra(EXTRA_FINISH, true);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 让全屏页自己收摊的旗标 */
    public static final String EXTRA_FINISH = "com.tvguard.FINISH";


    // ================= 锁定界面 =================

    /** 当前锁定是不是由全屏锁定页承载（而不是悬浮窗） */
    private boolean lockAsActivity = false;
    /** 上次拉起全屏锁定页的时刻。tick 每秒都会走到这里，必须节流 */
    private long lastLockLaunchMs = 0;

    /**
     * 锁定同样先走全屏页。
     * 时间用完这种锁定一锁就是大半天，如果只是挂个悬浮窗，
     * 孩子按一下桌面键照样去看别的，等于没锁。
     */
    private void showLock(final String title, final String desc) {
        if (lockShowing || lockAsActivity) return;
        reassertCount = 0;
        focusLostCount = 0;

        // 和弹题一个道理：浮窗优先。
        // 全屏锁定页同样靠 startActivity，后台一样会被系统拦，
        // 拦掉之后「今日时长用完」就完全不生效了，孩子照样能看。
        if (canOverlay() && showOverlayLock(title, desc)) {
            Diag.log(this, "锁定", "浮窗锁定已挂上，通道=" + channelName(overlayType()));
            return;
        }

        lockAsActivity = true;
        lastLockLaunchMs = android.os.SystemClock.elapsedRealtime();
        try {
            int reason = lockReason == LOCK_DAILY
                    ? LockActivity.REASON_DAILY : LockActivity.REASON_WRONG;
            startActivity(LockActivity.makeIntent(this, title, desc, reason));
        } catch (Throwable t) {
            Diag.log(this, "锁定", "全屏锁定页启动抛异常 " + t.getClass().getSimpleName());
            fallbackLockOverlay(title, desc);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (LockActivity.isShowing()) {
                    Diag.log(GuardService.this, "锁定", "全屏锁定页已显示");
                } else {
                    fallbackLockOverlay(title, desc);
                }
            }
        }, 1400);
    }

    /** 全屏锁定页起不来，退悬浮窗（拦不住桌面键，但至少看得见） */
    private void fallbackLockOverlay(String title, String desc) {
        lockAsActivity = false;
        if (!canOverlay()) {
            Diag.log(this, "锁定", "锁定页没起来，也没有悬浮窗权限，只能等下次重试");
            return;
        }
        if (showOverlayLock(title, desc)) {
            Diag.log(this, "锁定", "改用悬浮窗锁定（此模式下桌面键拦不住）");
        }
    }

    private boolean showOverlayLock(String title, String desc) {
        if (lockShowing) return true;
        final int type = overlayType();
        if (type == -1) return false;
        final WindowManager w = overlayWm(type);
        if (w == null) return false;
        lockView = LayoutInflater.from(this).inflate(R.layout.lock_overlay, null);
        ((TextView) lockView.findViewById(R.id.tvLockTitle)).setText(title);
        ((TextView) lockView.findViewById(R.id.tvLockDesc)).setText(desc);
        bindLockUnlockButton(lockView, title, desc);
        clearSystemUi(lockView);
        lockParams = buildParams();
        try {
            w.addView(lockView, lockParams);
            lockWm = w;
            lockShowing = true;
            lockShownAt = android.os.SystemClock.elapsedRealtime();
            lockView.requestFocus();
            return true;
        } catch (Throwable t) {
            lockShowing = false;
            lockView = null;
            return false;
        }
    }

    private void updateLockCountdown() {
        if (!lockShowing || lockView == null) return;
        TextView tv = lockView.findViewById(R.id.tvCountdown);
        if (tv == null) return;

        if (lockReason == LOCK_WRONG) {
            long sec = prefs.lockRemainSec();
            tv.setText(fmtClock(sec));
        } else if (lockReason == LOCK_DAILY) {
            tv.setText("明日 0 点重置");
        }
    }

    private void removeLock() {
        removeLock(true);
    }

    /**
     * @param closeActivity 要不要顺手把全屏锁定页也关掉。
     *                      家长从锁定页解锁时传 false：那一页自己会 finish，
     *                      这里再发一次 Intent 会多一次重建，屏幕上会闪一下。
     */
    private void removeLock(boolean closeActivity) {
        if (lockView != null && lockShowing) {
            try {
                (lockWm != null ? lockWm : wm).removeView(lockView);
            } catch (Throwable ignored) {
            }
        }
        lockWm = null;
        lockShowing = false;
        lockShownAt = 0;
        lockView = null;
        lockAsActivity = false;
        if (closeActivity && LockActivity.isShowing()) closeFullScreen(LockActivity.class);
    }

    /**
     * 浮窗锁定页上的「家长解锁」按钮。
     *
     * 浮窗里没有密码键盘，所以这里只做一件事：把家长送到全屏锁定页，
     * 那里有完整的密码框。孩子点也一样，进去要密码，无害。
     */
    private void bindLockUnlockButton(View lockRoot, final String title, final String desc) {
        View unlock = lockRoot.findViewById(R.id.btnParentUnlock);
        if (unlock == null) return;
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Diag.log(GuardService.this, "锁定", "家长点了浮窗上的解锁，转到全屏锁定页输密码");
                int reason = lockReason == LOCK_DAILY
                        ? LockActivity.REASON_DAILY : LockActivity.REASON_WRONG;
                removeLock();
                // removeLock 会把 lockAsActivity 清掉，所以必须放在它后面
                lockAsActivity = true;
                lastLockLaunchMs = android.os.SystemClock.elapsedRealtime();
                try {
                    startActivity(LockActivity.makeIntent(GuardService.this, title, desc, reason));
                } catch (Throwable ignored) {
                    lockAsActivity = false;
                }
            }
        });
    }

    /**
     * 家长在锁定页输对了密码。
     *
     * 这是「输了密码还在锁屏」的死结所在：以前只关掉了界面，没有动锁定状态，
     * 守护服务下一秒读到 lockUntil 还在、或者今日用量还超着，就又把锁定页拉了回来。
     * 所以必须把状态本身清掉，而且只在这里清，别的地方不要重复写。
     */
    public void releaseLockByParent() {
        try {
            prefs.setLockUntil(0);
            prefs.setWrongCount(0);
            prefs.setStreakCount(0);
            prefs.setSinceLastSec(0);
            // 今天的时间已经用完的那种锁，光清锁定没用，一秒后又会超限。
            // 退 30 分钟回去当缓冲，家长才有时间真的把电视用起来。
            int limit = prefs.getDailyLimitMin() * 60;
            if (prefs.getTodayUsedSec() >= limit) {
                prefs.relieveDailySec(30 * 60);
                Diag.log(this, "锁定", "今日时长已用完，家长解锁后放宽 30 分钟");
            }
            lockReason = LOCK_NONE;
            removeLock(false);
            Diag.log(this, "锁定", "家长密码正确，本轮锁定已解除");
        } catch (Throwable ignored) {
        }
    }

    // ================= 焦点夺回 =================

    /**
     * 孩子最常干的事就是按 HOME 键回桌面。
     * 按 HOME 后系统会让桌面回到前台，全屏遮罩虽然还在最上层（系统级悬浮窗），
     * 但会丢掉窗口焦点，遥控器方向键就作用不到答题按钮上，看起来像卡死了。
     *
     * 这里每秒检查一次焦点，连续两次拿不到就重新挂载窗口把焦点抢回来。
     * 这个做法参考了 kiosk 类应用和 TvLockO 的思路：不跟系统抢 HOME 事件
     * （第三方 App 根本抢不到），而是抢完焦点后就一直在最上层。
     */
    private int focusLostCount = 0;
    private int reassertCount = 0;
    private long lastReassertAt = 0;

    private void reassertFocus(boolean isQuiz) {
        View v = isQuiz ? quizView : lockView;
        boolean showing = isQuiz ? quizShowing : lockShowing;
        if (!showing || v == null) {
            focusLostCount = 0;
            return;
        }
        if (v.hasWindowFocus()) {
            focusLostCount = 0;
            reassertCount = 0;
            return;
        }

        // 以前这里最多只重挂 6 次就放弃，理由是「有些电视的浮窗永远不报告焦点，
        // 无限重挂会一直闪屏」。结果孩子按十来次桌面键就把额度用完了，之后随便走。
        // 改成「不放弃，但越试越慢」：前三次 1.2 秒一次，之后 2 秒，再往后 4 秒。
        // 真的拿不到焦点的 ROM 最多每 4 秒闪一下，不会糊屏；能拿到的机器永远抢得回来。
        long now = android.os.SystemClock.elapsedRealtime();
        long gap = reassertCount < 3 ? 1200 : reassertCount < 10 ? 2000 : 4000;
        if (now - lastReassertAt < gap) return;

        // 给系统一次正常切换的机会，避免刚弹出就被误判
        focusLostCount++;
        if (focusLostCount < 2) return;
        focusLostCount = 0;
        lastReassertAt = now;
        reassertCount++;

        final WindowManager w = isQuiz
                ? (quizWm != null ? quizWm : wm)
                : (lockWm != null ? lockWm : wm);
        try {
            w.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
        try {
            if (isQuiz) {
                w.addView(v, quizParams);
                v.requestFocus();
                if (btns[0] != null && btns[0].getVisibility() == View.VISIBLE) {
                    btns[0].requestFocus();
                }
            } else {
                w.addView(v, lockParams);
                v.requestFocus();
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 通用 =================

    private void clearSystemUi(View v) {
        v.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private WindowManager.LayoutParams buildParams() {
        int t = overlayType();
        if (t == -1) t = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        final int type = t;
        // 注意：不能带 FLAG_NOT_FOCUSABLE，否则遥控器按键无法作用到遮罩上
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    private String fmtMin(int sec) {
        int m = sec / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分钟";
    }

    private String fmtClock(long sec) {
        long m = sec / 60;
        long s = sec % 60;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", m, s);
    }

    // ================= 定时唤醒（抗冻结） =================

    private PendingIntent beatPi() {
        Intent i = new Intent(this, GuardService.class);
        i.setAction(ACTION_BEAT);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        // O 起后台不能再随便 startService，闹钟拉起服务要写成 getForegroundService，
        // 否则进程被杀之后闹钟只会静默失败，看起来又变成「怎么都不弹」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, 90, i, f);
        }
        return PendingIntent.getService(this, 90, i, f);
    }

    private void armBeat() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = android.os.SystemClock.elapsedRealtime() + 60_000;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, beatPi());
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, beatPi());
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, beatPi());
            } catch (Throwable ignored) {
            }
        }
    }

    private void disarmBeat() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(beatPi());
        } catch (Throwable ignored) {
        }
    }

    // ================= 前台通知 =================

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "答题锁", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        b.setContentTitle("答题锁运行中")
                .setContentText("每 " + prefs.getIntervalMin() + " 分钟弹题，连对 "
                        + prefs.getStreakRequired() + " 题才能继续")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi)
                .setOngoing(true);

        startForeground(NOTI_ID, b.build());
    }

    // ================= 周期报告提醒 =================
    // 每周一提醒上周报告，每月 1 号提醒上月报告。
    // 不打扰人：一天最多检查一次，同一个周期只提醒一次，记录在 Prefs 里。

    private void checkPeriodicReport() {
        long now = System.currentTimeMillis();
        if (now - lastReportCheckDay < 3600_000L) return;
        lastReportCheckDay = now;

        java.util.Calendar c = java.util.Calendar.getInstance();
        String key = null;
        String title = null;

        if (c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY) {
            int week = c.get(java.util.Calendar.WEEK_OF_YEAR);
            int year = c.get(java.util.Calendar.YEAR);
            key = "w" + year + "-" + week;
            title = "上周学习报告已生成";
        } else if (c.get(java.util.Calendar.DAY_OF_MONTH) == 1) {
            int month = c.get(java.util.Calendar.MONTH) + 1;
            key = "m" + c.get(java.util.Calendar.YEAR) + "-" + month;
            title = "上月学习报告已生成";
        }

        if (key == null || key.equals(prefs.getReportKey())) return;
        prefs.setReportKey(key);
        notifyReport(title);
    }

    private void notifyReport(String title) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CH_REPORT, "答题锁报告", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, CH_REPORT);
            } else {
                b = new Notification.Builder(this);
            }

            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, new Intent(this, ReportActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            b.setContentTitle(title)
                    .setContentText("点开查看孩子的观看时长、答题正确率和各科表现")
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTI_REPORT_ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
