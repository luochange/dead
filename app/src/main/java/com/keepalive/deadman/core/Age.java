package com.keepalive.deadman.core;

import java.util.Calendar;

/** 出生时间 → 已活时长。纯逻辑，可在桌面 JVM 上测试。 */
public final class Age {

    private Age() {}

    public static boolean isValid(long birth, long now) {
        return birth > 0 && birth <= now;
    }

    /** 已活整天数（向下取整）。 */
    public static long totalDays(long birth, long now) {
        if (birth <= 0) return 0;
        long diff = now - birth;
        if (diff <= 0) return 0;
        return diff / 86400000L;
    }

    /** 当天剩余的时分秒，格式 HH:mm:ss（每秒跳动）。 */
    public static String clock(long birth, long now) {
        if (birth <= 0 || now <= birth) return "00:00:00";
        long rem = (now - birth) % 86400000L;
        long h = rem / 3600000L;
        long m = (rem % 3600000L) / 60000L;
        long s = (rem % 60000L) / 1000L;
        return pad2(h) + ":" + pad2(m) + ":" + pad2(s);
    }

    /** 日历口径的年月日，返回 {年, 月, 日}。 */
    public static int[] ymd(long birth, long now) {
        if (birth <= 0 || now <= birth) return new int[]{0, 0, 0};
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(birth);
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(now);

        int years = n.get(Calendar.YEAR) - b.get(Calendar.YEAR);
        Calendar anchor = (Calendar) b.clone();
        anchor.add(Calendar.YEAR, years);
        if (anchor.after(n)) {
            years--;
            anchor.add(Calendar.YEAR, -1);
        }

        int months = 0;
        while (true) {
            Calendar next = (Calendar) anchor.clone();
            next.add(Calendar.MONTH, 1);
            if (next.after(n)) break;
            anchor = next;
            months++;
            if (months > 11) break;
        }

        long days = Math.round((n.getTimeInMillis() - anchor.getTimeInMillis()) / 86400000.0);
        if (days < 0) days = 0;
        if (years < 0) return new int[]{0, 0, 0};
        return new int[]{years, months, (int) days};
    }

    /** "33 年 9 个月 21 天"，自动省略为 0 的高位。 */
    public static String describe(long birth, long now) {
        int[] v = ymd(birth, now);
        StringBuilder sb = new StringBuilder();
        if (v[0] > 0) sb.append(v[0]).append(" 年 ");
        if (v[0] > 0 || v[1] > 0) sb.append(v[1]).append(" 个月 ");
        sb.append(v[2]).append(" 天");
        return sb.toString();
    }

    /** 千分位：12345 → "12,345"。 */
    public static String group(long n) {
        String s = Long.toString(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++c % 3 == 0 && i > 0) sb.append(',');
        }
        if (n < 0) sb.append('-');
        return sb.reverse().toString();
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }
}
