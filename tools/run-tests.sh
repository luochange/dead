#!/bin/sh
# 桌面端验证 SmtpClient / Guard / Template（不需要安卓设备）
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

rm -rf out outbox
mkdir -p out outbox

javac -encoding UTF-8 -d out \
  $(find ../app/src/main/java/com/keepalive/deadman/core -name '*.java') \
  src/com/keepalive/deadman/core/SmtpTest.java

python3 smtp_server.py outbox > server.log 2>&1 &
SRV=$!
sleep 2
trap 'kill $SRV 2>/dev/null' EXIT INT TERM

java -Djavax.net.ssl.trustStore=tls/trust.p12 \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -Djavax.net.ssl.trustStoreType=PKCS12 \
     -cp out com.keepalive.deadman.core.SmtpTest 2465 2587 2525
