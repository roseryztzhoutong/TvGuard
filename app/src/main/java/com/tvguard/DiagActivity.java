package com.tvguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 计时与弹题诊断。
 *
 * 电视上没有 Logcat，家长也说不清「就是不弹」到底是哪种不弹。这里把内部状态摊开：
 * 计时器有没有在走、走到第几秒、最近几次弹题是成功还是被系统拦了。
 * 有问题时截个图发给开发者，比问二十句都准。
 */
public class DiagActivity extends Activity {

    private Prefs prefs;
    private TextView tvState;
    private TextView tvLog;
    private ScrollView scroll;

    @Override
    protected void attachBaseContext(Context base) {
        Prefs p = new Prefs(base);
        super.attachBaseContext(Ui.wrap(base, p.getUiScale()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_diag);

        prefs = new Prefs(this);
        tvState = findViewById(R.id.tvDiagState);
        tvLog = findViewById(R.id.tvDiagLog);
        scroll = findViewById(R.id.diagScroll);

        findViewById(R.id.btnDiagRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
                toast("已刷新，看到的都是这一刻的真实值");
            }
        });

        findViewById(R.id.btnDiagClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnDiagClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Diag.clear(DiagActivity.this);
                render();
                toast("日志已清空");
            }
        });

        findViewById(R.id.btnDiagCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });

        render();
        findViewById(R.id.btnDiagRefresh).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void copyAll() {
        String all = tvState.getText().toString() + "\n\n" + tvLog.getText().toString();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("这台电视没有剪贴板");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("答题锁诊断", all));
            toast("已复制。可以用手机版输入法粘到微信发出去");
        } catch (Throwable t) {
            toast("复制失败，直接拍照截图也一样");
        }
    }

    private void render() {
        StringBuilder sb = new StringBuilder();

        boolean running = isServiceRunning();
        sb.append("守护服务　　").append(running ? "在跑" : "没在跑")
                .append(prefs.isEnabled() ? "（已启用）" : "（已暂停）").append('\n');

        // 心跳是唯一能证明「服务真的在后台活着」的证据。
        // 电视系统常把后台冻住，冻住时界面看着一切正常，就是不计时也不弹题。
        long beat = prefs.beatGapSec();
        if (beat < 0) {
            sb.append("上次心跳　　没有记录，服务还没跑过\n");
        } else if (beat <= 5) {
            sb.append("上次心跳　　").append(beat).append(" 秒前，正常\n");
        } else if (beat <= 70) {
            sb.append("上次心跳　　").append(beat).append(" 秒前，有点慢（系统在省电）\n");
        } else {
            sb.append("上次心跳　　").append(beat).append(" 秒前，被系统冻住了，这就是不弹题的原因\n");
        }

        boolean battery = true;
        try {
            // isIgnoringBatteryOptimizations 是 API 23 才有的方法。minSdk 是 21，
            // 安卓 5.0 / 5.1 的老电视直接调会抛 NoSuchMethodError 把自检页整页打挂。
            // 那两个版本本来也没有「电池优化白名单」这套机制，按「已放行」算。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Throwable ignored) {
        }
        sb.append("后台省电　　").append(battery ? "已放行" : "未放行，可能被冻结").append('\n');

        boolean canOverlay = OwnerGuard.canOverlay(this);
        boolean a11yOn = A11y.enabled(this);
        boolean a11yLive = A11y.ready();
        boolean owner = OwnerGuard.isDeviceOwner(this);

        // 这一行决定了「切到 B 站还管不管得住」。
        // 没拿到设备所有者时，后台起界面会被系统拦、悬浮窗权限又没入口，
        // 结果就是在自己应用里一切正常，一出门全废。
        sb.append("最终权限　　")
                .append(owner ? "已拿到（设备所有者）" : "没拿到，需要用电脑跑一次配置")
                .append('\n');
        sb.append("悬浮窗权限　　").append(canOverlay ? "有" : "没有").append('\n');
        sb.append("无障碍服务　　")
                .append(a11yOn ? (a11yLive ? "已开启，浮窗通道可用" : "设置里开了，但服务没连上")
                        : "没开启")
                .append('\n');
        // 这一行是整块诊断里最该先看的一句：有没有一条能把题目盖上去的路
        // 桌面接管这一行排在最前，因为它决定「切到别的应用还管不管得住」，
        // 而且是唯一不需要电脑就能变绿的一条路。
        sb.append("桌面接管　　");
        if (HomeActivity.isDefaultHome(this)) {
            sb.append("已接管，按桌面键回答题锁");
        } else if (HomeActivity.homeSwitchable(this)) {
            sb.append("没接管，但电视允许换（去设置页点「去设成默认桌面」）");
        } else {
            sb.append("电视没给换桌面的入口");
        }
        sb.append('\n');
        sb.append("当前前台　　")
                .append(A11y.frontPkg == null || A11y.frontPkg.isEmpty()
                        ? "还没收到窗口事件" : A11y.frontPkg)
                .append('\n');

        String channel = canOverlay ? "悬浮窗权限"
                : (a11yLive ? "无障碍浮窗" : (a11yOn ? "无障碍没连上" : "无"));
        sb.append("浮窗通道　　").append(channel)
                .append("无".equals(channel) ? "，题弹不出来，先用电脑跑一次配置" : "")
                .append('\n');
        sb.append("跨应用能力　").append(OwnerGuard.powerText(this)).append('\n');
        sb.append("上次弹题　　").append(prefs.getLastPop().isEmpty()
                ? "还没弹过" : prefs.getLastPop()).append('\n');

        int used = prefs.getTodayUsedSec();
        int limit = prefs.getDailyLimitMin() * 60;
        sb.append("今日已看　　").append(fmt(used)).append(" / ").append(prefs.getDailyLimitMin()).append(" 分钟")
                .append(used >= limit ? "（已用完）" : "").append('\n');

        int interval = prefs.getIntervalMin() * 60;
        int since = prefs.getSinceLastSec();
        sb.append("距上次答题　").append(fmt(since)).append(" / ").append(prefs.getIntervalMin()).append(" 分钟")
                .append("（差 ").append(fmt(Math.max(0, interval - since))).append(" 到点）").append('\n');

        sb.append("答题间隔设定　").append(prefs.getIntervalMin()).append(" 分钟\n");
        sb.append("年级　　").append(prefs.hasGrade()
                ? SetupActivity.gradeName(prefs.getGrade()) : "还没选").append('\n');
        sb.append("连续答对　").append(prefs.getStreakCount()).append(" 题，连错 ").append(prefs.getWrongCount()).append(" 次\n");

        if (prefs.isLocked()) {
            sb.append("锁定中　　还剩 ").append(prefs.lockRemainSec()).append(" 秒\n");
        }

        sb.append("屏幕　　").append(isScreenOn() ? "亮着（每秒计时 +1）" : "熄了（不计时）").append('\n');

        tvState.setText(sb.toString());

        List<String> lines = Diag.recent(this, 80);
        if (lines.isEmpty()) {
            tvLog.setText("还没有记录。装上之后正常用一会儿，这里就会有内容了。");
        } else {
            StringBuilder lb = new StringBuilder();
            for (String s : lines) {
                lb.append(s).append('\n');
            }
            tvLog.setText(lb.toString());
        }
        scroll.scrollTo(0, 0);
    }

    private String fmt(int sec) {
        if (sec < 60) return sec + " 秒";
        return (sec / 60) + " 分 " + (sec % 60) + " 秒";
    }

    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isServiceRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningServiceInfo> list = am.getRunningServices(50);
            if (list == null) return false;
            String me = GuardService.class.getName();
            for (ActivityManager.RunningServiceInfo info : list) {
                if (info.service != null && me.equals(info.service.getClassName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 从设置页进来时用 */
    public static Intent makeIntent(Context c) {
        return new Intent(c, DiagActivity.class);
    }
}
