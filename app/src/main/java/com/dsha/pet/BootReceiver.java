package com.dsha.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 开机 / 应用更新后，如果桌宠此前是开着的，自动拉起来。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences p = c.getSharedPreferences("pet", Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", false)) return;
        try {
            Intent s = new Intent(c, PetService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception ignored) {
        }
    }
}
