package com.keepalive.deadman;

import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.SmtpClient;
import com.keepalive.deadman.core.Template;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 把配置 + 模板组装成一封邮件并发出。 */
final class Mail {

    private Mail() {}

    static void sendAuto(Prefs p) throws IOException {
        List<String> to = p.recipients();
        SmtpClient.send(config(p), to, render(p, p.subject()), render(p, p.body()));
    }

    static void sendTest(Prefs p) throws IOException {
        List<String> to = p.recipients();
        String subject = render(p, p.subject());
        if (!subject.startsWith("[测试]")) subject = "[测试] " + subject;
        String body = render(p, p.body())
                + "\n\n--------------------------------\n"
                + "这是一封测试邮件，用于验证 SMTP 配置是否正确。\n"
                + "发送时间：" + Template.dateTime(System.currentTimeMillis());
        SmtpClient.send(config(p), to, subject, body);
    }

    static SmtpClient.Config config(Prefs p) {
        SmtpClient.Config c = new SmtpClient.Config();
        c.host = p.host().trim();
        c.port = p.port();
        c.security = p.security();
        c.user = p.user().trim();
        c.password = p.password();
        c.fromAddress = p.user().trim();
        c.fromName = p.fromName();
        return c;
    }

    static String render(Prefs p, String template) {
        return Template.render(template, vars(p));
    }

    static Map<String, String> vars(Prefs p) {
        long last = p.lastCheckIn();
        long interval = p.intervalHours() * Guard.HOUR;
        Map<String, String> m = new HashMap<>();
        m.put("name", displayName(p));
        m.put("last_checkin", Template.dateTime(last));
        m.put("deadline", Template.dateTime(Guard.deadline(last, interval)));
        m.put("interval", Template.intervalText(p.intervalHours()));
        m.put("now", Template.dateTime(System.currentTimeMillis()));
        return m;
    }

    static String displayName(Prefs p) {
        String name = p.fromName();
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String user = p.user();
        int at = user.indexOf('@');
        return at > 0 ? user.substring(0, at) : "我";
    }
}
