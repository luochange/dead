package com.keepalive.deadman;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 所有配置与守护状态。授权码用 Keystore 加密存储。 */
public final class Prefs {

    public static final String DEFAULT_SUBJECT = "【重要】{name} 可能已失联，请尽快联系";

    public static final String DEFAULT_BODY =
            "你好：\n\n"
            + "我是 {name}。这封邮件由「守夜人」自动发出。\n\n"
            + "我最后一次安全打卡是在 {last_checkin}，已超过设定的 {interval} 没有确认。\n"
            + "如果你收到这封邮件，说明我可能遇到了意外，或者手机不在身边。\n\n"
            + "请尽快通过电话或上门确认我的情况，谢谢。\n\n"
            + "—— 由「守夜人」自动发送，请勿直接回复";

    private static final String FILE = "nightwatch";
    private static final int HISTORY_MAX = 20;
    private static final char F = '\u001F';

    private static Prefs instance;

    private final SharedPreferences sp;
    private Crypto crypto = new Crypto();
    private String cachedPassword;
    private boolean passwordLoaded;

    /** 一位收件人，可单独覆写主题与正文（留空则用默认模板）。 */
    public static final class Recipient {
        public String email = "";
        public String subject = "";
        public String body = "";

        public Recipient() {}

        public Recipient(String email) {
            this.email = email;
        }

        public Recipient(String email, String subject, String body) {
            this.email = email;
            this.subject = subject == null ? "" : subject;
            this.body = body == null ? "" : body;
        }

        public boolean hasCustom() {
            return !subject.trim().isEmpty() || !body.trim().isEmpty();
        }
    }

    public static synchronized Prefs get(Context context) {
        if (instance == null) instance = new Prefs(context.getApplicationContext());
        return instance;
    }

    private Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrate();
    }

    /** 测试用：替换加密实现（Robolectric 里没有 AndroidKeyStore）。 */
    void setCryptoForTest(Crypto c) {
        crypto = c;
        passwordLoaded = false;
        cachedPassword = null;
    }

    /** 重新从磁盘读取授权码，用于校验是否真的存进去了。 */
    void invalidatePasswordCache() {
        passwordLoaded = false;
        cachedPassword = null;
    }

    // ------------------------------------------------------------------ 配置读取

    public String host() { return sp.getString("host", ""); }

    public int port() { return sp.getInt("port", 465); }

    public int security() { return sp.getInt("security", 0); }

    public String user() { return sp.getString("user", ""); }

    public String fromName() { return sp.getString("from_name", ""); }

    public String password() {
        if (!passwordLoaded) {
            passwordLoaded = true;
            try {
                cachedPassword = crypto.decrypt(sp.getString("pass", ""));
            } catch (Exception e) {
                cachedPassword = "";
            }
        }
        return cachedPassword == null ? "" : cachedPassword;
    }

    public String subject() {
        String s = sp.getString("subject", DEFAULT_SUBJECT);
        return s == null || s.trim().isEmpty() ? DEFAULT_SUBJECT : s;
    }

    public String body() {
        String s = sp.getString("body", DEFAULT_BODY);
        return s == null || s.trim().isEmpty() ? DEFAULT_BODY : s;
    }

    public int intervalHours() { return clamp(sp.getInt("interval_hours", 24), 1, 720); }

    public int remindHours() { return clamp(sp.getInt("remind_hours", 3), 0, 720); }

    public int graceMinutes() { return clamp(sp.getInt("grace_minutes", 30), 0, 1440); }

    public boolean enabled() { return sp.getBoolean("enabled", false); }

    /** 出生时间（毫秒），0 表示未设置。 */
    public long birth() { return sp.getLong("birth_ms", 0); }

    // ------------------------------------------------------------------ 收件人

    public List<Recipient> recipientList() {
        List<Recipient> out = new ArrayList<>();
        String raw = sp.getString("recipients", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(String.valueOf(F), -1);
            if (parts.length < 1) continue;
            Recipient r = new Recipient();
            r.email = parts[0].trim();
            if (r.email.isEmpty()) continue;
            if (parts.length > 1) r.subject = decode(parts[1]);
            if (parts.length > 2) r.body = decode(parts[2]);
            out.add(r);
        }
        return out;
    }

    public List<String> recipients() {
        List<String> out = new ArrayList<>();
        for (Recipient r : recipientList()) out.add(r.email);
        return out;
    }

    public String recipientsRaw() {
        StringBuilder sb = new StringBuilder();
        for (Recipient r : recipientList()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.email);
        }
        return sb.toString();
    }

    public boolean isConfigured() {
        List<Recipient> list = recipientList();
        if (host().trim().isEmpty() || port() <= 0) return false;
        if (!com.keepalive.deadman.core.SmtpClient.isEmail(user())) return false;
        if (password().isEmpty()) return false;
        if (list.isEmpty()) return false;
        for (Recipient r : list) {
            if (!com.keepalive.deadman.core.SmtpClient.isEmail(r.email)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 状态

    public long lastCheckIn() { return sp.getLong("last_checkin", 0); }

    public long sentAt() { return sp.getLong("sent_at", 0); }

    public void setSentAt(long t) { sp.edit().putLong("sent_at", t).apply(); }

    public long sending() { return sp.getLong("sending_since", 0); }

    public void setSending(long t) { sp.edit().putLong("sending_since", t).apply(); }

    public String lastError() { return sp.getString("last_error", ""); }

    public void setLastError(String msg) {
        sp.edit().putString("last_error", msg == null ? "" : msg).apply();
    }

    public void setEnabled(boolean on) { sp.edit().putBoolean("enabled", on).apply(); }

    /** 打卡：重置计时，并清除"已发送"状态。 */
    public void checkIn(long now) {
        sp.edit()
                .putLong("last_checkin", now)
                .putLong("sent_at", 0)
                .putLong("sending_since", 0)
                .remove("last_error")
                .putBoolean("enabled", true)
                .apply();
        addHistory("checkin", "完成打卡");
    }

    // ------------------------------------------------------------------ 记录

    public static final class Item {
        public final long ts;
        public final String type;
        public final String detail;

        Item(long ts, String type, String detail) {
            this.ts = ts;
            this.type = type;
            this.detail = detail;
        }
    }

    public List<Item> history(int max) {
        String raw = sp.getString("history", "");
        List<Item> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (out.size() >= max) break;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            try {
                out.add(new Item(Long.parseLong(parts[0]), parts[1], parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public void addHistory(String type, String detail) {
        String d = detail == null ? "" : detail.replace('\n', ' ').replace('\t', ' ');
        String line = System.currentTimeMillis() + "\t" + type + "\t" + d;
        String existing = sp.getString("history", "");
        StringBuilder sb = new StringBuilder(line);
        if (existing != null && !existing.isEmpty()) {
            String[] old = existing.split("\n");
            int keep = Math.min(old.length, HISTORY_MAX - 1);
            for (int i = 0; i < keep; i++) sb.append('\n').append(old[i]);
        }
        sp.edit().putString("history", sb.toString()).apply();
    }

    // ------------------------------------------------------------------ 写入

    public Edit edit() {
        return new Edit();
    }

    public final class Edit {
        private final SharedPreferences.Editor ed = sp.edit();

        public Edit host(String v) { ed.putString("host", v == null ? "" : v.trim()); return this; }

        public Edit port(int v) { ed.putInt("port", v); return this; }

        public Edit security(int v) { ed.putInt("security", v); return this; }

        public Edit user(String v) { ed.putString("user", v == null ? "" : v.trim()); return this; }

        public Edit fromName(String v) { ed.putString("from_name", v == null ? "" : v.trim()); return this; }

        public Edit password(String v) {
            try {
                ed.putString("pass", crypto.encrypt(v == null ? "" : v));
                cachedPassword = v == null ? "" : v;
                passwordLoaded = true;
            } catch (Exception ignored) {
            }
            return this;
        }

        public Edit intervalHours(int v) { ed.putInt("interval_hours", v); return this; }

        public Edit remindHours(int v) { ed.putInt("remind_hours", v); return this; }

        public Edit graceMinutes(int v) { ed.putInt("grace_minutes", v); return this; }

        public Edit subject(String v) { ed.putString("subject", v); return this; }

        public Edit body(String v) { ed.putString("body", v); return this; }

        public Edit birth(long ms) { ed.putLong("birth_ms", ms); return this; }

        public Edit recipients(List<Recipient> list) {
            StringBuilder sb = new StringBuilder();
            if (list != null) {
                for (Recipient r : list) {
                    if (r == null || r.email == null || r.email.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(r.email.trim()).append(F)
                      .append(encode(r.subject)).append(F)
                      .append(encode(r.body));
                }
            }
            ed.putString("recipients", sb.toString());
            return this;
        }

        /** 兼容旧调用：只给地址，内容走默认模板。 */
        public Edit recipientsRaw(String raw) {
            List<Recipient> list = new ArrayList<>();
            for (String addr : splitAddresses(raw)) list.add(new Recipient(addr));
            return recipients(list);
        }

        public void commit() { ed.apply(); }
    }

    // ------------------------------------------------------------------ 迁移与工具

    /** 老版本只存了一串地址，首次启动时转成新的收件人结构。 */
    private void migrate() {
        if (sp.contains("recipients")) return;
        String legacy = sp.getString("to", "");
        if (legacy == null || legacy.trim().isEmpty()) return;
        List<Recipient> list = new ArrayList<>();
        for (String addr : splitAddresses(legacy)) list.add(new Recipient(addr));
        edit().recipients(list).commit();
    }

    public static List<String> splitAddresses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String[] parts = raw.split("[\\s,;，；、]+");
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String encode(String s) {
        if (s == null) s = "";
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.decode(s, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
