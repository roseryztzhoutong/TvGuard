package com.tvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启。孩子断电重启绕不过去，这一环是必须的。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Prefs p = new Prefs(context);
            if (!p.isEnabled()) return;
            // 开机第一件事先把设备所有者能给的权限重新落一遍。
            // 系统升级或恢复出厂后，这些授权可能悄悄掉回未授予状态，
            // 表现就是「开机之后又能切出去看电视了」。
            OwnerGuard.applyPolicies(context);
            Intent i = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }
}
