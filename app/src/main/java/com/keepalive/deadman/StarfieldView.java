package com.keepalive.deadman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Random;

/** Cosmic 背景：缓慢漂移 + 呼吸闪烁的星点。只在可见时刷新，避免耗电。 */
public class StarfieldView extends View {

    private static final int COUNT = 64;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] xs = new float[COUNT];
    private final float[] ys = new float[COUNT];
    private final float[] rs = new float[COUNT];
    private final float[] phase = new float[COUNT];
    private final float[] speed = new float[COUNT];
    private final int[] colors = new int[COUNT];
    private final int[] maxAlpha = new int[COUNT];

    private boolean ready = false;
    private long startAt = 0;
    private long lastFrame = 0;

    public StarfieldView(Context c) { super(c); init(); }
    public StarfieldView(Context c, AttributeSet a) { super(c, a); init(); }
    public StarfieldView(Context c, AttributeSet a, int def) { super(c, a, def); init(); }

    private void init() {
        setWillNotDraw(false);
        startAt = SystemClock.uptimeMillis();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        Random rnd = new Random(20261026L);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < COUNT; i++) {
            xs[i] = rnd.nextFloat() * w;
            ys[i] = rnd.nextFloat() * h;
            rs[i] = (0.5f + rnd.nextFloat() * 1.3f) * d;
            phase[i] = rnd.nextFloat() * 6.28f;
            speed[i] = (0.6f + rnd.nextFloat() * 1.6f) * d; // px / 秒
            int pick = rnd.nextInt(10);
            int c;
            if (pick < 6) c = ContextCompat.getColor(getContext(), R.color.star_1);
            else if (pick < 9) c = ContextCompat.getColor(getContext(), R.color.star_2);
            else c = ContextCompat.getColor(getContext(), R.color.star_3);
            colors[i] = c;
            maxAlpha[i] = Color.alpha(c);
        }
        ready = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;

        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;

        float h = getHeight();
        for (int i = 0; i < COUNT; i++) {
            ys[i] += speed[i] * dt;
            if (ys[i] > h + 4) ys[i] = -4;

            float twinkle = 0.5f + 0.5f * (float) Math.sin((now - startAt) / 1400.0 + phase[i]);
            paint.setColor(colors[i]);
            paint.setAlpha((int) (maxAlpha[i] * (0.30f + 0.70f * twinkle)));
            canvas.drawCircle(xs[i], ys[i], rs[i], paint);
        }

        if (isShown()) {
            // ~30fps，足够顺滑又省电
            postInvalidateDelayed(33);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastFrame = 0;
    }
}
