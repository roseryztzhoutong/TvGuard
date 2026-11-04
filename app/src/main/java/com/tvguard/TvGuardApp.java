package com.tvguard;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * 全局入口，只干一件事：盯着「现在前台是家长在操作，还是孩子在用」。
 *
 * <p>为什么不能只靠 Activity 自己报：这个应用里有六个界面
 * （设置、选年级、诊断、报告、兼容性、退出），每个都要在 onResume / onPause 里
 * 写一对调用，漏一个就出问题，而且以后新加界面一定会忘。
 * 挂一次生命周期回调，所有界面自动覆盖。
 *
 * <p>要解决的三个具体麻烦都出自同一个判断缺失：
 * 一、家长调参数时，守护服务照常计时，把家长操作的时间算成孩子的观看时间；
 * 二、家长在设置页待满一个周期，题会压到设置页上面，把人自己锁在外面；
 * 三、这种情况下家长还得答一道题才能继续改参数，非常荒唐。
 *
 * <p>刻意不把答题页、锁定页、桌面算进来：
 * 它们本身就是守护逻辑的一部分，算进来等于把逻辑关掉。
 */
public final class TvGuardApp extends Application {

    /** 有几个家长界面叠着（设置页拉起选年级页时是两个，不是零个） */
    private static volatile int depth = 0;
    /** 最近一次家长界面进出前台的时间，用来给僵死状态兜底 */
    private static volatile long changedAt = 0;

    /**
     * 家长界面不动的上限。
     *
     * <p>超过这个时间就认为那个界面早就被系统回收了，只是回调没来。
     * 没有这道保险，一次异常退出会让计时永久停住，家长会以为软件不工作了。
     */
    private static final long STALE_MS = 20 * 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity a, Bundle b) {
                }

                @Override
                public void onActivityStarted(Activity a) {
                }

                @Override
                public void onActivityResumed(Activity a) {
                    if (!isParentUi(a)) return;
                    depth++;
                    changedAt = SystemClock.elapsedRealtime();
                }

                @Override
                public void onActivityPaused(Activity a) {
                    if (!isParentUi(a)) return;
                    if (depth > 0) depth--;
                    changedAt = SystemClock.elapsedRealtime();
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                }
            });
        } catch (Throwable ignored) {
            // 注册失败不影响主流程，最坏退回到「照常计时」，和以前一样
        }
    }

    /**
     * 这个界面算不算「家长在操作」。
     *
     * <p>答题页和锁定页继承自 BaseLockActivity，桌面是 HomeActivity，
     * 这三种是守护逻辑自己的舞台，必须排除。剩下的都是家长手动打开的。
     */
    private static boolean isParentUi(Activity a) {
        if (a == null) return false;
        if (a instanceof BaseLockActivity) return false;
        if (a instanceof HomeActivity) return false;
        return true;
    }

    /**
     * 家长界面现在是不是在屏幕上。
     *
     * <p>守护服务每个心跳都会问一次。为真时既不累加计时，也不弹题。
     */
    public static boolean parentUiShowing() {
        if (depth <= 0) return false;
        long t = changedAt;
        if (t > 0 && SystemClock.elapsedRealtime() - t > STALE_MS) {
            depth = 0;
            return false;
        }
        return true;
    }

    /** 调试用：现在压着几层家长界面 */
    public static int parentUiDepth() {
        return depth;
    }
}
