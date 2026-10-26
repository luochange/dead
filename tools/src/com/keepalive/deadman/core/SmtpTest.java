package com.keepalive.deadman.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SmtpTest {

    static int passed = 0;

    static void ok(String name) {
        passed++;
        System.out.println("PASS  " + name);
    }

    static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    public static void main(String[] args) throws Exception {
        int ssl = Integer.parseInt(args[0]);
        int starttls = Integer.parseInt(args[1]);
        int plain = Integer.parseInt(args[2]);

        String subject = "【重要】我可能已失联，请尽快联系我 —— 中英 mixed test";
        String body = "你好：\n\n这是正文测试，包含中文、English、数字 123。\n"
                + ".这一行以点开头，用于测试 dot-stuffing\n"
                + "最后一行。\n";

        SmtpClient.Config c = new SmtpClient.Config();
        c.host = "localhost";
        c.port = ssl;
        c.security = SmtpClient.SEC_SSL;
        c.user = "me@test.local";
        c.password = "s3cret-pass";
        c.fromAddress = "me@test.local";
        c.fromName = "守夜人 · 测试";

        SmtpClient.send(c, Arrays.asList("a@test.local", "b@test.local"), subject, body);
        ok("SSL 465 + AUTH PLAIN + 两个收件人");

        // STARTTLS，且服务器只提供 AUTH LOGIN
        SmtpClient.Config c2 = new SmtpClient.Config();
        c2.host = "localhost";
        c2.port = starttls;
        c2.security = SmtpClient.SEC_STARTTLS;
        c2.user = "me@test.local";
        c2.password = "s3cret-pass";
        c2.fromAddress = "me@test.local";
        c2.fromName = "Nightwatch";
        SmtpClient.send(c2, Arrays.asList("starttls@test.local"), "STARTTLS 测试", "STARTTLS + LOGIN 正文");
        ok("STARTTLS 587 + AUTH LOGIN");

        // 明文 + 部分收件人被拒
        SmtpClient.Config c3 = new SmtpClient.Config();
        c3.host = "localhost";
        c3.port = plain;
        c3.security = SmtpClient.SEC_NONE;
        c3.user = "me@test.local";
        c3.password = "s3cret-pass";
        c3.fromAddress = "me@test.local";
        boolean partial = false;
        try {
            SmtpClient.send(c3, Arrays.asList("good@test.local", "reject@test.local"), "部分失败", "部分失败正文");
        } catch (SmtpClient.PartialFailure e) {
            partial = true;
        }
        assertTrue(partial, "部分收件人被拒时应抛 PartialFailure");
        ok("部分收件人被拒 → 已投递的仍然成功");

        // 密码错误
        SmtpClient.Config c4 = new SmtpClient.Config();
        c4.host = "localhost";
        c4.port = ssl;
        c4.security = SmtpClient.SEC_SSL;
        c4.user = "me@test.local";
        c4.password = "wrong";
        c4.fromAddress = "me@test.local";
        boolean failed = false;
        String err = "";
        try {
            SmtpClient.send(c4, Arrays.asList("a@test.local"), "x", "y");
        } catch (java.io.IOException e) {
            failed = true;
            err = e.getMessage();
        }
        assertTrue(failed && err.contains("535"), "错误密码应报 535，实际：" + err);
        ok("密码错误 → 明确报错：" + err.substring(0, Math.min(46, err.length())) + "…");

        // 非法收件人
        boolean bad = false;
        try {
            SmtpClient.send(c, Arrays.asList("not-an-email"), "x", "y");
        } catch (java.io.IOException e) {
            bad = true;
        }
        assertTrue(bad, "非法收件人应被拦截");
        ok("非法邮箱地址拦截");

        // ---------------- Guard 逻辑
        long now = 1_700_000_000_000L;
        long interval = 24 * Guard.HOUR;
        long grace = 30 * Guard.MINUTE;
        long last23 = now - 23 * Guard.HOUR;
        long last25 = now - 25 * Guard.HOUR;
        assertTrue(Guard.deadline(last23, interval) == last23 + interval, "deadline 计算");
        assertTrue(Guard.sendAt(last23, interval, grace) == last23 + interval + grace, "sendAt 计算");
        assertTrue(!Guard.isOverdue(now, last23, interval, grace, 0), "23h 未打卡 + 30m 宽限，此时还不该发");
        assertTrue(Guard.isOverdue(now, last25, interval, grace, 0), "25h 未打卡，已超过宽限应发信");
        assertTrue(Guard.isOverdue(last23 + interval + grace + 1000, last23, interval, grace, 0), "刚过宽限应发信");
        assertTrue(!Guard.isOverdue(now, last25, interval, grace, last25 + interval + 1), "已发过就不再发");
        assertTrue(!Guard.isOverdue(now, 0, interval, grace, 0), "从未打卡不应发信");
        assertTrue(!Guard.isOverdue(last23 + interval + 10_000, last23, interval, 10 * Guard.MINUTE, 0), "宽限期内不发");
        assertTrue(Guard.isOverdue(last23 + interval + 11 * Guard.MINUTE, last23, interval, 10 * Guard.MINUTE, 0), "宽限期结束即发");
        assertTrue(Guard.formatSpan(2 * Guard.HOUR + 30 * Guard.MINUTE).equals("2 小时 30 分"),
                "中文时长：" + Guard.formatSpan(2 * Guard.HOUR + 30 * Guard.MINUTE));
        assertTrue(Guard.clock(3661_000L).equals("01:01:01"), "时钟：" + Guard.clock(3661_000L));
        assertTrue(Guard.fraction(now, now, interval) == 1f, "刚开始剩余比例为 1");
        assertTrue(Guard.fraction(now + interval * 2, now, interval) == 0f, "超时后比例为 0");
        ok("Guard 截止/宽限/已发送/格式化逻辑");

        // ---------------- 模板
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        vars.put("name", "张三");
        vars.put("interval", "1 天");
        String rendered = Template.render("我是{name}，已{interval}未打卡（{unknown}）", vars);
        assertTrue(rendered.equals("我是张三，已1 天未打卡（{unknown}）"), "模板渲染：" + rendered);
        ok("模板占位符替换");

        // ---------------- 地址格式化
        String asciiName = SmtpClient.formatAddress("John Doe", "a@b.com");
        assertTrue(asciiName.equals("John Doe <a@b.com>"), "普通 ASCII 显示名：" + asciiName);
        String quoted = SmtpClient.formatAddress("John \"Johnny\" Doe", "a@b.com");
        assertTrue(quoted.equals("\"John \\\"Johnny\\\" Doe\" <a@b.com>"), "带引号显示名：" + quoted);
        String cn = SmtpClient.formatAddress("守夜人", "a@b.com");
        assertTrue(cn.startsWith("=?UTF-8?B?") && cn.endsWith("?= <a@b.com>"), "中文显示名：" + cn);
        String noName = SmtpClient.formatAddress("", "a@b.com");
        assertTrue(noName.equals("a@b.com"), "无显示名：" + noName);
        ok("发件人地址格式化（引号转义 / 中文编码）");

        System.out.println("\n全部通过：" + passed + " 项");
    }
}
