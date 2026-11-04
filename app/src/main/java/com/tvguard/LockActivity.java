package com.tvguard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 全屏锁定页。
 *
 * 两种情况会进这里：
 *  1. 连错次数太多，短期罚等；
 *  2. 今天看电视的时间用完了。
 *
 * 和答题页一样吃双减：锁任务模式 + 按键白名单。时间到了自己退，
 * 家长要提前放开就长按菜单键输密码。
 */
public class LockActivity extends BaseLockActivity {

    public static final int REASON_WRONG = 1;
    public static final int REASON_DAILY = 2;

    /** 供 GuardService 判断是否已经有一个锁定页在显示，避免重复拉起 */
    public static volatile boolean alive = false;

    /** 最近一次确实在前台的时刻，给 alive 加有效期，防止僵尸标记把锁定挡住 */
    private static volatile long lastFrontAt = 0;

    public static boolean isShowing() {
        if (!alive) return false;
        long t = lastFrontAt;
        return t > 0 && android.os.SystemClock.elapsedRealtime() - t < 5000;
    }

    @Override
    protected void onAliveSignal() {
        lastFrontAt = android.os.SystemClock.elapsedRealtime();
    }

    private TextView tvCountdown;
    private int reason = REASON_WRONG;

    @Override
    protected int contentLayout() {
        return R.layout.lock_overlay;
    }

    @Override
    protected String logTag() {
        return "锁定";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        lastFrontAt = android.os.SystemClock.elapsedRealtime();

        Intent i = getIntent();
        reason = i == null ? REASON_WRONG : i.getIntExtra("reason", REASON_WRONG);
        String title = i == null ? null : i.getStringExtra("title");
        String desc = i == null ? null : i.getStringExtra("desc");

        TextView tvTitle = findViewById(R.id.tvLockTitle);
        TextView tvDesc = findViewById(R.id.tvLockDesc);
        tvCountdown = findViewById(R.id.tvCountdown);
        if (tvTitle != null && title != null) tvTitle.setText(title);
        if (tvDesc != null && desc != null) tvDesc.setText(desc);

        // 看得见的家长出口。点它要密码，孩子点不开，但家长一眼就知道从哪进。
        View unlock = findViewById(R.id.btnParentUnlock);
        if (unlock != null) {
            unlock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showGate();
                }
            });
            unlock.requestFocus();
        }

        handler.post(ticker);
    }

    @Override
    protected String gateHint() {
        return "输家长密码，确认后立刻解除锁定";
    }

    /** 传参不麻烦，给 GuardService 一个现成的构造方法 */
    public static Intent makeIntent(Context c, String title, String desc, int reason) {
        Intent i = new Intent(c, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.putExtra("title", title);
        i.putExtra("desc", desc);
        i.putExtra("reason", reason);
        return i;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (reason == REASON_WRONG) {
                long sec = prefs.lockRemainSec();
                if (tvCountdown != null) tvCountdown.setText(fmtClock(sec));
                if (sec <= 0) {
                    finish();
                    return;
                }
            } else {
                if (tvCountdown != null) tvCountdown.setText("明日 0 点重置");
                // 跨天了，时长自动清零，锁定页该收
                int used = prefs.getTodayUsedSec();
                int limit = prefs.getDailyLimitMin() * 60;
                if (used < limit) {
                    finish();
                    return;
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private String fmtClock(long sec) {
        if (sec < 0) sec = 0;
        long m = sec / 60;
        long s = sec % 60;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", m, s);
    }

    /**
     * 家长验完密码：把锁定状态真正解开，再回设置页。
     *
     * 一定要在 super.onParentPass() 之前清状态。super 会把设置页拉起来并 finish 本页，
     * 而守护服务下一秒就会读到「还没解锁」然后重新把锁定页压回来，
     * 家长看到的就是「输了密码还在锁屏，怎么都退不出去」。
     */
    @Override
    protected void onParentPass() {
        try {
            Kiosk.exit(this);
        } catch (Throwable ignored) {
        }

        GuardService g = GuardService.inst;
        if (g != null) {
            g.releaseLockByParent();
        } else {
            // 守护服务没在跑（被系统杀了）也要清，否则下次一启动又按旧状态锁上
            prefs.setLockUntil(0);
            prefs.setWrongCount(0);
            prefs.setStreakCount(0);
            prefs.setSinceLastSec(0);
            int limit = prefs.getDailyLimitMin() * 60;
            if (prefs.getTodayUsedSec() >= limit) prefs.relieveDailySec(30 * 60);
        }
        Diag.log(this, "锁定", "家长密码放行，回设置页");

        // 记住这一次是家长主动开的，进设置页不要又弹一次引导
        super.onParentPass();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        lastFrontAt = 0;
        super.onDestroy();
    }

    /** 锁定页上的返回键也不放行，重写一下让语义清楚 */
    @Override
    public void onBackPressed() {
        // 故意空实现
    }
}
