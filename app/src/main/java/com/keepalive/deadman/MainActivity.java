package com.keepalive.deadman;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.keepalive.deadman.core.Age;
import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.Template;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 9001;

    private CountdownRingView ring;
    private TextView tvStatus, tvStatusSub, tvLast, tvDeadline, tvRemind, tvTestState, tvEnabledHint;
    private TextView tvAgeDays, tvAgeClock, tvAgeYmd, tvAgeHint;
    private View ageValues;
    private MaterialButton btnTest;
    private SwitchMaterial swEnabled;
    private View cardWarning;
    private LinearLayout historyContainer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastKickAt = 0;
    private boolean suppressSwitch = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ring = findViewById(R.id.ring);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusSub = findViewById(R.id.tvStatusSub);
        tvLast = findViewById(R.id.tvLast);
        tvDeadline = findViewById(R.id.tvDeadline);
        tvRemind = findViewById(R.id.tvRemind);
        tvTestState = findViewById(R.id.tvTestState);
        tvEnabledHint = findViewById(R.id.tvEnabledHint);
        ageValues = findViewById(R.id.ageValues);
        tvAgeDays = findViewById(R.id.tvAgeDays);
        tvAgeClock = findViewById(R.id.tvAgeClock);
        tvAgeYmd = findViewById(R.id.tvAgeYmd);
        tvAgeHint = findViewById(R.id.tvAgeHint);
        btnTest = findViewById(R.id.btnTest);
        swEnabled = findViewById(R.id.swEnabled);
        cardWarning = findViewById(R.id.cardWarning);
        historyContainer = findViewById(R.id.historyContainer);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> openSettings());
        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.cardAge).setOnClickListener(v -> openSettings());
        cardWarning.setOnClickListener(v -> openSettings());
        findViewById(R.id.btnCheckIn).setOnClickListener(v -> doCheckIn());
        btnTest.setOnClickListener(v -> sendTestMail());

        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            onToggleEnabled(isChecked);
        });

        Notify.ensureChannels(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        ticker.run();
        View stars = findViewById(R.id.stars);
        if (stars != null) stars.invalidate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ------------------------------------------------------------------ 打卡

    private void doCheckIn() {
        Prefs p = Prefs.get(this);
        long now = System.currentTimeMillis();
        p.checkIn(now);
        Scheduler.cancelSend(this);
        Scheduler.scheduleAll(this);
        Notify.clearAll(this);

        findViewById(R.id.btnCheckIn).performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        refresh(true);

        long deadline = Guard.deadline(now, p.intervalHours() * Guard.HOUR);
        Toast.makeText(this, "已打卡 ✓\n下次截止：" + Template.dateTime(deadline), Toast.LENGTH_SHORT).show();
    }

    private void onToggleEnabled(boolean checked) {
        Prefs p = Prefs.get(this);
        if (checked) {
            if (!p.isConfigured()) {
                suppressSwitch = true;
                swEnabled.setChecked(false);
                suppressSwitch = false;
                Toast.makeText(this, "请先填写完整的邮箱配置", Toast.LENGTH_SHORT).show();
                openSettings();
                return;
            }
            p.checkIn(System.currentTimeMillis());
            Scheduler.scheduleAll(this);
            Scheduler.ensureWatchdog(this);
            requestNotificationPermission();
            Toast.makeText(this, "守护已开启，记得每天打卡", Toast.LENGTH_SHORT).show();
        } else {
            p.setEnabled(false);
            Scheduler.disable(this);
            Toast.makeText(this, "守护已关闭，不会发送任何邮件", Toast.LENGTH_SHORT).show();
        }
        refresh(true);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ------------------------------------------------------------------ 测试邮件

    private void sendTestMail() {
        final Prefs p = Prefs.get(this);
        if (!p.isConfigured()) {
            Toast.makeText(this, "请先在设置里填写完整的邮箱配置", Toast.LENGTH_SHORT).show();
            openSettings();
            return;
        }
        btnTest.setEnabled(false);
        tvTestState.setVisibility(View.VISIBLE);
        tvTestState.setTextColor(ContextCompat.getColor(this, R.color.muted));
        tvTestState.setText("正在为 " + p.recipientList().size() + " 位收件人逐封发送…");

        executor.execute(() -> {
            String error = null;
            Mail.Result result = null;
            try {
                result = Mail.sendAll(p, true);
            } catch (Exception e) {
                error = e.getMessage();
                if (error == null || error.trim().isEmpty()) error = e.getClass().getSimpleName();
            }
            final String err = error;
            final Mail.Result r = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnTest.setEnabled(true);
                if (err == null && r != null) {
                    tvTestState.setTextColor(ContextCompat.getColor(this, R.color.primary));
                    tvTestState.setText("测试邮件已发出：" + r.summary());
                    p.addHistory("sent", "测试邮件：" + r.summary());
                } else {
                    tvTestState.setTextColor(ContextCompat.getColor(this, R.color.danger));
                    tvTestState.setText("发送失败：" + err);
                    p.addHistory("error", "测试邮件失败：" + err);
                }
                renderHistory(p);
            });
        });
    }

    // ------------------------------------------------------------------ 刷新界面

    private void refresh(boolean force) {
        Prefs p = Prefs.get(this);
        long now = System.currentTimeMillis();

        boolean enabled = p.enabled();
        boolean configured = p.isConfigured();
        long last = p.lastCheckIn();
        long interval = p.intervalHours() * Guard.HOUR;
        long grace = p.graceMinutes() * Guard.MINUTE;
        long deadline = Guard.deadline(last, interval);
        long sendAt = Guard.sendAt(last, interval, grace);
        long sentAt = p.sentAt();
        boolean sent = sentAt > 0 && sentAt >= last;

        int primary = ContextCompat.getColor(this, R.color.primary);
        int secondary = ContextCompat.getColor(this, R.color.secondary);
        int safe = ContextCompat.getColor(this, R.color.safe);
        int amber = ContextCompat.getColor(this, R.color.amber);
        int danger = ContextCompat.getColor(this, R.color.danger);
        int muted = ContextCompat.getColor(this, R.color.muted);

        if (swEnabled.isChecked() != enabled) {
            suppressSwitch = true;
            swEnabled.setChecked(enabled);
            suppressSwitch = false;
        }
        tvEnabledHint.setText(enabled ? "守护中 · 请每天打卡一次" : "关闭后不会发送任何邮件");
        cardWarning.setVisibility(configured ? View.GONE : View.VISIBLE);

        tvLast.setText(last > 0 ? Template.dateTime(last) : "还没有打卡");
        tvDeadline.setText(last > 0 ? Template.dateTime(deadline) : "—");
        tvRemind.setText(p.remindHours() == 0
                ? "不提前提醒 · 宽限 " + p.graceMinutes() + " 分钟"
                : "提前 " + p.remindHours() + " 小时 · 宽限 " + p.graceMinutes() + " 分钟");

        renderAge(p, now);

        if (!enabled) {
            ring.setState(0f, "--:--:--", "未启用", muted);
            tvStatus.setText("守护未启用");
            tvStatus.setTextColor(muted);
            tvStatusSub.setText(configured ? "打开下方开关开始守护" : "先配置邮箱，再开启守护");
        } else if (last <= 0) {
            ring.setState(0f, "--:--:--", "等待打卡", amber);
            tvStatus.setText("还没有打卡记录");
            tvStatus.setTextColor(amber);
            tvStatusSub.setText("点「立即打卡」开始第一次计时");
        } else if (sent) {
            ring.setState(0f, "已发送", "邮件已发出", danger);
            tvStatus.setText("失联邮件已发送");
            tvStatus.setTextColor(danger);
            tvStatusSub.setText("发送于 " + Template.dateTime(sentAt) + " · 回来打卡即可恢复");
        } else if (now < deadline) {
            long remain = deadline - now;
            ring.setState(Guard.fraction(now, last, interval), Guard.clock(remain), "距截止", primary);
            tvStatus.setText("守护中 · 一切正常");
            tvStatus.setTextColor(safe);
            tvStatusSub.setText("还剩 " + Guard.formatSpan(remain));
        } else if (now < sendAt) {
            long untilSend = sendAt - now;
            ring.setState(0f, Guard.clock(untilSend), "宽限期中", amber);
            tvStatus.setText("已到打卡时间，请尽快打卡");
            tvStatus.setTextColor(amber);
            tvStatusSub.setText(untilSend > 0
                    ? "还有 " + Guard.formatSpan(untilSend) + " 就会自动发信"
                    : "即将自动发信");
        } else {
            ring.setState(0f, "00:00:00", "已超时", danger);
            tvStatus.setText("正在发送失联邮件…");
            tvStatus.setTextColor(danger);
            tvStatusSub.setText(p.lastError().isEmpty()
                    ? "若无网络，将在恢复联网后立即发送"
                    : "上次失败：" + p.lastError());
            if (force || now - lastKickAt > 60_000) {
                lastKickAt = now;
                Scheduler.enqueueSend(this);
            }
        }

        if (force || historyContainer.getChildCount() == 0) renderHistory(p);
    }

    private void renderAge(Prefs p, long now) {
        long birth = p.birth();
        if (birth > 0 && birth <= now) {
            ageValues.setVisibility(View.VISIBLE);
            tvAgeHint.setVisibility(View.GONE);
            tvAgeDays.setText(Age.group(Age.totalDays(birth, now)));
            tvAgeClock.setText(Age.clock(birth, now));
            tvAgeYmd.setText(Age.describe(birth, now));
        } else {
            ageValues.setVisibility(View.GONE);
            tvAgeHint.setVisibility(View.VISIBLE);
            tvAgeYmd.setText("");
        }
    }

    private void renderHistory(Prefs p) {
        if (historyContainer == null) return;
        List<Prefs.Item> items = p.history(6);
        historyContainer.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无记录");
            empty.setTextColor(ContextCompat.getColor(this, R.color.muted_dim));
            empty.setTextSize(12f);
            empty.setPadding(0, dp(16), 0, dp(16));
            historyContainer.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (Prefs.Item item : items) {
            View row = inflater.inflate(R.layout.row_history, historyContainer, false);
            TextView detail = row.findViewById(R.id.tvDetail);
            TextView time = row.findViewById(R.id.tvTime);
            View dot = row.findViewById(R.id.dot);

            detail.setText(item.detail);
            time.setText(fmt.format(new Date(item.ts)));
            int color;
            if ("sent".equals(item.type)) color = ContextCompat.getColor(this, R.color.secondary);
            else if ("error".equals(item.type)) color = ContextCompat.getColor(this, R.color.amber);
            else color = ContextCompat.getColor(this, R.color.safe);
            dot.setBackgroundTintList(ColorStateList.valueOf(color));
            historyContainer.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
