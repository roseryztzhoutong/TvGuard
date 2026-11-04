package com.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务。这个类只干两件事，但这两件事是整台电视能不能用的关键。
 *
 * 一、提供「无障碍浮窗」。
 *    这台电视（华为智慧屏）的设置里根本没有「悬浮窗」这一项，家长想给也给不了。
 *    但 TYPE_ACCESSIBILITY_OVERLAY 这个窗口类型不需要 SYSTEM_ALERT_WINDOW 权限，
 *    只要无障碍服务在跑就能挂，而且挂出来同样在最上层、同样能吃掉遥控器按键。
 *    这是绕开「电视没有悬浮窗授权」的唯一正路。
 *
 * 二、让进程不被冻死。
 *    被系统绑定的无障碍服务优先级很高，电视的省电策略基本不动它。
 *    之前家长反馈「只有打开答题锁才会计时、才弹题」，本质就是后台被冻住了。
 *
 * 注意：这里只做「保持连接 + 提供 WindowManager」，不读屏幕内容
 * （accessibility_service 配置里 canRetrieveWindowContent 是 false），
 * 不监听任何输入，也不往外发任何东西。
 */
public class A11y extends AccessibilityService {

    private static volatile A11y inst;

    /**
     * 现在前台是哪个包。
     *
     * 这个值让「到点把人送回桌面」这件事变得可能。
     * 没有它，服务只能干等，不知道自己该不该动手；
     * 有了它，孩子刚点开 B 站的那一刻就能判断出「时间到了」并立刻按 HOME。
     */
    public static volatile String frontPkg = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        Diag.log(this, "无障碍", "服务已连接，可以用无障碍浮窗了");
        // 连上就顺手把守护服务拉起来。家长开完无障碍往往直接退出设置，
        // 如果这里不拉，还得他自己回答题锁里点一下才开始计时。
        try {
            startService(new Intent(this, GuardService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只听一件事：窗口换成了哪个应用。
     *
     * 不读控件树，不读文字，不读输入内容。拿到的只有一个包名，
     * 形如「tv.danmaku.bili」。这个信息用来回答一个问题：
     * 孩子现在到底是在答题锁自己的界面上，还是已经溜到别的应用里去了。
     *
     * 拿到之后交给守护服务判断该不该动手，判断逻辑不放在这里，
     * 因为这里没有配置、没有计时，只有一条事件流。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence cs = event.getPackageName();
            if (cs == null) return;
            String pkg = cs.toString();
            if (pkg.isEmpty()) return;
            frontPkg = pkg;
            GuardService g = GuardService.inst;
            if (g != null) g.onFrontApp(pkg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按一次遥控器上的桌面键。
     *
     * 这是整条免电脑链路里最关键的十几行代码。
     *
     * performGlobalAction 是无障碍服务自带的能力，不需要悬浮窗权限，
     * 不需要设备所有者，不需要电脑配置。家长只要在电视设置里把这个服务打开就行。
     *
     * 为什么按 HOME 有用：如果我们已经是默认桌面，HOME 键回到的就是答题锁自己，
     * 而答题锁桌面在 onResume 里会检查「该不该答题」，该就问。
     * 于是「在别处看久了」这件事被自动收束回「必须先答题」。
     */
    public static boolean goHome() {
        A11y a = inst;
        if (a == null) return false;
        try {
            return a.performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 按键过滤。
     *
     * 只在一种情况下出手：答题浮窗已经挂上，但那个窗口没拿到按键焦点
     * （个别电视的无障碍浮窗画得出来、吃不掉遥控器）。这时候遥控器按什么都没反应，
     * 孩子答不完题，比不弹还糟。这里把方向键和确认键手动转给答题界面。
     *
     * 浮窗自己有焦点时一律返回 false，让系统按正常路径走，绝不插手。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            GuardService g = GuardService.inst;
            if (g == null) return false;
            return g.onA11yKey(event);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (inst == this) inst = null;
        Diag.log(this, "无障碍", "服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    /** 服务是不是真的连着。连着才有 WindowManager 可以挂无障碍浮窗 */
    public static boolean ready() {
        return inst != null;
    }

    /** 挂无障碍浮窗专用的 WindowManager。必须用服务自己的那个，用别处拿的会被拒 */
    public static WindowManager wm() {
        A11y a = inst;
        if (a == null) return null;
        try {
            return (WindowManager) a.getSystemService(WINDOW_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 形如 com.tvguard/com.tvguard.A11y，用来和系统设置里的字符串比对 */
    public static String id(Context c) {
        return c.getPackageName() + "/" + A11y.class.getName();
    }

    public static ComponentName component(Context c) {
        return new ComponentName(c, A11y.class);
    }

    /**
     * 家长有没有在系统设置里把这个无障碍服务打开。
     *
     * 不查 AccessibilityManager，直接读 Settings.Secure 里那一串，
     * 因为前者在部分电视固件上返回空列表而实际是开着的，后者更准。
     */
    public static boolean enabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            return s.toLowerCase().contains(id(c).toLowerCase());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳到系统的无障碍设置页。电视裁剪得厉害，跳不进去就退到应用详情页 */
    public static boolean openSettings(Context c) {
        String[] actions = {
                Settings.ACTION_ACCESSIBILITY_SETTINGS,
                "android.settings.ACCESSIBILITY_SETTINGS",
                Settings.ACTION_SETTINGS
        };
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
