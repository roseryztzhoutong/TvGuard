package com.tvguard;

import android.content.Context;

/**
 * 一条龙拿到「设备所有者」这个身份。
 *
 * <p>这是整个项目唯一能把管理能力伸出本应用的路。没有它，孩子按一下桌面键回华为主页，
 * 我们什么都做不了；有它，系统才允许我们三件事：
 * 自己批悬浮窗权限（绕开电视设置里根本没有那个入口）、把题目从后台压到 B 站上面、
 * 答题时进真锁任务让桌面键彻底失效。
 *
 * <p>为什么以前非得用电脑：这个身份只有 shell 用户能授予。以前的做法是拿电脑跑 adb，
 * 家长要装工具、记 IP、敲命令。现在改成应用自己连本机的调试服务，家长只需要在电视上
 * 点一次「允许」，剩下的命令由软件自己执行。
 *
 * <p>绕不过去的前提：电视开发者选项里得把调试开关打开。
 * 华为智慧屏上这个开关叫「HDC 调试」，名字听起来像另一套协议，但实测打开它之后
 * 5555 端口上跑的仍然是标准 adb，所以名字不影响我们，打开就行。
 * 那是系统开关，只有人手动能开。没开的话下面的步骤会直接告诉你去看哪一项，不会含糊。
 *
 * <p>所有方法都阻塞，必须在工作线程调用。
 */
public final class SelfGrant {

    /**
     * 必须放在 STEPS 之前声明。
     *
     * <p>Java 的静态字段按文本顺序初始化，如果 PKG 写在 STEPS 后面，
     * 拼命令时它还是 null，命令会变成 "dpm set-device-owner null/.AdminReceiver"，
     * 而且报错信息完全看不出是这个原因。
     */
    private static final String PKG = "com.tvguard";

    /**
     * 执行顺序有讲究，dpm 必须放最后。
     *
     * <p>系统在交出「设备所有者」身份的那一刻会立刻杀掉本应用的进程去重建，
     * 如果 dpm 排第一，后面四条命令根本来不及跑，家长会看到一句失败，
     * 而实际上身份已经拿到了一半。所以先做四条不依赖身份、只需要 shell 权限的准备动作，
     * 最后再交身份。这样即使进程当场被杀，前面的配置也已落盘。
     */
    private static final String[] STEPS = {
            // 无障碍既是我们画浮窗的备用通道，也是能在别的应用前台按 HOME 的唯一角色
            "settings put secure enabled_accessibility_services " + PKG + "/" + PKG + ".A11y",
            "settings put secure accessibility_enabled 1",
            // 电视设置里没有悬浮窗开关，只能从 appops 这边直接放行
            "appops set " + PKG + " SYSTEM_ALERT_WINDOW allow",
            // 不进省电白名单，系统会把后台冻结，计时会断
            "cmd deviceidle whitelist +" + PKG,
            // 最后一步：交出身份。已经有账号的电视会在这一步被系统拒绝
            "dpm set-device-owner " + PKG + "/.AdminReceiver",
    };

    private SelfGrant() {
    }

    public interface Cb {
        /** 进度一句话，给人看的 */
        void onStep(String s);

        /**
         * @param ok     整体是否成功（设备所有者确实拿到了）
         * @param detail 给人看的结论，成功是「已经全部打开」，失败是下一步该干什么
         */
        void onDone(boolean ok, String detail);
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 执行全部步骤。
     *
     * <p>注意：成功之后系统会重启本应用的进程（设备所有者变更的正常行为），
     * 所以调用方不要在成功回调里做「立刻读回权限状态」这种操作，
     * 那一刻应用可能正在被杀。状态判断放在界面重新起来之后做。
     */
    public static void run(Context ctx, Cb cb) {
        if (OwnerGuard.isDeviceOwner(ctx)) {
            report(cb, true, "已经拿到最终权限，不需要重做。");
            return;
        }

        if (cb != null) cb.onStep("开始申请权限");

        LocalAdb.Result r = LocalAdb.runShell(ctx, STEPS, new LocalAdb.Cb() {
            @Override
            public void onStep(String step) {
                if (cb != null) cb.onStep(step);
            }
        });

        // 最先看的是真实身份，不是命令的返回值。
        // dpm 成功后系统会立刻杀本进程，那次网络读很可能断在半路，
        // 返回的是「执行中断」，可身份其实已经拿到了。以系统状态为准。
        if (OwnerGuard.isDeviceOwner(ctx)) {
            Diag.log(ctx, "权限", "自助授权成功，已是设备所有者");
            report(cb, true, successText());
            return;
        }

        if (r.code == LocalAdb.R_NO_PORT) {
            Diag.log(ctx, "权限", "探测失败 " + r.log);
            report(cb, false, LocalAdb.advice(r.probe));
            return;
        }
        if (r.code == LocalAdb.R_NO_ADB) {
            Diag.log(ctx, "权限", "端口在但不认 adb " + r.log);
            report(cb, false, LocalAdb.advice(r.probe));
            return;
        }
        if (r.code == LocalAdb.R_DENIED) {
            report(cb, false,
                    "电视上那个「允许调试」的框没有点允许。\n\n"
                            + "再点一次「一键获取最终权限」，电视会重新弹框，"
                            + "用遥控器把焦点移到「允许」并按 OK。\n"
                            + "如果框一闪就没了，说明这台电视对本地连接不弹框，那就只能借一台安卓手机来做（说明书里有）。");
            return;
        }

        // 只认 dpm 那一条的输出，后面的 settings / appops 都是辅助
        String dpmOut = firstBlock(r.log.toString(), "dpm set-device-owner");
        Diag.log(ctx, "权限", "自助授权结果：" + oneLine(dpmOut));

        if (looksLikeSuccess(dpmOut)) {
            report(cb, true, successText());
            return;
        }

        report(cb, false, translate(dpmOut));
    }

    private static String successText() {
        return "拿到了。这台电视现在完全受控：\n"
                + "切到任何应用都会弹题，答题时按桌面键、返回键都出不去，卸载也做不了。\n"
                + "界面可能会闪一下自动重启，这是系统的正常反应，不是出错。";
    }

    // ---------------------------------------------------------------- 文案

    private static void report(Cb cb, boolean ok, String detail) {
        if (cb != null) cb.onDone(ok, detail);
    }

    private static boolean looksLikeSuccess(String out) {
        if (out == null) return false;
        String s = out.toLowerCase();
        return s.contains("success") || s.contains("already a device owner");
    }

    /**
     * 把 dpm 的原始输出翻译成家长能照做的话。
     *
     * <p>这一步比技术本身更重要。dpm 的报错是一整段 Java 异常栈，
     * 家长看到只会以为软件坏了，实际问题往往是「电视上登着华为账号」这一件小事。
     */
    private static String translate(String out) {
        String s = out == null ? "" : out;
        String low = s.toLowerCase();

        if (low.contains("already some accounts") || low.contains("accounts on the device")) {
            return "系统拒绝了，原因很具体：这台电视上登着账号。\n\n"
                    + "安卓规定有账号的设备不允许交出管理权，怕被人恶意锁机。\n"
                    + "去 设置 → 账号中心（或「华为账号」）把账号退出登录，然后回来再点一次。\n\n"
                    + "如果退不出来（有些电视强制绑定），只能恢复出厂设置，"
                    + "开机时跳过账号登录，装好软件立刻做这一步。";
        }
        if (low.contains("already several users") || low.contains("multiple users")) {
            return "系统拒绝了，因为电视上开了多个用户（比如儿童空间、访客模式）。\n\n"
                    + "去 设置 → 用户 里把多余的用户删掉，只留一个主用户，再回来点一次。";
        }
        if (low.contains("not allowed to set the device owner")) {
            return "系统拒绝了。常见原因是电视上还登着账号，或者之前设过别的管理应用。\n\n"
                    + "先检查：设置 → 账号 里有没有登录的账号，有就退掉。\n"
                    + "还不行的话，把这段原文发给技术支持看：\n\n" + oneLine(s);
        }
        if (low.contains("unknown option") || low.contains("not found") || low.contains("inaccessible")) {
            return "这台电视的调试通道不认 dpm 命令，说明它的系统被厂家裁剪过。\n\n"
                    + "这条路对这台机器不成立。原文：\n\n" + oneLine(s);
        }
        return "命令执行了，但系统没有确认成功。原文如下，可以直接发给技术支持：\n\n" + oneLine(s);
    }

    /** 从合并输出里取出某条命令后面那段 */
    private static String firstBlock(String all, String key) {
        if (all == null) return "";
        int i = all.indexOf("$ " + key);
        if (i < 0) return "";
        int j = all.indexOf("\n$ ", i + 1);
        String seg = j < 0 ? all.substring(i) : all.substring(i, j);
        int nl = seg.indexOf('\n');
        return nl < 0 ? seg : seg.substring(nl + 1).trim();
    }

    private static String oneLine(String s) {
        if (s == null) return "（没有输出）";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        if (t.isEmpty()) return "（没有输出）";
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
