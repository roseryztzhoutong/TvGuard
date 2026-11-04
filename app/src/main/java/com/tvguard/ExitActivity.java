package com.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 退出与关闭页。
 *
 * 进来之前已经过密码门（MainActivity 的 MODE_CONFIRM_EXIT），所以这一页本身不再验密码。
 * 单独做一页而不是在设置页塞个按钮，是因为这一页干的是「拆掉防护」，
 * 得让孩子按不到，也得让家长一眼看清每一条拆了什么、留了什么。
 */
public class ExitActivity extends Activity {

    /** 卸载要按两下，中间留 8 秒反悔窗口，防手滑 */
    private static final long CONFIRM_WINDOW = 8000L;

    private Prefs prefs;
    private boolean armed = false;        // 卸载按钮是否已进入「确认」状态

    private TextView tvMsg;
    private Button btnUninstall;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable disarm = new Runnable() {
        @Override
        public void run() {
            armed = false;
            if (btnUninstall != null) btnUninstall.setText("卸载");
            if (tvMsg != null) tvMsg.setText("想好了再点一次「卸载」。");
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
        setContentView(R.layout.activity_exit);

        tvMsg = findViewById(R.id.tvMsg);
        btnUninstall = findViewById(R.id.btnUninstall);

        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disarmNow();
                haltGuard();
                tvMsg.setText("已停止。孩子现在随便看电视，不会再弹题。\n"
                        + "下次打开本软件，输密码后点「恢复守护」就能重新开。");
                toastNow("已停止");
            }
        });

        findViewById(R.id.btnRelease).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disarmNow();
                haltGuard();
                OwnerGuard.releaseAll(ExitActivity.this);
                if (OwnerGuard.isDeviceOwner(ExitActivity.this)) {
                    tvMsg.setText("答题已停止，防卸载没能解开（系统没放开设备所有者）。\n"
                            + "卸载要用电脑执行：adb shell dpm remove-active-admin com.tvguard/.AdminReceiver");
                } else {
                    tvMsg.setText("已停止，防卸载也解除了。\n"
                            + "现在可以直接返回，也可以在系统设置里正常卸载，不会再被拦。");
                }
                toastNow("已关闭");
            }
        });

        findViewById(R.id.btnUninstall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!armed) {
                    armed = true;
                    btnUninstall.setText("确认卸载");
                    tvMsg.setText("卸载会连答题记录、设置、密码一起清掉，找不回来。\n"
                            + "确定要删就再点一次，8 秒内有效。");
                    ui.removeCallbacks(disarm);
                    ui.postDelayed(disarm, CONFIRM_WINDOW);
                    return;
                }
                disarmNow();
                doUninstall();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnStop).requestFocus();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================= 动作 =================

    /** 停服务、关开关、关局域网小网页。不碰防护，不碰数据。 */
    private void haltGuard() {
        prefs.setEnabled(false);
        try {
            stopService(new Intent(this, GuardService.class));
        } catch (Throwable ignored) {
        }
        try {
            ReportHub.stopServer();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 卸载：先把所有自保手段拆干净，再交给系统卸载器。
     * 顺序不能错。设备所有者身份还在的话，系统会直接拒绝卸载，家长会以为软件删不掉。
     */
    private void doUninstall() {
        haltGuard();
        OwnerGuard.releaseAll(this);

        // 身份没交出去的话系统会直接拒绝卸载，与其让家长点一次失败一次，不如先说清楚
        if (OwnerGuard.isDeviceOwner(this)) {
            tvMsg.setText("系统没放开设备所有者身份，直接卸载会被拒绝。\n"
                    + "用电脑连电视执行：adb shell dpm remove-active-admin com.tvguard/.AdminReceiver\n"
                    + "然后再卸载。");
            return;
        }

        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        } catch (Throwable ignored) {
            // 部分电视砍掉了卸载界面，落到下面的提示
        }
        tvMsg.setText("这台电视没有卸载界面。防护已经解除了，可以从电脑执行：\n"
                + "adb uninstall com.tvguard\n"
                + "或者去 设置 → 应用 → 答题锁 → 卸载。");
    }

    private void disarmNow() {
        armed = false;
        ui.removeCallbacks(disarm);
        if (btnUninstall != null) btnUninstall.setText("卸载");
    }

    private void toastNow(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
