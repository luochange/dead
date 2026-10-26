package com.keepalive.deadman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** 通知栏上的「立即打卡」。 */
public class CheckInReceiver extends BroadcastReceiver {

    public static final String ACTION_CHECKIN = "com.keepalive.deadman.CHECKIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CHECKIN.equals(intent.getAction())) return;
        Prefs p = Prefs.get(context);
        p.checkIn(System.currentTimeMillis());
        p.setEnabled(true);

        Scheduler.cancelSend(context);
        Notify.clearAll(context);
        Scheduler.scheduleAll(context);

        try {
            Toast.makeText(context, "已打卡 ✓ 计时重新开始", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
