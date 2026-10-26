package com.keepalive.deadman;

import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.SmtpClient;
import com.keepalive.deadman.core.Template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按收件人逐封发送：每个人可以用自己的主题/正文，留空则回落到默认模板。
 * 一人一封，收件人之间互相不可见。
 */
final class Mail {

    private Mail() {}

    static final class Result {
        int sent;
        final List<String> failedTo = new ArrayList<>();
        String lastError = "";

        boolean anySent() { return sent > 0; }
        boolean partial() { return sent > 0 && !failedTo.isEmpty(); }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("成功 ").append(sent).append(" 封");
            if (!failedTo.isEmpty()) {
                sb.append("，失败 ").append(failedTo.size()).append(" 封：");
                for (int i = 0; i < failedTo.size(); i++) {
                    if (i > 0) sb.append("；");
                    sb.append(failedTo.get(i));
                }
            }
            return sb.toString();
        }
    }

    /** 只给某个收件人用的变量表。 */
    static Map<String, String> vars(Prefs p, Prefs.Recipient r) {
        long last = p.lastCheckIn();
        long interval = p.intervalHours() * Guard.HOUR;
        Map<String, String> m = new HashMap<>();
        m.put("name", displayName(p));
        m.put("last_checkin", Template.dateTime(last));
        m.put("deadline", Template.dateTime(Guard.deadline(last, interval)));
        m.put("interval", Template.intervalText(p.intervalHours()));
        m.put("now", Template.dateTime(System.currentTimeMillis()));
        m.put("to", r == null ? "" : r.email);
        return m;
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

    static Result sendAll(Prefs p, boolean test) throws IOException {
        List<Prefs.Recipient> list = p.recipientList();
        Result result = new Result();
        if (list.isEmpty()) throw new IOException("没有收件人");

        SmtpClient.Config cfg = config(p);
        boolean first = true;

        for (Prefs.Recipient r : list) {
            Map<String, String> vars = vars(p, r);
            String subject = Template.render(
                    r.subject.trim().isEmpty() ? p.subject() : r.subject, vars);
            String body = Template.render(
                    r.body.trim().isEmpty() ? p.body() : r.body, vars);
            if (test) {
                if (!subject.startsWith("[测试]")) subject = "[测试] " + subject;
                body = body + "\n\n--------------------------------\n"
                        + "这是一封测试邮件，用于验证 SMTP 配置与这封信的内容。\n"
                        + "收件人：" + r.email + "\n"
                        + "发送时间：" + Template.dateTime(System.currentTimeMillis());
            }
            try {
                SmtpClient.send(cfg, java.util.Collections.singletonList(r.email), subject, body);
                result.sent++;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
                result.failedTo.add(r.email + "（" + msg + "）");
                result.lastError = msg;
                if (first) {
                    // 第一封就失败，多半是配置/网络问题，直接抛出让上层重试
                    throw new IOException(msg);
                }
            }
            first = false;
        }

        if (result.sent == 0) {
            throw new IOException(result.lastError.isEmpty() ? "全部发送失败" : result.lastError);
        }
        return result;
    }

    static String displayName(Prefs p) {
        String name = p.fromName();
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String user = p.user();
        int at = user.indexOf('@');
        return at > 0 ? user.substring(0, at) : "我";
    }
}
