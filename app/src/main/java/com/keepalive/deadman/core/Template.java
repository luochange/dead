package com.keepalive.deadman.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** 邮件模板占位符替换与时间格式化。 */
public final class Template {

    private Template() {}

    public static String render(String tpl, Map<String, String> vars) {
        if (tpl == null) return "";
        String out = tpl;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{" + e.getKey() + "}", v);
        }
        return out;
    }

    public static String dateTime(long ms) {
        if (ms <= 0) return "未知";
        SimpleDateFormat f = new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA);
        return f.format(new Date(ms));
    }

    public static String dateTimeFull(long ms) {
        if (ms <= 0) return "未知";
        SimpleDateFormat f = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA);
        return f.format(new Date(ms));
    }

    /** 用于邮件里描述周期，例如 "24 小时"。 */
    public static String intervalText(int hours) {
        if (hours > 0 && hours % 24 == 0) return (hours / 24) + " 天";
        return hours + " 小时";
    }
}
