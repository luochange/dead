package com.keepalive.deadman;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.SmtpClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 9002;

    private TextInputEditText etHost, etPort, etUser, etPass, etFromName, etTo,
            etInterval, etRemind, etGrace, etSubject, etBody;
    private RadioButton rbSsl, rbStarttls, rbNone;
    private TextView tvPassHint, tvNotifState, tvAlarmState, tvBatteryState, tvTestResult;
    private MaterialButton btnSave, btnTestMail, btnNotif, btnAlarm, btnBattery;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etFromName = findViewById(R.id.etFromName);
        etTo = findViewById(R.id.etTo);
        etInterval = findViewById(R.id.etInterval);
        etRemind = findViewById(R.id.etRemind);
        etGrace = findViewById(R.id.etGrace);
        etSubject = findViewById(R.id.etSubject);
        etBody = findViewById(R.id.etBody);
        rbSsl = findViewById(R.id.rbSsl);
        rbStarttls = findViewById(R.id.rbStarttls);
        rbNone = findViewById(R.id.rbNone);
        tvPassHint = findViewById(R.id.tvPassHint);
        tvNotifState = findViewById(R.id.tvNotifState);
        tvAlarmState = findViewById(R.id.tvAlarmState);
        tvBatteryState = findViewById(R.id.tvBatteryState);
        tvTestResult = findViewById(R.id.tvTestResult);
        btnSave = findViewById(R.id.btnSave);
        btnTestMail = findViewById(R.id.btnTestMail);
        btnNotif = findViewById(R.id.btnNotif);
        btnAlarm = findViewById(R.id.btnAlarm);
        btnBattery = findViewById(R.id.btnBattery);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> {
            if (save()) {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        btnTestMail.setOnClickListener(v -> testMail());
        btnNotif.setOnClickListener(v -> askNotification());
        btnAlarm.setOnClickListener(v -> askExactAlarm());
        btnBattery.setOnClickListener(v -> askBattery());

        findViewById(R.id.chipGmail).setOnClickListener(v ->
                applyPreset("smtp.gmail.com", 465, 0,
                        "Gmail 需要「应用专用密码」：Google 账号 → 安全性 → 开启两步验证 → 应用专用密码，生成 16 位密码填在这里（不是 Google 登录密码）。"));
        findViewById(R.id.chipQq).setOnClickListener(v ->
                applyPreset("smtp.qq.com", 465, 0,
                        "QQ 邮箱：设置 → 账号 → 开启「POP3/SMTP 服务」→ 生成授权码，把授权码填在这里。"));
        findViewById(R.id.chip163).setOnClickListener(v ->
                applyPreset("smtp.163.com", 465, 0,
                        "163 邮箱：设置 → POP3/SMTP/IMAP → 开启 SMTP 服务 → 设置客户端授权密码，填这个授权码。"));
        findViewById(R.id.chipOutlook).setOnClickListener(v ->
                applyPreset("smtp-mail.outlook.com", 587, 1,
                        "Outlook / Hotmail：使用微软账号密码；若开启了两步验证，请生成「应用密码」。端口 587 使用 STARTTLS。"));

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ 读写

    private void load() {
        Prefs p = Prefs.get(this);
        etHost.setText(p.host());
        etPort.setText(String.valueOf(p.port()));
        etUser.setText(p.user());
        etPass.setText(p.password());
        etFromName.setText(p.fromName());
        etTo.setText(p.recipientsRaw());
        etInterval.setText(String.valueOf(p.intervalHours()));
        etRemind.setText(String.valueOf(p.remindHours()));
        etGrace.setText(String.valueOf(p.graceMinutes()));
        etSubject.setText(p.subject());
        etBody.setText(p.body());

        if (p.security() == 1) rbStarttls.setChecked(true);
        else if (p.security() == 2) rbNone.setChecked(true);
        else rbSsl.setChecked(true);
    }

    private boolean save() {
        String host = text(etHost);
        int port = number(etPort, -1);
        String user = text(etUser);
        String pass = text(etPass);
        String toRaw = text(etTo);
        int interval = number(etInterval, 24);
        int remind = number(etRemind, 3);
        int grace = number(etGrace, 30);
        String subject = text(etSubject);
        String body = text(etBody);

        if (host.isEmpty()) {
            etHost.requestFocus();
            toast("请填写 SMTP 服务器地址");
            return false;
        }
        if (port < 1 || port > 65535) {
            etPort.requestFocus();
            toast("端口不正确");
            return false;
        }
        if (!SmtpClient.isEmail(user)) {
            etUser.requestFocus();
            toast("发件邮箱格式不正确");
            return false;
        }
        if (pass.isEmpty()) {
            etPass.requestFocus();
            toast("请填写密码 / 授权码");
            return false;
        }
        List<String> tos = Prefs.splitAddresses(toRaw);
        if (tos.isEmpty()) {
            etTo.requestFocus();
            toast("请至少填写一个收件邮箱");
            return false;
        }
        for (String t : tos) {
            if (!SmtpClient.isEmail(t)) {
                etTo.requestFocus();
                toast("收件邮箱格式不正确：" + t);
                return false;
            }
        }
        if (interval < 1 || interval > 720) {
            etInterval.requestFocus();
            toast("打卡周期应在 1 ~ 720 小时之间");
            return false;
        }
        if (remind < 0 || remind >= interval) {
            etRemind.requestFocus();
            toast("提前提醒应小于打卡周期");
            return false;
        }
        if (grace < 0 || grace > 1440) {
            etGrace.requestFocus();
            toast("宽限期应在 0 ~ 1440 分钟之间");
            return false;
        }
        if (subject.trim().isEmpty()) {
            etSubject.requestFocus();
            toast("邮件主题不能为空");
            return false;
        }
        if (body.trim().isEmpty()) {
            etBody.requestFocus();
            toast("邮件正文不能为空");
            return false;
        }

        int security = rbStarttls.isChecked() ? 1 : (rbNone.isChecked() ? 2 : 0);

        Prefs p = Prefs.get(this);
        p.edit()
                .host(host)
                .port(port)
                .security(security)
                .user(user)
                .password(pass)
                .fromName(text(etFromName))
                .recipientsRaw(toRaw)
                .intervalHours(interval)
                .remindHours(remind)
                .graceMinutes(grace)
                .subject(subject)
                .body(body)
                .commit();

        p.invalidatePasswordCache();
        if (!pass.equals(p.password())) {
            tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.danger));
            tvTestResult.setText("授权码没能保存：系统密钥库异常。请重启手机后再试，或换一台设备。");
            toast("授权码保存失败，请重启后重试");
            return false;
        }

        if (p.enabled()) {
            // 改成更短的周期后，旧打卡时间可能已经"过期"。
            // 此刻用户就在眼前，不应该立刻给联系人发信，按现在重新计时。
            long now = System.currentTimeMillis();
            if (Guard.isOverdue(now, p.lastCheckIn(), interval * Guard.HOUR, grace * Guard.MINUTE, p.sentAt())) {
                p.checkIn(now);
                Toast.makeText(this, "新周期已生效，按此刻重新计时", Toast.LENGTH_SHORT).show();
            }
            Scheduler.scheduleAll(this);
        }
        return true;
    }

    // ------------------------------------------------------------------ 测试

    private void testMail() {
        if (!save()) return;
        final Prefs p = Prefs.get(this);
        btnTestMail.setEnabled(false);
        tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.muted));
        tvTestResult.setText("正在连接 " + p.host() + ":" + p.port() + " …");

        executor.execute(() -> {
            String error = null;
            String partial = null;
            long start = System.currentTimeMillis();
            try {
                Mail.sendTest(p);
            } catch (SmtpClient.PartialFailure e) {
                partial = e.getMessage();
            } catch (Exception e) {
                error = e.getMessage();
                if (error == null || error.trim().isEmpty()) error = e.getClass().getSimpleName();
            }
            final String err = error;
            final String part = partial;
            final long cost = System.currentTimeMillis() - start;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnTestMail.setEnabled(true);
                if (err == null && part != null) {
                    tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.amber));
                    tvTestResult.setText("⚠️ 邮件已发出（" + cost + " ms），但部分收件人被服务器拒绝：" + part);
                    p.addHistory("error", "测试邮件部分投递：" + part);
                } else if (err == null) {
                    tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.primary));
                    tvTestResult.setText("✅ 发送成功（耗时 " + cost + " ms），请到收件箱确认，也检查一下垃圾邮件。");
                    p.addHistory("sent", "测试邮件发送成功");
                } else {
                    tvTestResult.setTextColor(ContextCompat.getColor(this, R.color.danger));
                    tvTestResult.setText("❌ 发送失败：" + err);
                    p.addHistory("error", "测试邮件失败：" + err);
                }
            });
        });
    }

    private void applyPreset(String host, int port, int security, String hint) {
        etHost.setText(host);
        etPort.setText(String.valueOf(port));
        if (security == 1) rbStarttls.setChecked(true);
        else rbSsl.setChecked(true);
        tvPassHint.setText(hint);
    }

    // ------------------------------------------------------------------ 权限

    private void refreshPermissions() {
        boolean notif = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        tvNotifState.setText(notif ? "已开启" : "未开启，可能收不到打卡提醒");
        btnNotif.setVisibility(notif ? android.view.View.GONE : android.view.View.VISIBLE);

        boolean alarm = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarm = am == null || am.canScheduleExactAlarms();
        }
        tvAlarmState.setText(alarm ? "已允许，可准点发信" : "未允许，发信可能被系统延后");
        btnAlarm.setVisibility(alarm ? android.view.View.GONE : android.view.View.VISIBLE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        tvBatteryState.setText(battery ? "已忽略电池优化，后台更可靠" : "未忽略，长时间不用可能延迟");
        btnBattery.setVisibility(battery ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            toast("请到系统设置中开启通知");
        }
    }

    private void askExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        toast("请在系统设置中允许「闹钟和提醒」");
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("请在系统设置的电池优化中把本应用设为「不限制」");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissions();
    }

    // ------------------------------------------------------------------ 小工具

    private String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int number(TextInputEditText et, int fallback) {
        try {
            return Integer.parseInt(text(et));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
