package com.keepalive.deadman.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 SMTP 客户端：只用 JDK/Android 自带 API，无第三方依赖。
 * 支持 465 隐式 TLS、587 STARTTLS、AUTH PLAIN / AUTH LOGIN、UTF-8 主题与正文。
 */
public final class SmtpClient {

    public static final int SEC_SSL = 0;
    public static final int SEC_STARTTLS = 1;
    public static final int SEC_NONE = 2;

    public static final class Config {
        public String host = "";
        public int port = 465;
        public int security = SEC_SSL;
        public String user = "";
        public String password = "";
        public String fromAddress = "";
        public String fromName = "";
        public int connectTimeoutMs = 20000;
        public int readTimeoutMs = 45000;
        /** 仅测试时可注入，正式使用时为 null（走系统信任库并校验证书域名）。 */
        public SSLSocketFactory sslSocketFactory;
    }

    private SmtpClient() {}

    public static void send(Config cfg, List<String> recipients, String subject, String body) throws IOException {
        if (cfg == null) throw new IOException("缺少邮件配置");
        if (isEmpty(cfg.host)) throw new IOException("未填写 SMTP 服务器地址");
        if (cfg.port <= 0 || cfg.port > 65535) throw new IOException("SMTP 端口不正确");
        if (!isEmail(cfg.fromAddress)) throw new IOException("发件邮箱格式不正确");
        if (recipients == null || recipients.isEmpty()) throw new IOException("没有填写收件人");
        for (String r : recipients) {
            if (!isEmail(r)) throw new IOException("收件人邮箱格式不正确：" + r);
        }
        if (!isEmpty(cfg.user) && isEmpty(cfg.password)) throw new IOException("未填写邮箱密码 / 授权码");

        Socket raw = null;
        Session s = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(cfg.host, cfg.port), cfg.connectTimeoutMs);
            raw.setSoTimeout(cfg.readTimeoutMs);

            if (cfg.security == SEC_SSL) {
                s = new Session(wrap(raw, cfg));
            } else {
                s = new Session(raw);
            }

            s.require(s.read(), 220, "连接邮件服务器");
            Resp ehlo = s.ehlo();
            s.require(ehlo, 250, "EHLO 握手");
            s.capabilities = ehlo.text;

            if (cfg.security == SEC_STARTTLS) {
                if (!s.capabilities.toUpperCase(Locale.US).contains("STARTTLS")) {
                    throw new IOException("服务器不支持 STARTTLS，请改用 SSL 加密（端口 465）");
                }
                s.require(s.cmd("STARTTLS"), 220, "STARTTLS");
                Session tls = new Session(wrap(raw, cfg));
                s = tls;
                Resp ehlo2 = s.ehlo();
                s.require(ehlo2, 250, "TLS 后 EHLO");
                s.capabilities = ehlo2.text;
            }

            if (!isEmpty(cfg.user)) {
                s.auth(cfg);
            }

            s.require(s.cmd("MAIL FROM:<" + cfg.fromAddress.trim() + ">"), 250, "指定发件人");
            StringBuilder rejected = new StringBuilder();
            int accepted = 0;
            for (String rcpt : recipients) {
                Resp r = s.cmd("RCPT TO:<" + rcpt.trim() + ">");
                if (r.code == 250 || r.code == 251) {
                    accepted++;
                } else {
                    if (rejected.length() > 0) rejected.append("；");
                    rejected.append(rcpt).append("（").append(r.code).append(" ").append(abbreviate(r.text)).append("）");
                }
            }
            if (accepted == 0) {
                throw new IOException("所有收件人都被服务器拒绝：" + rejected);
            }

            s.require(s.cmd("DATA"), 354, "DATA");
            s.writeData(buildMessage(cfg, recipients, subject, body, System.currentTimeMillis()));
            s.require(s.read(), 250, "投递邮件");

            try {
                s.cmd("QUIT");
            } catch (IOException ignored) {
                // 服务器可能已关闭连接，不影响投递结果
            }
            if (rejected.length() > 0) {
                // 部分收件人失败：整体仍算成功，但把信息抛给上层记录
                throw new PartialFailure(accepted + " 个收件人已投递，以下被拒绝：" + rejected);
            }
        } finally {
            if (s != null) s.close();
            if (raw != null) {
                try { raw.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 部分收件人被拒，但邮件已成功发出。 */
    public static final class PartialFailure extends IOException {
        public PartialFailure(String msg) { super(msg); }
    }

    // ---------------------------------------------------------------- 协议细节

    private static final class Resp {
        int code;
        String text = "";
    }

    private static final class Session {
        private final Socket sock;
        private final InputStream in;
        private final OutputStream out;
        String capabilities = "";

        Session(Socket s) throws IOException {
            sock = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        String readLine() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                if (b != '\r') buf.write(b);
                if (buf.size() > 8192) throw new IOException("SMTP 响应异常：单行过长");
            }
            if (buf.size() == 0) throw new IOException("服务器断开了连接");
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        Resp read() throws IOException {
            String line = readLine();
            Resp r = new Resp();
            r.code = parseCode(line);
            StringBuilder txt = new StringBuilder(textOf(line));
            int guard = 0;
            while (line.length() > 3 && line.charAt(3) == '-') {
                if (++guard > 50) throw new IOException("SMTP 响应异常：行数过多");
                line = readLine();
                String t = textOf(line);
                if (t.length() > 0) {
                    if (txt.length() > 0) txt.append('\n');
                    txt.append(t);
                }
            }
            r.text = txt.toString();
            return r;
        }

        Resp cmd(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return read();
        }

        void require(Resp r, int code, String what) throws IOException {
            if (r.code != code) {
                String hint = "";
                if (r.code == 535 || r.code == 534 || r.code == 530) {
                    hint = "（账号或授权码不正确，也可能是服务器要求使用「应用专用密码」）";
                } else if (r.code == 550 || r.code == 553) {
                    hint = "（发件人地址被拒绝，请确认发件邮箱与登录账号一致）";
                }
                throw new IOException(what + "失败：" + r.code + " " + abbreviate(r.text) + hint);
            }
        }

        Resp ehlo() throws IOException {
            Resp r = cmd("EHLO " + localName());
            if (r.code != 250) r = cmd("HELO " + localName());
            return r;
        }

        void auth(Config cfg) throws IOException {
            String caps = " " + capabilities.toUpperCase(Locale.US).replace("AUTH=", "AUTH ") + " ";
            boolean plain = caps.contains(" PLAIN ");
            if (plain) {
                String token = B64.encode(("\u0000" + cfg.user + "\u0000" + cfg.password).getBytes(StandardCharsets.UTF_8));
                Resp r = cmd("AUTH PLAIN " + token);
                if (r.code == 334) r = cmd(token);
                require(r, 235, "SMTP 登录认证");
            } else {
                require(cmd("AUTH LOGIN"), 334, "SMTP 登录认证");
                require(cmd(B64.encode(cfg.user.getBytes(StandardCharsets.UTF_8))), 334, "SMTP 用户名");
                require(cmd(B64.encode(cfg.password.getBytes(StandardCharsets.UTF_8))), 235, "SMTP 密码 / 授权码");
            }
        }

        void writeData(byte[] message) throws IOException {
            String text = new String(message, StandardCharsets.UTF_8);
            int start = 0;
            int n = text.length();
            while (start <= n) {
                int idx = text.indexOf("\r\n", start);
                String line;
                if (idx < 0) {
                    line = text.substring(start);
                    start = n + 1;
                } else {
                    line = text.substring(start, idx);
                    start = idx + 2;
                }
                if (line.startsWith(".")) out.write('.');
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\r');
                out.write('\n');
                if (idx < 0) break;
            }
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void close() {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private static SSLSocket wrap(Socket raw, Config cfg) throws IOException {
        SSLSocketFactory f = cfg.sslSocketFactory != null
                ? cfg.sslSocketFactory
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(raw, cfg.host, cfg.port, true);
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(p);
        s.setSoTimeout(cfg.readTimeoutMs);
        s.startHandshake();
        return s;
    }

    private static int parseCode(String line) throws IOException {
        if (line.length() < 3) throw new IOException("无法解析 SMTP 响应：" + line);
        try {
            return Integer.parseInt(line.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("无法解析 SMTP 响应：" + line);
        }
    }

    private static String textOf(String line) {
        if (line.length() <= 4) return "";
        return line.substring(4).trim();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static String localName() {
        return "localhost";
    }

    // ---------------------------------------------------------------- 邮件构造

    static byte[] buildMessage(Config cfg, List<String> to, String subject, String body, long now) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("From: ").append(formatAddress(cfg.fromName, cfg.fromAddress)).append("\r\n");
        sb.append("To: ");
        for (int i = 0; i < to.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatAddress("", to.get(i)));
        }
        sb.append("\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        sb.append("Date: ").append(rfc2822(now)).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n");
        sb.append("X-Mailer: Nightwatch/1.0 (Android)\r\n");
        sb.append("\r\n");

        String b64 = B64.encode(body.getBytes(StandardCharsets.UTF_8));
        int i = 0;
        while (i < b64.length()) {
            int end = Math.min(i + 76, b64.length());
            sb.append(b64, i, end).append("\r\n");
            i = end;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String formatAddress(String name, String addr) {
        String a = addr == null ? "" : addr.trim();
        if (isEmpty(name)) return a;

        String n = sanitize(name);
        boolean ascii = true;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 32 || c > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            // 纯 ASCII 显示名：含特殊字符时按 RFC 5322 加引号转义
            String specials = "()<>@,;:\\\".[]";
            boolean special = false;
            for (int i = 0; i < n.length(); i++) {
                if (specials.indexOf(n.charAt(i)) >= 0) {
                    special = true;
                    break;
                }
            }
            if (special || n.startsWith(" ") || n.endsWith(" ")) {
                String escaped = n.replace("\\", "\\\\").replace("\"", "\\\"");
                n = "\"" + escaped + "\"";
            }
            return n + " <" + a + ">";
        }
        return encodeHeader(n) + " <" + a + ">";
    }

    /** RFC 2047 编码：纯 ASCII 原样输出，否则按 UTF-8 分块 base64。 */
    static String encodeHeader(String s) {
        String clean = sanitize(s);
        boolean ascii = true;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c < 32 || c > 126) { ascii = false; break; }
        }
        if (ascii) return clean;

        byte[] raw = clean.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        boolean first = true;
        while (pos < raw.length) {
            int end = Math.min(pos + 45, raw.length);
            while (end < raw.length && end > pos && (raw[end] & 0xC0) == 0x80) end--;
            if (end == pos) end = Math.min(pos + 45, raw.length);
            if (!first) out.append("\r\n ");
            out.append("=?UTF-8?B?").append(B64.encode(raw, pos, end - pos)).append("?=");
            first = false;
            pos = end;
        }
        return out.toString();
    }

    static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    static String rfc2822(long now) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(now));
    }

    public static boolean isEmail(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.length() < 5 || v.length() > 254) return false;
        if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf(' ') >= 0) return false;
        int at = v.indexOf('@');
        if (at <= 0 || at != v.lastIndexOf('@') || at == v.length() - 1) return false;
        String domain = v.substring(at + 1);
        if (domain.indexOf('.') < 0) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '@' || c == '.' || c == '_' || c == '-' || c == '+' || c == '%' || c == '\'';
            if (!ok) return false;
        }
        return true;
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 自带 Base64（java.util.Base64 在 Android 上需要 API 26）。 */
    static final class B64 {
        private static final char[] T =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] d) { return encode(d, 0, d.length); }

        static String encode(byte[] d, int off, int len) {
            StringBuilder sb = new StringBuilder(((len + 2) / 3) * 4);
            int i = off, end = off + len;
            while (i + 2 < end) {
                int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
                sb.append(T[(n >> 18) & 63]).append(T[(n >> 12) & 63])
                  .append(T[(n >> 6) & 63]).append(T[n & 63]);
                i += 3;
            }
            int rem = end - i;
            if (rem == 1) {
                int n = (d[i] & 0xFF) << 16;
                sb.append(T[(n >> 18) & 63]).append(T[(n >> 12) & 63]).append("==");
            } else if (rem == 2) {
                int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
                sb.append(T[(n >> 18) & 63]).append(T[(n >> 12) & 63])
                  .append(T[(n >> 6) & 63]).append('=');
            }
            return sb.toString();
        }
    }
}
