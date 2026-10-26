package com.keepalive.deadman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.textfield.TextInputEditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SmokeTest {

    private Context app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs p = Prefs.get(app);
        // Robolectric 没有 AndroidKeyStore，用可逆桩替代
        p.setCryptoForTest(new Crypto() {
            @Override
            String encrypt(String plain) { return "test:" + plain; }

            @Override
            String decrypt(String stored) {
                return stored != null && stored.startsWith("test:") ? stored.substring(5) : "";
            }
        });
        p.edit()
                .password("dummy-app-password")
                .host("smtp.example.com").port(465).security(0)
                .user("me@example.com").fromName("测试用户")
                .recipientsRaw("a@example.com, b@example.com")
                .intervalHours(24).remindHours(3).graceMinutes(30)
                .subject("【测试】{name} 失联").body("我最后一次打卡是 {last_checkin}")
                .commit();
    }

    @Test
    public void mainActivity_inflates_and_checkInWorks() {
        Prefs p = Prefs.get(app);
        p.checkIn(System.currentTimeMillis());

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull(a.findViewById(R.id.ring));
        assertNotNull(a.findViewById(R.id.btnCheckIn));
        assertNotNull(a.findViewById(R.id.historyContainer));
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnCheckIn).getVisibility());

        TextView status = a.findViewById(R.id.tvStatus);
        assertEquals("守护中 · 一切正常", status.getText().toString());

        long before = p.lastCheckIn();
        a.findViewById(R.id.btnCheckIn).performClick();
        assertTrue("打卡后计时应重置", p.lastCheckIn() >= before);
        assertTrue("打卡应写入记录", p.history(5).size() > 0);
        assertEquals("已打卡 ✓\n下次截止：" + com.keepalive.deadman.core.Template
                        .dateTime(com.keepalive.deadman.core.Guard.deadline(p.lastCheckIn(),
                                p.intervalHours() * com.keepalive.deadman.core.Guard.HOUR)),
                ShadowToast.getTextOfLatestToast());
        a.finish();
    }

    @Test
    public void mainActivity_survivesDisabledAndOverdueStates() {
        Prefs p = Prefs.get(app);
        p.setEnabled(false);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("守护未启用", ((TextView) a.findViewById(R.id.tvStatus)).getText().toString());

        // 模拟已经超时很久（但未启用发送，避免真实网络请求）
        p.edit().commit();
        p.setEnabled(true);
        long old = System.currentTimeMillis() - 48L * 3600_000L;
        p.checkIn(old);
        MainActivity b = Robolectric.buildActivity(MainActivity.class).setup().get();
        String s = ((TextView) b.findViewById(R.id.tvStatus)).getText().toString();
        assertTrue("超时状态应显示正在发送，实际：" + s, s.contains("正在发送"));
        a.finish();
        b.finish();
    }

    @Test
    public void settingsActivity_inflates_andSaves() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        TextInputEditText host = a.findViewById(R.id.etHost);
        assertNotNull(host);
        assertEquals("smtp.example.com", host.getText().toString());

        TextInputEditText pass = a.findViewById(R.id.etPass);
        pass.setText("dummy-app-password");

        a.findViewById(R.id.btnSave).performClick();
        assertTrue("保存成功后应关闭页面", a.isFinishing());
    }

    @Test
    public void settingsActivity_rejectsBadInput() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        TextInputEditText host = a.findViewById(R.id.etHost);
        TextInputEditText to = a.findViewById(R.id.etTo);
        host.setText("");
        a.findViewById(R.id.btnSave).performClick();
        assertTrue("空服务器应拦截", !a.isFinishing());
        assertEquals("请填写 SMTP 服务器地址", ShadowToast.getTextOfLatestToast());

        host.setText("smtp.example.com");
        to.setText("not-an-email");
        a.findViewById(R.id.btnSave).performClick();
        assertTrue("非法收件人应拦截，toast=" + ShadowToast.getTextOfLatestToast(), !a.isFinishing());
        assertTrue("提示应为收件邮箱错误，实际：" + ShadowToast.getTextOfLatestToast(),
                ShadowToast.getTextOfLatestToast().contains("收件邮箱格式不正确"));
    }

    @Test
    public void ringView_draws_withoutCrash() {
        CountdownRingView v = new CountdownRingView(app);
        v.setState(0.5f, "12:34:56", "距截止", 0xFF4FD1C5);
        int size = (int) (220 * app.getResources().getDisplayMetrics().density);
        v.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, size, size);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(bmp));
        assertTrue(size > 0);
    }
}
