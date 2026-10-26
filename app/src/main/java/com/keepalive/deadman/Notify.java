package com.keepalive.deadman;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** 通知：提醒、超时、已发送、失败。 */
final class Notify {

    static final String CH_REMIND = "remind";
    static final String CH_ALERT = "alert";
    static final String CH_STATUS = "status";

    static final int ID_REMIND = 1001;
    static final int ID_OVERDUE = 1002;
    static final int ID_SENT = 1003;
    static final int ID_ERROR = 1004;

    private Notify() {}

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(CH_STATUS, "守护状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("打卡与发信结果");

        NotificationChannel remind = new NotificationChannel(CH_REMIND, "打卡提醒", NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("临近截止或已超时的提醒");
        remind.enableVibration(true);

        NotificationChannel alert = new NotificationChannel(CH_ALERT, "紧急通知", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("失联邮件发送结果与错误");
        alert.enableVibration(true);

        nm.createNotificationChannel(status);
        nm.createNotificationChannel(remind);
        nm.createNotificationChannel(alert);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent checkInAction(Context c) {
        Intent i = new Intent(c, CheckInReceiver.class);
        i.setAction(CheckInReceiver.ACTION_CHECKIN);
        return PendingIntent.getBroadcast(c, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationCompat.Builder base(Context c, String channel) {
        return new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF4FD1C5)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .setPriority(NotificationCompat.PRIORITY_HIGH);
    }

    static void remind(Context c, String remain) {
        NotificationCompat.Builder b = base(c, CH_REMIND)
                .setContentTitle("还有 " + remain + " 就要截止了")
                .setContentText("点一下完成今日打卡，计时会重新开始")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("还有 " + remain + " 就要截止了。\n\n点下面的「立即打卡」即可完成确认；若逾期未打卡，将自动把预设邮件发送给：" + join(Prefs.get(c).recipients())))
                .addAction(0, "立即打卡", checkInAction(c));
        post(c, ID_REMIND, b);
    }

    static void overdue(Context c, String untilSend) {
        NotificationCompat.Builder b = base(c, CH_REMIND)
                .setContentTitle("已到打卡截止时间")
                .setContentText(untilSend == null ? "失联邮件即将自动发送" : "还有 " + untilSend + " 就会自动发信")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText((untilSend == null ? "" : "还有 " + untilSend + " 就会自动发信。\n\n")
                                + "现在打卡还来得及，计时会从头开始。"))
                .addAction(0, "立即打卡", checkInAction(c));
        post(c, ID_OVERDUE, b);
    }

    static void sent(Context c, String to, long when) {
        NotificationCompat.Builder b = base(c, CH_ALERT)
                .setContentTitle("失联邮件已发送")
                .setContentText("已发送给 " + to)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("由于超过设定时间未打卡，邮件已自动发送给：\n" + to
                                + "\n\n回来后请打开 App 打卡，即可恢复正常守护。"))
                .setColor(0xFFFF6B6B);
        post(c, ID_SENT, b);
    }

    static void error(Context c, String msg) {
        NotificationCompat.Builder b = base(c, CH_ALERT)
                .setContentTitle("自动发信失败")
                .setContentText(msg)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(msg + "\n\n请打开 App 检查邮箱设置，或手动发送邮件通知对方。"))
                .setColor(0xFFFF6B6B);
        post(c, ID_ERROR, b);
    }

    private static String join(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    private static void post(Context c, int id, NotificationCompat.Builder b) {
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // 用户关闭了通知权限
        }
    }

    static void clearReminders(Context c) {
        NotificationManagerCompat.from(c).cancel(ID_REMIND);
        NotificationManagerCompat.from(c).cancel(ID_OVERDUE);
    }

    static void clearAll(Context c) {
        clearReminders(c);
        NotificationManagerCompat.from(c).cancel(ID_SENT);
        NotificationManagerCompat.from(c).cancel(ID_ERROR);
    }
}
