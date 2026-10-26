package com.keepalive.deadman;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.SmtpClient;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import javax.net.ssl.SSLException;

/** 真正的发信执行者：到期就发，失败自动重试，联网后继续。 */
public class CheckWorker extends Worker {

    private static final Object LOCK = new Object();

    public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        Prefs p = Prefs.get(c);

        if (!p.enabled()) {
            Scheduler.scheduleAll(c);
            return Result.success();
        }

        long now = System.currentTimeMillis();
        long interval = p.intervalHours() * Guard.HOUR;
        long grace = p.graceMinutes() * Guard.MINUTE;
        long last = p.lastCheckIn();

        if (last <= 0 || !Guard.isOverdue(now, last, interval, grace, p.sentAt())) {
            Scheduler.scheduleAll(c);
            return Result.success();
        }

        if (!p.isConfigured()) {
            String msg = "邮箱配置不完整（服务器 / 账号 / 授权码 / 收件人）";
            p.setLastError(msg);
            p.addHistory("error", msg);
            Notify.error(c, msg);
            return Result.failure();
        }

        synchronized (LOCK) {
            long t = System.currentTimeMillis();
            if (!Guard.isOverdue(t, last, interval, grace, p.sentAt())) {
                return Result.success();
            }
            if (p.sending() > t - 10 * Guard.MINUTE) {
                // 另一个实例正在发送
                return Result.retry();
            }
            p.setSending(t);
            try {
                Mail.sendAuto(p);
                long sentAt = System.currentTimeMillis();
                List<String> to = p.recipients();
                p.setSentAt(sentAt);
                p.setLastError("");
                p.addHistory("sent", "已发送给 " + join(to));
                Notify.sent(c, join(to), sentAt);
                return Result.success();
            } catch (SmtpClient.PartialFailure e) {
                long sentAt = System.currentTimeMillis();
                p.setSentAt(sentAt);
                p.setLastError(e.getMessage());
                p.addHistory("sent", "部分投递：" + e.getMessage());
                Notify.sent(c, join(p.recipients()), sentAt);
                return Result.success();
            } catch (Exception e) {
                String msg = friendly(e);
                p.setLastError(msg);
                p.addHistory("error", msg);
                Notify.error(c, msg);
                return Result.retry();
            } finally {
                p.setSending(0);
            }
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String friendly(Exception e) {
        if (e instanceof UnknownHostException) {
            return "无法解析服务器地址，请检查网络或 SMTP 服务器填写是否正确";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接邮件服务器超时，请检查网络，或换用其他端口 / 加密方式";
        }
        if (e instanceof SSLException) {
            return "TLS 加密握手失败：" + e.getMessage() + "（可尝试切换 SSL / STARTTLS）";
        }
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }
}
