package com.stradaos.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Motor vectorial MapLibre GL JS ejecutado en WebView con aceleración WebGL.
 * Incluye activos locales empaquetados, edificios 3D permanentes, inclinación automática
 * de 30° con zoom cercano al iniciar la navegación y modo sin conexión con teselas .map.
 */
public class MapLibreMapView extends FrameLayout {

    public interface Listener {
        void onFollowChanged(boolean following);
        void onMapPick(double lat, double lon);
        void onHazardTap(String alertId);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Listener listener;
    private boolean ready;
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
    private boolean view3d = true;
    private int tiltAngle = 30;
    private boolean headingUp;
    private boolean offlineMode;
    private boolean navActive;
    private boolean autoZoom = true;
    private int bright = 1;
    private Nav.Place previewPlace;
    private long lastPosPushAt;
    private boolean waveActive;
    private String waveKind = "radar";
    private double waveLat = Double.NaN, waveLon = Double.NaN;
    private String pendingAlertsJson = "[]";
    private String pendingJamsJson = "[]";

    private final LruCache<String, byte[]> offlineTileCache = new LruCache<String, byte[]>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value != null ? value.length : 1;
        }
    };
    private byte[] emptyTileLight;
    private byte[] emptyTileDark;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    public MapLibreMapView(Context c, final float radiusPx, boolean light, int redColor) {
        super(c);
        lightMode = light;
        accentColor = redColor;
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences("launcher", 0);
            bright = sp.getInt("map_bright", 1);
            autoZoom = sp.getBoolean("map_auto_zoom", true);
        } catch (Throwable ignored) { }
        setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF06080E);
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
            web.setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF06080E);
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
            if (Build.VERSION.SDK_INT >= 33) {
                try { ws.setAlgorithmicDarkeningAllowed(false); } catch (Throwable ignored) { }
            } else if (Build.VERSION.SDK_INT >= 29) {
                try { ws.setForceDark(WebSettings.FORCE_DARK_OFF); } catch (Throwable ignored) { }
            }
            ws.setCacheMode(WebSettings.LOAD_DEFAULT);

            web.addJavascriptInterface(new Bridge(), "AndroidBridge");
            web.setWebChromeClient(new WebChromeClient());
            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (Build.VERSION.SDK_INT >= 21 && request != null && request.getUrl() != null) {
                        WebResourceResponse res = interceptOfflineTile(request.getUrl().toString());
                        if (res != null) return res;
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @SuppressWarnings("deprecation")
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    WebResourceResponse res = interceptOfflineTile(url);
                    if (res != null) return res;
                    return super.shouldInterceptRequest(view, url);
                }
            });
            addView(web, new LayoutParams(-1, -1));
            ui.post(new Runnable() {
                public void run() {
                    if (!destroyed && web != null) {
                        web.loadUrl(buildInitUrl());
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    private String buildInitUrl() {
        String hex = String.format(Locale.US, "#%06X", accentColor & 0xFFFFFF);
        return "file:///android_asset/map/maplibre_nav.html"
                + "?light=" + (lightMode ? "1" : "0")
                + "&bright=" + bright
                + "&offline=" + (offlineMode ? "1" : "0")
                + "&tilt=" + (tiltAngle >= 48 ? 60 : 30)
                + "&autoZoom=" + (autoZoom ? "1" : "0")
                + "&headingUp=" + (headingUp ? "1" : "0")
                + "&nav=" + (navActive ? "1" : "0")
                + "&lat=" + String.format(Locale.US, "%.6f", centerLat)
                + "&lon=" + String.format(Locale.US, "%.6f", centerLon)
                + "&accent=" + Uri.encode(hex);
    }

    private WebResourceResponse interceptOfflineTile(String url) {
        if (url == null || !url.startsWith("https://strada.offline/tiles/")) return null;
        try {
            Uri uri = Uri.parse(url);
            List<String> segs = uri.getPathSegments();
            if (segs == null || segs.size() < 4) return buildPngResponse(blankTilePng(lightMode));
            int z = Integer.parseInt(segs.get(1));
            int x = Integer.parseInt(segs.get(2));
            String yStr = segs.get(3);
            int dot = yStr.indexOf('.');
            if (dot > 0) yStr = yStr.substring(0, dot);
            int y = Integer.parseInt(yStr);

            byte[] png = null;
            OfflineMap fm = LiveMapView.fileMap;
            if (fm != null && fm.coversFast(z, x, y)) {
                String key = "v2/" + z + "/" + x + "/" + y + "/" + (lightMode ? 1 : 0) + "/" + bright;
                png = offlineTileCache.get(key);
                if (png == null) {
                    Bitmap bmp = fm.render(z, x, y, 256, lightMode, bright);
                    if (bmp != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                        bmp.recycle();
                        png = bos.toByteArray();
                        offlineTileCache.put(key, png);
                    }
                }
            }
            if (png == null) png = blankTilePng(lightMode);
            return buildPngResponse(png);
        } catch (Throwable e) {
            return buildPngResponse(blankTilePng(lightMode));
        }
    }

    private byte[] blankTilePng(boolean light) {
        if (light && emptyTileLight != null) return emptyTileLight;
        if (!light && emptyTileDark != null) return emptyTileDark;
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565);
        bmp.eraseColor(light ? 0xFFE8EBF0 : 0xFF06080E);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
        bmp.recycle();
        byte[] bytes = bos.toByteArray();
        if (light) emptyTileLight = bytes;
        else emptyTileDark = bytes;
        return bytes;
    }

    private static WebResourceResponse buildPngResponse(byte[] png) {
        WebResourceResponse resp = new WebResourceResponse("image/png", "binary", new ByteArrayInputStream(png));
        if (Build.VERSION.SDK_INT >= 21) {
            Map<String, String> hdrs = new HashMap<String, String>(2);
            hdrs.put("Access-Control-Allow-Origin", "*");
            hdrs.put("Cache-Control", "max-age=600");
            resp.setResponseHeaders(hdrs);
            resp.setStatusCodeAndReasonPhrase(200, "OK");
        }
        return resp;
    }

    public void setListener(Listener l) { listener = l; }
    public boolean hasFix() { return hasFix && !Double.isNaN(carLat); }
    public boolean isFollowing() { return following && !overviewMode; }
    public boolean isOverview() { return overviewMode; }
    public boolean isOfflineMode() { return offlineMode; }
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
        setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF06080E);
        if (web != null) web.setBackgroundColor(light ? 0xFFE8EBF0 : 0xFF06080E);
        String hex = String.format(Locale.US, "#%06X", redColor & 0xFFFFFF);
        execJs("window.StradaMap && StradaMap.setTheme(" + (light ? "true" : "false") + ", " + JSONObject.quote(hex) + ");");
    }

    public void setBrightness(int b) {
        bright = Math.max(0, Math.min(2, b));
        execJs("window.StradaMap && StradaMap.setBrightness(" + bright + ");");
    }

    public void setOfflineMode(boolean offline) {
        offlineMode = offline;
        execJs("window.StradaMap && StradaMap.setOfflineMode(" + (offline ? "true" : "false") + ", false);");
    }

    public void clearOfflineCache() {
        offlineTileCache.evictAll();
        if (offlineMode) {
            execJs("window.StradaMap && StradaMap.setOfflineMode(true, true);");
        }
    }

    public void onTiltConfigChanged() {
        LiveMapView.view3d = true;
        view3d = true;
        tiltAngle = LiveMapView.tiltAngle >= 48 ? 60 : 30;
        execJs("window.StradaMap && StradaMap.setTiltConfig(true, " + tiltAngle + ");");
    }

    public void setAutoZoom(boolean enabled) {
        autoZoom = enabled;
        execJs("window.StradaMap && StradaMap.setAutoZoom(" + (enabled ? "true" : "false") + ");");
    }

    public void setHeadingUp(boolean hu) {
        headingUp = hu;
        execJs("window.StradaMap && StradaMap.setHeadingUp(" + (hu ? "true" : "false") + ");");
    }

    public void setNavActive(boolean active) {
        navActive = active;
        execJs("window.StradaMap && StradaMap.setNavActive(" + (active ? "true" : "false") + ");");
    }

    public void startNavCamera() {
        startNavCamera(tiltAngle >= 48 ? 60 : 30, autoZoom);
    }

    public void startNavCamera(int pitchDeg, boolean enableAutoZoom) {
        navActive = true;
        view3d = true;
        tiltAngle = pitchDeg >= 48 ? 60 : 30;
        autoZoom = enableAutoZoom;
        headingUp = true;
        following = true;
        overviewMode = false;
        execJs("window.StradaMap && StradaMap.startNavCamera(" + tiltAngle + ", " + (autoZoom ? "true" : "false") + ");");
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

    public void setRoadAlerts(List<RoadAlerts.AlertItem> alerts, List<RoadAlerts.JamSegment> jams) {
        try {
            JSONArray aArr = new JSONArray();
            if (alerts != null) {
                int maxA = Math.min(alerts.size(), 250);
                for (int i = 0; i < maxA; i++) {
                    RoadAlerts.AlertItem a = alerts.get(i);
                    if (a == null || Double.isNaN(a.lat) || Double.isNaN(a.lon)) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", a.id != null ? a.id : "");
                    o.put("lat", a.lat);
                    o.put("lon", a.lon);
                    o.put("icon", RoadAlerts.iconKeyForAlert(a));
                    o.put("cat", a.category != null ? a.category : "emergency");
                    o.put("title", a.title != null ? a.title : "");
                    aArr.put(o);
                }
            }
            JSONArray jArr = new JSONArray();
            if (jams != null) {
                int maxJ = Math.min(jams.size(), 150);
                for (int i = 0; i < maxJ; i++) {
                    RoadAlerts.JamSegment seg = jams.get(i);
                    if (seg == null || seg.pts == null || seg.pts.length < 4) continue;
                    JSONObject jo = new JSONObject();
                    jo.put("level", seg.level);
                    jo.put("street", seg.street != null ? seg.street : "");
                    jo.put("pts", ptsArray(seg.pts));
                    jArr.put(jo);
                }
            }
            pendingAlertsJson = aArr.toString();
            pendingJamsJson = jArr.toString();
            pushRoadAlerts();
        } catch (Throwable ignored) { }
    }

    private void pushRoadAlerts() {
        execJs("window.StradaMap && StradaMap.setRoadHazards(" + pendingAlertsJson + ", " + pendingJamsJson + ");");
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
        offlineTileCache.evictAll();
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
                    if (destroyed) return;
                    ready = true;
                    syncReadyState();
                }
            });
        }

        @JavascriptInterface
        public void onEngineFailed(final String reason) {
            ui.post(new Runnable() {
                public void run() {
                    if (!destroyed && !offlineMode) {
                        setOfflineMode(true);
                    }
                }
            });
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

        @JavascriptInterface
        public void onHazardTap(final String alertId) {
            ui.post(new Runnable() {
                public void run() {
                    if (!destroyed && listener != null) listener.onHazardTap(alertId);
                }
            });
        }
    }

    private void syncReadyState() {
        setTheme(lightMode, accentColor);
        setBrightness(bright);
        setOfflineMode(offlineMode);
        execJs(String.format(Locale.US, "window.StradaMap && StradaMap.setInitialCenter(%.6f, %.6f, 15.0);", centerLat, centerLon));
        setAutoZoom(autoZoom);
        onTiltConfigChanged();
        setHeadingUp(headingUp);
        if (navActive) {
            startNavCamera(tiltAngle, autoZoom);
        } else {
            setNavActive(false);
        }
        if (hasFix) pushPosition();
        if (previewPlace != null) setPreviewPlace(previewPlace);
        syncRoute();
        pushRoadAlerts();
        pushWaveAlert();
        if (overviewMode) setOverview(true);
    }
}
