package com.stradaos.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * Registro de temas premium de StradaOS (colores, fondos, miniaturas y disponibilidad).
 */
final class Prem {
    private Prem() { }

    static final String[] IDS = {
        Aurora.ID, Cobalto.ID, Faro.ID, CockpitThemes.NIGHTPULSE
    };
    static String style = Aurora.ID;

    static String normalize(String id) {
        return ("aurora_drive".equals(id) || "cyberflow".equals(id)) ? CockpitThemes.NIGHTPULSE : id;
    }

    static boolean known(String id) {
        return Aurora.ID.equals(id) || Cobalto.ID.equals(id) || Faro.ID.equals(id) || CockpitThemes.isCustom(id);
    }

    /** Temas gratuitos/desbloqueados inmediatamente en Personalización. */
    static boolean free(String id) {
        return Aurora.ID.equals(id) || Cobalto.ID.equals(id) || CockpitThemes.isCustom(id);
    }

    static int paidCount() {
        int n = 0;
        for (String id : IDS) if (!free(id)) n++;
        return n;
    }

    static boolean forceBundle;
    static boolean bundle() { return forceBundle || paidCount() >= 2; }
    static boolean cob() { return Cobalto.ID.equals(style); }
    static boolean faro() { return Faro.ID.equals(style); }
    static boolean cockpit() { return CockpitThemes.isCockpit(style); }
    static boolean mapOnlyOk() { return Aurora.ID.equals(style); }

    static String name(String id) {
        id = normalize(id);
        if (Cobalto.ID.equals(id)) return "Cobalto";
        if (Faro.ID.equals(id)) return "Faro";
        if (CockpitThemes.NIGHTPULSE.equals(id)) return "NightPulse";
        return "Aurora";
    }

    static String desc(String id) {
        id = normalize(id);
        if (CockpitThemes.NIGHTPULSE.equals(id))
            return "Futurista · Oscuro · Deportivo. Paneles de vidrio negro, acentos azul eléctrico, barra lateral derecha de apps, mapa central amplio y tu Nissan Almera N17 Impul blanco en vista elevada trasera con pilotos LED.";
        if (Faro.ID.equals(id))
            return "Fondo carbón (o perla en claro) con un haz de luz diagonal. A la izquierda, el velocímetro y un carro visto desde arriba que enciende los faros de noche; en el centro, el mapa integrado al fondo; a la derecha, el clima por horas y tus próximos mantenimientos, con el reproductor y la barra de apps debajo.";
        return Cobalto.ID.equals(id)
            ? "Azul noche en tres columnas: el mapa se difumina hasta el reproductor de carátula redonda, y tu vehículo muestra el velocímetro arriba, su foto en el medio y el mantenimiento abajo. La temperatura va sobre el mapa."
            : "Fondo con un arco de color difuminado y granulado, tarjetas translúcidas, la hora en grande con música, vehículo y clima arriba, el mapa a lo ancho y una barra tipo píldora. El botón «Solo mapa» agranda el mapa.";
    }

    static int base(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.base(style, l)
            : faro() ? Faro.base(l) : cob() ? Cobalto.base(l) : Aurora.base(l);
    }
    static int glass(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.glass(style, l)
            : faro() ? Faro.glass(l) : cob() ? Cobalto.glassTop(l) : Aurora.glass(l);
    }
    static int glassEdge(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.glassEdge(style, l)
            : faro() ? Faro.glassEdge(l) : cob() ? Cobalto.glassEdge(l) : Aurora.glassEdge(l);
    }
    static int pill(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.pill(style, l)
            : faro() ? Faro.pill(l) : cob() ? Cobalto.pill(l) : Aurora.pill(l);
    }
    static int pillEdge(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.pillEdge(style, l)
            : faro() ? Faro.pillEdge(l) : cob() ? Cobalto.pillEdge(l) : Aurora.pillEdge(l);
    }
    static int pillIcon(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.pillIcon(style, l)
            : faro() ? Faro.pillIcon(l) : cob() ? Cobalto.pillIcon(l) : Aurora.pillIcon(l);
    }
    static int soft(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.soft(style, l)
            : faro() ? Faro.soft(l) : cob() ? Cobalto.soft(l) : Aurora.soft(l);
    }
    static float mapAlpha(boolean l) {
        return CockpitThemes.isCustom(style) ? CockpitThemes.mapAlpha(style, l)
            : faro() ? Faro.mapAlpha(l) : cob() ? Cobalto.mapAlpha(l) : Aurora.mapAlpha(l);
    }

    private static Bitmap cache;
    private static String cacheKey;

    /** Fondo del tema en uso a media resolución, con grano; se guarda hasta que cambie el tamaño, el modo o el tema. */
    static Bitmap background(int w, int h, boolean light, int accent) {
        int bw = Math.max(64, w / 2), bh = Math.max(64, h / 2);
        String key = style + ":" + bw + "x" + bh + ":" + light + ":" + accent + ":" + Faro.mapLevel;
        if (cache != null && !cache.isRecycled() && key.equals(cacheKey)) return cache;
        Bitmap b;
        try {
            b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            b.eraseColor(base(light));
            return b;
        }
        Canvas c = new Canvas(b);
        if (CockpitThemes.isCustom(style)) CockpitThemes.paint(style, c, bw, bh, light, accent);
        else if (faro()) Faro.paint(c, bw, bh, light, accent);
        else if (cob()) Cobalto.paint(c, bw, bh, light, accent);
        else Aurora.paint(c, bw, bh, light, accent);
        if (CockpitThemes.isCustom(style)) {
            int ga = CockpitThemes.grainAmp(style, light);
            if (ga > 0) Aurora.grain(b, ga);
        } else if (faro()) Aurora.grain(b, Faro.grain(light));
        else Aurora.grain(b, light);
        cache = b;
        cacheKey = key;
        return b;
    }
}
