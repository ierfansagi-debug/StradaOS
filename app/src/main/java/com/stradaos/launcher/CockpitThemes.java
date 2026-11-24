package com.stradaos.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import java.util.HashMap;

/**
 * Paletas, fondos, tarjetas, tipografía, iluminación del Nissan Almera N17 Impul y miniaturas para NightPulse.
 */
final class CockpitThemes {
    private CockpitThemes() { }

    static final String NIGHTPULSE = "nightpulse";

    static boolean isCustom(String id) {
        return NIGHTPULSE.equals(id);
    }

    static boolean isCockpit(String id) {
        return Faro.ID.equals(id) || isCustom(id);
    }

    static String carForTheme(String id) {
        if (NIGHTPULSE.equals(id)) return Faro.CAR_ALMERA_N17_REAR;
        return Faro.CAR_ALMERA_N17;
    }

    // ── Tipografía en caché ──
    private static final HashMap<String, Typeface> fontCache = new HashMap<String, Typeface>();

    static Typeface displayFont(Context c, String id) {
        String key = "d:" + id;
        Typeface tf = fontCache.get(key);
        if (tf != null) return tf;
        int res = NIGHTPULSE.equals(id) ? R.font.rajdhani : 0;
        if (res != 0 && c != null) {
            try { tf = ResourcesCompat.getFont(c, res); } catch (Throwable ignored) { }
            if (tf == null && Build.VERSION.SDK_INT >= 26) {
                try { tf = c.getResources().getFont(res); } catch (Throwable ignored) { }
            }
        }
        if (tf == null) {
            tf = NIGHTPULSE.equals(id) ? Typeface.create("sans-serif-condensed", Typeface.BOLD) : Typeface.DEFAULT_BOLD;
        }
        fontCache.put(key, tf);
        return tf;
    }

    static Typeface bodyFont(Context c, String id) {
        return Typeface.DEFAULT;
    }

    // ── Colores (Oscuro / Claro) ──

    static int base(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFFE6ECF5 : 0xFF07090F;
        return Faro.base(l);
    }

    static int glass(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xDBFFFFFF : 0xC40B1019;
        return Faro.glass(l);
    }

    static int glassEdge(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0x380062E6 : 0x551E90FF;
        return Faro.glassEdge(l);
    }

    static int pill(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xE6FFFFFF : 0xEB0A0F18;
        return Faro.pill(l);
    }

    static int pillEdge(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0x330062E6 : 0x5C1E90FF;
        return Faro.pillEdge(l);
    }

    static int pillIcon(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFF0F172A : 0xFFEAF4FF;
        return Faro.pillIcon(l);
    }

    static int tile(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFFFFFFFF : 0xFF131B2A;
        return Faro.tile(l);
    }

    static int track(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFFD2DCEB : 0xFF1B273D;
        return Faro.track(l);
    }

    static int soft(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0x140062E6 : 0x241E90FF;
        return Faro.soft(l);
    }

    static int text(String id, boolean l, int fallback) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFF0E1624 : 0xFFF2F7FF;
        return fallback;
    }

    static int muted(String id, boolean l, int fallback) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFF5A6B84 : 0xFF8AA4C8;
        return fallback;
    }

    static int accent(String id, boolean l, int userAccent) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFF0062E6 : 0xFF1E90FF;
        return userAccent;
    }

    static int secondary(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 0xFF38BDF8 : 0xFF7DD3FC;
        return Faro.COPPER;
    }

    static int navHighlight(String id, boolean l, int userAccent) {
        return accent(id, l, userAccent);
    }

    static float mapAlpha(String id, boolean l) {
        float baseAlpha = Faro.mapAlpha(l);
        if (NIGHTPULSE.equals(id)) return Math.min(1f, baseAlpha + (l ? 0.08f : 0.14f));
        return baseAlpha;
    }

    static ColorMatrixColorFilter mapTone(String id, boolean l) {
        if (!isCustom(id)) return Faro.mapTone(l);
        float sat = l ? 0.36f : 0.28f;
        float con = l ? 1.12f : 1.24f;
        float pivot = l ? 235f : 48f;
        float[] tint = l ? new float[]{0.96f, 0.99f, 1.05f} : new float[]{0.86f, 0.96f, 1.15f};
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        float off = pivot * (1 - con);
        m.postConcat(new ColorMatrix(new float[]{con, 0, 0, 0, off, 0, con, 0, 0, off, 0, 0, con, 0, off, 0, 0, 0, 1, 0}));
        ColorMatrix t = new ColorMatrix();
        t.setScale(tint[0], tint[1], tint[2], 1f);
        m.postConcat(t);
        return new ColorMatrixColorFilter(m);
    }

    static int beamColor(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return 0xFFFFF4DC;
        return Faro.WARM;
    }

    static int poolColor(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return 0xFF1E90FF;
        return Faro.LAVENDER;
    }

    static int tailColor(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return 0xFFFF2448;
        return 0xFFFF2A2A;
    }

    static int grainAmp(String id, boolean l) {
        if (NIGHTPULSE.equals(id)) return l ? 4 : 5;
        return Faro.grain(l);
    }

    // ── Fondo del tema ──

    static void paint(String id, Canvas c, float w, float h, boolean light, int userAccent) {
        if (NIGHTPULSE.equals(id)) {
            paintNightPulse(c, w, h, light);
        } else {
            Faro.paint(c, w, h, light, userAccent);
        }
    }

    private static void paintNightPulse(Canvas c, float w, float h, boolean light) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        boolean tall = h > w * 1.05f;
        p.setShader(new LinearGradient(0, 0, w, h,
            light ? new int[]{0xFFF0F4FA, 0xFFE6ECF5, 0xFFD9E2F0} : new int[]{0xFF0C1019, 0xFF07090F, 0xFF04060A},
            new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        int blue = accent(NIGHTPULSE, light, 0);
        int ice = secondary(NIGHTPULSE, light);
        // Resplandor detrás del cuadro de instrumentos izquierdo
        float gx = tall ? w * 0.24f : w * 0.14f, gy = tall ? h * 0.56f : h * 0.24f;
        float gr = Math.min(w, h) * 0.44f;
        p.setShader(new RadialGradient(gx, gy, gr,
            new int[]{Aurora.a(blue, light ? 0.16f : 0.24f), Aurora.a(ice, light ? 0.06f : 0.07f), 0},
            new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Resplandor sutil detrás del mapa grande
        p.setShader(new RadialGradient(w * 0.54f, h * 0.44f, Math.max(w, h) * 0.48f,
            new int[]{Aurora.a(blue, light ? 0.08f : 0.14f), 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Haz metálico diagonal fino estilo vidrio deportivo alemán
        float diag = (float) Math.hypot(w, h), nx = h / diag, ny = w / diag;
        float cx = w * 0.52f, cy = h * 0.48f, bw = diag * 0.16f;
        int core = light ? 0xFFFFFFFF : 0xFF8FC4FF;
        float a = light ? 0.55f : 0.11f;
        p.setShader(new LinearGradient(cx - nx * bw, cy - ny * bw, cx + nx * bw, cy + ny * bw,
            new int[]{0, Aurora.a(core, a), 0}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    static Drawable cardDrawable(String id, boolean light, float radius, float margin, float scale) {
        return new CardBg(id, light, radius, margin, scale);
    }

    private static final class CardBg extends Drawable {
        private final String id;
        private final boolean light;
        private final float r, m, k;
        private final RectF b = new RectF();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);

        CardBg(String id, boolean light, float r, float m, float k) {
            this.id = id;
            this.light = light;
            this.r = r;
            this.m = m;
            this.k = k;
        }

        @Override protected void onBoundsChange(Rect bd) {
            float l = bd.left + m, t = bd.top + m, rt = bd.right - m, bt = bd.bottom - m;
            if (NIGHTPULSE.equals(id) && !light) {
                edgeP.setShader(new LinearGradient(l, t, rt, bt,
                    new int[]{0x8838BDF8, 0x4D1E90FF, 0x1F1E90FF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            } else {
                edgeP.setShader(null);
            }
        }

        @Override public void draw(Canvas c) {
            Rect bd = getBounds();
            b.set(bd.left + m, bd.top + m, bd.right - m, bd.bottom - m);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);

            if (light || NIGHTPULSE.equals(id)) {
                int shadowCol = NIGHTPULSE.equals(id) && !light ? 0xFF0A3A75 : 0xFF141B28;
                float baseAlpha = NIGHTPULSE.equals(id) && !light ? 0.035f : 0.018f;
                for (int i = 4; i >= 0; i--) {
                    float g = (i + 1) * 1.1f * k;
                    p.setColor(Aurora.a(shadowCol, baseAlpha + (4 - i) * 0.004f));
                    b.set(bd.left + m - g, bd.top + m - g * 0.4f + (light ? 2.2f * k : 0),
                        bd.right - m + g, bd.bottom - m + g + (light ? 2.2f * k : 0));
                    c.drawRoundRect(b, r + g, r + g, p);
                }
                b.set(bd.left + m, bd.top + m, bd.right - m, bd.bottom - m);
            }

            // Relleno de la tarjeta
            p.setColor(glass(id, light));
            c.drawRoundRect(b, r, r, p);

            // NightPulse: sutil línea de luz superior estilo cristal negro deportivo
            if (NIGHTPULSE.equals(id) && !light) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, 0.8f * k));
                p.setColor(0x28FFFFFF);
                c.drawLine(b.left + r, b.top + k, b.right - r, b.top + k, p);
            }

            // Borde principal
            edgeP.setStyle(Paint.Style.STROKE);
            edgeP.setStrokeWidth(Math.max(1f, k));
            if (edgeP.getShader() == null) edgeP.setColor(glassEdge(id, light));
            c.drawRoundRect(b, r, r, edgeP);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Iluminación de ángulo 45° en modo oscuro: faros LED cálidos y pilotos traseros en NightPulse. */
    static final class AngleLights extends View {
        private final String carId;
        private final String themeId;
        private final float k;
        private Bitmap frame;

        AngleLights(Context c, String themeId, String carId, float scale) {
            super(c);
            this.themeId = themeId;
            this.carId = carId;
            this.k = scale;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            frame = null;
            if (w <= 0 || h <= 0) return;
            try {
                frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(frame);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                RectF r = Faro.carRect(w, h, carId);

                if (Faro.CAR_ALMERA_N17_REAR.equals(carId)) {
                    int beam = beamColor(themeId, false);
                    // Conos nítidos de faros LED blanco cálido proyectándose hacia adelante desde la parte frontal del N17
                    float[] fx = {r.left + r.width() * 0.155f, r.left + r.width() * 0.845f};
                    float fy0 = r.top + r.height() * 0.135f;
                    float fTopY = Math.max(4f * k, r.top - r.height() * 0.27f);
                    float fCoreY = Math.max(4f * k, r.top - r.height() * 0.21f);
                    float blurOuter = Math.max(2f, 8.5f * k);
                    float blurInner = Math.max(1.2f, 2.8f * k);
                    for (int i = 0; i < 2; i++) {
                        boolean left = i == 0;
                        float lx = fx[i];
                        float outSpread = Math.min(r.width() * 0.24f, (left ? lx : w - lx) - blurOuter * 1.4f);
                        outSpread = Math.max(r.width() * 0.08f, outSpread);
                        float inSpread = r.width() * 0.08f;
                        Path outerCone = new Path();
                        outerCone.moveTo(lx - 3f * k, fy0);
                        outerCone.lineTo(left ? lx - outSpread : lx - inSpread, fTopY);
                        outerCone.lineTo(left ? lx + inSpread : lx + outSpread, fTopY);
                        outerCone.lineTo(lx + 3f * k, fy0);
                        outerCone.close();
                        p.setShader(new LinearGradient(lx, fy0, lx, fTopY,
                            new int[]{Aurora.a(beam, 0.42f), Aurora.a(beam, 0.14f), 0},
                            new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
                        p.setMaskFilter(new BlurMaskFilter(blurOuter, BlurMaskFilter.Blur.NORMAL));
                        c.drawPath(outerCone, p);

                        float coreOut = outSpread * 0.46f;
                        float coreIn = inSpread * 0.48f;
                        Path innerCone = new Path();
                        innerCone.moveTo(lx - 1.8f * k, fy0);
                        innerCone.lineTo(left ? lx - coreOut : lx - coreIn, fCoreY);
                        innerCone.lineTo(left ? lx + coreIn : lx + coreOut, fCoreY);
                        innerCone.lineTo(lx + 1.8f * k, fy0);
                        innerCone.close();
                        p.setShader(new LinearGradient(lx, fy0, lx, fCoreY,
                            new int[]{Aurora.a(beam, 0.68f), Aurora.a(beam, 0.22f), 0},
                            new float[]{0f, 0.50f, 1f}, Shader.TileMode.CLAMP));
                        p.setMaskFilter(new BlurMaskFilter(blurInner, BlurMaskFilter.Blur.NORMAL));
                        c.drawPath(innerCone, p);
                    }
                    p.setShader(null);
                    p.setMaskFilter(null);

                    int tail = tailColor(themeId, false);
                    // Pilotos LED traseros en vista 45° elevada (Nissan N17 Impul)
                    float[] lx = {r.left + r.width() * 0.088f, r.left + r.width() * 0.912f};
                    float[] ly = {r.top + r.height() * 0.625f, r.top + r.height() * 0.625f};
                    float rx = r.width() * 0.110f;
                    float ry = r.height() * 0.080f;
                    for (int i = 0; i < 2; i++) {
                        c.save();
                        c.scale(1f, ry / Math.max(1f, rx), lx[i], ly[i]);
                        p.setShader(new RadialGradient(lx[i], ly[i], rx,
                            new int[]{Aurora.a(tail, 0.88f), Aurora.a(tail, 0.34f), 0},
                            new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
                        c.drawCircle(lx[i], ly[i], rx, p);
                        c.restore();
                        p.setShader(null);
                        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, 3.0f * k), BlurMaskFilter.Blur.NORMAL));
                        p.setColor(0xFFFFB8C4);
                        c.drawOval(new RectF(lx[i] - 4.2f * k, ly[i] - 2.2f * k,
                            lx[i] + 4.2f * k, ly[i] + 2.2f * k), p);
                        p.setMaskFilter(null);
                    }
                    // Tercera luz de freno LED bajo el alerón Impul
                    float bx = r.left + r.width() * 0.500f, by = r.top + r.height() * 0.535f;
                    float barHalf = r.width() * 0.070f;
                    c.save();
                    c.scale(1f, 0.34f, bx, by);
                    p.setShader(new RadialGradient(bx, by, barHalf * 1.45f,
                        new int[]{Aurora.a(tail, 0.72f), Aurora.a(tail, 0.20f), 0},
                        new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
                    c.drawCircle(bx, by, barHalf * 1.45f, p);
                    c.restore();
                    p.setShader(null);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(Math.max(1.5f, 2.5f * k));
                    p.setColor(0xFFFFC2CC);
                    c.drawLine(bx - barHalf * 0.78f, by, bx + barHalf * 0.78f, by, p);
                    p.setStrokeCap(Paint.Cap.BUTT);

                    // Reflejo rojo sutil en el suelo detrás del difusor trasero
                    float gx = r.centerX(), gy = Math.min(h - 4 * k, r.bottom - r.height() * 0.04f);
                    float gr = r.width() * 0.34f;
                    c.save();
                    c.scale(1f, 0.28f, gx, gy);
                    p.setShader(new RadialGradient(gx, gy, gr,
                        new int[]{Aurora.a(tail, 0.22f), Aurora.a(tail, 0.06f), 0},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                    c.drawCircle(gx, gy, gr, p);
                    c.restore();
                    p.setShader(null);
                }
            } catch (Throwable e) {
                frame = null;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (frame != null && !frame.isRecycled()) c.drawBitmap(frame, 0, 0, null);
        }
    }

    // ── Emblema moderno plano de Nissan en plata cromada ──

    static final class NissanLogoView extends View {
        private final boolean light;
        private final float k;

        NissanLogoView(Context c, boolean light, float scale) {
            super(c);
            this.light = light;
            this.k = scale;
            setContentDescription("Nissan");
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            drawNissanLogo(c, w / 2f, h / 2f, w * 0.92f, h * 0.92f, light);
        }
    }

    static void drawNissanLogo(Canvas c, float cx, float cy, float boxW, float boxH, boolean light) {
        float rad = Math.min(boxW * 0.34f, boxH * 0.44f);
        if (rad < 2f) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] chromeCols = light
            ? new int[]{0xFF202630, 0xFF535E6F, 0xFF282F3B}
            : new int[]{0xFFF4F7FC, 0xFFB0BAC9, 0xFFE6ECF5};
        Shader chrome = new LinearGradient(cx - rad * 1.2f, cy - rad, cx + rad * 1.2f, cy + rad,
            chromeCols, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);

        // Anillo abierto superior e inferior estilo moderno Nissan
        p.setShader(chrome);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(Math.max(1.2f, rad * 0.165f));
        RectF oval = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(oval, 207f, 126f, false, p);
        c.drawArc(oval, 27f, 126f, false, p);

        // Líneas horizontales finas del emblema plano
        float barW = rad * 1.18f;
        float barOff = rad * 0.285f;
        p.setStrokeWidth(Math.max(0.8f, rad * 0.085f));
        c.drawLine(cx - barW, cy - barOff, cx + barW, cy - barOff, p);
        c.drawLine(cx - barW, cy + barOff, cx + barW, cy + barOff, p);

        // Tipografía geométrica NISSAN centrada
        if (rad >= 6.5f) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            float ts = rad * 0.37f;
            p.setTextSize(ts);
            if (Build.VERSION.SDK_INT >= 21) p.setLetterSpacing(0.18f);
            Paint.FontMetrics fm = p.getFontMetrics();
            float ty = cy - (fm.ascent + fm.descent) / 2f;
            c.drawText("NISSAN", cx, ty, p);
        }
        p.setShader(null);
    }

    // ── Miniatura para la galería de Personalización ──

    static final class Thumb extends View {
        final String id;
        final boolean portrait, light;
        boolean selected;
        private final float s;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Bitmap bg, car;

        Thumb(Context c, String id, float s, boolean portrait, boolean light) {
            super(c);
            this.id = id;
            this.s = s;
            this.portrait = portrait;
            this.light = light;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, (int) (w * (portrait ? 1.42f : 0.586f)));
        }

        private void rr(Canvas c, float l, float t, float rt, float b, float rad, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(color);
            r.set(l, t, rt, b);
            c.drawRoundRect(r, rad, rad, p);
        }

        private void card(Canvas c, float l, float t, float rt, float b, float rad) {
            if (light) {
                rr(c, l, t + rad * 0.14f, rt, b + rad * 0.22f, rad, 0x0C141B28);
            }
            rr(c, l, t, rt, b, rad, glass(id, light));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, s * 0.7f));
            p.setShader(null);
            p.setColor(glassEdge(id, light));
            r.set(l, t, rt, b);
            c.drawRoundRect(r, rad, rad, p);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float u = portrait ? w : h, rad = u * 0.06f, crad = u * 0.032f;
            if (bg == null || bg.getWidth() != w || bg.getHeight() != h) {
                try {
                    bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    paint(id, new Canvas(bg), w, h, light, Main.RED);
                    int ga = grainAmp(id, light);
                    if (ga > 0) Aurora.grain(bg, ga);
                } catch (Throwable e) { bg = null; }
            }
            r.set(0, 0, w, h);
            if (bg != null) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
            } else rr(c, 0, 0, w, h, rad, base(id, light));

            int txCol = text(id, light, 0xFFF2F3F5), muCol = muted(id, light, 0xFF8C919A);
            int acc = accent(id, light, Main.RED), sec = secondary(id, light);
            float pad = u * 0.032f, gap = u * 0.022f;
            float top = pad + u * 0.06f;

            // Barra de aplicaciones vertical en el borde derecho (sin barra inferior)
            float railW = w * (portrait ? 0.112f : 0.082f);
            float railX = w - pad - railW, railY = pad, railH = h - 2 * pad;
            float cRight = railX - gap;
            float playH = u * (portrait ? 0.082f : 0.115f), playY = h - pad - playH;

            if (!portrait) {
                float colFrac = 0.255f;
                float rFrac = 0.45f;
                float colW = (cRight - pad) * colFrac, rx = pad + colW + gap;
                float rw = cRight - rx, mw = (rw - gap) * rFrac;
                column(c, pad, pad, colW, h - 2 * pad, txCol, acc, sec);
                map(c, rx, top, rx + mw, playY - gap, crad, u, acc, sec);
                float wx = rx + mw + gap, wh = (playY - gap - top) * 0.55f;
                weather(c, wx, top, cRight - wx, wh, crad, txCol, muCol, acc);
                maint(c, wx, top + wh + gap, cRight - wx, playY - gap - (top + wh + gap), crad, txCol, acc);
                player(c, rx, playY, cRight - rx, playH, crad, txCol, muCol, acc);
            } else {
                float mapB = h * 0.47f;
                map(c, pad, top, cRight, mapB, crad, u, acc, sec);
                float rowY = mapB + gap, rowH = playY - gap - rowY, colW = (cRight - pad) * 0.42f;
                column(c, pad, rowY, colW, rowH, txCol, acc, sec);
                float wx = pad + colW + gap, wh = rowH * 0.55f;
                weather(c, wx, rowY, cRight - wx, wh, crad, txCol, muCol, acc);
                maint(c, wx, rowY + wh + gap, cRight - wx, rowH - wh - gap, crad, txCol, acc);
                player(c, pad, playY, cRight - pad, playH, crad, txCol, muCol, acc);
            }

            // Cápsula vertical derecha de apps
            float rRad = railW * 0.34f;
            rr(c, railX, railY, railX + railW, railY + railH, rRad, pill(id, light));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, s * 0.6f));
            p.setColor(pillEdge(id, light));
            r.set(railX, railY, railX + railW, railY + railH);
            c.drawRoundRect(r, rRad, rRad, p);
            float tsz = Math.min(railW * 0.68f, (railH - gap) / 7.2f);
            for (int i = 0; i < 6; i++) {
                float cy = railY + railH * (i + 0.5f) / 6f;
                float cx = railX + railW / 2f;
                int tc = i == 5 ? tile(id, light) : Aurora.a(i % 2 == 0 ? acc : sec, 0.9f);
                rr(c, cx - tsz / 2, cy - tsz / 2, cx + tsz / 2, cy + tsz / 2, tsz * 0.26f, tc);
            }
            if (selected) {
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * s);
                p.setColor(Main.RED);
                r.set(1.25f * s, 1.25f * s, w - 1.25f * s, h - 1.25f * s);
                c.drawRoundRect(r, rad, rad, p);
            }
        }

        private void column(Canvas c, float x, float y, float w, float hh, int textCol, int acc, int sec) {
            float gr = Math.min(w * 0.26f, hh * 0.15f), cx = x + w / 2, gy = y + hh * 0.04f + gr;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(1f, gr * 0.07f));
            p.setColor(track(id, light));
            r.set(cx - gr, gy - gr, cx + gr, gy + gr);
            c.drawArc(r, 135, 270, false, p);
            p.setStrokeWidth(Math.max(1.5f, gr * 0.14f));
            p.setColor(acc);
            c.drawArc(r, 135, 115, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            rr(c, cx - gr * 0.35f, gy - gr * 0.22f, cx + gr * 0.35f, gy + gr * 0.18f, gr * 0.08f, Aurora.a(textCol, 0.9f));

            // Indicador GPS compacto bajo el velocímetro
            float gpsY = gy + gr * 1.05f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3DDC84);
            c.drawCircle(cx - w * 0.07f, gpsY, Math.max(1.3f, gr * 0.08f), p);
            rr(c, cx - w * 0.01f, gpsY - Math.max(0.8f, gr * 0.05f), cx + w * 0.11f, gpsY + Math.max(0.8f, gr * 0.05f),
                gr * 0.04f, Aurora.a(textCol, 0.55f));

            // Franja inferior reservada para el emblema grande de Nissan
            float logoBand = Math.max(gr * 0.85f, hh * 0.14f);
            float logoCy = y + hh - logoBand * 0.52f;
            drawNissanLogo(c, cx, logoCy, w * 0.54f, logoBand * 0.92f, light);

            String carId = carForTheme(id);
            float ct = gpsY + gr * 0.24f, ch = Math.max(18f, (y + hh - logoBand) - ct);
            RectF cr = Faro.carRect(w, ch, carId);
            if (!light) {
                int bm = beamColor(id, false);
                p.setStyle(Paint.Style.FILL);
                if (NIGHTPULSE.equals(id)) {
                    float fy0 = ct + cr.top + cr.height() * 0.14f;
                    float fTop = Math.max(ct, ct + cr.top - cr.height() * 0.24f);
                    p.setShader(new LinearGradient(0, fy0, 0, fTop, Aurora.a(bm, 0.42f), 0, Shader.TileMode.CLAMP));
                    for (int i = 0; i < 2; i++) {
                        float lx0 = x + cr.left + cr.width() * (i == 0 ? 0.16f : 0.84f);
                        Path cone = new Path();
                        cone.moveTo(lx0 - w * 0.02f, fy0);
                        cone.lineTo(lx0 - w * 0.10f, fTop);
                        cone.lineTo(lx0 + w * 0.10f, fTop);
                        cone.lineTo(lx0 + w * 0.02f, fy0);
                        cone.close();
                        c.drawPath(cone, p);
                    }
                }
                // Sombra elíptica de estudio en miniatura
                p.setShader(null);
                c.save();
                float sy = ct + cr.bottom - cr.height() * 0.07f;
                c.scale(1f, 0.24f, cx, sy);
                p.setColor(0xA0000000);
                c.drawCircle(cx, sy, cr.width() * 0.48f, p);
                c.restore();
            }
            int cw = Math.max(1, Math.round(cr.width())), chh = Math.max(1, Math.round(cr.height()));
            if (car == null || car.getWidth() != cw || car.getHeight() != chh)
                car = Faro.carBitmap(getContext(), carId, cw, chh, !light);
            if (car != null) c.drawBitmap(car, x + cr.left, ct + cr.top, null);
            if (!light && NIGHTPULSE.equals(id)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Aurora.a(tailColor(id, false), 0.85f));
                c.drawCircle(x + cr.left + cr.width() * 0.088f, ct + cr.top + cr.height() * 0.625f, Math.max(1.5f, cr.width() * 0.048f), p);
                c.drawCircle(x + cr.left + cr.width() * 0.912f, ct + cr.top + cr.height() * 0.625f, Math.max(1.5f, cr.width() * 0.048f), p);
                c.drawCircle(x + cr.left + cr.width() * 0.500f, ct + cr.top + cr.height() * 0.535f, Math.max(1.1f, cr.width() * 0.028f), p);
            }
        }

        private void map(Canvas c, float l, float t, float rt, float b, float crad, float u, int acc, int sec) {
            float mw = rt - l, mh = b - t;
            int mapBg = light ? 0xB8EEF3FA : 0xB00F1726;
            rr(c, l, t, rt, b, crad, mapBg);
            c.save();
            c.clipRect(l, t, rt, b);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, u * 0.012f));
            p.setColor(light ? 0xB3FFFFFF : 0x80323B4A);
            for (int i = 1; i < 6; i++) c.drawLine(l, t + mh * i / 6f, rt, t + mh * i / 6f - mh * 0.18f, p);
            for (int i = 1; i < 6; i++) c.drawLine(l + mw * i / 6f, t, l + mw * i / 6f + mw * 0.08f, b, p);
            p.setStrokeWidth(Math.max(1.8f, u * 0.022f));
            p.setColor(Aurora.a(acc, light ? 0.65f : 0.85f));
            c.drawLine(l + mw * 0.15f, t + mh * 0.82f, rt - mw * 0.15f, t + mh * 0.25f, p);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, s * 0.7f));
            p.setColor(glassEdge(id, light));
            r.set(l, t, rt, b);
            c.drawRoundRect(r, crad, crad, p);
            rr(c, l + mw * 0.05f, t + mh * 0.05f, l + mw * 0.55f, t + mh * 0.05f + u * 0.044f, u * 0.012f, tile(id, light));
            p.setStyle(Paint.Style.FILL);
            p.setColor(acc);
            Path a = new Path();
            float ax = l + mw * 0.5f, ay = t + mh * 0.55f, as = u * 0.03f;
            a.moveTo(ax, ay - as); a.lineTo(ax + as * 0.7f, ay + as * 0.7f); a.lineTo(ax, ay + as * 0.35f); a.lineTo(ax - as * 0.7f, ay + as * 0.7f); a.close();
            c.drawPath(a, p);
        }

        private void weather(Canvas c, float x, float y, float w, float hh, float rad, int textCol, int mutedCol, int acc) {
            card(c, x, y, x + w, y + hh, rad);
            rr(c, x + w * 0.08f, y + hh * 0.12f, x + w * 0.3f, y + hh * 0.17f, hh * 0.02f, Aurora.a(mutedCol, 0.8f));
            rr(c, x + w * 0.08f, y + hh * 0.24f, x + w * 0.34f, y + hh * 0.42f, hh * 0.04f, Aurora.a(textCol, 0.9f));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, hh * 0.02f));
            p.setColor(acc);
            c.drawCircle(x + w * 0.82f, y + hh * 0.22f, hh * 0.08f, p);
            for (int i = 0; i < 6; i++) {
                float cx = x + w * (0.12f + i * 0.152f);
                c.drawCircle(cx, y + hh * 0.72f, hh * 0.045f, p);
            }
        }

        private void maint(Canvas c, float x, float y, float w, float hh, float rad, int textCol, int acc) {
            card(c, x, y, x + w, y + hh, rad);
            int[] cols = new int[]{Faro.urgency(-1, light), acc, Faro.urgency(0.6, light), Faro.urgency(0.9, light)};
            for (int i = 0; i < 4; i++) {
                float ry = y + hh * (0.22f + i * 0.19f);
                rr(c, x + w * 0.07f, ry, x + w * 0.085f, ry + hh * 0.12f, w * 0.01f, cols[i]);
                rr(c, x + w * 0.13f, ry + hh * 0.035f, x + w * (0.55f + (i % 2) * 0.15f), ry + hh * 0.085f, hh * 0.02f, Aurora.a(textCol, 0.8f));
            }
        }

        private void player(Canvas c, float x, float y, float w, float hh, float rad, int textCol, int mutedCol, int acc) {
            card(c, x, y, x + w, y + hh, rad);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Aurora.a(textCol, 0.85f));
            for (int i = 0; i < 3; i++) c.drawCircle(x + w * (0.06f + i * 0.055f), y + hh / 2, hh * (i == 1 ? 0.13f : 0.09f), p);
            rr(c, x + w * 0.24f, y + hh * 0.18f, x + w * 0.24f + hh * 0.64f, y + hh * 0.82f, hh * 0.12f, Aurora.a(acc, 0.85f));
            rr(c, x + w * 0.24f + hh * 0.8f, y + hh * 0.32f, x + w * 0.52f, y + hh * 0.44f, hh * 0.05f, Aurora.a(textCol, 0.9f));
            rr(c, x + w * 0.24f + hh * 0.8f, y + hh * 0.56f, x + w * 0.46f, y + hh * 0.66f, hh * 0.05f, Aurora.a(mutedCol, 0.8f));
            rr(c, x + w * 0.6f, y + hh * 0.6f, x + w * 0.94f, y + hh * 0.64f, hh * 0.02f, track(id, light));
            rr(c, x + w * 0.6f, y + hh * 0.6f, x + w * 0.78f, y + hh * 0.64f, hh * 0.02f, acc);
        }
    }
}
