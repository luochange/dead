package com.keepalive.deadman.core;

/** 纯逻辑：打卡周期、截止时间、是否该发信。不依赖 Android，可在桌面上直接测试。 */
public final class Guard {

    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private Guard() {}

    /** 下次截止时间：最后一次打卡 + 周期。未打卡返回 0。 */
    public static long deadline(long lastCheckIn, long intervalMs) {
        if (lastCheckIn <= 0) return 0;
        if (intervalMs <= 0) intervalMs = DAY;
        return lastCheckIn + intervalMs;
    }

    /** 真正发信的时间点：截止时间 + 宽限期。 */
    public static long sendAt(long lastCheckIn, long intervalMs, long graceMs) {
        long d = deadline(lastCheckIn, intervalMs);
        if (d == 0) return 0;
        return d + Math.max(0, graceMs);
    }

    /** 现在是否应该发出"失联"邮件。sentAt 是上一次已发送的时间。 */
    public static boolean isOverdue(long now, long lastCheckIn, long intervalMs, long graceMs, long sentAt) {
        if (lastCheckIn <= 0) return false;
        if (sentAt > 0 && sentAt >= lastCheckIn) return false;
        return now >= sendAt(lastCheckIn, intervalMs, graceMs);
    }

    /** 距离截止还剩多少毫秒（可能为负）。 */
    public static long remaining(long now, long lastCheckIn, long intervalMs) {
        long d = deadline(lastCheckIn, intervalMs);
        if (d == 0) return 0;
        return d - now;
    }

    /** 剩余比例 0..1，用于进度环。 */
    public static float fraction(long now, long lastCheckIn, long intervalMs) {
        long d = deadline(lastCheckIn, intervalMs);
        if (d == 0 || intervalMs <= 0) return 0f;
        float f = (d - now) / (float) intervalMs;
        if (f < 0f) return 0f;
        if (f > 1f) return 1f;
        return f;
    }

    /** 把毫秒格式化成中文时长，如 "12 小时 34 分"。 */
    public static String formatSpan(long ms) {
        boolean neg = ms < 0;
        long s = Math.abs(ms) / 1000L;
        long days = s / 86400L;
        long hours = (s % 86400L) / 3600L;
        long mins = (s % 3600L) / 60L;
        long secs = s % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" 天 ");
            sb.append(hours).append(" 小时");
        } else if (hours > 0) {
            sb.append(hours).append(" 小时 ").append(mins).append(" 分");
        } else if (mins > 0) {
            sb.append(mins).append(" 分 ").append(secs).append(" 秒");
        } else {
            sb.append(secs).append(" 秒");
        }
        return (neg ? "已超 " : "") + sb.toString();
    }

    /** 倒计时用 HH:MM:SS（超过 24 小时显示 "1天 03:04"）。 */
    public static String clock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000L;
        long days = s / 86400L;
        long hours = (s % 86400L) / 3600L;
        long mins = (s % 3600L) / 60L;
        long secs = s % 60L;
        if (days > 0) {
            return String.format(java.util.Locale.US, "%d天 %02d:%02d", days, hours, mins);
        }
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, mins, secs);
    }
}
