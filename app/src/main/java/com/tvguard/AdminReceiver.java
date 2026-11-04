package com.tvguard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 设备管理器。激活后，孩子在设置里卸载本应用需要先取消激活，
 * 而取消激活会走到本类的 onDisableRequested，可在此拦截提示。
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "关闭后孩子可以直接卸载本应用，答题锁将失效。\n如确需关闭，请先在应用内输入家长密码。";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "答题锁的防卸载保护已关闭", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "防卸载保护已开启", Toast.LENGTH_SHORT).show();
    }
}
