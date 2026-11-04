package com.tvguard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * 局域网小网页服务。
 *
 * 电视上没有微信、没有邮件客户端，报告「发」不出去。
 * 换个思路：让电视自己在局域网里开一个网页，家长用手机浏览器打开就能看。
 * 只监听局域网，数据不出家门，也不需要任何第三方服务。
 */
public class LanReportServer {

    public interface Provider {
        String html();
    }

    public static final int DEFAULT_PORT = 8666;

    private ServerSocket server;
    private Thread worker;
    private Provider provider;
    private int port = DEFAULT_PORT;
    private volatile boolean running;

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    /** 起服务，返回实际端口；失败返回 -1 */
    public synchronized int start(Provider p) {
        if (running) return port;
        provider = p;
        for (int i = 0; i < 10; i++) {
            int tryPort = DEFAULT_PORT + i;
            try {
                server = new ServerSocket(tryPort);
                port = tryPort;
                running = true;
                break;
            } catch (Exception ignored) {
                // 端口被占就换下一个
            }
        }
        if (!running) return -1;

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    Socket s = null;
                    try {
                        s = server.accept();
                        handle(s);
                    } catch (Exception ignored) {
                        // 关服务时 accept 会抛异常，属于正常退出
                    } finally {
                        if (s != null) {
                            try { s.close(); } catch (Exception ignored) { }
                        }
                    }
                }
            }
        }, "lan-report");
        worker.setDaemon(true);
        worker.start();
        return port;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(4000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String first = in.readLine();
            if (first == null) return;
            String path = "/";
            String[] parts = first.split(" ");
            if (parts.length >= 2) path = parts[1];

            String body;
            String status;
            if (path.startsWith("/favicon")) {
                status = "404 Not Found";
                body = "";
            } else {
                status = "200 OK";
                body = provider == null ? "<h1>报告未就绪</h1>" : provider.html();
            }
            byte[] data = body.getBytes("UTF-8");
            String head = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    /** 电视在局域网里的 IPv4 地址 */
    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("rmnet")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public String url() {
        String ip = localIp();
        if (ip == null) return null;
        return "http://" + ip + ":" + port + "/";
    }
}
