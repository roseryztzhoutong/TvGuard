package com.tvguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.zip.CRC32;

/**
 * 在电视上自己连自己的 ADB 服务，拿到 shell 权限。
 *
 * <p>为什么需要这个：这台电视（华为智慧屏）没有「悬浮窗权限」入口，也不允许第三方应用当桌面，
 * 后台弹页面又被系统拦。唯一还能一次性打通所有能力的身份是「设备所有者」，
 * 而系统规定这个身份只能由 shell 用户（也就是 adb）授予，应用自己申请不到。
 *
 * <p>以前的版本让家长拿电脑跑脚本，成本太高。其实 Android 允许应用连接本机的 adb 服务：
 * adbd 在电视上监听 127.0.0.1:5555，应用拿出自己的 RSA 公钥，电视会弹一个
 * 「是否允许 USB 调试」的授权框，家长用遥控器点一下允许，公钥就进了白名单，
 * 之后这个应用就等于拿到了 adb shell 的权限。
 *
 * <p>这不是漏洞，是安卓设计好的机制：每一次授权都必须由人在屏幕上点确认。
 * 我们能做的只是把「电脑」这个中间环节省掉。
 *
 * <p>前提只有一个，绕不过去：电视的开发者选项里必须把「ADB 调试 / 网络 ADB 调试」打开。
 * 那是一个系统开关，只有人手动能开，代码碰不到。没开的话下面 {@link #probe} 会明确报出来。
 *
 * <p>协议实现参考 AOSP 的 adb 传输层：24 字节定长头 + 可选负载，
 * 握手走 CNXN → AUTH(TOKEN) → AUTH(SIGNATURE) → 必要时 AUTH(RSAPUBLICKEY) → CNXN。
 */
public final class LocalAdb {

    // ---------------------------------------------------------------- 常量

    /** adb 协议版本，AOSP 里一直用这个值 */
    private static final int A_VERSION = 0x01000001;
    /** 单个通道的最大负载，256KB 是 adb 的常用上限 */
    private static final int MAX_PAYLOAD = 256 * 1024;

    // 命令字，按小端序存的是 ASCII 字符本身
    private static final int CMD_CNXN = 0x4e584e43;  // CNXN
    private static final int CMD_AUTH = 0x48545541;  // AUTH
    private static final int CMD_OPEN = 0x4e45504f;  // OPEN
    private static final int CMD_OKAY = 0x59414b4f;  // OKAY
    private static final int CMD_CLSE = 0x45534c43;  // CLSE
    private static final int CMD_WRTE = 0x45545257;  // WRTE
    private static final int CMD_STLS = 0x534c5453;  // STLS

    // AUTH 的三种子类型
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBKEY = 3;

    /** adb over network 的固定端口 */
    public static final int PORT = 5555;
    /**
     * 华为 HDC 的控制端口。
     *
     * <p>这个端口只用来「探一下开没开」，绝不对它发 adb 报文。
     * 它的存在与否能帮我们判断一件事：家长打开的到底是安卓调试，还是纯鸿蒙调试。
     */
    public static final int PORT_HDC = 8710;

    private static final String SP = "local_adb";
    private static final String K_PRIV = "rsa_priv";
    private static final String K_PUB = "rsa_pub";

    // ---------------------------------------------------------------- 结果类型

    /** 连不上端口：电视没开「网络 ADB 调试」，或者 adbd 没在监听 */
    public static final int R_NO_PORT = 1;
    /** 需要人在电视上点「允许」 */
    public static final int R_NEED_ALLOW = 2;
    /** 弹了授权框但被拒绝（或者超时没人点） */
    public static final int R_DENIED = 3;
    /** 连上了，命令也执行了 */
    public static final int R_OK = 4;
    /** 连上了，但某条命令执行失败 */
    public static final int R_CMD_FAIL = 5;
    /** 端口开着，但对端说的不是安卓调试协议（比如被换成了华为自己的协议） */
    public static final int R_NO_ADB = 6;

    // 单点探测的三种结局
    /** 端口连不上：服务根本没起来 */
    private static final int P_CLOSED = 0;
    /** 端口连得上，但握手回的不是 adb 报文 */
    private static final int P_NOT_ADB = 1;
    /** 端口连得上，回的是合法 adb 报文 */
    private static final int P_ADB = 2;

    /** 进度与结果的回调，一律在主线程之外的调用方自己切回去 */
    public interface Cb {
        /**
         * @param step 给人看的一句话，比如「正在等电视上点允许」
         */
        void onStep(String step);
    }

    private LocalAdb() {
    }

    /** 一次执行的结果 */
    public static final class Result {
        public int code;
        public String text;
        /** 每条命令的原始输出，给诊断页和失败排查用 */
        public StringBuilder log = new StringBuilder();
        /**
         * 这次执行前的探测结论。失败时全靠它来说清「是哪一种不通」，
         * 界面上那句处置建议就是从这里面读出来的。
         */
        public Probe probe;

        Result(int c, String t) {
            code = c;
            text = t;
        }

        public boolean ok() {
            return code == R_OK;
        }
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 探测的结果，比一个 boolean 多带了不少信息。
     *
     * <p>为什么不再用 boolean：家长看到「没打开」这四个字是没法动手的。
     * 端口到底是不存在、还是存在但换了协议、还是只开了华为那个口，三种情况
     * 对应三条完全不同的处置办法，必须分开说。
     */
    public static final class Probe {
        /** 命中的地址，null 表示没命中 */
        public String host;
        public int state = P_CLOSED;
        /** 一句话说明，给人看的 */
        public String detail = "";
        /** 对端回的报文头部十六进制，诊断用 */
        public String raw = "";
        /** 试过哪些地址，诊断页要显示 */
        public String tried = "";
        /** 华为 HDC 的控制端口是不是开着 */
        public boolean hdcOpen;

        public boolean usable() {
            return state == P_ADB;
        }

        /** 端口开着，但对端说的不是安卓调试协议 */
        public boolean notAdb() {
            return state == P_NOT_ADB;
        }
    }

    /**
     * 探一遍本机的调试服务。
     *
     * <p>为什么要试多个地址：adbd 有时候只监听 wlan0 / eth0 那张网卡，
     * 127.0.0.1 反而不通。也有反过来的——只监听回环。
     * 所以先把回环放第一个试（最快），再逐个试本机的局域网地址。
     *
     * <p>这个方法只做握手的第一回合：发一条 CNXN 过去，看它回的
     * 24 字节头对不对得上 magic。对得上就说明是 adb，对不上就记下原始字节，
     * 让家长和我们都知道「门在，但锁换了」。整个过程不授权、不改任何状态、没有副作用。
     */
    public static Probe probe() {
        Probe p = new Probe();

        java.util.List<String> hosts = new java.util.ArrayList<>();
        hosts.add("127.0.0.1");
        String lan = null;
        try {
            java.util.Enumeration<java.net.NetworkInterface> it =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                java.net.NetworkInterface ni = it.nextElement();
                if (ni == null || !ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a == null || a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if (s == null || s.contains(":") || s.contains("%")) continue;
                    if (!hosts.contains(s)) {
                        hosts.add(s);
                        if (lan == null) lan = s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < hosts.size(); i++) {
            String h = hosts.get(i);
            if (tried.length() > 0) tried.append(" · ");
            tried.append(h).append(':').append(PORT);

            int st = probeOne(h, p);
            if (st == P_ADB) {
                p.host = h;
                p.state = P_ADB;
                break;
            }
            if (st == P_NOT_ADB) {
                // 门开着但锁换了，记下来继续试别的地址，万一别处是好的
                p.host = h;
                p.state = P_NOT_ADB;
            }
        }
        p.tried = tried.toString();

        // 顺手看一眼华为 HDC 的口，只做纯 TCP 连接，不发任何报文
        p.hdcOpen = reachable("127.0.0.1", PORT_HDC) || (lan != null && reachable(lan, PORT_HDC));

        if (p.state == P_ADB && p.detail.isEmpty()) p.detail = "调试通道已就绪";
        return p;
    }

    /**
     * 只回答「通不通」这件事，不判断协议。
     *
     * <p>给 SelfGrant 用的快捷判断，现在直接建立在 {@link #probe()} 上，
     * 这样两种入口看到的是同一个结论，不会出现「这里说通、那里说没通」。
     */
    public static boolean portOpen() {
        return probe().usable();
    }

    /** 一次完整的探测对话，用来区分「没开门」和「门开了但锁换了」 */
    private static int probeOne(String host, Probe p) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PORT), 1800);
            s.setSoTimeout(1800);
            s.setTcpNoDelay(true);

            OutputStream o = s.getOutputStream();
            InputStream i = s.getInputStream();

            byte[] pay = "host::\u0000".getBytes("UTF-8");
            ByteBuffer hb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            hb.putInt(CMD_CNXN);
            hb.putInt(A_VERSION);
            hb.putInt(MAX_PAYLOAD);
            hb.putInt(pay.length);
            hb.putInt((int) crcOf(pay));
            hb.putInt(CMD_CNXN ^ 0xffffffff);
            o.write(hb.array());
            o.write(pay);
            o.flush();

            byte[] h = new byte[24];
            int off = 0;
            while (off < 24) {
                int r = i.read(h, off, 24 - off);
                if (r < 0) break;
                off += r;
            }

            if (off < 24) {
                p.detail = "端口在，但对面一个字节都不回";
                p.raw = hex(h, off);
                return P_NOT_ADB;
            }

            p.raw = hex(h, 24);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = b.getInt();
            int a0 = b.getInt();
            b.getInt();
            b.getInt();
            b.getInt();
            int magic = b.getInt();

            if (magic == (cmd ^ 0xffffffff) && knownCmd(cmd)) {
                if (cmd == CMD_CNXN) {
                    p.detail = "调试通道已就绪";
                } else if (cmd == CMD_AUTH && a0 == AUTH_TOKEN) {
                    p.detail = "调试通道在，等着我们出示密钥";
                } else {
                    p.detail = "调试通道在";
                }
                return P_ADB;
            }

            p.detail = "端口在，但回的不是安卓调试协议";
            return P_NOT_ADB;

        } catch (Throwable t) {
            p.detail = "连不上（" + t.getClass().getSimpleName() + "）";
            return P_CLOSED;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean knownCmd(int cmd) {
        return cmd == CMD_CNXN || cmd == CMD_AUTH || cmd == CMD_OPEN || cmd == CMD_OKAY
                || cmd == CMD_CLSE || cmd == CMD_WRTE || cmd == CMD_STLS;
    }

    /** 纯连接测试，不发报文，不会干扰对端 */
    private static boolean reachable(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 900);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把探测结论翻译成家长能照着做的一段话。
     *
     * <p>这段话只在这里写一份。以前说明书和界面各写一遍，改了一处忘了另一处，
     * 结果界面上还留着「华为的 HDC 走不通」这种错误的结论。
     * 现在所有入口都从这里取，改一处就是改全部。
     */
    public static String advice(Probe p) {
        if (p == null) return "还没有检查调试通道，先点「只检查调试通道」。";

        if (p.usable()) {
            return "通道已经通了（" + p.host + ":5555，" + p.detail + "）。\n\n"
                    + "现在点「一键获取最终权限」。点完电视上会弹出「是否允许 USB 调试」，"
                    + "用遥控器把光标移到「允许」再按 OK。允许一次之后就不用再允许了。";
        }

        if (p.notAdb()) {
            return "端口是开的，但它回的东西不是安卓调试协议。\n\n"
                    + "探测地址：" + (p.host == null ? "未知" : p.host + ":5555") + "\n"
                    + "它回的前 24 个字节：" + (p.raw == null || p.raw.isEmpty() ? "（什么都没回）" : p.raw) + "\n\n"
                    + "意思是这台电视把 5555 这个位置换成了华为自己的协议，我们的命令它听不懂，"
                    + "而授权只能走安卓那条通道。\n\n"
                    + "能做的两件事：\n"
                    + "1. 回开发者选项，把「HDC 调试」关掉再打开一次，有时重启服务后协议就变回安卓的了，"
                    + "然后再点一次检查。\n"
                    + "2. 把上面这行十六进制发给我，我确认它到底是什么协议。";
        }

        if (p.hdcOpen) {
            return "电视上华为自己的调试口（8710）开着，但安卓调试口（5555）没开。\n\n"
                    + "意思是你现在只打开了 HDC 那一半。回开发者选项再翻一遍，"
                    + "看有没有单独一项叫「USB 调试」「ADB 调试」或「无线调试」，有就一起打开，"
                    + "回来重新点「只检查调试通道」。\n\n"
                    + "如果整个开发者选项里确实找不到带 ADB 字样的开关，那这台电视只给了 HDC，"
                    + "安卓命令发不进去。这种情况用下面两个办法绕开：\n"
                    + "1. 找一台安卓手机，装个 ADB 工具（比如甲壳虫 ADB 助手），手机和电视连同一个 WiFi，"
                    + "用手机连 5555 执行那条授权命令。前提还是电视的 5555 得开着。\n"
                    + "2. 买一个两百块左右的安卓电视盒子（小米盒子、当贝盒子都行），把软件装盒子上。"
                    + "盒子的开发者选项是标准安卓，这条路一定走得通。";
        }

        return "电视上没有开放任何调试口，这一步只能由你在电视上手动打开。\n\n"
                + "华为智慧屏（S / V / Vision 系列）遥控器操作：\n"
                + "1. 设置 → 系统 → 关于本机，光标停在「型号代码」上连续按确认键 7 到 12 次，"
                + "屏幕会提示「您已进入开发人员模式」。\n"
                + "2. 返回设置 → 通用 → 其他 → 开发人员选项。\n"
                + "3. 先把页面顶部的「开发人员选项」总开关打开，再往下找「HDC 调试」并打开。\n"
                + "4. 有的机型这一项在「系统和更新 → 开发人员选项」里，叫「USB 调试」或「ADB 调试」，"
                + "看到哪个就开哪个，能开的全开。\n\n"
                + "开完回来再点一次「只检查调试通道」。电视重启后这个开关可能自己关掉，需要重新打开。";
    }

    private static long crcOf(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return c.getValue();
    }

    private static String hex(byte[] b, int n) {
        if (b == null || n <= 0) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n && i < 24; i++) {
            if (i > 0) s.append(' ');
            s.append(String.format("%02X", b[i]));
        }
        return s.toString();
    }

    /**
     * 依次执行一组 shell 命令，返回合并后的输出。
     *
     * <p>这个方法会阻塞，必须放在工作线程里调。等待家长点「允许」的时候，
     * 单条读超时放宽到 30 秒，因为从电视弹框到人找到遥控器需要时间。
     */
    public static Result runShell(Context ctx, String[] cmds, Cb cb) {
        Conn c = null;
        try {
            // 先探一遍再连：这样连的地址是验证过能回 adb 报文的那个，
            // 而不是硬写 127.0.0.1 撞运气。探不通时也能立刻说清是哪一种不通。
            if (cb != null) cb.onStep("正在探测电视的调试服务");
            Probe p = probe();
            if (!p.usable()) {
                Result r = new Result(p.state == P_NOT_ADB ? R_NO_ADB : R_NO_PORT, p.detail);
                r.probe = p;
                r.log.append("probe state=").append(p.state)
                        .append(" host=").append(p.host)
                        .append(" tried=").append(p.tried)
                        .append(" raw=").append(p.raw)
                        .append(" hdc8710=").append(p.hdcOpen);
                return r;
            }

            if (cb != null) cb.onStep("正在连接电视的调试服务");
            c = new Conn(ctx, p.host, cb);
            c.connect();

            StringBuilder all = new StringBuilder();
            for (String cmd : cmds) {
                if (cb != null) cb.onStep("正在执行：" + shortCmd(cmd));
                String out = c.exec(cmd);
                all.append("$ ").append(cmd).append('\n').append(out).append('\n');
            }
            Result r = new Result(R_OK, all.toString().trim());
            r.log = all;
            return r;

        } catch (NoPort e) {
            Result r = new Result(R_NO_PORT,
                    "调试服务在探测之后又断掉了。电视可能刚重启，把「HDC 调试」重新打开再试。");
            return r;
        } catch (Denied e) {
            Result r = new Result(R_DENIED,
                    "授权框被拒绝了，或者 30 秒内没有人点允许。再试一次，电视上弹框时用遥控器点「允许」。");
            return r;
        } catch (Throwable t) {
            Result r = new Result(R_CMD_FAIL, "执行中断：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
            return r;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String shortCmd(String cmd) {
        if (cmd == null) return "";
        return cmd.length() > 42 ? cmd.substring(0, 42) + "…" : cmd;
    }

    // ---------------------------------------------------------------- 内部异常

    private static class NoPort extends Exception {
        NoPort(String m) {
            super(m);
        }
    }

    private static class Denied extends Exception {
        Denied(String m) {
            super(m);
        }
    }

    // ---------------------------------------------------------------- 连接

    /** 一条到本机 adbd 的连接，握手完成后可以反复开通道跑命令 */
    private static final class Conn {

        private final Context ctx;
        private final Cb cb;
        /** 探测阶段确认过能回 adb 报文的地址，回环或者局域网，不一定 */
        private final String host;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        /** 通道号从 1 开始，每条命令用一个新号，用完不复用 */
        private int nextId = 1;

        Conn(Context c, String host, Cb cb) {
            this.ctx = c.getApplicationContext();
            this.host = host;
            this.cb = cb;
        }

        void close() {
            try {
                if (socket != null) socket.close();
            } catch (Throwable ignored) {
            }
        }

        // ------------------------------------------------------------ 握手

        void connect() throws Exception {
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, PORT), 2500);
            } catch (Throwable t) {
                throw new NoPort(t.toString());
            }
            // 读完一条消息可能要等人在电视上点授权，给足 30 秒
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            send(CMD_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".getBytes("UTF-8"));

            int tokenTimes = 0;
            while (true) {
                Msg m = read();

                if (m.cmd == CMD_CNXN) {
                    // 握手成功。设备信息在负载里，留着给诊断页看
                    if (cb != null) cb.onStep("已连接电视调试服务");
                    return;
                }

                if (m.cmd == CMD_STLS) {
                    // 老版本 adbd 会先要求升级到 TLS。我们连的是本机回环，
                    // 跟着它的版本号回一条 STLS 就行，正式通道仍然是明文。
                    send(CMD_STLS, A_VERSION, 0, null);
                    continue;
                }

                if (m.cmd != CMD_AUTH) {
                    continue;
                }

                if (m.a0 != AUTH_TOKEN) {
                    continue;
                }

                tokenTimes++;
                if (tokenTimes == 1) {
                    // 第一轮：用已经存过的密钥签名。以前授权过的话，这一下就过了
                    send(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(m.data));
                } else if (tokenTimes == 2) {
                    // 签名不被承认，说明这台电视还没见过我们。
                    // 递公钥过去，系统会弹「是否允许 USB 调试」，等家长点确认。
                    if (cb != null) {
                        cb.onStep("电视即将弹出授权框，请用遥控器点「允许」");
                    }
                    send(CMD_AUTH, AUTH_PUBKEY, 0, pubKeyBlob());
                } else {
                    throw new Denied("多次授权未通过");
                }
            }
        }

        // ------------------------------------------------------------ 跑命令

        String exec(String cmd) throws Exception {
            int localId = nextId++;
            // OPEN 的负载就是服务名，shell 服务会把冒号后面当命令行
            send(CMD_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int remoteId = -1;

            while (true) {
                Msg m = read();

                if (m.cmd == CMD_OKAY) {
                    // a1 是本端的通道号，对上就说明通道开了
                    if (m.a1 == localId) remoteId = m.a0;
                    continue;
                }

                if (m.cmd == CMD_WRTE) {
                    if (m.data != null && m.data.length > 0) buf.write(m.data);
                    // 收到数据必须回 OKAY，否则 adbd 不会继续发
                    send(CMD_OKAY, m.a0, m.a1, null);
                    continue;
                }

                if (m.cmd == CMD_CLSE) {
                    // 对端关通道，我们回一条 CLSE 表示知道了，然后收工
                    if (m.a1 == localId) {
                        send(CMD_CLSE, m.a0, localId, null);
                        return buf.toString("UTF-8");
                    }
                    continue;
                }

                if (m.cmd == CMD_OPEN) {
                    // 我们不发 OPEN 请求，理论上不会有。真出现就拒绝掉
                    send(CMD_CLSE, 0, m.a0, null);
                }
            }
        }

        // ------------------------------------------------------------ 消息收发

        private void send(int cmd, int a0, int a1, byte[] data) throws Exception {
            int len = data == null ? 0 : data.length;
            ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(cmd);
            h.putInt(a0);
            h.putInt(a1);
            h.putInt(len);
            h.putInt(crc(data));
            h.putInt(cmd ^ 0xffffffff);
            out.write(h.array());
            if (len > 0) out.write(data);
            out.flush();
        }

        private Msg read() throws Exception {
            byte[] h = new byte[24];
            readFully(h, 24);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            Msg m = new Msg();
            m.cmd = b.getInt();
            m.a0 = b.getInt();
            m.a1 = b.getInt();
            m.len = b.getInt();
            b.getInt();          // 校验和，现在的 adbd 不校验，读掉就行
            b.getInt();          // magic
            if (m.len > 0 && m.len <= 4 * 1024 * 1024) {
                m.data = new byte[m.len];
                readFully(m.data, m.len);
            }
            return m;
        }

        private void readFully(byte[] dst, int n) throws Exception {
            int off = 0;
            while (off < n) {
                int r = in.read(dst, off, n - off);
                if (r < 0) throw new Denied("连接被电视断开了");
                off += r;
            }
        }

        private int crc(byte[] data) {
            if (data == null || data.length == 0) return 0;
            CRC32 c = new CRC32();
            c.update(data, 0, data.length);
            return (int) c.getValue();
        }

        // ------------------------------------------------------------ 密钥

        /**
         * 取本机保存的密钥对，没有就现生成一对存起来。
         *
         * <p>必须持久化：电视里记的是公钥指纹，每次换钥匙都得重新去电视上点一次允许。
         * 密钥存在应用私有目录的 SharedPreferences 里，卸载后一起清掉。
         */
        private KeyPair keyPair() throws Exception {
            SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
            String privB64 = sp.getString(K_PRIV, null);
            String pubB64 = sp.getString(K_PUB, null);

            if (privB64 != null && pubB64 != null) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    PrivateKey priv = kf.generatePrivate(
                            new PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)));
                    RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                            new java.security.spec.X509EncodedKeySpec(
                                    Base64.decode(pubB64, Base64.NO_WRAP)));
                    return new KeyPair(pub, priv);
                } catch (Throwable ignored) {
                    // 存的钥匙坏了就重新生成，代价是家长要再点一次允许
                }
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            sp.edit()
                    .putString(K_PRIV, Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString(K_PUB, Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
            return kp;
        }

        /** 用私钥对 adbd 给的 token 签名 */
        private byte[] signToken(byte[] token) throws Exception {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(keyPair().getPrivate());
            s.update(token);
            return s.sign();
        }

        /**
         * adb 要求的公钥格式：SSH wire format，再 base64。
         *
         * <p>结构是三段长度前缀加内容：类型名 "ssh-rsa"、指数 e、模数 n。
         * 末尾跟一个标识，电视的授权记录里会显示成一行小字，
         * 家长在弹框上能认出这是答题锁而不是别的什么在要权限。
         */
        private byte[] pubKeyBlob() throws Exception {
            RSAPublicKey pub = (RSAPublicKey) keyPair().getPublic();
            byte[] type = "ssh-rsa".getBytes("UTF-8");
            byte[] e = stripLeadingZeros(pub.getPublicExponent().toByteArray());
            byte[] n = stripLeadingZeros(pub.getModulus().toByteArray());

            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeIntBytes(b, type);
            writeIntBytes(b, e);
            writeIntBytes(b, n);

            String b64 = Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP);
            return (b64 + " tvguard-tv\u0000").getBytes("UTF-8");
        }

        private void writeIntBytes(ByteArrayOutputStream b, byte[] v) {
            int len = v.length;
            b.write((len >>> 24) & 0xff);
            b.write((len >>> 16) & 0xff);
            b.write((len >>> 8) & 0xff);
            b.write(len & 0xff);
            b.write(v, 0, v.length);
        }

        /**
         * BigInteger.toByteArray 会多带一个 0x00 符号位，SSH 格式不允许多余的前导零。
         * 这个坑不处理的话，公钥指纹算出来对不上，电视会一直拒绝。
         */
        private byte[] stripLeadingZeros(byte[] in) {
            int i = 0;
            while (i < in.length - 1 && in[i] == 0) i++;
            if (i == 0) return in;
            byte[] r = new byte[in.length - i];
            System.arraycopy(in, i, r, 0, r.length);
            return r;
        }
    }

    /** 一条 adb 消息 */
    private static final class Msg {
        int cmd;
        int a0;
        int a1;
        int len;
        byte[] data;
    }
}
