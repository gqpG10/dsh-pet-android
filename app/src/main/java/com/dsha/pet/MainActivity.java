package com.dsha.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private TextView debug;
    private TextView dshaValue;
    private TextView dshaBtn;
    private TextView epValue;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (debug != null) setDsh(PetService.lastPoll);
            if (epValue != null) {
                String st = PetService.discoveryStatus;
                epValue.setText(st);
                epValue.setTextColor(st != null && st.startsWith("已连接")
                        ? 0xFF35B37E : 0xFF9A97B8);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        GradientDrawable pageBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFF2F7, 0xFFEDEFFF, 0xFFF7F4FF});
        sv.setBackground(pageBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(46), dp(20), dp(28));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 头像 + 标题 ----------
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(round(0xFFFFFFFF, 26));
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setElevation(dp(3));

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_launcher);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0xFFFFF0F7);
        avatar.setBackground(avatarBg);
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        hero.addView(avatar, avatarLp);

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams heroTextLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        heroTextLp.leftMargin = dp(16);

        TextView title = new TextView(this);
        title.setText("蓝毛小女仆");
        title.setTextSize(21);
        title.setTextColor(0xFF3B3A5C);
        heroText.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("桌面陪伴 · 不需要 root");
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF9A97B8);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(5);
        heroText.addView(subtitle, subLp);
        hero.addView(heroText, heroTextLp);

        root.addView(hero, matchTop(0));

        // ---------- 状态卡片 ----------
        LinearLayout cards = card();
        LinearLayout.LayoutParams cardsLp = matchTop(dp(14));
        root.addView(cards, cardsLp);

        TextView cardTitle = sectionTitle("运行状态");
        cards.addView(cardTitle);

        LinearLayout rowPerm = row();
        rowPerm.addView(label("悬浮窗权限"), weightLp(1f));
        status = value("未授予", 0xFFF0973B);
        rowPerm.addView(status);
        cards.addView(rowPerm, matchTop(dp(12)));

        LinearLayout rowDsh = row();
        rowDsh.addView(label("DSH 连接"), weightLp(1f));
        debug = value("检测中…", 0xFF9A97B8);
        rowDsh.addView(debug);
        cards.addView(rowDsh, matchTop(dp(10)));

        LinearLayout rowDsha = row();
        rowDsha.addView(label("DSHA 客户端"), weightLp(1f));
        dshaValue = value("检查中…", 0xFF9A97B8);
        rowDsha.addView(dshaValue);
        cards.addView(rowDsha, matchTop(dp(10)));

        LinearLayout rowEp = row();
        rowEp.addView(label("DSH 端点"), weightLp(1f));
        epValue = value("查找中…", 0xFF9A97B8);
        rowEp.addView(epValue);
        cards.addView(rowEp, matchTop(dp(10)));

        // ---------- 主按钮 ----------
        TextView start = pill("启动桌宠", 0xFFFFFFFF, true);
        start.setOnClickListener(v -> startPet());
        root.addView(start, matchTop(dp(20)));

        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        TextView stop = pill("停止桌宠", 0xFF7B6BB5, false);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, PetService.class));
            status.setText("已停止");
            status.setTextColor(0xFF9A97B8);
        });
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pair.addView(stop, leftLp);

        TextView perm = pill("授予悬浮窗权限", 0xFF7B6BB5, false);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = dp(12);
        perm.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        pair.addView(perm, rightLp);
        root.addView(pair, matchTop(dp(12)));

        dshaBtn = pill("去 GitHub 获取 DSHA", 0xFF7B6BB5, false);
        dshaBtn.setOnClickListener(v -> openDshaDownload());
        dshaBtn.setVisibility(View.GONE);
        root.addView(dshaBtn, matchTop(dp(12)));

        LinearLayout epBtns = new LinearLayout(this);
        epBtns.setOrientation(LinearLayout.HORIZONTAL);
        TextView redetect = pill("重新检测", 0xFF7B6BB5, false);
        redetect.setOnClickListener(v -> {
            if (PetService.running) {
                PetService.requestRediscover();
                setDsh("重新检测中…");
            } else {
                testDsh();
            }
        });
        epBtns.addView(redetect, weightLp(1f));
        TextView manualBtn = pill("手动填写", 0xFF7B6BB5, false);
        LinearLayout.LayoutParams manualLp = weightLp(1f);
        manualLp.leftMargin = dp(12);
        manualBtn.setOnClickListener(v -> showManualDialog());
        epBtns.addView(manualBtn, manualLp);
        root.addView(epBtns, matchTop(dp(12)));

        TextView deepBtn = pill("深度查找（全端口，较慢）", 0xFF7B6BB5, false);
        deepBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("深度查找")
                .setMessage("会扫描 1024–65535 全部端口，通常 10–40 秒。只在自动查找失败时使用。")
                .setPositiveButton("开始", (d, w) -> {
                    PetService.requestDeepScan();
                    setDsh("深度查找中…");
                })
                .setNegativeButton("取消", null)
                .show());
        root.addView(deepBtn, matchTop(dp(10)));

        TextView test = pill("测试 DSH 连接", 0xFF7B6BB5, false);
        test.setOnClickListener(v -> testDsh());
        root.addView(test, matchTop(dp(12)));

        // ---------- 玩法说明 ----------
        LinearLayout tips = card();
        tips.setBackground(round(0xFFF3F0FF, 22));
        tips.setElevation(0);
        root.addView(tips, matchTop(dp(18)));

        tips.addView(sectionTitle("怎么玩"));
        tips.addView(tip("单击 —— Q 弹一下，好感 +1"));
        tips.addView(tip("拖动甩出去 —— 会在屏幕上弹来弹去"));
        tips.addView(tip("双击 —— 打开 DSHA"));
        tips.addView(tip("长按 —— 呼出菜单（余额 / 碎碎念 / 大小 / 动作）"));

        TextView footer = new TextView(this);
        footer.setText("v" + version() + " · 常驻不耗电 · 不依赖 ADB / Shizuku");
        footer.setTextSize(11);
        footer.setTextColor(0xFFB4B0CC);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(22);
        root.addView(footer, footLp);

        setContentView(sv);

        try {
            Typeface cute = Typeface.create(
                    Typeface.createFromAsset(getAssets(), "font/cute.ttf"), Typeface.BOLD);
            applyFont(root, cute);
        } catch (Exception ignored) {
        }

        testDsh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        boolean can = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        status.setText(can ? "已授予" : "未授予");
        status.setTextColor(can ? 0xFF35B37E : 0xFFF0973B);

        boolean dsha = hasDsha();
        if (dshaValue != null) {
            dshaValue.setText(dsha ? "已安装" : "未安装");
            dshaValue.setTextColor(dsha ? 0xFF35B37E : 0xFFF0973B);
        }
        if (dshaBtn != null) dshaBtn.setVisibility(dsha ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    // ---------- 小工具 ----------
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(0xFFFFFFFF, 22));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setElevation(dp(3));
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF8D89AC);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFF5B5878);
        return t;
    }

    private TextView value(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(color);
        return t;
    }

    private TextView tip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(0xFF6F6B8E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView pill(String text, int color, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(primary ? 16 : 13.5f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(primary ? 16 : 13), dp(10), dp(primary ? 16 : 13));
        GradientDrawable bg;
        if (primary) {
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFFFFA9C9, 0xFFA98CFF});
            bg.setCornerRadius(dp(20));
        } else {
            bg = round(0xFFFFFFFF, 20);
            bg.setStroke(dp(1.2f), 0xFFE2D8FF);
        }
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), bg, null));
        return t;
    }

    private LinearLayout.LayoutParams matchTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topDp;
        return lp;
    }

    private LinearLayout.LayoutParams weightLp(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private void setDsh(String s) {
        if (debug == null) return;
        debug.setText(s);
        debug.setTextColor(s != null && s.startsWith("OK") ? 0xFF35B37E : 0xFFF0973B);
    }

    private boolean hasDsha() {
        try {
            getPackageManager().getPackageInfo("dsh.android.client", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openDshaDownload() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/DSH-APP/DSHA")));
        } catch (Exception ignored) {
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "4.1";
        }
    }

    private void testDsh() {
        setDsh("检测中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r;
                DshEndpoint cached = DshEndpoint.cached(MainActivity.this);
                DshEndpoint found = null;
                if (cached != null && DshEndpoint.probe(cached.host, cached.port, 600, 1200)) {
                    found = cached;
                }
                if (found == null) {
                    found = DshEndpoint.resolveBlocking(MainActivity.this, false,
                            new DshEndpoint.Listener() {
                                @Override
                                public void onProgress(final String message) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            setDsh(message);
                                        }
                                    });
                                }
                            });
                }
                if (found != null) {
                    r = "OK " + found.host + ":" + found.port;
                    PetService.discoveryStatus = "已连接 " + found.host + ":" + found.port
                            + "（" + found.source + "）";
                } else {
                    DshEndpoint c2 = DshEndpoint.cached(MainActivity.this);
                    boolean dshAlive = c2 != null && DshEndpoint.looksLikeDsh(c2.host, c2.port);
                    r = dshAlive ? "DSH 在跑，但桌宠插件没加载" : "未找到 DSH";
                }
                final String rr = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setDsh(rr);
                    }
                });
            }
        }).start();
    }

    private void showManualDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        String m = DshEndpoint.getManual(this);
        String hostStr = "127.0.0.1";
        String portStr = String.valueOf(DshEndpoint.DEFAULT_PORT);
        if (m != null && m.contains(":")) {
            String[] parts = m.split(":", 2);
            hostStr = parts[0];
            portStr = parts[1];
        }

        EditText host = new EditText(this);
        host.setHint("主机，如 127.0.0.1");
        host.setText(hostStr);
        host.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(host);

        EditText port = new EditText(this);
        port.setHint("端口，如 38859");
        port.setText(portStr);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(port);

        new AlertDialog.Builder(this)
                .setTitle("手动指定 DSH 地址")
                .setMessage("留空主机或点「清除」可恢复自动查找。")
                .setView(box)
                .setPositiveButton("保存并检测", (d, w) -> {
                    try {
                        int p = Integer.parseInt(port.getText().toString().trim());
                        String h = host.getText().toString().trim();
                        if (h.isEmpty()) {
                            DshEndpoint.setManual(this, null, 0);
                        } else {
                            DshEndpoint.setManual(this, h, p);
                        }
                        PetService.requestRediscover();
                        setDsh("已保存，正在检测…");
                    } catch (Exception e) {
                        setDsh("端口格式不对");
                    }
                })
                .setNeutralButton("清除", (d, w) -> {
                    DshEndpoint.setManual(this, null, 0);
                    PetService.requestRediscover();
                    setDsh("已清除手动端点");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startPet() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        startForegroundService(new Intent(this, PetService.class));
        status.setText("运行中");
        status.setTextColor(0xFF35B37E);
        finish();
    }

    private void applyFont(View v, Typeface tf) {
        if (v == null || tf == null) return;
        if (v instanceof TextView) ((TextView) v).setTypeface(tf);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFont(g.getChildAt(i), tf);
        }
    }
}
