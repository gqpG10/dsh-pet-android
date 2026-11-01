package com.dsha.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 每 15 分钟看一眼：服务还活着吗？心跳停了就拉起来。 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences p = c.getSharedPreferences("pet", Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", false)) return;
        long beat = p.getLong("lastBeat", 0);
        if (System.currentTimeMillis() - beat < 3 * 60 * 1000L) return;
        try {
            Intent s = new Intent(c, PetService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception ignored) {
        }
    }
}
