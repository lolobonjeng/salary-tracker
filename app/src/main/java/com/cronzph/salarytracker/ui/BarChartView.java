package com.cronzph.salarytracker.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.cronzph.salarytracker.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Simpleng stacked bar chart — walang external library, kaya magaan sa device.
 * Kada bar: base pay (baba) + OT (taas), pill-shaped.
 */
public class BarChartView extends View {

    public static class Bar {
        public final String label;
        public final double base;
        public final double ot;
        public final boolean today;

        public Bar(String label, double base, double ot, boolean today) {
            this.label = label;
            this.base = base;
            this.ot = ot;
            this.today = today;
        }
    }

    private final List<Bar> bars = new ArrayList<>();

    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabelToday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float density;

    public BarChartView(Context context) {
        this(context, null);
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;

        pBase.setColor(ContextCompat.getColor(getContext(), R.color.chartBase));
        pOt.setColor(ContextCompat.getColor(getContext(), R.color.chartOt));
        pStub.setColor(ContextCompat.getColor(getContext(), R.color.chartTrack));
        pAxis.setColor(ContextCompat.getColor(getContext(), R.color.chartTrack));
        pAxis.setStrokeWidth(Math.max(1f, density));
        pAxis.setStyle(Paint.Style.STROKE);

        int track = ContextCompat.getColor(getContext(), R.color.chartTrack);
        pToday.setColor((track & 0x00FFFFFF) | 0x5A000000);

        pLabel.setColor(ContextCompat.getColor(getContext(), R.color.neutral));
        pLabel.setTextSize(9f * sp);
        pLabel.setTextAlign(Paint.Align.CENTER);

        pLabelToday.setColor(ContextCompat.getColor(getContext(), R.color.chartBase));
        pLabelToday.setTextSize(9f * sp);
        pLabelToday.setTextAlign(Paint.Align.CENTER);
        pLabelToday.setFakeBoldText(true);
    }

    public void setData(List<Bar> data) {
        bars.clear();
        if (data != null) bars.addAll(data);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int n = bars.size();
        if (n == 0) return;

        float w = getWidth();
        float h = getHeight();
        float labelSpace = 15f * density;
        float topPad = 8f * density;
        float baseline = h - labelSpace;
        float area = baseline - topPad;
        if (w <= 0 || area <= 0) return;

        double max = 0;
        for (Bar b : bars) {
            max = Math.max(max, b.base + b.ot);
        }
        if (max <= 0) max = 1;

        float slot = w / n;
        float barW = Math.min(slot * 0.62f, 20f * density);
        float radius = barW / 2f;
        float gap = 2f * density;

        canvas.drawLine(0f, baseline, w, baseline, pAxis);

        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            float cx = slot * i + slot / 2f;
            float left = cx - barW / 2f;

            if (b.today) {
                rect.set(cx - slot / 2f + 2f * density, topPad - 4f * density,
                        cx + slot / 2f - 2f * density, baseline);
                canvas.drawRoundRect(rect, 6f * density, 6f * density, pToday);
            }

            double total = b.base + b.ot;
            if (total <= 0) {
                rect.set(left, baseline - 3f * density, left + barW, baseline);
                canvas.drawRoundRect(rect, radius, radius, pStub);
            } else {
                float totalH = (float) (total / max * area);
                float baseH = (float) (b.base / max * area);
                float otH = totalH - baseH;
                float baseTop = baseline - baseH;

                if (baseH > 0f) {
                    rect.set(left, baseTop, left + barW, baseline);
                    canvas.drawRoundRect(rect, radius, radius, pBase);
                }
                if (otH > 0f) {
                    float otBottom = baseH > 0f ? baseTop - gap : baseline;
                    float otTop = otBottom - otH;
                    rect.set(left, otTop, left + barW, otBottom);
                    canvas.drawRoundRect(rect, radius, radius, pOt);
                }
            }

            canvas.drawText(b.label, cx, h - 3f * density, b.today ? pLabelToday : pLabel);
        }
    }
}
