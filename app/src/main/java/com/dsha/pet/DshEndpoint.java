package com.dsha.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DSH 端点发现。
 *
 * 老版本把 http://127.0.0.1:38859 写死在代码里，换设备（DSHA 选了别的端口）就永远连不上。
 * 这里按 手动 > 缓存 > 扫描 的顺序找，握手用 /dsh-pet-7340/state 的 JSON 业务字段判断，
 * 不靠端口号猜。
 */
public final class DshEndpoint {
    /** dsh-pet 插件固定的路由前缀（见插件源码 ROUTE_PREFIX）。 */
    public static final String PATH = "/dsh-pet-7340";
    public static final int DEFAULT_PORT = 38859;
    /** 上次全端口扫描时间，避免短时间内反复空扫。 */
    private static volatile long lastFullScanAt = 0;
    /** 最近一次扫描中“端口开着但不是 DSH”的提示，供界面显示。 */
    public static volatile String lastHint = null;
    private static final Set<Integer> openPorts = java.util.Collections.synchronizedSet(new LinkedHashSet<Integer>());

    private static final String PREFS = "dsh_pet_endpoint";
    private static final String K_HOST = "ep_host";
    private static final String K_PORT = "ep_port";
    private static final String K_MANUAL = "ep_manual";

    public final String host;
    public final int port;
    public final String source; // manual / cache / scan

    public DshEndpoint(String host, int port, String source) {
        this.host = host;
        this.port = port;
        this.source = source;
    }

    public String base() {
        String h = host != null && host.contains(":") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + PATH;
    }

    public String url(String suffix) {
        if (suffix == null || suffix.isEmpty()) return base();
        return base() + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    public interface Listener {
        void onProgress(String message);
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 读缓存端点（不验证连通）。没有则返回 null。 */
    public static DshEndpoint cached(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String h = sp.getString(K_HOST, null);
        int p = sp.getInt(K_PORT, 0);
        if (h == null || p <= 0) return null;
        return new DshEndpoint(h, p, "cache");
    }

    /**
     * 阻塞解析端点。deep=true 时扫描 1024-65535 全端口，否则只扫常见动态端口段。
     * 必须放在后台线程调用。
     */
    public static DshEndpoint resolveBlocking(Context ctx, boolean deep, Listener l) {
        SharedPreferences sp = prefs(ctx);

        lastHint = null;
        openPorts.clear();

        // 1) 手动指定：用户说了算，连不上也不自动覆盖
        String manual = sp.getString(K_MANUAL, null);
        if (manual != null && !manual.isEmpty()) {
            String[] parts = manual.split(":", 2);
            try {
                int port = Integer.parseInt(parts[1].trim());
                progress(l, "正在连接手动端点 " + parts[0] + ":" + port + "…");
                if (probe(parts[0].trim(), port, 800, 1400)) {
                    DshEndpoint e = new DshEndpoint(parts[0].trim(), port, "manual");
                    save(sp, e);
                    return e;
                }
                progress(l, "手动端点连不上，继续自动查找…");
            } catch (Exception ignored) {
            }
        }

        // 2) 上次成功的端点
        DshEndpoint c = cached(ctx);
        if (c != null) {
            progress(l, "正在验证上次的端点 " + c + "…");
            if (probe(c.host, c.port, 400, 900)) return c;
        }

        // 3) 常见动态端口段（Linux 临时端口 32768-60999，覆盖大多数情况）
        List<Integer> fast = new ArrayList<>();
        Set<Integer> seed = new LinkedHashSet<>();
        if (c != null) seed.add(c.port);
        seed.add(DEFAULT_PORT);
        for (int p = 30000; p <= 61000; p++) seed.add(p);
        fast.addAll(seed);
        progress(l, "正在扫描本机端口（" + fast.size() + " 个）…");
        DshEndpoint hit = scan(fast, "127.0.0.1");
        if (hit == null) {
            progress(l, "IPv4 没找到，试试 IPv6 回环…");
            hit = scan(fast, "::1");
        }
        if (hit != null) {
            save(sp, hit);
            return hit;
        }

        // 4) 全量兜底：手动深度查找，或自动兜底（10 分钟内只做一次，避免反复空扫）
        boolean full = deep || (System.currentTimeMillis() - lastFullScanAt > 10 * 60 * 1000L);
        if (full) {
            lastFullScanAt = System.currentTimeMillis();
            Set<Integer> rest = new LinkedHashSet<>();
            for (int p = 1024; p <= 65535; p++) {
                if (p >= 30000 && p <= 61000) continue;
                rest.add(p);
            }
            progress(l, "深度扫描剩余端口（" + rest.size() + " 个）…");
            hit = scan(new ArrayList<>(rest), "127.0.0.1");
            if (hit == null) hit = scan(new ArrayList<>(rest), "::1");
            if (hit != null) {
                save(sp, hit);
                return hit;
            }
        }
        // 5) 扫完仍没有：看看是不是 DSH 起着但插件没加载
        for (int p : new ArrayList<>(openPorts)) {
            if (looksLikeDsh("127.0.0.1", p) || looksLikeDsh("::1", p)) {
                lastHint = "DSH 在运行，但桌宠插件没加载（端口 " + p + "）";
                break;
            }
        }
        return null;
    }

    /** 最近一次扫描中 TCP 能连上的端口，用于界面诊断。 */
    public static String openPortsSummary() {
        if (openPorts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int p : new ArrayList<>(openPorts)) {
            if (n++ > 0) sb.append("、");
            sb.append(p);
            if (n >= 6) { sb.append("…"); break; }
        }
        return sb.toString();
    }

    /** 单点探测：TCP 能连上且 /state 握手通过。 */
    public static boolean probe(String host, int port, int connectMs, int readMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectMs);
            return handshake(s, host, port, readMs);
        } catch (Throwable t) {
            return false;
        } finally {
            close(s);
        }
    }

    /** 判断某个端点是否是 DSH（根路径 401 特征），用于给出更准确的提示。 */
    public static boolean looksLikeDsh(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 500);
            s.setSoTimeout(900);
            OutputStream o = s.getOutputStream();
            o.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.flush();
            String raw = readAll(s);
            return raw.contains("dsh web authentication required");
        } catch (Throwable t) {
            return false;
        } finally {
            close(s);
        }
    }

    public static void setManual(Context ctx, String host, int port) {
        SharedPreferences sp = prefs(ctx);
        if (host == null || host.trim().isEmpty() || port <= 0) {
            sp.edit().remove(K_MANUAL).apply();
        } else {
            sp.edit().putString(K_MANUAL, host.trim() + ":" + port).apply();
        }
    }

    public static String getManual(Context ctx) {
        return prefs(ctx).getString(K_MANUAL, null);
    }

    public static void invalidate(Context ctx) {
        prefs(ctx).edit().remove(K_HOST).remove(K_PORT).apply();
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private static DshEndpoint scan(final List<Integer> ports, final String host) {
        final int threads = Math.min(128, Math.max(16, ports.size() / 64));
        final AtomicInteger idx = new AtomicInteger(0);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<DshEndpoint> hit = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!stop.get()) {
                        int i = idx.getAndIncrement();
                        if (i >= ports.size()) return;
                        int port = ports.get(i);
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(host, port), 90);
                            openPorts.add(port);
                            if (handshake(s, host, port, 700)) {
                                if (hit.compareAndSet(null,
                                        new DshEndpoint(host, port, "scan"))) {
                                    stop.set(true);
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            close(s);
                        }
                    }
                }
            }, "dsh-scan-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return hit.get();
    }

    // ------------------------------------------------------------------
    // HTTP 握手（裸 Socket，避免连接池问题）
    // ------------------------------------------------------------------

    private static boolean handshake(Socket s, String host, int port, int readMs) {
        try {
            s.setSoTimeout(readMs);
            OutputStream o = s.getOutputStream();
            String req = "GET " + PATH + "/state HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: application/json\r\n"
                    + "User-Agent: dsh-pet-android\r\n\r\n";
            o.write(req.getBytes("UTF-8"));
            o.flush();
            String raw = readAll(s);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) return false;
            String head = raw.substring(0, split);
            if (!head.startsWith("HTTP/1.") || !head.contains(" 200")) return false;
            String body = raw.substring(split + 4);
            return body.contains("\"sections\"")
                    || body.contains("\"workStatus\"")
                    || body.contains("\"balance\"");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAll(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > (1 << 20)) break;
        }
        return bos.toString("UTF-8");
    }

    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void save(SharedPreferences sp, DshEndpoint e) {
        sp.edit().putString(K_HOST, e.host).putInt(K_PORT, e.port).apply();
    }

    private static void progress(Listener l, String msg) {
        if (l != null) l.onProgress(msg);
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
