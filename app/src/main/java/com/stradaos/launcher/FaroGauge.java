package com.stradaos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Velocímetro del cockpit (Faro y NightPulse).
 * Soporta tres modos (0 digital, 1 analógico, 2 mixto) más tipografía, paleta y detalles propios de cada tema.
 */
public class FaroGauge extends View {

    private static final float START = 135f, SWEEP = 270f;
    private static final float TAU = 0.42f;

    private final float k;
    private int mode = 2;
    private boolean light, mph, ok;
    private float max = 200, target, val;
    private long last;
    private int limit;
    private int text = 0xFFF2F3F5, muted = 0xFF8C919A, track = 0xFF2A2D33;
    private String themeId = Faro.ID;

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pProg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pUnit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix rot = new Matrix();

    public FaroGauge(Context c, float scale) {
        super(c);
        k = scale;
        for (Paint p : new Paint[]{pTrack, pProg, pGlow}) { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        pBig.setTextAlign(Paint.Align.CENTER);
        pBig.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        pUnit.setTextAlign(Paint.Align.CENTER);
        pNum.setTextAlign(Paint.Align.CENTER);
    }

    /** Colores del tema (claro u oscuro) y estilo de diseño del cockpit activo. */
    public void setTheme(boolean isLight, int textColor, int mutedColor) {
        setCockpitTheme(Faro.ID, isLight, textColor, mutedColor);
    }

    public void setCockpitTheme(String id, boolean isLight, int textColor, int mutedColor) {
        themeId = id != null ? id : Faro.ID;
        light = isLight;
        text = textColor;
        muted = mutedColor;
        track = CockpitThemes.track(themeId, isLight);
        if (CockpitThemes.isCustom(themeId)) {
            Typeface tf = CockpitThemes.displayFont(getContext(), themeId);
            pBig.setTypeface(tf != null ? tf : Typeface.create("sans-serif-light", Typeface.NORMAL));
        } else {
            pBig.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        invalidate();
    }

    /** 0 digital, 1 analógico, 2 mixto (el valor de la preferencia «speedo»). */
    public void setMode(int m) { mode = m; invalidate(); }

    public void setUnit(boolean isMph) { mph = isMph; max = isMph ? 120 : 200; invalidate(); }

    /** Límite de la vía en la unidad elegida (0 = desconocido: no se muestra). */
    public void setLimit(int l) { if (l != limit) { limit = l; invalidate(); } }

    /** Velocidad ya en la unidad elegida. Sin GPS el avance vuelve a cero y el número muestra «--». */
    public void setSpeed(float v, boolean hasFix) {
        ok = hasFix;
        float t = hasFix ? Math.max(0, Math.min(max * 1.05f, v)) : 0;
        if (t == target) { invalidate(); return; }
        target = t;
        if (last == 0) last = SystemClock.uptimeMillis() - 16;
        postInvalidateOnAnimation();
    }

    private int progColor() {
        if (CockpitThemes.NIGHTPULSE.equals(themeId)) return light ? 0xFF0062E6 : 0xFF7DD3FC;
        return light ? text : Faro.COPPER_LIGHT;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (last != 0) {
            float dt = Math.min(0.1f, Math.max(0f, (now - last) / 1000f));
            val += (target - val) * (1f - (float) Math.exp(-dt / TAU));
            if (Math.abs(target - val) < 0.04f) { val = target; last = 0; }
            else { last = now; postInvalidateOnAnimation(); }
        }

        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, d = Math.min(w, h);
        if (d <= 0) return;

        boolean nightPulse = CockpitThemes.NIGHTPULSE.equals(themeId);

        // Fondo sutil detrás del velocímetro según tema
        if (nightPulse && !light) {
            pBg.setShader(new RadialGradient(cx, cy, d / 2f, new int[]{0x381E90FF, 0x120A4DA2, 0x00000000},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, d / 2f, pBg);
        } else if (light) {
            pBg.setShader(new RadialGradient(cx, cy, d / 2f, new int[]{0xD9FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, d / 2f, pBg);
        }

        float frac = Math.max(0, Math.min(1, val / max));
        String num = ok ? String.valueOf(Math.round(val < 1.5f && target == 0 ? 0 : val)) : "--";
        String unit = mph ? "mph" : "km/h";
        if (mode == 0) { drawDigital(c, w, h, cx, cy, d, frac, num, unit); return; }

        float r = d / 2f * 0.80f;
        rect.set(cx - r, cy - r, cx + r, cy + r);

        // pista fina
        pTrack.setColor(track);
        pTrack.setStrokeWidth(Math.max(1.5f, r * 0.028f));
        c.drawArc(rect, START, SWEEP, false, pTrack);

        // NightPulse: marcas deportivas en el aro incluso en modo mixto
        if (nightPulse && mode == 2) {
            pDot.setStyle(Paint.Style.FILL);
            for (int v = 0; v <= max; v += 20) {
                double a = Math.toRadians(START + SWEEP * v / max);
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                boolean active = ok && val >= v;
                pDot.setColor(active ? (light ? 0xFF0062E6 : 0xFF7DD3FC) : Aurora.a(muted, 0.35f));
                c.drawCircle(cx + cs * r * 0.88f, cy + sn * r * 0.88f, r * 0.016f, pDot);
            }
        }

        float sweep = SWEEP * frac;
        if (sweep > 0.5f) {
            if (nightPulse) {
                if (!light) {
                    pGlow.setShader(null);
                    pGlow.setColor(Aurora.a(0xFF1E90FF, 0.22f));
                    pGlow.setStrokeWidth(r * 0.16f);
                    c.drawArc(rect, START, sweep, false, pGlow);
                }
                float f = Math.max(0.02f, sweep / 360f);
                int c0 = light ? 0xFF004BB5 : 0xFF0052CC;
                int c1 = light ? 0xFF0062E6 : 0xFF1E90FF;
                int c2 = light ? 0xFF38BDF8 : 0xFF7DD3FC;
                SweepGradient sg = new SweepGradient(cx, cy, new int[]{c0, c1, c2, c2}, new float[]{0f, f * 0.5f, f, 1f});
                rot.setRotate(START, cx, cy);
                sg.setLocalMatrix(rot);
                pProg.setShader(sg);
                pProg.setColor(0xFF000000);
            } else if (!light) {
                // Faro original
                pGlow.setShader(null);
                pGlow.setColor(Aurora.a(Faro.COPPER, 0.16f));
                pGlow.setStrokeWidth(r * 0.17f);
                c.drawArc(rect, START, sweep, false, pGlow);
                float f = Math.max(0.02f, sweep / 360f);
                SweepGradient sg = new SweepGradient(cx, cy, new int[]{Faro.COPPER_DEEP, Faro.COPPER, Faro.COPPER_LIGHT, Faro.COPPER_LIGHT},
                    new float[]{0f, f * 0.5f, f, 1f});
                rot.setRotate(START, cx, cy);
                sg.setLocalMatrix(rot);
                pProg.setShader(sg);
                pProg.setColor(0xFF000000);
            } else {
                pProg.setShader(null);
                pProg.setColor(text);
            }
            pProg.setStrokeWidth(r * 0.065f);
            c.drawArc(rect, START, sweep, false, pProg);
            pProg.setShader(null);
        }

        if (mode == 1) {
            pDot.setStyle(Paint.Style.FILL);
            pNum.setColor(muted);
            pNum.setTextSize(r * 0.13f);
            for (int v = 0; v <= max; v += 10) {
                double a = Math.toRadians(START + SWEEP * v / max);
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                boolean major = v % 20 == 0;
                pDot.setColor(major ? Aurora.a(text, 0.55f) : Aurora.a(text, 0.22f));
                c.drawCircle(cx + cs * r * 0.86f, cy + sn * r * 0.86f, r * (major ? 0.022f : 0.013f), pDot);
                if (v % 40 == 0) {
                    float rl = r * 0.68f;
                    c.drawText(String.valueOf(v), cx + cs * rl, cy + sn * rl - (pNum.ascent() + pNum.descent()) / 2f, pNum);
                }
            }
            double na = Math.toRadians(START + sweep);
            float ncs = (float) Math.cos(na), nsn = (float) Math.sin(na);
            pGlow.setShader(null);
            pGlow.setStrokeWidth(Math.max(2f, r * 0.03f));
            pGlow.setColor(progColor());
            c.drawLine(cx + ncs * r * 0.10f, cy + nsn * r * 0.10f, cx + ncs * r * 0.80f, cy + nsn * r * 0.80f, pGlow);
            pDot.setColor(progColor());
            c.drawCircle(cx, cy, r * 0.06f, pDot);
            pUnit.setColor(muted);
            pUnit.setTextSize(r * 0.13f);
            c.drawText(ok ? num + " " + unit : unit, cx, cy + r * 0.50f, pUnit);
        } else {
            pBig.setColor(text);
            pBig.setTextSize(r * 0.68f);
            c.drawText(num, cx, cy + r * 0.18f, pBig);
            pUnit.setColor(muted);
            pUnit.setTextSize(r * 0.14f);
            c.drawText(unit, cx, cy + r * 0.45f, pUnit);
        }
        drawLimit(c, cx + r * 0.98f, cy - r * 0.86f, r * 0.20f);
    }

    private void drawDigital(Canvas c, float w, float h, float cx, float cy, float d, float frac, String num, String unit) {
        pBig.setColor(text);
        pBig.setTextSize(d * 0.40f);
        float base = cy + d * 0.10f;
        c.drawText(num, cx, base, pBig);
        pUnit.setColor(muted);
        pUnit.setTextSize(d * 0.08f);
        c.drawText(unit, cx, base + d * 0.12f, pUnit);
        float lw = d * 0.56f, y = base + d * 0.21f, x0 = cx - lw / 2, x1 = cx + lw / 2;
        pTrack.setColor(track);
        pTrack.setStrokeWidth(Math.max(1.5f, d * 0.014f));
        c.drawLine(x0, y, x1, y, pTrack);
        if (frac > 0.005f) {
            pProg.setShader(null);
            pProg.setColor(progColor());
            pProg.setStrokeWidth(Math.max(2f, d * 0.024f));
            c.drawLine(x0, y, x0 + lw * frac, y, pProg);
        }
        drawLimit(c, cx + d * 0.36f, cy - d * 0.30f, d * 0.08f);
    }

    /** Circulito del límite (solo con la navegación integrada y la vía con límite conocido). */
    private void drawLimit(Canvas c, float x, float y, float rr) {
        if (limit <= 0) return;
        boolean over = ok && val > limit + 2;
        pDot.setStyle(Paint.Style.FILL);
        pDot.setColor(light ? 0xFFFFFFFF : 0xFF17191D);
        c.drawCircle(x, y, rr, pDot);
        pDot.setStyle(Paint.Style.STROKE);
        pDot.setStrokeWidth(Math.max(1.5f, rr * 0.16f));
        pDot.setColor(over ? 0xFFFF453A : light ? 0xFFC9CDD3 : 0xFF3A3E45);
        c.drawCircle(x, y, rr, pDot);
        pDot.setStyle(Paint.Style.FILL);
        pNum.setColor(text);
        pNum.setTextSize(rr * (limit >= 100 ? 0.78f : 0.92f));
        pNum.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(String.valueOf(limit), x, y - (pNum.ascent() + pNum.descent()) / 2f, pNum);
        pNum.setTypeface(Typeface.DEFAULT);
    }
}
