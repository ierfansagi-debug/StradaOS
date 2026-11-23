package com.stradaos.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Motor vectorial MapLibre GL JS ejecutado en WebView con aceleración WebGL.
 * Soporta estilos día/noche (OpenFreeMap), edificios 3D, inclinación 30°/45°/60°,
 * rutas GeoJSON, gestos táctiles de arrastre/pellizco y reserva automática a OpenStreetMap.
 */
public class MapLibreMapView extends FrameLayout {

    public interface Listener {
        void onFollowChanged(boolean following);
        void onMapPick(double lat, double lon);
        void onEngineFallback(String reason);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Listener listener;
    private boolean ready;
    private boolean failed;
    private boolean destroyed;

    private double centerLat = 4.7110, centerLon = -74.0721;
    private double carLat = Double.NaN, carLon = Double.NaN;
    private float carBearing = Float.NaN;
    private float carSpeedKmh;
    private boolean hasFix;
    private double zoomLevel = 15.0;

    private boolean following = true;
    private boolean overviewMode;
    private boolean lightMode;
    private int accentColor = 0xFFE63946;
    private boolean view3d;
    private int tiltAngle = 45;
    private boolean headingUp;
    private Nav.Place previewPlace;
    private long lastPosPushAt;
    private boolean waveActive;
    private String waveKind = "radar";
    private double waveLat = Double.NaN, waveLon = Double.NaN;

    private final Runnable readyWatchdog = new Runnable() {
        public void run() {
            if (!ready && !failed && !destroyed) {
                triggerFallback("maplibre_timeout");
            }
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public MapLibreMapView(Context c, final float radiusPx, boolean light, int redColor) {
        super(c);
        lightMode = light;
        accentColor = redColor;
        setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF15171C);
        if (Build.VERSION.SDK_INT >= 21 && radiusPx > 0) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            setClipToOutline(true);
        }

        try {
            web = new WebView(c) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return super.onTouchEvent(event);
                }
            };
            web.setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF15171C);
            web.setOverScrollMode(OVER_SCROLL_NEVER);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(true);
            if (Build.VERSION.SDK_INT >= 16) {
                ws.setAllowUniversalAccessFromFileURLs(true);
                ws.setAllowFileAccessFromFileURLs(true);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);

            web.addJavascriptInterface(new Bridge(), "AndroidBridge");
            web.setWebChromeClient(new WebChromeClient());
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (Build.VERSION.SDK_INT >= 23 && request != null && request.isForMainFrame()) {
                        triggerFallback("main_frame_error");
                    }
                }
            });
            addView(web, new LayoutParams(-1, -1));
            web.loadUrl("file:///android_asset/map/maplibre_nav.html");
            ui.postDelayed(readyWatchdog, 7500L);
        } catch (Throwable e) {
            triggerFallback(e.getClass().getSimpleName());
        }
    }

    public void setListener(Listener l) { listener = l; }
    public boolean hasFix() { return hasFix && !Double.isNaN(carLat); }
    public boolean isFollowing() { return following && !overviewMode; }
    public boolean isOverview() { return overviewMode; }
    public double getCenterLat() { return previewPlace != null ? previewPlace.lat : centerLat; }
    public double getCenterLon() { return previewPlace != null ? previewPlace.lon : centerLon; }
    public int getZoomLevel() { return (int) Math.round(zoomLevel); }
    public Nav.Place getPreviewPlace() { return previewPlace; }

    public void setInitialCenter(double la, double lo) {
        if (Double.isNaN(la) || Double.isNaN(lo)) return;
        centerLat = la;
        centerLon = lo;
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setInitialCenter(%.6f, %.6f, 15.0);", la, lo));
    }

    public void autoZoom() {
        recenter();
    }

    public void setTheme(boolean light, int redColor) {
        lightMode = light;
        accentColor = redColor;
        setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF15171C);
        if (web != null) web.setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF15171C);
        String hex = String.format(Locale.US, "#%06X", redColor & 0xFFFFFF);
        execJs("window.StradaMap && StradaMap.setTheme(" + (light ? "true" : "false") + ", " + JSONObject.quote(hex) + ");");
    }

    public void onTiltConfigChanged() {
        view3d = LiveMapView.view3d;
        tiltAngle = LiveMapView.tiltAngle;
        execJs("window.StradaMap && StradaMap.setTiltConfig(" + (view3d ? "true" : "false") + ", " + tiltAngle + ");");
    }

    public void setHeadingUp(boolean hu) {
        headingUp = hu;
        execJs("window.StradaMap && StradaMap.setHeadingUp(" + (hu ? "true" : "false") + ");");
    }

    public void setPosition(double la, double lo, float br, float speedKmh) {
        if (Double.isNaN(la) || Double.isNaN(lo)) return;
        carLat = la;
        carLon = lo;
        if (!Float.isNaN(br)) carBearing = br;
        carSpeedKmh = speedKmh;
        hasFix = true;
        if (following && previewPlace == null && !overviewMode) {
            centerLat = la;
            centerLon = lo;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (ready && now - lastPosPushAt < 180L) return;
        lastPosPushAt = now;
        pushPosition();
    }

    private void pushPosition() {
        if (!hasFix || Double.isNaN(carLat) || Double.isNaN(carLon)) return;
        double brVal = Float.isNaN(carBearing) ? 0.0 : carBearing;
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setPosition(%.6f, %.6f, %.2f, %.1f, true);",
                carLat, carLon, brVal, carSpeedKmh));
    }

    public void setPreviewPlace(Nav.Place p) {
        previewPlace = p;
        overviewMode = false;
        if (p == null) {
            execJs("window.StradaMap && StradaMap.clearPreview(false);");
            return;
        }
        following = false;
        centerLat = p.lat;
        centerLon = p.lon;
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setPreviewPlace(%.6f, %.6f, %s);",
                p.lat, p.lon, JSONObject.quote(p.name != null ? p.name : "")));
    }

    public void clearPreview(boolean snapBack) {
        previewPlace = null;
        if (snapBack) {
            following = true;
            overviewMode = false;
        }
        execJs("window.StradaMap && StradaMap.clearPreview(" + (snapBack ? "true" : "false") + ");");
    }

    public void focusPoint(double la, double lo, int z) {
        if (Double.isNaN(la) || Double.isNaN(lo)) return;
        centerLat = la;
        centerLon = lo;
        following = false;
        overviewMode = false;
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.focusPoint(%.6f, %.6f, %d);", la, lo, z > 0 ? z : 16));
    }

    public void setOverview(boolean ov) {
        overviewMode = ov;
        if (ov) following = false;
        execJs("window.StradaMap && StradaMap.setOverview(" + (ov ? "true" : "false") + ");");
    }

    public void recenter() {
        previewPlace = null;
        overviewMode = false;
        following = true;
        execJs("window.StradaMap && StradaMap.recenter();");
    }

    public void zoomBy(int d) {
        execJs("window.StradaMap && StradaMap.zoomBy(" + d + ");");
    }

    public void setWaveAlert(boolean active, String kind, double lat, double lon) {
        String k = kind != null ? kind : "radar";
        if (waveActive == active && k.equals(waveKind)
                && ((Double.isNaN(waveLat) && Double.isNaN(lat)) || Math.abs(waveLat - lat) < 1e-6)
                && ((Double.isNaN(waveLon) && Double.isNaN(lon)) || Math.abs(waveLon - lon) < 1e-6)) {
            return;
        }
        waveActive = active;
        waveKind = k;
        waveLat = lat;
        waveLon = lon;
        pushWaveAlert();
    }

    private void pushWaveAlert() {
        if (!waveActive || Double.isNaN(waveLat) || Double.isNaN(waveLon)) {
            execJs("window.StradaMap && StradaMap.setWaveAlert(false, 'radar', NaN, NaN);");
            return;
        }
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setWaveAlert(true, %s, %.6f, %.6f);",
                JSONObject.quote(waveKind), waveLat, waveLon));
    }

    public void syncPoliceReports() {
        if (!ready) return;
        try {
            List<Nav.PoliceReport> list = Nav.getPoliceReports(getContext());
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                Nav.PoliceReport pr = list.get(i);
                arr.put(new JSONObject().put("lat", pr.lat).put("lon", pr.lon));
            }
            execJs("window.StradaMap && StradaMap.setPoliceReports(" + arr.toString() + ");");
        } catch (Throwable ignored) { }
    }

    public void syncRoute() {
        if (!ready) return;
        Nav.Route r = Nav.active;
        if (r == null || r.pts == null || r.pts.length < 4) {
            execJs("window.StradaMap && StradaMap.setRoute(null);");
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("pts", ptsArray(r.pts));
            obj.put("destLat", r.destLat);
            obj.put("destLon", r.destLon);

            List<Nav.Route> alts = Nav.altRoutes;
            if (alts != null && alts.size() > 1) {
                JSONArray altArr = new JSONArray();
                for (int i = 0; i < alts.size(); i++) {
                    Nav.Route ar = alts.get(i);
                    if (ar == null || ar == r || ar.pts == null || ar.pts.length < 4) continue;
                    altArr.put(ptsArray(ar.pts));
                }
                obj.put("alts", altArr);
            }
            if (!r.waypoints.isEmpty()) {
                JSONArray wa = new JSONArray();
                for (int i = 0; i < r.waypoints.size(); i++) {
                    Nav.Place wp = r.waypoints.get(i);
                    wa.put(new JSONObject().put("lat", wp.lat).put("lon", wp.lon));
                }
                obj.put("waypoints", wa);
            }
            if (!r.cameras.isEmpty()) {
                JSONArray ca = new JSONArray();
                for (int i = 0; i < r.cameras.size(); i++) {
                    Nav.Camera cm = r.cameras.get(i);
                    ca.put(new JSONObject().put("lat", cm.lat).put("lon", cm.lon));
                }
                obj.put("cameras", ca);
            }
            if (!r.incidents.isEmpty()) {
                JSONArray ia = new JSONArray();
                for (int i = 0; i < r.incidents.size(); i++) {
                    Nav.Incident inc = r.incidents.get(i);
                    ia.put(new JSONObject().put("lat", inc.lat).put("lon", inc.lon));
                }
                obj.put("incidents", ia);
            }
            execJs("window.StradaMap && StradaMap.setRoute(" + obj.toString() + ");");
        } catch (Throwable ignored) { }
    }

    private static JSONArray ptsArray(double[] pts) throws Exception {
        int n = pts.length / 2;
        int step = Math.max(1, n / 2000);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < n; i += step) {
            arr.put(pts[i * 2]);
            arr.put(pts[i * 2 + 1]);
        }
        if ((n - 1) % step != 0) {
            arr.put(pts[(n - 1) * 2]);
            arr.put(pts[(n - 1) * 2 + 1]);
        }
        return arr;
    }

    private void execJs(final String js) {
        if (!ready || destroyed || web == null) return;
        ui.post(new Runnable() {
            public void run() {
                if (destroyed || web == null) return;
                try {
                    if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(js, null);
                    else web.loadUrl("javascript:" + js);
                } catch (Throwable ignored) { }
            }
        });
    }

    private void triggerFallback(final String reason) {
        if (failed || destroyed) return;
        failed = true;
        ui.removeCallbacks(readyWatchdog);
        ui.post(new Runnable() {
            public void run() {
                if (!destroyed && listener != null) listener.onEngineFallback(reason);
            }
        });
    }

    public void onPause() {
        if (web != null) {
            try { web.onPause(); } catch (Throwable ignored) { }
        }
    }

    public void onResume() {
        if (web != null) {
            try { web.onResume(); } catch (Throwable ignored) { }
        }
    }

    public void destroy() {
        destroyed = true;
        ready = false;
        waveActive = false;
        ui.removeCallbacks(readyWatchdog);
        if (web != null) {
            try {
                removeView(web);
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) { }
            web = null;
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void onMapReady() {
            ui.post(new Runnable() {
                public void run() {
                    if (destroyed || failed) return;
                    ready = true;
                    ui.removeCallbacks(readyWatchdog);
                    syncReadyState();
                }
            });
        }

        @JavascriptInterface
        public void onEngineFailed(final String reason) {
            triggerFallback(reason);
        }

        @JavascriptInterface
        public void onFollowChanged(final boolean f) {
            ui.post(new Runnable() {
                public void run() {
                    following = f;
                    if (f) overviewMode = false;
                    if (listener != null) listener.onFollowChanged(f);
                }
            });
        }

        @JavascriptInterface
        public void onCameraMoved(final double lat, final double lon, final double zoom) {
            centerLat = lat;
            centerLon = lon;
            zoomLevel = zoom;
        }

        @JavascriptInterface
        public void onMapPick(final double lat, final double lon) {
            ui.post(new Runnable() {
                public void run() {
                    if (!destroyed && listener != null) listener.onMapPick(lat, lon);
                }
            });
        }
    }

    private void syncReadyState() {
        setTheme(lightMode, accentColor);
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setInitialCenter(%.6f, %.6f, 15.0);", centerLat, centerLon));
        onTiltConfigChanged();
        setHeadingUp(headingUp);
        if (hasFix) pushPosition();
        if (previewPlace != null) setPreviewPlace(previewPlace);
        syncRoute();
        syncPoliceReports();
        pushWaveAlert();
        if (overviewMode) setOverview(true);
    }
}
