package com.keepalive.deadman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** 倒计时圆环：剩余时间 + 进度弧。 */
public class CountdownRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float fraction = 0f;
    private String mainText = "--:--:--";
    private String subText = "未启用";
    private int accent = 0xFF4FD1C5;

    public CountdownRingView(Context context) {
        super(context);
        init();
    }

    public CountdownRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CountdownRingView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0xFF22304C);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(accent);

        mainPaint.setColor(0xFFE8EEF9);
        mainPaint.setTextAlign(Paint.Align.CENTER);
        mainPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        subPaint.setColor(0xFF8FA0BD);
        subPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setState(float fraction, String mainText, String subText, int color) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        this.mainText = mainText == null ? "" : mainText;
        this.subText = subText == null ? "" : subText;
        if (color != 0) {
            accent = color;
            arcPaint.setColor(color);
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
        float stroke = dp(9);
        float r = Math.min(w, h) / 2f - stroke - dp(2);

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        canvas.drawCircle(cx, cy, r, trackPaint);

        if (fraction > 0.002f) {
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, -90f, 360f * fraction, false, arcPaint);
        }

        mainPaint.setTextSize(sp(25));
        subPaint.setTextSize(sp(12));

        Paint.FontMetrics mf = mainPaint.getFontMetrics();
        Paint.FontMetrics sf = subPaint.getFontMetrics();
        float mh = mf.descent - mf.ascent;
        float sh = sf.descent - sf.ascent;
        float gap = dp(9);
        float total = mh + gap + sh;
        float top = cy - total / 2f;

        canvas.drawText(mainText, cx, top - mf.ascent, mainPaint);
        canvas.drawText(subText, cx, top + mh + gap - sf.ascent, subPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
