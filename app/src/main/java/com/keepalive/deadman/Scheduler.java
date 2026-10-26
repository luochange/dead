package com.keepalive.deadman;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.keepalive.deadman.core.Guard;

import java.util.concurrent.TimeUnit;

/** 三路保险：精确闹钟 + WorkManager 看门狗 + 开机重建。 */
final class Scheduler {

    static final String ACTION_ALARM = "com.keepalive.deadman.ALARM";
    static final String EXTRA_KIND = "kind";
    static final String KIND_REMIND = "remind";
    static final String KIND_DEADLINE = "deadline";
    static final String KIND_SEND = "send";

    private static final int RC_REMIND = 2001;
    private static final int RC_DEADLINE = 2002;
    private static final int RC_SEND = 2003;

    private Scheduler() {}

    static void scheduleAll(Context c) {
        cancelAlarms(c);
        ensureWatchdog(c);

        Prefs p = Prefs.get(c);
        if (!p.enabled() || p.lastCheckIn() <= 0) {
            Notify.clearReminders(c);
            return;
        }

        long now = System.currentTimeMillis();
        long interval = p.intervalHours() * Guard.HOUR;
        long grace = p.graceMinutes() * Guard.MINUTE;
        long deadline = Guard.deadline(p.lastCheckIn(), interval);
        long sendAt = Guard.sendAt(p.lastCheckIn(), interval, grace);
        long remindAt = deadline - p.remindHours() * Guard.HOUR;
        boolean sent = p.sentAt() > 0 && p.sentAt() >= p.lastCheckIn();
        boolean sending = p.sending() > now - 10 * Guard.MINUTE;

        if (!sent && !sending) {
            if (sendAt > now + 2000) {
                setAlarm(c, RC_SEND, sendAt, KIND_SEND);
            } else {
                enqueueSend(c);
            }
        }
        if (deadline > now) {
            setAlarm(c, RC_DEADLINE, deadline, KIND_DEADLINE);
        }
        if (remindAt > now && remindAt < deadline) {
            setAlarm(c, RC_REMIND, remindAt, KIND_REMIND);
        }
    }

    static void disable(Context c) {
        cancelAlarms(c);
        cancelSend(c);
        Notify.clearAll(c);
    }

    private static void setAlarm(Context c, int rc, long at, String kind) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c, rc, kind);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent alarmIntent(Context c, int rc, String kind) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_ALARM);
        i.putExtra(EXTRA_KIND, kind);
        return PendingIntent.getBroadcast(c, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelAlarms(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int rc : new int[]{RC_REMIND, RC_DEADLINE, RC_SEND}) {
            am.cancel(alarmIntent(c, rc, KIND_SEND));
        }
    }

    static void enqueueSend(Context c) {
        Prefs p = Prefs.get(c);
        if (p.sending() > System.currentTimeMillis() - 10 * Guard.MINUTE) return;

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CheckWorker.class)
                .setInputData(new Data.Builder().putString("mode", "send").build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        try {
            WorkManager.getInstance(c).enqueueUniqueWork("send-job", ExistingWorkPolicy.KEEP, req);
        } catch (IllegalStateException ignored) {
            // WorkManager 未初始化（极少数机型 / 测试环境）：下次启动会重试
        }
    }

    static void cancelSend(Context c) {
        try {
            WorkManager.getInstance(c).cancelUniqueWork("send-job");
        } catch (IllegalStateException ignored) {
        }
    }

    static void ensureWatchdog(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(CheckWorker.class, 15, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putString("mode", "watchdog").build())
                .build();
        try {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.UPDATE, req);
        } catch (IllegalStateException ignored) {
        }
    }
}
