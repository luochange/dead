package com.keepalive.deadman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/** 倒计时圆环：刻度 + 霓虹渐变弧 + 光晕。 */
public class CountdownRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float fraction = 0f;
    private String mainText = "--:--:--";
    private String subText = "未启用";
    private int accent = 0xFF4F8CFF;
    private SweepGradient gradient;

    public CountdownRingView(Context c) { super(c); init(); }
    public CountdownRingView(Context c, AttributeSet a) { super(c, a); init(); }
    public CountdownRingView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.ring_track));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(accent);
        glowPaint.setAlpha(38);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setColor(0xFF23305A);

        mainPaint.setColor(ContextCompat.getColor(getContext(), R.color.text));
        mainPaint.setTextAlign(Paint.Align.CENTER);
        mainPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        subPaint.setColor(ContextCompat.getColor(getContext(), R.color.muted));
        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setLetterSpacing(0.12f);
    }

    public void setState(float fraction, String mainText, String subText, int color) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        this.mainText = mainText == null ? "" : mainText;
        this.subText = subText == null ? "" : subText;
        if (color != 0) {
            accent = color;
            glowPaint.setColor(color);
            gradient = null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float stroke = dp(8);
        float r = Math.min(w, h) / 2f - dp(14);

        trackPaint.setStrokeWidth(dp(1.2f));
        canvas.drawCircle(cx, cy, r, trackPaint);

        drawTicks(canvas, cx, cy, r);

        if (gradient == null) {
            int light = blend(accent, Color.WHITE, 0.35f);
            int violet = ContextCompat.getColor(getContext(), R.color.secondary);
            gradient = new SweepGradient(cx, cy,
                    new int[]{accent, light, violet, accent},
                    new float[]{0f, 0.35f, 0.7f, 1f});
            Matrix m = new Matrix();
            m.postRotate(-90, cx, cy);
            gradient.setLocalMatrix(m);
        }

        if (fraction > 0.002f) {
            oval.set(cx - r, cy - r, cx + r, cy + r);

            glowPaint.setStrokeWidth(stroke + dp(10));
            glowPaint.setAlpha(26);
            canvas.drawArc(oval, -90f, 360f * fraction, false, glowPaint);

            glowPaint.setStrokeWidth(stroke + dp(5));
            glowPaint.setAlpha(46);
            canvas.drawArc(oval, -90f, 360f * fraction, false, glowPaint);

            arcPaint.setStrokeWidth(stroke);
            arcPaint.setShader(gradient);
            canvas.drawArc(oval, -90f, 360f * fraction, false, arcPaint);
        }

        mainPaint.setTextSize(sp(26));
        subPaint.setTextSize(sp(11));

        Paint.FontMetrics mf = mainPaint.getFontMetrics();
        Paint.FontMetrics sf = subPaint.getFontMetrics();
        float mh = mf.descent - mf.ascent;
        float sh = sf.descent - sf.ascent;
        float gap = dp(10);
        float total = mh + gap + sh;
        float top = cy - total / 2f;

        canvas.drawText(mainText, cx, top - mf.ascent, mainPaint);
        canvas.drawText(subText, cx, top + mh + gap - sf.ascent, subPaint);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float r) {
        tickPaint.setStrokeWidth(dp(1));
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6 - 90);
            boolean major = i % 5 == 0;
            if (!major && fraction < 0.02f) continue;
            float len = major ? dp(6) : dp(3);
            float x1 = cx + (float) (Math.cos(a) * (r + dp(7)));
            float y1 = cy + (float) (Math.sin(a) * (r + dp(7)));
            float x2 = cx + (float) (Math.cos(a) * (r + dp(7) + len));
            float y2 = cy + (float) (Math.sin(a) * (r + dp(7) + len));
            tickPaint.setAlpha(major ? 110 : 55);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
