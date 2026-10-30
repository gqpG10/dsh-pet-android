package com.dsha.pet;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PetService extends Service {
    public static final String STATE_URL = "http://127.0.0.1:38859/dsh-pet-7340/state";
    public static final String STATE_BASE = "http://127.0.0.1:38859/dsh-pet-7340";
    public static volatile String lastPoll = "未运行";
    private static final String DSHA_PKG = "dsh.android.client";

    private WindowManager wm;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private Handler ui;
    private ExecutorService net;
    private volatile boolean alive = true;

    private float downX, downY;
    private float petScale = 1f;
    private int curAnimIdx = 0;
    private float dragDist;
    private int startLpX, startLpY;
    private long downTime;
    private long lastTapUp = 0;
    private long lastTouch = 0;
    private float lastTapX, lastTapY;
    private boolean moved;
    private final int slop = 12;
    private Runnable longPressRunnable;
    private boolean menuShown;
    private String lastBalanceText = "";
    private int homeX, homeY;
    private float anchorX = Float.NaN, anchorY = Float.NaN;
    private org.json.JSONObject actionsCache;

    // ---- 物理 / 好感度 ----
    private boolean flying;
    private float flyVx, flyVy;
    private long lastFrameTime;
    private VelocityTracker vt;
    private SharedPreferences prefs;
    private int affection;
    private Vibrator vib;
    private final Runnable resumeTick = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            long now = SystemClock.uptimeMillis();
            if (!flying && menuView == null && now - lastTouch > 2500) {
                js("window.petResume && petResume()");
            }
            ui.postDelayed(this, 700);
        }
    };

    private final Runnable flyTick = new Runnable() {
        @Override
        public void run() {
            if (!flying || !alive) return;
            long now = System.nanoTime();
            float dt = lastFrameTime == 0 ? 0.016f : (now - lastFrameTime) / 1e9f;
            lastFrameTime = now;
            if (dt > 0.04f) dt = 0.04f;
            if (dt < 0.004f) dt = 0.004f;
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int minX = 0, maxX = Math.max(0, sw - lp.width);
            int minY = 0, maxY = Math.max(0, sh - lp.height);
            float x = lp.x + flyVx * dt;
            float y = lp.y + flyVy * dt;
            boolean hit = false;
            String dir = null;
            // 反射而不是简单钳位：保留多余位移，撞墙后的轨迹连续，不会“顿一下”
            if (x < minX) {
                x = minX + (minX - x); flyVx = -flyVx * 0.95f; hit = true; dir = "x";
            } else if (x > maxX) {
                x = maxX - (x - maxX); flyVx = -flyVx * 0.94f; hit = true; dir = "x";
            }
            if (y < minY) {
                y = minY + (minY - y); flyVy = -flyVy * 0.95f; hit = true; dir = dir == null ? "y" : "xy";
            } else if (y > maxY) {
                y = maxY - (y - maxY); flyVy = -flyVy * 0.94f; hit = true; dir = dir == null ? "y" : "xy";
            }
            x = Math.max(minX, Math.min(maxX, x));
            y = Math.max(minY, Math.min(maxY, y));
            if (hit) {
                // 墙只做反射，不补充能量：撞完照样按线性减速走
                js("window.petBounce && petBounce(" + JSONObject.quote(dir) + ")");
                buzz(8);
            }
            int nx = Math.round(x), ny = Math.round(y);
            if (nx != lp.x || ny != lp.y) moveWindow(nx, ny);
            float sp = (float) Math.hypot(flyVx, flyVy);
            if (sp > 0f) {
                float ns = Math.max(0f, sp - FLY_DECEL * dt);
                float k = ns / sp;
                flyVx *= k;
                flyVy *= k;
                sp = ns;
            }
            if (sp < FLY_STOP) {
                flying = false;
                flyVx = 0;
                flyVy = 0;
                js("window.petResume && petResume()");
            } else {
                web.postOnAnimation(this);
            }
        }
    };
    private View menuView;
    private View bubbleView;
    private int bubbleW, bubbleH;
    private String lastState = "";
    private Typeface cute;
    private String lastShownBalance = "";
    private long lastSayCounter = -1;
    private boolean sayInit = false;
    private String lastWhisperText = null;
    private long lastWhisperAt = 0;
    private final Runnable hideBubbleR = new Runnable() {
        @Override
        public void run() {
            hideBubbleNow();
        }
    };
    private final Runnable autoClose = new Runnable() {
        @Override
        public void run() {
            hideMenu();
        }
    };

    private interface Pick {
        void on(int i);
    }

    private long lastNotifyCounter = -1;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        float d = getResources().getDisplayMetrics().density;
        ui = new Handler(Looper.getMainLooper());
        net = Executors.newCachedThreadPool();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            cute = Typeface.create(Typeface.createFromAsset(getAssets(), "font/cute.ttf"), Typeface.BOLD);
        } catch (Exception ignored) {
        }
        prefs = getSharedPreferences("pet", MODE_PRIVATE);
        affection = prefs.getInt("affection", 0);
        petScale = prefs.getFloat("petScale", 1f);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vt = VelocityTracker.obtain();

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new PetHost(), "PetHost");
        web.loadUrl("file:///android_asset/pet.html");

        loadBboxes();
        int[] r0 = rectFor(0);
        curRect = r0.clone();
        int w = Math.max(1, Math.round(r0[2] * K * d)), h = Math.max(1, Math.round(r0[3] * K * d));
        lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (16 * d);
        lp.y = (int) (160 * d);
        homeX = lp.x;
        homeY = lp.y;

        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (moved || !alive) return;
                js("window.petSquash && petSquash()");
                buzz(28);
                menuShown = true;
                showMenu();
            }
        };

        web.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 飞行中按一下 = 接住
                        if (flying) {
                            flying = false;
                            ui.removeCallbacks(flyTick);
                            flyVx = 0;
                            flyVy = 0;
                            js("window.petResume && petResume()");
                            js("window.petSquash && petSquash()");
                            showBubble("接住啦～", 2200);
                            addAffection(2, "♥ 接住 +2");
                            buzz(25);
                            return true;
                        }
                        hideMenu();
                        hideBubbleNow();
                        js("window.petFreeze && petFreeze()");
                        lastTouch = SystemClock.uptimeMillis();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragDist = 0f;
                        downTime = e.getEventTime();
                        startLpX = lp.x;
                        startLpY = lp.y;
                        moved = false;
                        menuShown = false;
                        ui.removeCallbacks(longPressRunnable);
                        vt.clear();
                        vt.addMovement(e);
                        ui.postDelayed(longPressRunnable, 500);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        vt.addMovement(e);
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        dragDist = Math.max(dragDist, (float) Math.hypot(dx, dy));
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            moved = true;
                            ui.removeCallbacks(longPressRunnable);
                        }
                        if (moved && dragDist > 12 * d) {
                            lastTouch = SystemClock.uptimeMillis();
                            moveWindow(clampX(startLpX + (int) dx), clampY(startLpY + (int) dy));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        ui.removeCallbacks(longPressRunnable);
                        lastTouch = SystemClock.uptimeMillis();
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000, 5200f);
                        float vx = vt.getXVelocity();
                        float vy = vt.getYVelocity();
                        vt.clear();
                        if (moved) {
                            // 真甩出去要满足：拖动距离够长 + 松手速度够快；轻点/微动只当点击
                            if (dragDist > 24 * d && Math.hypot(vx, vy) > 1400) {
                                startFly(vx, vy);
                                return true;
                            }
                            js("window.petResume && petResume()");
                        }
                        if (menuShown) {
                            js("window.petResume && petResume()");
                            return true;
                        }
                        long now = System.currentTimeMillis();
                        boolean dbl = now - lastTapUp < 400
                                && Math.abs(e.getRawX() - lastTapX) < 80 * d
                                && Math.abs(e.getRawY() - lastTapY) < 80 * d;
                        if (dbl) {
                            lastTapUp = 0;
                            openDsha();
                        } else {
                            lastTapUp = now;
                            lastTapX = e.getRawX();
                            lastTapY = e.getRawY();
                            if (web != null) {
                                web.evaluateJavascript(
                                        "window.petSquash && petSquash(); window.petTap && petTap()", null);
                            }
                            addAffection(1, null);
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    js("window.petResume && petResume()");
                                }
                            }, 1200);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("pet", "桌宠", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "pet")
                : new Notification.Builder(this);
        b.setContentTitle("桌宠运行中 · 好感 Lv." + heartLevel())
                .setContentText("单击Q弹 · 双击开DSH · 甩出去会飞 · 飞行中按一下接住")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        wm.addView(web, lp);
        ui.postDelayed(resumeTick, 800);
        final Thread poller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "pet-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void logPoll(String text) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "poll.log");
            if (f.length() > 200000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new java.util.Date()) + " " + text + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    private static final float K = 150f / 640f;
    private static final float FLY_DECEL = 480f; // px/s²，线性减速
    private static final float FLY_STOP = 40f;
    private JSONArray bboxes;
    private int[] curRect = {0, 0, 640, 360};

    private void loadBboxes() {
        try {
            java.io.InputStream in = getAssets().open("bbox.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            bboxes = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            bboxes = null;
        }
    }

    private int[] rectFor(int idx) {
        if (bboxes != null && idx >= 0 && idx < bboxes.length()) {
            try {
                JSONArray a = bboxes.getJSONArray(idx);
                return new int[]{a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)};
            } catch (Exception ignored) {
            }
        }
        return new int[]{0, 0, 640, 360};
    }

    public class PetHost {
        @JavascriptInterface
        public void setAnim(final int idx) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    curAnimIdx = idx;
                    applyAnimBox(idx);
                }
            });
        }
    }

    private void applyAnimBox(int idx) {
        if (!alive || lp == null) return;
        int[] r = rectFor(idx);
        float d = getResources().getDisplayMetrics().density;
        float PADW = 1.4f;
        // 宠物缩放：只影响模型窗口，气泡/菜单是独立窗口不受影响
        float sc = petScale;
        float maxW = getResources().getDisplayMetrics().widthPixels * 0.92f
                / Math.max(1f, r[2] * K * d * PADW);
        float maxH = getResources().getDisplayMetrics().heightPixels * 0.92f
                / Math.max(1f, r[3] * K * d * PADW);
        sc = Math.min(sc, Math.min(maxW, maxH));
        int nw = Math.max(1, Math.round(r[2] * K * d * PADW * sc));
        int nh = Math.max(1, Math.round(r[3] * K * d * PADW * sc));
        // 画布内缩比例：与 pet.html 里的 pad 保持一致
        float PAD = 1f / 7f;
        float cw = Math.max(1, curRect[2]);
        float ch = Math.max(1, curRect[3]);
        // 宠物锚点（视频坐标 320,200）在旧窗口中的相对位置
        float oldRx = PAD + (320 - curRect[0]) * (1 - 2 * PAD) / cw;
        float oldRy = PAD + (200 - curRect[1]) * (1 - 2 * PAD) / ch;
        if (Float.isNaN(anchorX)) {
            anchorX = lp.x + oldRx * lp.width;
            anchorY = lp.y + oldRy * lp.height;
        }
        // 在新窗口中的相对位置
        float newRx = PAD + (320 - r[0]) * (1 - 2 * PAD) / Math.max(1, r[2]);
        float newRy = PAD + (200 - r[1]) * (1 - 2 * PAD) / Math.max(1, r[3]);
        int nx = Math.round(anchorX - newRx * nw);
        int ny = Math.round(anchorY - newRy * nh);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        lp.width = nw;
        lp.height = nh;
        int px = Math.max(0, Math.min(sw - nw, nx));
        int py = Math.max(0, Math.min(sh - nh, ny));
        lp.x = px;
        lp.y = py;
        // 从“实际落点”回写锚点：即使被屏幕边缘夹住，也不会在下次换动作时来回拉扯
        anchorX = px + newRx * nw;
        anchorY = py + newRy * nh;
        curRect = r;
        wm.updateViewLayout(web, lp);
        js("window.petBox && window.petBox(" + r[0] + "," + r[1] + "," + r[2] + "," + r[3] + ")");
    }

    /** 统一移动窗口：同时平移浮点锚点，避免取整误差累积造成“飘”。 */
    private void moveWindow(int x, int y) {
        if (lp == null || web == null) return;
        if (!Float.isNaN(anchorX)) {
            anchorX += x - lp.x;
            anchorY += y - lp.y;
        }
        lp.x = x;
        lp.y = y;
        wm.updateViewLayout(web, lp);
    }

    private void getJson(final String url, final StrCb cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = httpGet(url);
                } catch (Exception e) {
                    out = null;
                }
                final String res = out;
                logPoll("POST " + url + " -> " + (res == null ? "null" : res.substring(0, Math.min(70, res.length()))));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.on(res);
                    }
                });
            }
        });
    }

    private static String totalFromState(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject sections = root.optJSONObject("sections");
            if (sections == null) return null;
            JSONObject bal = sections.optJSONObject("balance");
            if (bal == null) return null;
            JSONObject data = bal.optJSONObject("data");
            if (data == null || !data.optBoolean("ok", false)) return null;
            JSONObject inner = data.optJSONObject("data");
            if (inner == null) return null;
            JSONArray wallets = inner.optJSONArray("wallets");
            if (wallets == null || wallets.length() == 0) return null;
            return wallets.getJSONObject(0).optString("total", null);
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshBalance(final StrCb cb) {
        postJson(PetService.STATE_BASE + "/balance", null, new StrCb() {
            @Override
            public void on(String b) {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getJson(PetService.STATE_BASE + "/state", new StrCb() {
                            @Override
                            public void on(String body) {
                                String t = totalFromState(body);
                                if (t != null) {
                                    lastBalanceText = t;
                                    lastShownBalance = t;
                                }
                                if (cb != null) cb.on(t);
                            }
                        });
                    }
                }, 1000);
            }
        });
    }

    private void js(String code) {
        try {
            web.evaluateJavascript(code, null);
        } catch (Exception ignored) {
        }
    }

    private org.json.JSONObject loadActions() {
        if (actionsCache != null) return actionsCache;
        try {
            java.io.InputStream in = getAssets().open("actions.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            actionsCache = new org.json.JSONObject(bo.toString("UTF-8"));
        } catch (Exception ignored) {
            actionsCache = new org.json.JSONObject();
        }
        return actionsCache;
    }

    private void hideMenu() {
        ui.removeCallbacks(autoClose);
        if (menuView != null) {
            try {
                wm.removeView(menuView);
            } catch (Exception ignored) {
            }
            menuView = null;
        }
    }

    private void showPage(String title, String[] labels, final Pick pick) {
        hideMenu();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF02B2B30);
        bg.setCornerRadius(14 * d);
        bg.setStroke((int) (1 * d), 0x33FFFFFF);
        box.setBackground(bg);
        box.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(0xFFAAAAAA);
            t.setTextSize(10);
            if (cute != null) t.setTypeface(cute);
            t.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
            box.addView(t);
        }
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(12f);
            if (cute != null) tv.setTypeface(cute);
            tv.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.on(idx);
                }
            });
            box.addView(tv);
        }
        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = box.getMeasuredWidth();
        int bh = box.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int mx = lp.x + lp.width + (int) (6 * d);
        if (mx + bw > screenW) mx = Math.max(0, lp.x - bw - (int) (6 * d));
        int my = Math.min(lp.y, Math.max(0, screenH - bh - (int) (60 * d)));
        mlp.x = mx;
        mlp.y = my;
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = box;
        wm.addView(box, mlp);
        ui.postDelayed(autoClose, 12000);
    }

    /** DSHA（DeepSeek Harness Android）是否安装。三个联动功能依赖它。 */
    private boolean hasDsha() {
        try {
            getPackageManager().getPackageInfo("dsh.android.client", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showMenu() {
        final boolean dsha = hasDsha();
        java.util.ArrayList<String> ls = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        if (dsha) {
            ls.add("打开 DSH"); ids.add(0);
            ls.add("查看余额"); ids.add(1);
            ls.add("碎碎念"); ids.add(2);
            ls.add("对话"); ids.add(3);
        }
        ls.add("动作 ▸"); ids.add(4);
        ls.add("大小 ▸"); ids.add(5);
        ls.add("显示位置 ▸"); ids.add(6);
        ls.add("回到初始位置"); ids.add(7);
        ls.add("好感度"); ids.add(8);
        ls.add("退出桌宠"); ids.add(9);
        if (!dsha) {
            ls.add("获取 DSHA ↓"); ids.add(10);
        }
        final String[] labels = ls.toArray(new String[0]);
        showPage("桌宠 · 好感 Lv." + heartLevel(), labels,
                new Pick() {
                    @Override
                    public void on(int i) {
                        int act = (i >= 0 && i < ids.size()) ? ids.get(i) : -1;
                        switch (act) {
                            case 0:
                                hideMenu();
                                openDsha();
                                break;
                            case 1:
                                hideMenu();
                                showBubble("查一下余额…", 1600);
                                refreshBalance(new StrCb() {
                                    @Override
                                    public void on(String t) {
                                        if (t == null) showBubble("余额没查到，DSH 没响应", 2600);
                                        else showBubble("余额 ¥" + t, 6000);
                                    }
                                });
                                break;
                            case 2:
                                hideMenu();
                                doWhisper(true);
                                break;
                            case 3:
                                hideMenu();
                                showBubble("去 DSH 里跟我说话吧～", 2200);
                                openDsha();
                                break;
                            case 4:
                                showActions();
                                break;
                            case 5:
                                showSize();
                                break;
                            case 6:
                                showDisplay();
                                break;
                            case 7:
                                hideMenu();
                                flying = false;
                                ui.removeCallbacks(flyTick);
                                flyVx = 0;
                                flyVy = 0;
                                anchorX = Float.NaN;
                                anchorY = Float.NaN;
                                moveWindow(homeX, homeY);
                                break;
                            case 8:
                                hideMenu();
                                showBubble("♥ 好感 Lv." + heartLevel() + "  " + affection + " 点", 3000);
                                break;
                            case 9:
                                hideMenu();
                                stopSelf();
                                break;
                            case 10:
                                hideMenu();
                                openDshaDownload();
                                break;
                            default:
                                break;
                        }
                    }
                });
    }

    /** 没装 DSHA 时，引导去 GitHub 下载。 */
    private void openDshaDownload() {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/DSH-APP/DSHA"));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            showBubble("浏览器没打开，去 GitHub 搜 DSH-APP/DSHA", 3200);
        }
    }

    private void doWhisper(final boolean retry) {
        showBubble("让我想想…", 1800);
        postJson(STATE_BASE + "/whisper?pet=main&surface=web", null, new StrCb() {
            @Override
            public void on(String b) {
                if (b == null) {
                    showBubble("碎碎念没发出去…DSH 没响应", 2600);
                    return;
                }
                String t = null;
                String why = null;
                try {
                    JSONObject o = new JSONObject(b);
                    t = o.optString("text", null);
                    if (!o.optBoolean("ok", false)) {
                        why = o.optString("message", o.optString("reason", null));
                    }
                } catch (Exception ignored) {
                }
                if (t != null && !t.isEmpty()) {
                    lastWhisperText = t;
                    lastWhisperAt = System.currentTimeMillis();
                    showBubble(t, 7000);
                    return;
                }
                // 显示模式不是 web/both 时服务端会拒绝，自动改成 both 再重试一次
                if (retry && b.contains("pet-not-displayed")) {
                    postJson(STATE_BASE + "/pet-display", "{\"id\":\"main\",\"display\":\"both\"}",
                            new StrCb() {
                                @Override
                                public void on(String r) {
                                    doWhisper(false);
                                }
                            });
                    return;
                }
                if (why != null) {
                    showBubble("碎碎念失败：" + why, 3200);
                } else {
                    showBubble(FALLBACK_WHISPER[(int) (Math.random() * FALLBACK_WHISPER.length)], 6000);
                }
            }
        });
    }

    private void showSize() {
        hideMenu();
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF02B2B30);
        bg.setCornerRadius(14 * d);
        bg.setStroke((int) (1 * d), 0x33FFFFFF);
        box.setBackground(bg);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));

        final TextView title = new TextView(this);
        title.setText("大小 · " + Math.round(petScale * 100) + "%");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12f);
        if (cute != null) title.setTypeface(cute);
        box.addView(title);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(150);
        sb.setProgress(Math.round((petScale - 0.5f) * 100));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                (int) (210 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (2 * d);
        sb.setLayoutParams(slp);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                petScale = 0.5f + p / 100f;
                title.setText("大小 · " + Math.round(petScale * 100) + "%");
                applyAnimBox(curAnimIdx);
                ui.removeCallbacks(autoClose);
                ui.postDelayed(autoClose, 12000);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putFloat("petScale", petScale).apply();
            }
        });
        box.addView(sb);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = new TextView(this);
        reset.setText("重置 100%");
        reset.setTextColor(0xFFBBBBBB);
        reset.setTextSize(11f);
        if (cute != null) reset.setTypeface(cute);
        reset.setPadding(0, (int) (6 * d), (int) (24 * d), (int) (4 * d));
        reset.setClickable(true);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                petScale = 1f;
                sb.setProgress(100);
                title.setText("大小 · 100%");
                applyAnimBox(curAnimIdx);
                prefs.edit().putFloat("petScale", petScale).apply();
            }
        });
        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(0xFFFFFFFF);
        done.setTextSize(11f);
        if (cute != null) done.setTypeface(cute);
        done.setPadding((int) (24 * d), (int) (6 * d), 0, (int) (4 * d));
        done.setClickable(true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
            }
        });
        row.addView(reset);
        row.addView(done);
        box.addView(row);

        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = box.getMeasuredWidth();
        int bh = box.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int mx = lp.x + lp.width + (int) (6 * d);
        if (mx + bw > screenW) mx = Math.max(0, lp.x - bw - (int) (6 * d));
        int my = Math.min(lp.y, Math.max(0, screenH - bh - (int) (60 * d)));
        mlp.x = mx;
        mlp.y = my;
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = box;
        wm.addView(box, mlp);
        ui.postDelayed(autoClose, 12000);
    }

    private void showDisplay() {
        final String[] ids = {"web", "desktop", "both", "none"};
        showPage("显示位置", new String[]{"‹ 返回", "仅网页", "仅桌面", "两者都显示", "都不显示"},
                new Pick() {
                    @Override
                    public void on(int i) {
                        if (i == 0) {
                            showMenu();
                            return;
                        }
                        final String id = ids[i - 1];
                        hideMenu();
                        String body = "{\"id\":\"main\",\"display\":" + JSONObject.quote(id) + "}";
                        postJson(PetService.STATE_BASE + "/pet-display", body, new StrCb() {
                            @Override
                            public void on(String b) {
                                showBubble(b == null ? "显示位置没改成：DSH 接口没响应" : "显示位置已改为 " + id, 2400);
                            }
                        });
                    }
                });
    }

    /** 宠物旁边的独立小气泡（不压在宠物身上），自动避让屏幕边缘。 */
    private void showBubble(final String text, final int ms) {
        if (text == null || text.length() == 0 || !alive) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                try {
                    ui.removeCallbacks(hideBubbleR);
                    hideBubbleNow();
                    TextView tv = new TextView(PetService.this);
                    tv.setText(text);
                    tv.setTextColor(0xFF2B2B30);
                    tv.setTextSize(12f);
                    if (cute != null) tv.setTypeface(cute);
                    tv.setLineSpacing(0, 1.15f);
                    float d = getResources().getDisplayMetrics().density;
                    int padH = (int) (11 * d), padV = (int) (8 * d);
                    tv.setPadding(padH, padV, padH, padV);
                    tv.setMaxWidth((int) (230 * d));
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(0xFFFFFDF6);
                    g.setCornerRadius(15 * d);
                    g.setStroke(Math.max(1, (int) d), 0x22000000);
                    tv.setBackground(g);
                    tv.measure(View.MeasureSpec.makeMeasureSpec((int) (230 * d), View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    bubbleW = tv.getMeasuredWidth();
                    bubbleH = tv.getMeasuredHeight();
                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    p.gravity = Gravity.TOP | Gravity.START;
                    p.x = bubbleX();
                    p.y = bubbleY();
                    wm.addView(tv, p);
                    bubbleView = tv;
                    ui.postDelayed(hideBubbleR, ms <= 0 ? 3000 : ms);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private int bubbleX() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int x = lp.x + (lp.width - bubbleW) / 2;
        return Math.max(0, Math.min(Math.max(0, sw - bubbleW), x));
    }

    private int bubbleY() {
        int gap = (int) (5 * getResources().getDisplayMetrics().density);
        int y = lp.y - bubbleH - gap;
        if (y < 0) y = lp.y + lp.height + gap;
        int sh = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, Math.min(Math.max(0, sh - bubbleH), y));
    }

    private void placeBubble() {
        if (bubbleView == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) bubbleView.getLayoutParams();
            p.x = bubbleX();
            p.y = bubbleY();
            wm.updateViewLayout(bubbleView, p);
        } catch (Exception ignored) {
        }
    }

    private void hideBubbleNow() {
        if (bubbleView != null) {
            try {
                wm.removeView(bubbleView);
            } catch (Exception ignored) {
            }
            bubbleView = null;
        }
    }

    private static final String[] FALLBACK_WHISPER = {
            "主人在忙什么呀，我就在旁边看着～",
            "有点困了…让我趴一会儿",
            "今天也要加油鸭！",
            "偷偷看你一眼，嘿嘿",
            "要不要摸摸我的头？",
            "碎碎念一句：记得喝水呀",
            "我在这儿呢，别把我忘了～",
            "桌面好安静，陪我玩会儿吧"
    };

    private static final String[][] WORK_TEXTS = {
            {"正在认真想下一步呢", "让我整理一下思路~", "正在梳理接下来要做什么"},
            {"正在处理任务呢", "这一步正在进行中哦", "正在继续干活呢，别打扰我~"},
            {"这一步处理好啦，接着看下一步", "搞定一步，继续看看哦~"},
            {"需要你确认一下呢", "这里要等你看一下哦", "轮到你来决定啦，等你~"},
            {"这一轮搞定啦，真棒！", "任务完成咯，可以摸鱼啦~"},
            {"这一步好像没跑通呢", "刚才的操作遇到点问题哦", "这里卡了一下，再等等你呢"}
    };

    private static String pickWork(int i) {
        if (i < 0 || i >= WORK_TEXTS.length) return null;
        String[] a = WORK_TEXTS[i];
        return a[(int) (Math.random() * a.length) % a.length];
    }

    private static final String[] WORK_KEYS = {"thinking", "working", "result", "waiting", "success", "error"};

    private void toastJs(String text) {
        showBubble(text, 2600);
    }

    private int clampX(int x) {
        int max = Math.max(0, getResources().getDisplayMetrics().widthPixels - lp.width);
        return Math.max(0, Math.min(max, x));
    }

    private int clampY(int y) {
        int max = Math.max(0, getResources().getDisplayMetrics().heightPixels - lp.height);
        return Math.max(0, Math.min(max, y));
    }

    /** 松手甩出去：带阻尼的惯性飞行，撞到屏幕边反弹并 Q 弹一下。 */
    private void startFly(float vx, float vy) {
        hideBubbleNow();
        js("window.petFreeze && petFreeze()");
        flying = true;
        flyVx = Math.max(-2200f, Math.min(2200f, vx));
        flyVy = Math.max(-2200f, Math.min(2200f, vy));
        lastFrameTime = System.nanoTime();
        web.postOnAnimation(flyTick);
    }

    private void buzz(int ms) {
        try {
            if (vib != null && vib.hasVibrator()) vib.vibrate(ms);
        } catch (Exception ignored) {
        }
    }

    private int heartLevel() {
        return 1 + affection / 20;
    }

    private void addAffection(int n, String text) {
        affection += n;
        if (affection < 0) affection = 0;
        try {
            prefs.edit().putInt("affection", affection).apply();
        } catch (Exception ignored) {
        }
        if (text != null) {
            showBubble(text + "  Lv." + heartLevel(), 2600);
        } else if (affection % 10 == 0) {
            showBubble("♥ 好感 Lv." + heartLevel() + "  " + affection, 2600);
        }
    }

    private interface StrCb {
        void on(String body);
    }

    /** 后台 POST（body 可为 null），结果回主线程。 */
    private void postJson(final String url, final String body, final StrCb cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                String out = null;
                try {
                    out = httpPost(url, body);
                } catch (Exception e) {
                    out = "ERR " + e.getClass().getSimpleName();
                }
                final String res = out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.on(res);
                    }
                });
            }
        });
    }


    private void showActions() {
        final org.json.JSONObject root = loadActions();
        final java.util.List<String> cats = new ArrayList<>();
        Iterator<String> it = root.keys();
        while (it.hasNext()) cats.add(it.next());
        if (cats.isEmpty()) return;
        String[] labels = new String[cats.size() + 1];
        labels[0] = "‹ 返回";
        for (int i = 0; i < cats.size(); i++) labels[i + 1] = cats.get(i);
        showPage("换个动作", labels, new Pick() {
            @Override
            public void on(int i) {
                if (i == 0) {
                    showMenu();
                    return;
                }
                final String cat = cats.get(i - 1);
                final JSONArray arr = root.optJSONArray(cat);
                if (arr == null || arr.length() == 0) return;
                final String[] names = new String[arr.length() + 1];
                names[0] = "‹ 返回";
                for (int k = 0; k < arr.length(); k++) names[k + 1] = arr.optString(k, "");
                showPage(cat, names, new Pick() {
                    @Override
                    public void on(int j) {
                        if (j == 0) {
                            showActions();
                            return;
                        }
                        hideMenu();
                        js("window.petPlay && petPlay(" + JSONObject.quote(names[j]) + ")");
                    }
                });
            }
        });
    }

    private void openDsha() {
        String err = null;
        try {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            q.setPackage(DSHA_PKG);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(q, 0);
            if (list != null && !list.isEmpty()) {
                ActivityInfo ai = list.get(0).activityInfo;
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(ai.packageName, ai.name));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
            err = "没有找到启动入口";
        } catch (Exception e) {
            err = e.getClass().getSimpleName();
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(DSHA_PKG);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName();
        }
        if (web != null) web.evaluateJavascript("window.petTap && petTap()", null);
        final String msg = err == null ? "打不开 DSHA" : "打不开 DSHA：" + err;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PetService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void pollLoop() {
        while (alive) {
            try {
                String body = httpGet(STATE_URL);
                if (body != null) {
                    lastPoll = "OK " + body.length() + "B";
                    logPoll(lastPoll);
                    handleState(body);
                } else {
                    lastPoll = "HTTP 非 200";
                    logPoll(lastPoll);
                }
            } catch (Exception e) {
                lastPoll = "ERR " + e;
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                logPoll(sw.toString());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static String httpGet(String url) throws Exception {
        return rawRequest("GET", url, null);
    }

    private static String httpPost(String url, String body) throws Exception {
        return rawRequest("POST", url, body);
    }

    /** 裸 Socket HTTP/1.1：绕过 OkHttp 连接池，避免复用到已被服务端关掉的旧连接。 */
    private static String rawRequest(String method, String url, String body) throws Exception {
        URL u = new URL(url);
        String host = u.getHost();
        int port = u.getPort() < 0 ? 80 : u.getPort();
        String path = u.getFile();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setSoTimeout(4000);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(':').append(port).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("Accept: */*\r\n");
            req.append("User-Agent: dsh-pet-android\r\n");
            byte[] bb = body == null ? null : body.getBytes("UTF-8");
            if (bb != null) {
                req.append("Content-Type: application/json; charset=utf-8\r\n");
                req.append("Content-Length: ").append(bb.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            if (bb != null) os.write(bb);
            os.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            String resp = all.toString("UTF-8");
            int idx = resp.indexOf("\r\n\r\n");
            if (idx < 0) return null;
            String head = resp.substring(0, idx).toLowerCase(Locale.US);
            String payload = resp.substring(idx + 4);
            if (!head.startsWith("http/1.1 200") && !head.startsWith("http/1.0 200")) return null;
            if (head.contains("transfer-encoding: chunked")) payload = dechunk(payload);
            return payload;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static String dechunk(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int e = s.indexOf("\r\n", i);
            if (e < 0) break;
            int size;
            try { size = Integer.parseInt(s.substring(i, e).trim(), 16); } catch (Exception x) { break; }
            if (size == 0) break;
            int from = e + 2;
            int to = Math.min(s.length(), from + size);
            out.append(s, from, to);
            i = to + 2;
        }
        return out.toString();
    }

    private void handleState(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject sections = root.optJSONObject("sections");
        if (sections == null) return;

        String state = null;
        String balance = null;
        String notify = null;

        JSONObject ws = sections.optJSONObject("workStatus");
        if (ws != null) {
            JSONObject data = ws.optJSONObject("data");
            if (data != null && !data.isNull("state")) state = data.optString("state", null);
        }

        JSONObject bal = sections.optJSONObject("balance");
        if (bal != null) {
            JSONObject data = bal.optJSONObject("data");
            if (data != null && data.optBoolean("ok", false)) {
                JSONObject inner = data.optJSONObject("data");
                if (inner != null) {
                    JSONArray wallets = inner.optJSONArray("wallets");
                    if (wallets != null && wallets.length() > 0) {
                        balance = wallets.getJSONObject(0).optString("total", null);
                            if (balance != null) lastBalanceText = balance;
                    }
                }
            }
        }

        JSONObject nf = sections.optJSONObject("notify");
        if (nf != null) {
            long counter = nf.optLong("counter", -1);
            if (counter != lastNotifyCounter) {
                lastNotifyCounter = counter;
                JSONObject data = nf.optJSONObject("data");
                if (data != null) {
                    JSONObject ev = data.optJSONObject("event");
                    if (ev != null) {
                        String t = ev.optString("type", "");
                        if ("turn/end".equals(t)) {
                            JSONObject ed = ev.optJSONObject("data");
                            String kind = null;
                            if (ed != null) {
                                JSONObject reason = ed.optJSONObject("reason");
                                if (reason != null) kind = reason.optString("kind", null);
                            }
                            if ("completed".equals(kind)) notify = "success";
                            else if (kind != null) notify = "error";
                        } else if ("approval/asked".equals(t)) {
                            notify = "waiting";
                        }
                    }
                }
            }
        }

        if (balance != null && !balance.equals(lastShownBalance)) {
            lastShownBalance = balance;
            showBubble("余额 ¥" + balance, 5000);
        }
        if (notify != null) {
            if ("success".equals(notify)) showBubble(pickWork(4), 4500);
            else if ("error".equals(notify)) showBubble(pickWork(5), 4500);
            else showBubble(pickWork(3), 4500);
        }
        if (state != null && !state.equals(lastState)) {
            lastState = state;
            int wi = 0;
            for (int k = 0; k < WORK_KEYS.length; k++) {
                if (WORK_KEYS[k].equals(state)) wi = k;
            }
            showBubble(pickWork(wi), 4500);
        }

        JSONObject pets = root.optJSONObject("pets");
        if (pets != null) {
            JSONObject mainPet = pets.optJSONObject("main");
            if (mainPet != null) {
                JSONObject say = mainPet.optJSONObject("say");
                if (say != null) {
                    long sc = say.optLong("counter", -1);
                    if (sc > 0) {
                        if (!sayInit) {
                            sayInit = true;
                            lastSayCounter = sc;
                        } else if (sc != lastSayCounter) {
                            lastSayCounter = sc;
                            JSONObject d = say.optJSONObject("data");
                            String txt = d == null ? null : d.optString("text", null);
                            boolean dup = txt != null && txt.equals(lastWhisperText)
                                    && System.currentTimeMillis() - lastWhisperAt < 9000;
                            if (txt != null && txt.length() > 0 && !dup) showBubble(txt, 7000);
                        }
                    }
                }
            }
        }

        JSONObject push = new JSONObject();
        if (state != null) push.put("state", state);
        if (balance != null) push.put("balance", balance);
        if (notify != null) push.put("notify", notify);
        if (push.length() == 0) return;

        final String js = "window.petUpdate && petUpdate(" + push.toString() + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    public void onDestroy() {
        alive = false;
        flying = false;
        ui.removeCallbacks(flyTick);
        ui.removeCallbacks(longPressRunnable);
        hideMenu();
        if (vt != null) {
            try {
                vt.recycle();
            } catch (Exception ignored) {
            }
            vt = null;
        }
        if (net != null) net.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
