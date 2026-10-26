package com.keepalive.deadman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.keepalive.deadman.core.Guard;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String kind = intent.getStringExtra(Scheduler.EXTRA_KIND);
        Prefs p = Prefs.get(context);
        if (!p.enabled() || p.lastCheckIn() <= 0) return;

        long now = System.currentTimeMillis();
        long interval = p.intervalHours() * Guard.HOUR;
        long grace = p.graceMinutes() * Guard.MINUTE;
        long deadline = Guard.deadline(p.lastCheckIn(), interval);
        long sendAt = Guard.sendAt(p.lastCheckIn(), interval, grace);
        boolean sent = p.sentAt() > 0 && p.sentAt() >= p.lastCheckIn();

        if (Scheduler.KIND_REMIND.equals(kind)) {
            if (!sent) Notify.remind(context, Guard.formatSpan(Math.max(0, deadline - now)));
        } else if (Scheduler.KIND_DEADLINE.equals(kind)) {
            if (!sent) Notify.overdue(context, Guard.formatSpan(Math.max(0, sendAt - now)));
        } else if (Scheduler.KIND_SEND.equals(kind)) {
            Scheduler.enqueueSend(context);
        }

        Scheduler.scheduleAll(context);
    }
}
