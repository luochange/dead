#!/usr/bin/env python3
"""用于验证 SmtpClient 的本地 SMTP 服务器（plain / STARTTLS / 隐式 TLS）。"""
import socket, ssl, threading, base64, sys, os, time

HOST = "127.0.0.1"
USER = "me@test.local"
PASS = "s3cret-pass"


class Server(threading.Thread):
    def __init__(self, port, mode, outdir, offer_plain=True, cert="tls/server.crt", key="tls/server.key"):
        super().__init__(daemon=True)
        self.port, self.mode, self.outdir = port, mode, outdir
        self.offer_plain = offer_plain
        self.cert, self.key = cert, key
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(self.cert, self.key)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((HOST, port))
        self.sock.listen(5)
        self.count = 0
        self.lock = threading.Lock()

    def run(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        try:
            if self.mode == "implicit":
                conn = self.ctx.wrap_socket(conn, server_side=True)
            self.session(conn)
        except Exception as e:
            print("handler error:", e, file=sys.stderr)
        finally:
            try: conn.close()
            except Exception: pass

    def session(self, conn):
        f = conn.makefile("rb")
        tls = self.mode == "implicit"

        def send(s):
            conn.sendall(s.encode() + b"\r\n")

        def line():
            l = f.readline()
            if not l:
                raise ConnectionError("eof")
            return l.decode("utf-8", "replace").rstrip("\r\n")

        send("220 test.local ESMTP NightwatchTest")
        while True:
            try:
                cmd = line()
            except Exception:
                return
            up = cmd.upper()
            if up.startswith("EHLO"):
                caps = ["test.local", "SIZE 10485760", "8BITMIME"]
                if self.offer_plain:
                    caps.append("AUTH PLAIN LOGIN")
                else:
                    caps.append("AUTH LOGIN")
                if self.mode == "starttls" and not tls:
                    caps.append("STARTTLS")
                for i, cap in enumerate(caps):
                    send(("250 " if i == len(caps) - 1 else "250-") + cap)
            elif up.startswith("HELO"):
                send("250 test.local")
            elif up.startswith("AUTH PLAIN"):
                parts = cmd.split(" ", 2)
                if len(parts) == 3:
                    ok = self.check_plain(parts[2])
                    send("235 2.7.0 Authentication successful" if ok else "535 5.7.8 Authentication credentials invalid")
                else:
                    send("334 ")
                    ok = self.check_plain(line())
                    send("235 2.7.0 Authentication successful" if ok else "535 5.7.8 Authentication credentials invalid")
            elif up.startswith("AUTH LOGIN"):
                send("334 VXNlcm5hbWU6")
                u = base64.b64decode(line()).decode()
                send("334 UGFzc3dvcmQ6")
                p = base64.b64decode(line()).decode()
                ok = (u == USER and p == PASS)
                send("235 2.7.0 Authentication successful" if ok else "535 5.7.8 Authentication credentials invalid")
            elif up.startswith("STARTTLS"):
                if self.mode != "starttls" or tls:
                    send("502 5.5.1 STARTTLS not available")
                    continue
                send("220 2.0.0 Ready to start TLS")
                conn = self.ctx.wrap_socket(conn, server_side=True)
                f = conn.makefile("rb")
                tls = True
            elif up.startswith("MAIL FROM"):
                send("250 2.1.0 Ok")
            elif up.startswith("RCPT TO"):
                if "reject" in cmd.lower():
                    send("550 5.1.1 No such user")
                else:
                    send("250 2.1.5 Ok")
            elif up.startswith("DATA"):
                send("354 End data with <CR><LF>.<CR><LF>")
                buf = []
                while True:
                    l = f.readline()
                    if not l:
                        raise ConnectionError("eof in data")
                    if l in (b".\r\n", b".\n"):
                        break
                    if l.startswith(b".."):
                        l = l[1:]
                    buf.append(l)
                self.save(b"".join(buf))
                send("250 2.0.0 Ok: queued")
            elif up.startswith("QUIT"):
                send("221 2.0.0 Bye")
                return
            elif up.startswith("RSET"):
                send("250 2.0.0 Ok")
            else:
                send("500 5.5.2 Command unrecognized")

    def check_plain(self, token):
        try:
            raw = base64.b64decode(token.encode()).decode()
            _, u, p = raw.split("\x00")
            return u == USER and p == PASS
        except Exception:
            return False

    def save(self, data):
        with self.lock:
            self.count += 1
            n = self.count
        path = os.path.join(self.outdir, f"msg_{self.port}_{n}.eml")
        with open(path, "wb") as fh:
            fh.write(data)
        print("saved", path)


if __name__ == "__main__":
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    servers = [
        Server(2465, "implicit", outdir, offer_plain=True),
        Server(2587, "starttls", outdir, offer_plain=False),
        Server(2525, "plain", outdir, offer_plain=True),
    ]
    for s in servers:
        s.start()
    print("ready", flush=True)
    while True:
        time.sleep(1)
