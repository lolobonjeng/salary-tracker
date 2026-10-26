package com.cronzph.salarytracker.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * SweetAlert2-inspired icon: isang bilog na may kulay na may puti glyph
 * (check / X / ! / i) sa gitna, na may pop-in animation kapag ipinakita.
 *
 * <p>Pinapalitan nito ang dati na static {@code ImageView} icon sa
 * {@code dialog_app.xml} kapag sweet type ang ginamit. Pure Canvas draw —
 * walang dagdag na dependency o Lottie.
 */
public class SweetIconView extends View {

    public static final int TYPE_SUCCESS = 0;
    public static final int TYPE_ERROR   = 1;
    public static final int TYPE_WARNING = 2;
    public static final int TYPE_INFO    = 3;

    @IntDef({TYPE_SUCCESS, TYPE_ERROR, TYPE_WARNING, TYPE_INFO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    private static final float RING_INSET = 0.78f;        // white outer ring thickness ratio
    private static final float GLYPH_STROKE = 0.095f;     // stroke width relative to circle dia
    private static final float GLYPH_CHECK_LEN = 0.42f;   // check mark side length ratio
    private static final float POP_DURATION = 620f;

    private @Type int type = TYPE_SUCCESS;
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final RectF circleRect = new RectF();

    private float animScale = 0f;          // driven by ObjectAnimator
    private boolean glyphDrawn = false;

    public SweetIconView(@NonNull Context context) {
        super(context);
        init();
    }

    public SweetIconView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SweetIconView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.WHITE);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        glyphPaint.setColor(Color.WHITE);
        setType(TYPE_SUCCESS);
    }

    public void setType(@Type int type) {
        this.type = type;
        int color;
        switch (type) {
            case TYPE_SUCCESS: color = 0xFF1FAF5A; break;
            case TYPE_ERROR:   color = 0xFFE5484D; break;
            case TYPE_WARNING: color = 0xFFF0A93B; break;
            case TYPE_INFO:    color = 0xFF3A8BD8; break;
            default:           color = 0xFF1FAF5A; break;
        }
        circlePaint.setColor(color);
        invalidate();
    }

    public @Type int getType() {
        return type;
    }

    /** Paandarin ang pop-in animation. Dapat tawagin kapag ipapakita na. */
    public void animateIn() {
        animScale = 0f;
        glyphDrawn = false;
        ObjectAnimator scale = ObjectAnimator.ofFloat(this, "animScale", 0f, 1f);
        scale.setInterpolator(new OvershootInterpolator(1.9f));
        scale.setDuration((long) POP_DURATION);
        scale.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { glyphDrawn = true; invalidate(); }
        });
        scale.addUpdateListener(animation -> invalidate());
        scale.start();
    }

    /** Property para sa ObjectAnimator. */
    public void setAnimScale(float v) {
        this.animScale = v;
        invalidate();
    }

    public float getAnimScale() {
        return animScale;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = dp(92);  // default na laki ng icon
        setMeasuredDimension(resolveSize(size, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float baseRadius = Math.min(w, h) / 2f - dp(2);
        if (baseRadius <= 0) return;

        // Bilog na may overshoot scale.
        float s = animScale;
        float r = baseRadius * s;
        circleRect.set(cx - r, cy - r, cx + r, cy + r);

        // Outer white ring (very thin) — para may konting "lift" gaya ng SweetAlert2.
        ringPaint.setStrokeWidth(baseRadius * (1f - RING_INSET));
        canvas.drawCircle(cx, cy, r, ringPaint);

        // Colored fill circle.
        canvas.drawCircle(cx, cy, r, circlePaint);

        // Glyph — ilalabas lang kapag tapos na ang pop-in (para hindi sumasabay).
        if (!glyphDrawn) return;

        float glyphStroke = baseRadius * 2f * GLYPH_STROKE;
        glyphPaint.setStrokeWidth(glyphStroke);
        float g = baseRadius * GLYPH_CHECK_LEN;

        switch (type) {
            case TYPE_SUCCESS: drawCheck(canvas, cx, cy, g); break;
            case TYPE_ERROR:   drawCross(canvas, cx, cy, g); break;
            case TYPE_WARNING: drawBang(canvas, cx, cy, g); break;
            case TYPE_INFO:    drawInfo(canvas, cx, cy, g); break;
            default: break;
        }
    }

    private void drawCheck(Canvas c, float cx, float cy, float g) {
        // V-shape: taas-baba-kaliwa, pataas-kanan.
        checkPath.reset();
        checkPath.moveTo(cx - g * 0.55f, cy + g * 0.08f);
        checkPath.lineTo(cx - g * 0.08f, cy + g * 0.55f);
        checkPath.lineTo(cx + g * 0.62f, cy - g * 0.5f);
        c.drawPath(checkPath, glyphPaint);
    }

    private void drawCross(Canvas c, float cx, float cy, float g) {
        float h = g * 0.55f;
        c.drawLine(cx - h, cy - h, cx + h, cy + h, glyphPaint);
        c.drawLine(cx - h, cy + h, cx + h, cy - h, glyphPaint);
    }

    private void drawBang(Canvas c, float cx, float cy, float g) {
        // Exclamation: patayo na linya sa taas + maliit na dot sa baba.
        float top = cy - g * 0.65f;
        float bottom = cy + g * 0.18f;
        c.drawLine(cx, top, cx, bottom, glyphPaint);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        c.drawCircle(cx, cy + g * 0.55f, g * 0.16f, fill);
    }

    private void drawInfo(Canvas c, float cx, float cy, float g) {
        // "i": maliit na dot sa taas + patayo na linya sa baba.
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        c.drawCircle(cx, cy - g * 0.5f, g * 0.16f, fill);
        c.drawLine(cx, cy - g * 0.18f, cx, cy + g * 0.6f, glyphPaint);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
