# 守夜人 · Nightwatch

每天打卡一次的安全确认 App。**如果你连续超过设定时间没有打卡，它会自动用你自己的邮箱，把你预先写好的邮件发给指定的人。**

- 包名：`com.keepalive.deadman`
- 版本：1.0.0（versionCode 1）
- 支持：Android 7.0（API 24）及以上，targetSdk 34
- 体积：4.6 MB，无任何第三方统计/广告 SDK

---

## 一、安装

APK 在 `/workspace/Nightwatch-1.0.0.apk`。

**方式 A：数据线 + adb**

```bash
adb install -r Nightwatch-1.0.0.apk
```

**方式 B：直接传到手机**

把 APK 拷到手机（微信文件传输助手 / 网盘 / 数据线都行），点击安装；系统提示"未知来源应用"时，允许对该文件管理器授权即可。

> 首次打开如果提示"应用未经 Play 保护扫描"，选择"仍要安装"。

---

## 二、首次设置（约 3 分钟）

1. 打开 App → 右上角齿轮 → **设置**
2. 填发件邮箱：
   - 点 **Gmail / QQ邮箱 / 163 / Outlook** 快捷填充，会自动填好服务器、端口、加密方式
   - 填**发件邮箱地址**
   - 填**授权码**（注意：不是登录密码，见下表）
3. 填**收件人**（可多个，用逗号或换行分隔）
4. 设**打卡周期**（默认 24 小时）、**提前提醒**（默认 3 小时）、**宽限期**（默认 30 分钟）
5. 改**邮件主题 / 正文**（可用占位符，见下）
6. 点 **保存并发送测试邮件**，去收件箱（含垃圾箱）确认能收到
7. 回首页，打开 **启用守护** 开关，并允许通知权限
8. 建议顺手把设置页底部"后台可靠性"三项都点成已开启

### 授权码怎么拿

| 邮箱 | SMTP | 拿授权码的位置 |
|---|---|---|
| Gmail | smtp.gmail.com:465 | Google 账号 → 安全性 → 开启两步验证 → **应用专用密码**（16 位） |
| QQ 邮箱 | smtp.qq.com:465 | 设置 → 账号 → 开启「POP3/SMTP 服务」→ 生成**授权码** |
| 163 | smtp.163.com:465 | 设置 → POP3/SMTP/IMAP → 开启 SMTP → 设置**客户端授权密码** |
| Outlook | smtp-mail.outlook.com:587 | 微软账号密码；开了两步验证则用**应用密码** |

### 邮件里的占位符

| 占位符 | 含义 |
|---|---|
| `{name}` | 发件人显示名（没填则用邮箱前缀） |
| `{last_checkin}` | 最后一次打卡时间 |
| `{deadline}` | 应打卡截止时间 |
| `{interval}` | 打卡周期（如"1 天"） |
| `{now}` | 发信时间 |

---

## 三、日常使用

- 每天打开点一次 **立即打卡**，或直接点通知栏上的「立即打卡」按钮，计时重新开始。
- 首页圆环显示距截止还剩多久；进入宽限期会变黄，超时变红。
- 首页下方记录最近 6 条事件（打卡 / 发送 / 失败）。

**状态含义**

| 显示 | 含义 |
|---|---|
| 守护中 · 一切正常 | 计时中，无需操作 |
| 已到打卡时间，请尽快打卡 | 过了截止时间，仍在宽限期内，**现在打卡还来得及** |
| 正在发送失联邮件… | 已超宽限期，正在投递；没网就等联网后立即发 |
| 失联邮件已发送 | 已发出。回来后打卡即可恢复正常守护 |

---

## 四、可靠性：它凭什么"一定会发"

发信这件事由三层机制共同保证，任何一层失效还有下一层兜底：

1. **精确闹钟**（AlarmManager + `setExactAndAllowWhileIdle`）——到点即触发，Doze 休眠下也能唤醒
2. **WorkManager 看门狗**——每 15 分钟检查一次状态，闹钟被系统清掉也能补上
3. **开机重建**——重启、改时间、改时区、App 升级后自动重新排程；关机期间错过的期限，开机后立刻补发

发信失败（无网络、服务器报错）会自动指数退避重试，直到成功；成功后不会重复轰炸。

### 需要你配合的三件事（重要）

- **通知权限**：否则收不到「快到期」和「已发送」提醒
- **精确闹钟**：Android 12+ 需要在设置页点「去开启」（Android 14 默认不给）
- **电池优化**：把本应用设为「不限制 / 允许后台活动」

国产 ROM 额外建议：在系统设置里给「守夜人」开启**自启动**，并在最近任务里**锁定**它（小米/华为/OPPO/vivo 都会积极清理后台）。

---

## 五、安全与隐私

- 授权码用 **Android Keystore 里的 AES-256-GCM 密钥加密**后存本地，明文不落盘；换机/恢复出厂后密钥失效，需要重新填。
- 邮件通过 **TLS 直连你邮箱服务商的 SMTP 服务器**发出，不经过任何第三方中转服务器，App 也没有联网上报逻辑。
- 邮件主题/正文（含你的姓名和安排）以**明文存在本机**的 SharedPreferences 里，这是功能必需的，请注意手机本身的锁屏安全。
- 拿到授权码等于拿到发信能力，建议**单独用一个邮箱**专门做这件事，不要用主邮箱。

---

## 六、常见问题

**发测试邮件失败**

| 报错 | 处理 |
|---|---|
| `535 认证失败` | 用的是登录密码而不是授权码；或该邮箱没开启 SMTP 服务 |
| `连接超时` | 端口/加密方式不匹配（SSL 配 465，STARTTLS 配 587），或当前网络封了该端口，换 Wi-Fi/流量再试 |
| `TLS 握手失败` | 切换加密方式重试；公司网络可能有中间人代理 |
| `所有收件人都被拒绝` | 收件地址写错，或对方服务器拒收 |

**会不会误发？** 只有"超过周期 + 宽限期仍未打卡"才会发。改设置时如果新周期已经过期，App 会按此刻重新计时，不会立刻发信。

**忘记打卡但人没事？** 收到邮件后打卡即可，状态会立即恢复"守护中"，不会连续发。

---

## 七、技术实现

纯 Java（无 Kotlin），只依赖 AndroidX AppCompat、Material Components、WorkManager，SMTP 客户端是自己实现的（`core/SmtpClient.java`，约 400 行，不引入 JavaMail）。

```
app/src/main/java/com/keepalive/deadman/
├── MainActivity.java        首页：倒计时、打卡、状态、记录
├── SettingsActivity.java    设置：SMTP、模板、周期、权限
├── CountdownRingView.java   自绘倒计时圆环
├── Prefs.java               配置与状态（授权码加密存取）
├── Crypto.java              Keystore AES-256-GCM
├── Scheduler.java           闹钟 + WorkManager + 重建
├── CheckWorker.java         到期判定与发信（含失败重试）
├── AlarmReceiver.java       闹钟触发
├── CheckInReceiver.java     通知栏一键打卡
├── BootReceiver.java        开机/改时间/升级后重建
├── Notify.java              通知渠道与样式
├── Mail.java                配置 + 模板 → 邮件
└── core/                    纯 Java，可在桌面上直接测试
    ├── SmtpClient.java      SSL / STARTTLS / AUTH PLAIN·LOGIN / UTF-8 编码
    ├── Guard.java           截止、宽限、是否该发、时长格式化
    └── Template.java        占位符替换
```

### 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
cd /workspace/deadman
sh gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
sh gradlew testDebugUnitTest    # 5 项 Robolectric UI 测试
sh tools/run-tests.sh           # 8 项 SMTP 协议 / 逻辑测试（含真实 TLS 握手）
```

依赖走阿里云镜像（清华 TUNA 不提供 Maven 镜像）。

### 已通过的验证

- **SMTP 协议测试（8 项）**：SSL 465 + AUTH PLAIN、STARTTLS 587 + AUTH LOGIN、多收件人、部分收件人被拒、错误密码报错、非法地址拦截、截止/宽限逻辑、中文主题与正文编码（本地起真实 TLS SMTP 服务器，用标准邮件解析器反解校验）
- **界面测试（5 项，Robolectric）**：首页/设置页 inflate、打卡重置计时、禁用与超时状态、表单校验、圆环绘制
- **Android Lint**：0 error（minSdk 24 兼容性检查通过）
- **产物**：v2 签名校验通过、zipalign 通过

### 签名

**仓库里不含签名私钥**（`keystore/` 已被 `.gitignore` 排除），所以 clone 下来直接 `assembleRelease` 得到的是 unsigned 包。

要用自己的密钥出正式包：

```bash
keytool -genkeypair -keystore keystore/nightwatch.jks -alias nightwatch \
  -keyalg RSA -keysize 2048 -validity 10950
cat > keystore/signing.properties <<'PP'
storePassword=你的密码
keyAlias=nightwatch
keyPassword=你的密码
PP
```

**以后升级必须用同一个 keystore 签名**，否则无法覆盖安装（需要先卸载，配置会丢）。

---

## 八、已知限制

- 手机关机 / 完全断网期间无法发信——恢复后会自动补发，但会晚于预期时间。
- 被用户"强行停止"的 App 无法接收闹钟和开机广播，直到下次手动打开。国产 ROM 的深度清理同理。
- Gmail 对频繁自动化发信有风控，日常每天一封没有问题，但别用来做高频群发。
- 邮件正文是纯文本，不支持 HTML 和附件。