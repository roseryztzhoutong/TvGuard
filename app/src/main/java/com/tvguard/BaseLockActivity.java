package com.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 答题页和锁定页的共同底座。
 *
 * 这一版最关键的变化：答题期间把遥控器封死，孩子只能选题，出不去。
 *
 * 三道闸，从强到弱：
 *   一、锁任务模式（Kiosk.enter）：设备所有者时 HOME、返回、最近任务全部失效；
 *   二、按键白名单：除方向键和确认键，其余按键在 dispatchKeyEvent 里直接吃掉，
 *       连 View 都收不到。返回键、菜单键、音量键、媒体键一并吞掉；
 *   三、失焦拉回（watchdog）：万一前两道都没挡住（孩子按了 HOME 跑到桌面），
 *       700 毫秒内检测到没焦点就把自己重新拉到前台。
 *
 * 家长不是被封在里面：长按遥控器「菜单」键 1.2 秒（或「设置」键）会弹出密码框，
 * 输对了才放行。密码错照样罚等，和设置页的规则一致。
 */
public abstract class BaseLockActivity extends Activity {

    protected Handler handler;
    protected Prefs prefs;

    private boolean kioskOn = false;
    private boolean resumed = false;
    /**
     * 这个界面有没有真正到过一次前台。
     *
     * 拉回逻辑的守卫必须用它，不能用 resumed：
     * 按 HOME 的时候，系统会先给 onPause（resumed 变 false），再发 onUserLeaveHint。
     * 如果拿 resumed 当条件，按键那一刻守卫正好是关的，拉回代码一次都不会执行。
     * v2.7 就是这样，家长按下桌面键，界面老老实实就没了。
     */
    private boolean everFront = false;
    private long menuDownAt = 0;
    private long backDownAt = 0;
    private long lastRelaunchAt = 0;
    private long onCreateAt = 0;

    /**
     * 家长已经验过密码，这一页马上要退了。
     *
     * 置上之后所有「失焦就拉回」的逻辑立即停手。
     * 不封这个口子的话：密码验完先 startActivity 去设置页，
     * 这时本页失去焦点，watchdog 以为是孩子逃跑，又把本页拉了回来，
     * 结果就是家长输了密码却依然卡在锁定页，怎么都退不出去。
     */
    protected boolean parentPassing = false;

    // ---- 家长密码浮层 ----
    private boolean gateShowing = false;
    private View gateView = null;
    private TextView tvDots, tvHint;
    private String pinBuf = "";

    /** 子类要显示的布局 */
    protected abstract int contentLayout();

    /** 诊断日志里的标签，比如 答题 / 锁定 */
    protected abstract String logTag();

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        onCreateAt = SystemClock.elapsedRealtime();
        prefs = new Prefs(this);

        // 窗口底色必须是实心的。电视上如果 Activity 起来但首帧没画出来，
        // 家长看到的就是「什么都没弹」，还以为是软件没反应。
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0xFF0B1220));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(contentLayout());
        hideSystemUi();

        handler = new Handler(Looper.getMainLooper());

        // 锁任务这一步必须放到首帧之后。
        // startLockTask 在非设备所有者的机器上会走系统 Pin 流程，个别 ROM 上这一步很慢，
        // 放在 onCreate 里会拖住第一帧，界面迟迟画不出来，看起来就是「根本不弹」。
        // 延后 600 毫秒，先让界面出来，再来收遥控器。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                enterKiosk();
            }
        }, 600);

        handler.postDelayed(watchdog, 800);

        // 部分电视 ROM 按 HOME 时会发这个老广播，能提前一步知道。
        // Android 8 之后系统不再发，但电视固件普遍没跟上，白捡一个信号。
        try {
            registerReceiver(homeWatcher,
                    new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
        } catch (Throwable ignored) {
        }
    }

    /** 试着进锁任务，失败就算了，外层还有按键白名单和失焦拉回 */
    private void enterKiosk() {
        if (isFinishing() || kioskOn) return;
        try {
            kioskOn = Kiosk.enter(this);
        } catch (Throwable ignored) {
            kioskOn = false;
        }
        Diag.log(this, logTag(), "进入锁键界面，锁任务=" + (kioskOn ? "开" : "关")
                + "，设备所有者=" + (OwnerGuard.isDeviceOwner(this) ? "是" : "否"));
    }

    private final android.content.BroadcastReceiver homeWatcher =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String reason = intent.getStringExtra("reason");
                    if ("homekey".equals(reason) || "recentapps".equals(reason)
                            || "globalactions".equals(reason)) {
                        relaunch("系统广播 " + reason);
                    }
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        everFront = true;
        onAliveSignal();
        // onCreate 里没锁住的话，等界面真正可见了再试一次。
        // 个别 ROM 要求 Activity 处于前台才受理锁任务请求。
        if (!kioskOn) handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                enterKiosk();
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // 注意：这里故意什么都不做。
        // 上一版在 onPause 里 120 毫秒后强制把自己拉回前台，结果和系统的页面切换互相打架：
        // 系统正在切，我们同时在抢，抢到最后界面反而没起来。拉回只交给下面三条稳的信号。
    }

    /** 界面确实在前台了（onResume 或拿到焦点），子类用来对外报活 */
    protected void onAliveSignal() {
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 守护服务要收摊时（家长关掉了软件）会再发一次 Intent 让我们自己退
        if (intent != null && intent.getBooleanExtra(GuardService.EXTRA_FINISH, false)) {
            finish();
        }
    }

    // ================= 按键：只放方向键和确认键 =================

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();

        // 密码框打开时按正常界面走，但返回键用来撤销，别让孩子困在密码框里
        if (gateShowing) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideGate();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // 返回键默认是不放行的，但长按 3 秒当作家长的隐藏出口。
        // 有的电视遥控器根本没有「菜单」键，只留一个出口会把自己锁在外面。
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backDownAt = SystemClock.uptimeMillis();
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                long held = SystemClock.uptimeMillis() - backDownAt;
                if (backDownAt > 0 && held >= 3000) showGate();
                backDownAt = 0;
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) menuDownAt = SystemClock.uptimeMillis();
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                long held = SystemClock.uptimeMillis() - menuDownAt;
                if (menuDownAt > 0 && held >= 1200) showGate();
                menuDownAt = 0;
            }
            return true;
        }

        if (isAllowedKey(code)) return super.dispatchKeyEvent(e);

        // 其余全部吃掉：HOME、返回、最近任务、音量、媒体、数字键、搜索…
        return true;
    }

    /**
     * 唯一允许的键。
     *
     * 注意 KEYCODE_UNKNOWN：不少电视遥控器把 OK 键报成 0，不放开的话孩子选不了题。
     * 这个口子放出来也没风险，未知键不会触发任何系统行为。
     */
    private static boolean isAllowedKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        // 故意空实现，交不到系统那里
    }

    // ================= 家长密码 =================

    /**
     * 弹家长密码框。
     *
     * 子类也要能调：锁定页上有一个看得见的「家长解锁」按钮，
     * 点了就走这里。以前只有长按菜单键 / 长按返回键两个隐形手势，
     * 家长根本不知道，只会以为锁死了。
     */
    protected void showGate() {
        if (gateShowing) return;
        if (!prefs.hasPin()) {
            // 理论上不可能（首次引导强制设密码），兜底别把孩子锁死在里面
            onParentPass();
            return;
        }
        gateShowing = true;
        pinBuf = "";

        if (gateView == null) {
            gateView = LayoutInflater.from(this).inflate(R.layout.activity_gate, null);
            TextView title = gateView.findViewById(R.id.tvGateTitle);
            if (title != null) title.setText("家长确认");
            tvHint = gateView.findViewById(R.id.tvPinHint);
            tvDots = gateView.findViewById(R.id.tvPinDots);
            if (tvHint != null) tvHint.setText(gateHint());

            int[] ids = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
                    R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9};
            for (int i = 0; i < ids.length; i++) {
                final String digit = String.valueOf(i);
                View k = gateView.findViewById(ids[i]);
                if (k != null) k.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onGateDigit(digit);
                    }
                });
            }
            View del = gateView.findViewById(R.id.kDel);
            if (del != null) del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (pinBuf.length() > 0) {
                        pinBuf = pinBuf.substring(0, pinBuf.length() - 1);
                        updateDots();
                    }
                }
            });
            View ok = gateView.findViewById(R.id.kOk);
            if (ok != null) ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onGateOk();
                }
            });
            View cancel = gateView.findViewById(R.id.btnGateCancel);
            if (cancel != null) {
                cancel.setVisibility(View.VISIBLE);
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideGate();
                    }
                });
            }
            addContentView(gateView,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }

        gateView.setVisibility(View.VISIBLE);
        updateDots();
        View k1 = gateView.findViewById(R.id.k1);
        if (k1 != null) k1.requestFocus();
    }

    /** 密码框上方那句提示，子类可以改 */
    protected String gateHint() {
        return "输家长密码，确认后回设置页";
    }

    private void hideGate() {
        gateShowing = false;
        pinBuf = "";
        if (gateView != null) gateView.setVisibility(View.GONE);
        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        if (root != null) root.requestFocus();
    }

    private void onGateDigit(String d) {
        if (pinBuf.length() >= 8) return;
        pinBuf += d;
        updateDots();
    }

    private void updateDots() {
        if (tvDots != null) tvDots.setText(pinBuf.replaceAll(".", "● "));
    }

    private void onGateOk() {
        long left = (prefs.getPinLockUntil() - System.currentTimeMillis()) / 1000;
        if (left > 0) {
            hint("先等 " + left + " 秒再试");
            return;
        }
        if (prefs.checkPin(pinBuf)) {
            // 先把拉回逻辑关掉，再去做跳转。顺序不能反，见 parentPassing 的说明。
            parentPassing = true;
            prefs.clearPinFail();
            prefs.setPinLockUntil(0);
            hideGate();
            Diag.log(this, logTag(), "家长密码正确，放行");
            onParentPass();
            return;
        }

        pinBuf = "";
        updateDots();
        int fail = prefs.getPinFail() + 1;
        prefs.setPinFail(fail);
        long waitSec = fail >= 19 ? 300 : fail >= 14 ? 120 : fail >= 9 ? 60 : fail >= 4 ? 30 : 0;
        if (waitSec > 0) {
            prefs.setPinLockUntil(System.currentTimeMillis() + waitSec * 1000L);
            hint("错了，等 " + waitSec + " 秒再试");
        } else {
            hint("密码不对");
        }
    }

    private void hint(String s) {
        if (tvHint != null) tvHint.setText(s);
    }

    /** 家长验完密码要干嘛，子类各自决定 */
    protected void onParentPass() {
        parentPassing = true;
        MainActivity.skipGateOnce = true;
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
        finish();
    }

    // ================= 失焦拉回 =================
    //
    // 华为智慧屏实测：进了屏幕固定，按 HOME 仍然直接回桌面。
    // 系统把 HOME 交给自己的桌面处理，pinning 只挡住普通应用的启动，
    // 挡不住桌面自己。所以这一版不看 kiosk 状态：
    // 只要界面本来在前台、又被切走了，一律拉回来。
    //
    // 但拉回必须克制。上一版每 300 毫秒拉一次、还在 onPause 里拉，
    // 和系统的页面切换互相打架，界面反而起不来。这一版只在三条稳的信号上动手，
    // 两次之间至少隔 700 毫秒。

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            onAliveSignal();
            hideSystemUi();
            return;
        }
        // 起来后的头一秒系统本来就会有一次焦点切换，别当成逃跑
        if (SystemClock.elapsedRealtime() - onCreateAt < 1200) return;
        if (isFinishing() || gateShowing) return;
        if (!isScreenOn()) return;
        // 还没真正到过前台就别抢，那是启动过程，抢了会把它掐死
        if (!everFront) return;
        relaunch("失去焦点");
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 按 HOME 会走这里，这是最及时、最准的一次信号
        if (isFinishing() || gateShowing) return;
        if (!isScreenOn()) return;
        relaunch("按了桌面键");
    }

    /**
     * 页面真的不可见了。
     *
     * 这是按 HOME 之后最可靠的一次信号：onPause 可能只是被对话框盖住，
     * onStop 才代表真的离开了屏幕。在这里补一记拉回，比只靠 onUserLeaveHint 稳，
     * 因为部分电视固件根本不发 onUserLeaveHint。
     */
    @Override
    protected void onStop() {
        super.onStop();
        resumed = false;
        if (isFinishing() || gateShowing) return;
        if (!everFront) return;
        if (!isScreenOn()) return;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || gateShowing || !isScreenOn()) return;
                if (hasWindowFocus()) return;
                relaunch("页面被切到后台");
            }
        }, 300);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                checkStuck();
            } catch (Throwable ignored) {
            }
            if (handler != null) handler.postDelayed(this, 500);
        }
    };

    private void checkStuck() {
        if (isFinishing() || gateShowing) return;
        if (SystemClock.elapsedRealtime() - onCreateAt < 2000) return;
        // 只有「本来在前台、现在还没焦点」才算被切走
        if (!everFront) return;
        if (hasWindowFocus()) {
            onAliveSignal();
            return;
        }
        if (!isScreenOn()) return;
        relaunch("轮询发现没焦点");
    }

    /** 两次拉回之间的最小间隔。太密会跟系统的切屏动画打架，反而拉不回来 */
    private static final long RELAUNCH_GAP_MS = 700;

    private void relaunch(String why) {
        if (isFinishing()) return;
        // 家长已经放行了，别再把这一页抢回前台
        if (parentPassing) return;
        // 刚起来那一秒半不抢。部分电视在页面切换时会顺手给新页面也发一次
        // onUserLeaveHint / 切屏广播，这时候回抢会和系统自己的动画打架，
        // 结果就是页面一直卡在"正在启动"的状态，家长看到的是什么都不弹。
        if (SystemClock.elapsedRealtime() - onCreateAt < 1500) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRelaunchAt < RELAUNCH_GAP_MS) return;
        lastRelaunchAt = now;
        escapeCount++;
        Diag.log(this, logTag(), why + "，拉回第 " + escapeCount + " 次");

        bringSelfToFront(0);
    }

    /** 把自己顶回前台。第一次只发 Intent，200 毫秒后还没回来就再补一记 moveTaskToFront */
    private void bringSelfToFront(final int depth) {
        if (isFinishing()) return;
        try {
            Intent i = new Intent(this, getClass());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Throwable ignored) {
        }
        if (depth >= 1 || handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || hasWindowFocus()) return;
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)
                            getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.moveTaskToFront(getTaskId(),
                                android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 200);
    }

    /** 这次答题期间被抓回来几次，记日志用 */
    private int escapeCount = 0;

    protected boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    // ================= 通用 =================

    protected void hideSystemUi() {
        View root = getWindow().getDecorView();
        root.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onDestroy() {
        Kiosk.exit(this);
        try {
            unregisterReceiver(homeWatcher);
        } catch (Throwable ignored) {
        }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
