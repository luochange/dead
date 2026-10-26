package com.keepalive.deadman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.textfield.TextInputEditText;
import com.keepalive.deadman.core.Age;
import com.keepalive.deadman.core.Guard;
import com.keepalive.deadman.core.Template;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SmokeTest {

    private Context app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs p = Prefs.get(app);
        p.setCryptoForTest(new Crypto() {
            @Override
            String encrypt(String plain) { return "test:" + plain; }

            @Override
            String decrypt(String stored) {
                return stored != null && stored.startsWith("test:") ? stored.substring(5) : "";
            }
        });

        List<Prefs.Recipient> list = new ArrayList<>();
        list.add(new Prefs.Recipient("a@example.com"));
        list.add(new Prefs.Recipient("b@example.com",
                "只给 B 的主题 {name}", "只给 B 的正文 {to}"));

        p.edit()
                .host("smtp.example.com").port(465).security(0)
                .user("me@example.com").fromName("测试用户")
                .password("dummy-app-password")
                .recipients(list)
                .birth(0)
                .intervalHours(24).remindHours(3).graceMinutes(30)
                .subject("【测试】{name} 失联").body("我最后一次打卡是 {last_checkin}")
                .commit();
        p.setEnabled(false);
        p.setSentAt(0);
    }

    // ---------------------------------------------------------------- 首页

    @Test
    public void mainActivity_inflates_and_checkInWorks() {
        Prefs p = Prefs.get(app);
        p.checkIn(System.currentTimeMillis());

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull(a.findViewById(R.id.ring));
        assertNotNull(a.findViewById(R.id.btnCheckIn));
        assertNotNull(a.findViewById(R.id.historyContainer));
        assertEquals(View.VISIBLE, a.findViewById(R.id.btnCheckIn).getVisibility());

        assertEquals("守护中 · 一切正常",
                ((TextView) a.findViewById(R.id.tvStatus)).getText().toString());

        long before = p.lastCheckIn();
        a.findViewById(R.id.btnCheckIn).performClick();
        assertTrue("打卡后计时应重置", p.lastCheckIn() >= before);
        assertTrue("打卡应写入记录", p.history(5).size() > 0);
        a.finish();
    }

    @Test
    public void mainActivity_showsAge_cardHiddenWhenUnset() {
        Prefs p = Prefs.get(app);
        p.checkIn(System.currentTimeMillis());

        MainActivity without = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.VISIBLE, without.findViewById(R.id.tvAgeHint).getVisibility());
        assertEquals(View.GONE, without.findViewById(R.id.ageValues).getVisibility());
        without.finish();

        Calendar c = Calendar.getInstance();
        c.set(1992, Calendar.MARCH, 15, 8, 30, 0);
        c.set(Calendar.MILLISECOND, 0);
        p.edit().birth(c.getTimeInMillis()).commit();

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.VISIBLE, a.findViewById(R.id.ageValues).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.tvAgeHint).getVisibility());

        String days = ((TextView) a.findViewById(R.id.tvAgeDays)).getText().toString();
        assertTrue("应显示千分位天数，实际：" + days, days.contains(","));

        String ymd = ((TextView) a.findViewById(R.id.tvAgeYmd)).getText().toString();
        assertTrue("应显示年月日，实际：" + ymd, ymd.contains("年") && ymd.contains("天"));

        String clock = ((TextView) a.findViewById(R.id.tvAgeClock)).getText().toString();
        assertTrue("秒级时钟格式不对：" + clock, clock.matches("\\d{2}:\\d{2}:\\d{2}"));
        a.finish();
    }

    @Test
    public void mainActivity_survivesDisabledAndOverdueStates() {
        Prefs p = Prefs.get(app);
        p.setEnabled(false);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("守护未启用", ((TextView) a.findViewById(R.id.tvStatus)).getText().toString());

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
    @Config(sdk = 34, qualifiers = "night")
    public void mainActivity_worksInDarkTheme() {
        Prefs p = Prefs.get(app);
        p.checkIn(System.currentTimeMillis());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull(a.findViewById(R.id.ring));
        assertEquals("守护中 · 一切正常",
                ((TextView) a.findViewById(R.id.tvStatus)).getText().toString());
        a.finish();
    }

    // ---------------------------------------------------------------- 设置页

    @Test
    public void settingsActivity_inflates_andSaves() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        TextInputEditText host = a.findViewById(R.id.etHost);
        assertNotNull(host);
        assertEquals("smtp.example.com", host.getText().toString());

        // 两位收件人都渲染出来了
        assertEquals(2, ((android.widget.LinearLayout)
                a.findViewById(R.id.recipientContainer)).getChildCount());

        a.findViewById(R.id.btnSave).performClick();
        assertTrue("保存成功后应关闭页面", a.isFinishing());
    }

    @Test
    public void settingsActivity_rejectsBadInput() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextInputEditText host = a.findViewById(R.id.etHost);

        host.setText("");
        a.findViewById(R.id.btnSave).performClick();
        assertFalse(a.isFinishing());
        assertEquals("请填写 SMTP 服务器地址", ShadowToast.getTextOfLatestToast());

        host.setText("smtp.example.com");
        ((TextInputEditText) a.findViewById(R.id.etInterval)).setText("0");
        a.findViewById(R.id.btnSave).performClick();
        assertFalse("周期为 0 应拦截", a.isFinishing());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("打卡周期"));
    }

    // ---------------------------------------------------------------- 收件人 & 模板

    @Test
    public void recipients_persist_withCustomContent() {
        Prefs p = Prefs.get(app);
        List<Prefs.Recipient> list = p.recipientList();
        assertEquals(2, list.size());
        assertEquals("a@example.com", list.get(0).email);
        assertFalse("第一位用默认内容", list.get(0).hasCustom());

        assertEquals("只给 B 的主题 {name}", list.get(1).subject);
        assertTrue(list.get(1).hasCustom());

        Map<String, String> vars = Mail.vars(p, list.get(1));
        assertEquals("只给 B 的主题 测试用户", Template.render(list.get(1).subject, vars));
        assertEquals("只给 B 的正文 b@example.com", Template.render(list.get(1).body, vars));

        // 未定制的那位回落到默认模板
        Map<String, String> vars0 = Mail.vars(p, list.get(0));
        assertEquals("【测试】测试用户 失联", Template.render(p.subject(), vars0));
    }

    // ---------------------------------------------------------------- 绘制

    @Test
    public void ringView_draws_withoutCrash() {
        CountdownRingView v = new CountdownRingView(app);
        v.setState(0.5f, "12:34:56", "距截止", 0xFF4F8CFF);
        int size = (int) (236 * app.getResources().getDisplayMetrics().density);
        v.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, size, size);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(bmp));
        assertTrue(size > 0);
    }

    @Test
    public void starfieldView_draws_withoutCrash() {
        StarfieldView v = new StarfieldView(app);
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 1080, 1920);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1080, 1920,
                android.graphics.Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(bmp));
        assertTrue(true);
    }

    @Test
    public void ageMath_isConsistent() {
        Calendar b = Calendar.getInstance();
        b.set(1992, Calendar.MARCH, 15, 8, 30, 0);
        b.set(Calendar.MILLISECOND, 0);
        long birth = b.getTimeInMillis();
        long now = birth + 365L * 86400000L;
        assertTrue(Age.totalDays(birth, now) >= 364);
        assertTrue(Age.clock(birth, now).matches("\\d{2}:\\d{2}:\\d{2}"));
        int[] ymd = Age.ymd(birth, now);
        assertTrue(ymd[0] >= 0 && ymd[1] >= 0 && ymd[1] < 12 && ymd[2] >= 0);
    }
}
