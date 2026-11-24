package com.stradaos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Unified Malaysia-ready Road Alert Engine combining Waze for Cities JSON/GeoJSON data feeds
 * with Cloud Firestore crowdsourced driver reports, local TTL caching, and free-drive + navigation proximity warnings.
 */
public class RoadAlerts {

    public static final String CAT_POLICE = "police";
    public static final String CAT_ACCIDENT = "accident";
    public static final String CAT_EMERGENCY = "emergency";

    public static final int COLOR_POLICE = 0xFF2563EB;
    public static final int COLOR_ACCIDENT = 0xFFEF4444;
    public static final int COLOR_EMERGENCY = 0xFFF59E0B;
    public static final int COLOR_FLOOD = 0xFF06B6D4;
    public static final int COLOR_CLOSURE = 0xFFDC2626;

    private static final String CACHE_FILE = "waze_alerts_cache.json";
    private static final String DEFAULT_KEY_SENTINEL = "WAZE_CITIES_API_KEY_DEFAULT_VALUE";
    private static final long WAZE_DEFAULT_TTL_MS = 3L * 3600L * 1000L;
    private static final long ALERT_SPEAK_COOLDOWN_MS = 4L * 60L * 1000L;

    public static class AlertItem {
        public String id;
        public String source;      // "waze-scraper", "waze", "geojson", "community"
        public String category;    // "police", "accident", "emergency"
        public String subtype;     // "roadblock", "speed_trap", "accident_minor", "accident_major", "pothole", "flood", "construction", "stalled", "hazard", "road_closed", "sos"
        public String title;
        public String street;
        public String description;
        public String country;
        public String ownerUid;
        public double lat;
        public double lon;
        public double bearingDeg = Double.NaN;
        public int confirmCount = 1;
        public int dismissCount = 0;
        public int reliability = 5;
        public int delayS = 0;
        public long createdAtMs;
        public long expiresAtMs;
        public boolean cleared;
    }

    public static class JamSegment {
        public String id;
        public String street;
        public String severity;    // "moderate", "heavy", "standstill"
        public int level;
        public int delayS;
        public double speedKmh = -1;
        public double[] pts;       // lat0, lon0, lat1, lon1...
    }

    public static class Preset {
        public final String category;
        public final String subtype;
        public final String tagLabel;
        public final String title;

        public Preset(String category, String subtype, String tagLabel, String title) {
            this.category = category;
            this.subtype = subtype;
            this.tagLabel = tagLabel;
            this.title = title;
        }
    }

    public static final Preset[] MALAYSIA_PRESETS = new Preset[]{
        new Preset(CAT_POLICE, "roadblock", "Roadblock (SJR)", "Police roadblock"),
        new Preset(CAT_POLICE, "speed_trap", "Speed Trap", "Speed trap / enforcement"),
        new Preset(CAT_ACCIDENT, "accident_major", "Accident", "Traffic accident"),
        new Preset(CAT_EMERGENCY, "flood", "Flash Flood", "Flash flood"),
        new Preset(CAT_EMERGENCY, "hazard", "Road Hazard", "Road hazard"),
        new Preset(CAT_EMERGENCY, "road_closed", "Road Closed", "Emergency / road closed")
    };

    public static class FeedParseResult {
        public String format = "";
        public final List<AlertItem> alerts = new ArrayList<AlertItem>();
        public final List<JamSegment> jams = new ArrayList<JamSegment>();
        public String error;
    }

    public static class StatusSnapshot {
        public String format = "";
        public long lastSyncAtMs;
        public int feedAlerts;
        public int feedJams;
        public int cloudAlerts;
        public String lastError = "";
        public boolean syncing;
    }

    public interface AlertsListener {
        void onAlertsChanged();
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static final List<AlertItem> feedAlerts = new ArrayList<AlertItem>();
    private static final List<JamSegment> feedJams = new ArrayList<JamSegment>();
    private static final List<AlertItem> cloudAlerts = new ArrayList<AlertItem>();
    private static final List<AlertItem> localPending = new ArrayList<AlertItem>();
    private static final HashSet<String> hiddenIds = new HashSet<String>();
    private static final HashMap<String, Long> spokenAt = new HashMap<String, Long>();

    private static final StatusSnapshot status = new StatusSnapshot();
    private static volatile boolean cacheLoaded;
    private static volatile boolean pollerStarted;
    private static AlertsListener listener;

    public static volatile AlertItem nearbyAlert;
    public static volatile double nearbyAlertDistM = -1;
    private static volatile double lastAnchorLat = Double.NaN;
    private static volatile double lastAnchorLon = Double.NaN;

    private static Context appCtx;
    private static final Runnable pollRunnable = new Runnable() {
        public void run() {
            if (appCtx == null) return;
            Context c = appCtx;
            SharedPreferences sp = prefs(c);
            int mins = sp.getInt("waze_poll_minutes", 2);
            if (isAlertsEnabled(c) && mins > 0 && sp.getString("waze_feed_url", "").trim().length() > 0) {
                syncWazeFeedAsync(c, false, lastAnchorLat, lastAnchorLon, null);
            }
            scheduleNextPoll(c);
        }
    };

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public static void updateAnchor(double lat, double lon) {
        if (validLatLon(lat, lon)) {
            lastAnchorLat = lat;
            lastAnchorLon = lon;
        }
    }

    public static void init(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        loadCacheOnce(appCtx);
        if (!validLatLon(lastAnchorLat, lastAnchorLon)) {
            SharedPreferences sp = prefs(appCtx);
            if (sp.contains("last_lat")) {
                double la = sp.getFloat("last_lat", 0f);
                double lo = sp.getFloat("last_lon", 0f);
                if (validLatLon(la, lo)) {
                    lastAnchorLat = la;
                    lastAnchorLon = lo;
                }
            }
        }
        if (!pollerStarted) {
            pollerStarted = true;
            scheduleNextPoll(appCtx);
            SharedPreferences sp = prefs(appCtx);
            if (isAlertsEnabled(appCtx) && sp.getString("waze_feed_url", "").trim().length() > 0) {
                syncWazeFeedAsync(appCtx, false, lastAnchorLat, lastAnchorLon, null);
            }
        }
    }

    public static void setListener(AlertsListener l) {
        listener = l;
    }

    public static void notifyChanged() {
        final AlertsListener l = listener;
        if (l != null) {
            UI.post(new Runnable() {
                public void run() {
                    if (listener != null) listener.onAlertsChanged();
                }
            });
        }
    }

    public static boolean isAlertsEnabled(Context c) {
        if (c == null) return true;
        return prefs(c).getBoolean("alerts_enabled", true);
    }

    public static boolean isVoiceEnabled(Context c) {
        if (c == null) return true;
        return prefs(c).getBoolean("alerts_voice_enabled", true);
    }

    public static boolean hasConfiguredFeed(Context c) {
        if (c == null) return false;
        return prefs(c).getString("waze_feed_url", "").trim().length() > 0;
    }

    public static boolean isCategoryEnabled(Context c, String cat) {
        if (c == null || cat == null) return true;
        SharedPreferences sp = prefs(c);
        if (CAT_POLICE.equals(cat)) return sp.getBoolean("alert_cat_police", true);
        if (CAT_ACCIDENT.equals(cat)) return sp.getBoolean("alert_cat_accident", true);
        if (CAT_EMERGENCY.equals(cat)) return sp.getBoolean("alert_cat_emergency", true);
        return true;
    }

    public static String buildConfigWazeApiKey() {
        try {
            String k = BuildConfig.WAZE_CITIES_API_KEY;
            if (k != null) {
                k = k.trim();
                if (k.length() > 0 && !DEFAULT_KEY_SENTINEL.equals(k)) return k;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    public static String effectiveWazeApiKey(Context c) {
        if (c != null) {
            String custom = prefs(c).getString("waze_api_key", "").trim();
            if (custom.length() > 0) return custom;
        }
        return buildConfigWazeApiKey();
    }

    public static String apiKeySourceLabel(Context c) {
        if (c != null && prefs(c).getString("waze_api_key", "").trim().length() > 0) {
            return I18n.t("Clave personalizada configurada");
        }
        if (buildConfigWazeApiKey().length() > 0) {
            return I18n.t("Configurada desde AI Studio Secrets");
        }
        return I18n.t("Sin clave configurada");
    }

    public static StatusSnapshot getStatus() {
        synchronized (LOCK) {
            StatusSnapshot snap = new StatusSnapshot();
            snap.format = status.format;
            snap.lastSyncAtMs = status.lastSyncAtMs;
            snap.feedAlerts = status.feedAlerts;
            snap.feedJams = status.feedJams;
            snap.cloudAlerts = status.cloudAlerts;
            snap.lastError = status.lastError;
            snap.syncing = status.syncing;
            return snap;
        }
    }

    public static void scheduleNextPoll(Context c) {
        if (c == null) return;
        UI.removeCallbacks(pollRunnable);
        int mins = prefs(c).getInt("waze_poll_minutes", 2);
        if (mins > 0) {
            UI.postDelayed(pollRunnable, Math.max(30000L, mins * 60000L));
        }
    }

    public static void setCloudAlerts(List<AlertItem> items) {
        synchronized (LOCK) {
            cloudAlerts.clear();
            if (items != null) cloudAlerts.addAll(items);
            status.cloudAlerts = cloudAlerts.size();
            // Clear pending local items once mirrored from Firestore
            if (!localPending.isEmpty()) {
                long now = System.currentTimeMillis();
                for (int i = localPending.size() - 1; i >= 0; i--) {
                    AlertItem p = localPending.get(i);
                    boolean matched = now - p.createdAtMs > 20000L;
                    if (!matched) {
                        for (int j = 0; j < cloudAlerts.size(); j++) {
                            AlertItem ca = cloudAlerts.get(j);
                            if (ca.id != null && ca.id.equals(p.id)) {
                                matched = true;
                                break;
                            }
                        }
                    }
                    if (matched) localPending.remove(i);
                }
            }
        }
        notifyChanged();
    }

    public static void addLocalPending(AlertItem item) {
        if (item == null) return;
        synchronized (LOCK) {
            localPending.add(item);
        }
        notifyChanged();
    }

    public static void dismissAlertLocally(String alertId) {
        if (alertId == null) return;
        synchronized (LOCK) {
            hiddenIds.add(alertId);
            if (nearbyAlert != null && alertId.equals(nearbyAlert.id)) {
                nearbyAlert = null;
                nearbyAlertDistM = -1;
            }
        }
        notifyChanged();
    }

    public static void boostLocalConfirmation(String alertId) {
        if (alertId == null) return;
        synchronized (LOCK) {
            for (int i = 0; i < feedAlerts.size(); i++) {
                AlertItem a = feedAlerts.get(i);
                if (alertId.equals(a.id)) a.confirmCount++;
            }
            for (int i = 0; i < cloudAlerts.size(); i++) {
                AlertItem a = cloudAlerts.get(i);
                if (alertId.equals(a.id)) a.confirmCount++;
            }
        }
        notifyChanged();
    }

    public static List<AlertItem> getActiveAlerts(Context c) {
        loadCacheOnce(c);
        List<AlertItem> out = new ArrayList<AlertItem>();
        if (!isAlertsEnabled(c)) return out;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            addFiltered(c, cloudAlerts, now, out);
            addFiltered(c, localPending, now, out);
            addFiltered(c, feedAlerts, now, out);
        }
        return out;
    }

    public static List<JamSegment> getActiveJams(Context c) {
        loadCacheOnce(c);
        List<JamSegment> out = new ArrayList<JamSegment>();
        if (!isAlertsEnabled(c)) return out;
        synchronized (LOCK) {
            out.addAll(feedJams);
        }
        return out;
    }

    public static AlertItem findById(String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            for (int i = 0; i < cloudAlerts.size(); i++) {
                if (id.equals(cloudAlerts.get(i).id)) return cloudAlerts.get(i);
            }
            for (int i = 0; i < localPending.size(); i++) {
                if (id.equals(localPending.get(i).id)) return localPending.get(i);
            }
            for (int i = 0; i < feedAlerts.size(); i++) {
                if (id.equals(feedAlerts.get(i).id)) return feedAlerts.get(i);
            }
        }
        return null;
    }

    private static void addFiltered(Context c, List<AlertItem> src, long now, List<AlertItem> out) {
        for (int i = 0; i < src.size(); i++) {
            AlertItem a = src.get(i);
            if (a == null || a.cleared) continue;
            if (a.id != null && hiddenIds.contains(a.id)) continue;
            if (a.expiresAtMs > 0 && now > a.expiresAtMs) continue;
            if (!isCategoryEnabled(c, a.category)) continue;
            if (Double.isNaN(a.lat) || Double.isNaN(a.lon)) continue;
            // Deduplicate against already added items (~35m same category)
            boolean dup = false;
            for (int k = 0; k < out.size(); k++) {
                AlertItem ex = out.get(k);
                if (ex.id != null && ex.id.equals(a.id)) { dup = true; break; }
                if (ex.category != null && ex.category.equals(a.category)
                        && distM(ex.lat, ex.lon, a.lat, a.lon) < 35.0) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(a);
        }
    }

    public static int colorForCategory(String category) {
        if (CAT_POLICE.equals(category)) return COLOR_POLICE;
        if (CAT_ACCIDENT.equals(category)) return COLOR_ACCIDENT;
        return COLOR_EMERGENCY;
    }

    public static int colorForAlert(AlertItem a) {
        if (a == null) return COLOR_EMERGENCY;
        if (CAT_POLICE.equals(a.category)) return COLOR_POLICE;
        if (CAT_ACCIDENT.equals(a.category)) return COLOR_ACCIDENT;
        if ("flood".equals(a.subtype)) return COLOR_FLOOD;
        if ("road_closed".equals(a.subtype)) return COLOR_CLOSURE;
        return COLOR_EMERGENCY;
    }

    public static String iconKeyForAlert(AlertItem a) {
        if (a == null) return "hazard-hazard";
        if (CAT_POLICE.equals(a.category)) return "hazard-police";
        if (CAT_ACCIDENT.equals(a.category)) return "hazard-accident";
        if ("flood".equals(a.subtype)) return "hazard-flood";
        if ("road_closed".equals(a.subtype)) return "hazard-closure";
        if ("pothole".equals(a.subtype)) return "hazard-pothole";
        return "hazard-hazard";
    }

    public static String badgeGlyph(AlertItem a) {
        if (a == null) return "!";
        if (CAT_POLICE.equals(a.category)) return "P";
        if (CAT_ACCIDENT.equals(a.category)) return "!";
        if ("flood".equals(a.subtype)) return "~";
        if ("road_closed".equals(a.subtype)) return "X";
        if ("pothole".equals(a.subtype)) return "O";
        return "!";
    }

    public static String categoryLabel(String category) {
        if (CAT_POLICE.equals(category)) return I18n.t("Control policial");
        if (CAT_ACCIDENT.equals(category)) return I18n.t("Accidente");
        return I18n.t("Emergencia / peligro");
    }

    public static String displayTitle(AlertItem a) {
        if (a == null) return "";
        if ("roadblock".equals(a.subtype)) return I18n.t("Control policial (SJR)");
        if ("speed_trap".equals(a.subtype)) return I18n.t("Radar / control móvil");
        if ("accident_major".equals(a.subtype)) return I18n.t("Accidente grave");
        if ("accident_minor".equals(a.subtype)) return I18n.t("Accidente");
        if ("pothole".equals(a.subtype)) return I18n.t("Bache en la vía");
        if ("flood".equals(a.subtype)) return I18n.t("Inundación en la vía");
        if ("road_closed".equals(a.subtype)) return I18n.t("Vía cerrada");
        if ("construction".equals(a.subtype)) return I18n.t("Obras en la vía");
        if ("stalled".equals(a.subtype)) return I18n.t("Vehículo detenido");
        if ("sos".equals(a.subtype)) return I18n.t("Emergencia vial");
        if ("hazard".equals(a.subtype)) return I18n.t("Peligro en la vía");
        return categoryLabel(a.category);
    }

    public static String spokenWarning(AlertItem a, double distanceM, boolean mph) {
        String title = displayTitle(a);
        String distText = Nav.fmtStepDist(distanceM, mph);
        if (a.street != null && a.street.trim().length() > 0) {
            return title + ", " + distText + ", " + a.street.trim();
        }
        return title + ", " + distText;
    }

    // ───────────────────────── Waze Scraper & GeoJSON Sync ─────────────────────────

    public interface SyncCb {
        void onComplete(StatusSnapshot snapshot);
    }

    public static boolean isScraperBaseUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String u = rawUrl.trim().toLowerCase(Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".json") || path.endsWith(".geojson")) return false;
        if (u.contains("{apikey}") || u.contains("{key}") || u.contains("{token}")) return false;
        if (path.contains("/alerts") || path.contains("/jams") || u.contains("location=")) return false;
        return true;
    }

    public static void syncWazeFeedAsync(Context context, boolean userInitiated, SyncCb cb) {
        syncWazeFeedAsync(context, userInitiated, lastAnchorLat, lastAnchorLon, cb);
    }

    public static void syncWazeFeedAsync(final Context context, final boolean userInitiated,
                                          final double anchorLat, final double anchorLon, final SyncCb cb) {
        if (context == null) return;
        final Context c = context.getApplicationContext();
        updateAnchor(anchorLat, anchorLon);
        final String feedUrl = prefs(c).getString("waze_feed_url", "").trim();
        if (feedUrl.length() == 0) {
            synchronized (LOCK) {
                status.syncing = false;
                status.lastError = userInitiated ? I18n.t("Ingresa la URL del servidor o feed GeoJSON") : "";
            }
            if (cb != null) UI.post(new Runnable() { public void run() { cb.onComplete(getStatus()); } });
            return;
        }
        synchronized (LOCK) {
            if (status.syncing && !userInitiated) return;
            status.syncing = true;
            status.lastError = "";
        }
        new Thread(new Runnable() {
            public void run() {
                Net.init(c);
                try {
                    final String apiKey = effectiveWazeApiKey(c);
                    final String mode = prefs(c).getString("waze_api_key_mode", "auto");
                    double qLat = anchorLat;
                    double qLon = anchorLon;
                    if (!validLatLon(qLat, qLon)) {
                        qLat = lastAnchorLat;
                        qLon = lastAnchorLon;
                    }
                    if (!validLatLon(qLat, qLon)) {
                        SharedPreferences sp = prefs(c);
                        if (sp.contains("last_lat")) {
                            qLat = sp.getFloat("last_lat", 0f);
                            qLon = sp.getFloat("last_lon", 0f);
                        }
                    }
                    if (!validLatLon(qLat, qLon)) {
                        qLat = 4.7110;
                        qLon = -74.0721;
                    }
                    final String locParam = String.format(Locale.US, "%.5f,%.5f", qLat, qLon);

                    FeedParseResult finalRes;
                    String cachePayload;

                    if (isScraperBaseUrl(feedUrl)) {
                        String base = feedUrl;
                        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                        final String alertsUrl = appendQueryParam(base + "/alerts/search", "location", locParam);
                        final String jamsUrl = appendQueryParam(base + "/jams/search", "location", locParam);
                        final String[] bodies = new String[2];
                        final String[] errs = new String[2];
                        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
                        new Thread(new Runnable() {
                            public void run() {
                                try { bodies[0] = fetchWithAuth(alertsUrl, apiKey, mode); }
                                catch (Throwable e) { errs[0] = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
                                finally { latch.countDown(); }
                            }
                        }).start();
                        new Thread(new Runnable() {
                            public void run() {
                                try { bodies[1] = fetchWithAuth(jamsUrl, apiKey, mode); }
                                catch (Throwable e) { errs[1] = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(); }
                                finally { latch.countDown(); }
                            }
                        }).start();
                        latch.await();

                        if (bodies[0] == null && bodies[1] == null) {
                            throw new Exception(errs[0] != null ? errs[0] : (errs[1] != null ? errs[1] : "Connection failed"));
                        }
                        JSONObject wrap = new JSONObject();
                        if (bodies[0] != null) wrap.put("alertsRaw", bodies[0]);
                        if (bodies[1] != null) wrap.put("jamsRaw", bodies[1]);
                        cachePayload = wrap.toString();
                        finalRes = parseScraperPair(bodies[0], bodies[1]);
                    } else {
                        String reqUrl = feedUrl;
                        String low = reqUrl.toLowerCase(Locale.US);
                        if ((low.contains("/alerts/search") || low.contains("/jams/search")) && !low.contains("location=")) {
                            reqUrl = appendQueryParam(reqUrl, "location", locParam);
                        }
                        String payload = fetchWithAuth(reqUrl, apiKey, mode);
                        cachePayload = payload;
                        finalRes = parseFeedPayload(payload, reqUrl);
                    }

                    if (finalRes.error != null) {
                        synchronized (LOCK) {
                            status.syncing = false;
                            status.lastError = finalRes.error;
                        }
                    } else {
                        synchronized (LOCK) {
                            feedAlerts.clear();
                            feedAlerts.addAll(finalRes.alerts);
                            feedJams.clear();
                            feedJams.addAll(finalRes.jams);
                            status.format = finalRes.format;
                            status.feedAlerts = finalRes.alerts.size();
                            status.feedJams = finalRes.jams.size();
                            status.lastSyncAtMs = System.currentTimeMillis();
                            status.lastError = "";
                            status.syncing = false;
                        }
                        saveCache(c, finalRes.format, cachePayload);
                        notifyChanged();
                    }
                } catch (Throwable e) {
                    synchronized (LOCK) {
                        status.syncing = false;
                        status.lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    }
                }
                if (cb != null) {
                    final StatusSnapshot snap = getStatus();
                    UI.post(new Runnable() {
                        public void run() { cb.onComplete(snap); }
                    });
                }
            }
        }).start();
    }

    private static FeedParseResult parseScraperPair(String alertsRaw, String jamsRaw) {
        FeedParseResult merged = new FeedParseResult();
        merged.format = "waze-scraper";
        String errA = null, errJ = null;
        boolean anyOk = false;
        if (alertsRaw != null && alertsRaw.trim().length() > 0) {
            FeedParseResult ra = parseFeedPayload(alertsRaw, "alerts/search");
            if (ra.error == null) {
                anyOk = true;
                for (int i = 0; i < ra.alerts.size(); i++) ra.alerts.get(i).source = "waze-scraper";
                merged.alerts.addAll(ra.alerts);
                merged.jams.addAll(ra.jams);
            } else {
                errA = ra.error;
            }
        }
        if (jamsRaw != null && jamsRaw.trim().length() > 0) {
            FeedParseResult rj = parseFeedPayload(jamsRaw, "jams/search");
            if (rj.error == null) {
                anyOk = true;
                for (int i = 0; i < rj.alerts.size(); i++) rj.alerts.get(i).source = "waze-scraper";
                merged.alerts.addAll(rj.alerts);
                merged.jams.addAll(rj.jams);
            } else {
                errJ = rj.error;
            }
        }
        if (!anyOk) {
            merged.error = errA != null ? errA : (errJ != null ? errJ : I18n.t("Respuesta vacía del servidor"));
        }
        return merged;
    }

    private static String fetchWithAuth(String rawUrl, String apiKey, String mode) throws Exception {
        String reqUrl = applyUrlKeyTemplate(rawUrl, apiKey);
        boolean usedQuery = !reqUrl.equals(rawUrl)
                || reqUrl.contains("key=") || reqUrl.contains("apiKey=") || reqUrl.contains("api_key=");

        if ("query".equals(mode) && apiKey.length() > 0 && !usedQuery) {
            reqUrl = appendQueryParam(reqUrl, "apiKey", apiKey);
            usedQuery = true;
        }

        try {
            return httpGetText(reqUrl, apiKey, mode);
        } catch (HttpStatusException he) {
            if ((he.code == 401 || he.code == 403) && "auto".equals(mode) && apiKey.length() > 0 && !usedQuery) {
                String withParam = appendQueryParam(reqUrl, "apiKey", apiKey);
                return httpGetText(withParam, apiKey, mode);
            }
            throw he;
        }
    }

    private static class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String applyUrlKeyTemplate(String rawUrl, String apiKey) {
        if (apiKey == null || apiKey.length() == 0) return rawUrl;
        try {
            String enc = URLEncoder.encode(apiKey, "UTF-8");
            return rawUrl.replace("{apiKey}", enc).replace("{key}", enc).replace("{token}", enc);
        } catch (Throwable e) {
            return rawUrl;
        }
    }

    private static String appendQueryParam(String url, String name, String value) {
        try {
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + name + "=" + URLEncoder.encode(value, "UTF-8");
        } catch (Throwable e) {
            return url;
        }
    }

    private static String httpGetText(String url, String apiKey, String mode) throws Exception {
        HttpURLConnection conn = Net.open(url, 12000);
        conn.setRequestProperty("Accept", "application/geo+json, application/json, */*;q=0.8");
        if (apiKey != null && apiKey.length() > 0) {
            if ("bearer".equals(mode)) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            } else if (!"query".equals(mode)) {
                conn.setRequestProperty("X-API-Key", apiKey);
                conn.setRequestProperty("x-waze-api-key", apiKey);
            }
        }
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpStatusException(code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 6 * 1024 * 1024) throw new Exception("Feed payload too large");
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    // ───────────────────────── JSON & GeoJSON Parser ─────────────────────────

    public static FeedParseResult parseFeedPayload(String raw, String feedUrl) {
        FeedParseResult out = new FeedParseResult();
        if (raw == null || raw.trim().length() == 0) {
            out.error = I18n.t("Respuesta vacía del servidor");
            return out;
        }
        String text = raw.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        long now = System.currentTimeMillis();

        try {
            if (text.startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                if (looksLikeJamsArray(arr, feedUrl)) {
                    out.format = "waze-scraper";
                    parseWazeJamsArray(arr, now, out);
                } else {
                    out.format = "Waze JSON";
                    parseWazeAlertsArray(arr, now, out.alerts);
                }
                return out;
            }
            JSONObject root = new JSONObject(text);
            if (root.has("alertsRaw") || root.has("jamsRaw")) {
                return parseScraperPair(root.optString("alertsRaw", null), root.optString("jamsRaw", null));
            }
            // Check wrapped payloads (e.g. waze-scraper or envelope responses)
            if (root.has("data") && root.optJSONArray("data") != null) {
                JSONArray dataArr = root.optJSONArray("data");
                if (looksLikeJamsArray(dataArr, feedUrl)) {
                    out.format = "waze-scraper";
                    parseWazeJamsArray(dataArr, now, out);
                } else {
                    out.format = "waze-scraper";
                    parseWazeAlertsArray(dataArr, now, out.alerts);
                }
                return out;
            }
            if (root.has("data") && root.optJSONObject("data") != null) {
                JSONObject data = root.optJSONObject("data");
                if (data.has("features") || "FeatureCollection".equalsIgnoreCase(data.optString("type"))
                        || data.has("alerts") || data.has("jams")) {
                    root = data;
                }
            }

            String rootType = root.optString("type", "");
            if ("FeatureCollection".equalsIgnoreCase(rootType) || root.has("features")) {
                out.format = "GeoJSON";
                JSONArray features = root.optJSONArray("features");
                if (features != null) {
                    parseGeoJsonFeatures(features, now, out);
                }
                return out;
            }
            if ("Feature".equalsIgnoreCase(rootType) && root.has("geometry")) {
                out.format = "GeoJSON";
                JSONArray single = new JSONArray();
                single.put(root);
                parseGeoJsonFeatures(single, now, out);
                return out;
            }

            if (root.has("alerts") || root.has("jams")) {
                out.format = (feedUrl != null && (feedUrl.contains("/alerts/search") || feedUrl.contains("/jams/search")))
                        ? "waze-scraper" : "Waze JSON";
                JSONArray alerts = root.optJSONArray("alerts");
                if (alerts != null) parseWazeAlertsArray(alerts, now, out.alerts);
                JSONArray jams = root.optJSONArray("jams");
                if (jams != null) parseWazeJamsArray(jams, now, out);
                return out;
            }

            // Fallback generic array properties (e.g. incidents[], results[])
            JSONArray incArr = root.optJSONArray("incidents");
            if (incArr == null) incArr = root.optJSONArray("results");
            if (incArr != null) {
                out.format = looksLikeJamsArray(incArr, feedUrl) ? "waze-scraper" : "JSON Incidents";
                if (looksLikeJamsArray(incArr, feedUrl)) parseWazeJamsArray(incArr, now, out);
                else parseWazeAlertsArray(incArr, now, out.alerts);
                return out;
            }

            out.error = I18n.t("Formato JSON/GeoJSON no reconocido");
        } catch (Throwable e) {
            out.error = "Parse error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return out;
    }

    private static boolean looksLikeJamsArray(JSONArray arr, String feedUrl) {
        if (feedUrl != null && feedUrl.toLowerCase(Locale.US).contains("/jams")) return true;
        if (arr == null || arr.length() == 0) return false;
        JSONObject first = arr.optJSONObject(0);
        return first != null && (first.has("line") || first.has("speed_kmh") || first.has("length_meters") || first.has("is_blocked"));
    }

    private static void parseWazeAlertsArray(JSONArray arr, long now, List<AlertItem> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            double lat = Double.NaN, lon = Double.NaN;
            JSONObject loc = o.optJSONObject("location");
            if (loc != null) {
                lat = loc.optDouble("y", loc.optDouble("lat", loc.optDouble("latitude", Double.NaN)));
                lon = loc.optDouble("x", loc.optDouble("lon", loc.optDouble("lng", loc.optDouble("longitude", Double.NaN))));
            } else {
                lat = o.optDouble("lat", o.optDouble("latitude", o.optDouble("y", Double.NaN)));
                lon = o.optDouble("lon", o.optDouble("lng", o.optDouble("longitude", o.optDouble("x", Double.NaN))));
            }
            if (!validLatLon(lat, lon)) continue;

            JSONObject cause = o.optJSONObject("cause");
            String rawType = firstNonEmpty(o, "type", "alert_type", "category");
            String rawSub = firstNonEmpty(o, "subtype", "sub_type", "hazard_type");
            String desc = firstNonEmpty(o, "reportDescription", "description", "comments");
            if (cause != null) {
                if (rawType.length() == 0) rawType = firstNonEmpty(cause, "type", "category");
                if (rawSub.length() == 0) rawSub = firstNonEmpty(cause, "subtype", "sub_type");
                String cDesc = firstNonEmpty(cause, "description", "reportDescription");
                if (desc.length() == 0) desc = cDesc;
                else if (cDesc.length() > 0 && !desc.contains(cDesc)) desc = desc + " · " + cDesc;
            }
            String mappedCat = classifyCategory(rawType, rawSub, desc);
            if (mappedCat == null) continue;

            AlertItem item = new AlertItem();
            String rawId = firstNonEmpty(o, "uuid", "id", "alert_id");
            item.id = rawId.length() > 0 ? "waze_" + rawId : ("waze_" + i + "_" + Math.round(lat * 10000));
            item.source = "waze";
            item.category = mappedCat;
            item.subtype = classifySubtype(mappedCat, rawType, rawSub, desc);
            item.street = firstNonEmpty(o, "street", "roadName", "road_name", "city");
            item.description = desc;
            item.country = firstNonEmpty(o, "country", "countryCode");
            item.lat = lat;
            item.lon = lon;
            item.bearingDeg = o.has("magvar") ? o.optDouble("magvar", Double.NaN) : Double.NaN;
            int thumbs = o.optInt("nThumbsUp", o.optInt("num_thumbs_up", o.optInt("thumbs_up", 0)));
            item.confirmCount = Math.max(1, thumbs + 1);
            item.reliability = o.optInt("reliability", o.optInt("confidence", 5));
            long pub = parseEpochMs(o.opt("pubMillis"));
            if (pub <= 0) pub = parseEpochMs(o.opt("published_at"));
            if (pub <= 0) pub = parseEpochMs(o.opt("timestamp"));
            item.createdAtMs = pub > 0 ? pub : now;
            item.expiresAtMs = now + WAZE_DEFAULT_TTL_MS;
            item.title = displayTitle(item);
            out.add(item);
        }
    }

    private static void parseWazeJamsArray(JSONArray jams, long now, FeedParseResult out) {
        for (int i = 0; i < jams.length(); i++) {
            JSONObject j = jams.optJSONObject(i);
            if (j == null) continue;
            JSONArray line = j.optJSONArray("line");
            if (line == null || line.length() < 2) continue;
            double[] pts = new double[line.length() * 2];
            int k = 0;
            for (int p = 0; p < line.length(); p++) {
                JSONObject pt = line.optJSONObject(p);
                double lat = Double.NaN, lon = Double.NaN;
                if (pt != null) {
                    lat = pt.optDouble("y", pt.optDouble("lat", pt.optDouble("latitude", Double.NaN)));
                    lon = pt.optDouble("x", pt.optDouble("lon", pt.optDouble("lng", pt.optDouble("longitude", Double.NaN))));
                } else {
                    JSONArray arrPt = line.optJSONArray(p);
                    if (arrPt != null && arrPt.length() >= 2) {
                        lon = arrPt.optDouble(0, Double.NaN);
                        lat = arrPt.optDouble(1, Double.NaN);
                    }
                }
                if (!validLatLon(lat, lon)) continue;
                pts[k++] = lat;
                pts[k++] = lon;
            }
            if (k < 4) continue;
            JamSegment seg = new JamSegment();
            seg.id = firstNonEmpty(j, "uuid", "id");
            if (seg.id.length() == 0) seg.id = "jam_" + i;
            seg.street = firstNonEmpty(j, "street", "road_name", "end_node");
            boolean isBlocked = j.optBoolean("is_blocked", false);
            int level = j.optInt("level", -1);
            String rawSev = firstNonEmpty(j, "severity");
            if (level < 0 && rawSev.length() > 0) {
                String uSev = rawSev.toUpperCase(Locale.US);
                if (uSev.contains("BLOCK") || uSev.contains("STAND") || uSev.contains("SEVERE")) level = 5;
                else if (uSev.contains("HIGH") || uSev.contains("HEAVY")) level = 4;
                else if (uSev.contains("MOD") || uSev.contains("MED")) level = 3;
                else level = 2;
            }
            if (isBlocked && level < 5) level = 5;
            if (level < 0) level = 3;
            seg.level = level;
            seg.delayS = j.optInt("delay", j.optInt("delay_seconds", 0));
            seg.speedKmh = j.optDouble("speed_kmh", j.optDouble("speedKMH", -1.0));
            seg.severity = seg.level >= 4 ? "standstill" : seg.level == 3 ? "heavy" : "moderate";
            seg.pts = Arrays.copyOf(pts, k);
            out.jams.add(seg);

            // Surface extreme road-blocked jams even if no separate alert existed
            String blocking = j.optString("blockingAlertUuid", "");
            if ((isBlocked || seg.level >= 5) && blocking.length() == 0) {
                AlertItem inc = new AlertItem();
                inc.id = "jam_block_" + seg.id;
                inc.source = "waze";
                inc.category = CAT_EMERGENCY;
                inc.subtype = "road_closed";
                inc.street = seg.street;
                inc.description = seg.street.length() > 0 ? seg.street : I18n.t("Congestión severa / bloqueo");
                inc.lat = seg.pts[0];
                inc.lon = seg.pts[1];
                inc.delayS = seg.delayS;
                inc.createdAtMs = now;
                inc.expiresAtMs = now + WAZE_DEFAULT_TTL_MS;
                inc.title = displayTitle(inc);
                out.alerts.add(inc);
            }
        }
    }

    private static void parseGeoJsonFeatures(JSONArray features, long now, FeedParseResult out) {
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            if (f == null) continue;
            JSONObject geom = f.optJSONObject("geometry");
            if (geom == null) continue;
            JSONObject props = f.optJSONObject("properties");
            if (props == null) props = new JSONObject();

            String gType = geom.optString("type", "");
            JSONArray coords = geom.optJSONArray("coordinates");
            if (coords == null) continue;

            if ("Point".equalsIgnoreCase(gType)) {
                double lon = coords.optDouble(0, Double.NaN);
                double lat = coords.optDouble(1, Double.NaN);
                if (!validLatLon(lat, lon)) continue;
                AlertItem item = alertFromGeoProps(f, props, lat, lon, now, i);
                if (item != null) out.alerts.add(item);
            } else if ("LineString".equalsIgnoreCase(gType)) {
                double[] pts = coordsArrayToLatLon(coords);
                if (pts.length >= 4) {
                    maybeAddGeoJamOrAlert(f, props, pts, now, i, out);
                }
            } else if ("MultiLineString".equalsIgnoreCase(gType) || "Polygon".equalsIgnoreCase(gType)) {
                JSONArray first = coords.optJSONArray(0);
                if (first != null) {
                    double[] pts = coordsArrayToLatLon(first);
                    if (pts.length >= 4) {
                        maybeAddGeoJamOrAlert(f, props, pts, now, i, out);
                    }
                }
            }
        }
    }

    private static void maybeAddGeoJamOrAlert(JSONObject feature, JSONObject props, double[] pts,
                                              long now, int idx, FeedParseResult out) {
        int level = props.optInt("level", props.optInt("severity", 0));
        int delay = props.optInt("delay", props.optInt("delaySeconds", 0));
        String kind = props.optString("type", props.optString("featureType", ""));
        if ("jam".equalsIgnoreCase(kind) || props.has("speedKMH") || props.has("level") || delay > 0) {
            JamSegment seg = new JamSegment();
            seg.id = feature.optString("id", props.optString("uuid", "gj_jam_" + idx));
            seg.street = props.optString("street", props.optString("roadName", props.optString("name", ""))).trim();
            seg.level = level > 0 ? level : 3;
            seg.delayS = delay;
            seg.severity = seg.level >= 4 ? "standstill" : seg.level == 3 ? "heavy" : "moderate";
            seg.pts = pts;
            out.jams.add(seg);
        }
        int mid = ((pts.length / 2) / 2) * 2;
        AlertItem item = alertFromGeoProps(feature, props, pts[mid], pts[mid + 1], now, idx);
        if (item != null) out.alerts.add(item);
    }

    private static AlertItem alertFromGeoProps(JSONObject feature, JSONObject p, double lat, double lon, long now, int idx) {
        String rawType = firstNonEmpty(p, "type", "category", "alert_type", "alertType", "incidentType", "event", "kind");
        String rawSub = firstNonEmpty(p, "subtype", "sub_type", "subType", "hazardType", "reason");
        String desc = firstNonEmpty(p, "reportDescription", "description", "message", "title", "name");
        String mappedCat = classifyCategory(rawType, rawSub, desc);
        if (mappedCat == null) {
            if ("jam".equalsIgnoreCase(rawType) && p.optInt("level", 0) < 5) return null;
            mappedCat = CAT_EMERGENCY;
        }
        AlertItem item = new AlertItem();
        String fid = feature.optString("id", firstNonEmpty(p, "uuid", "id"));
        item.id = fid.length() > 0 ? "gj_" + fid : ("gj_" + idx + "_" + Math.round(lat * 10000));
        item.source = "geojson";
        item.category = mappedCat;
        item.subtype = classifySubtype(mappedCat, rawType, rawSub, desc);
        item.street = firstNonEmpty(p, "street", "roadName", "road", "location");
        item.description = desc;
        item.country = firstNonEmpty(p, "country", "countryCode");
        item.lat = lat;
        item.lon = lon;
        item.bearingDeg = p.has("magvar") ? p.optDouble("magvar", Double.NaN) : Double.NaN;
        item.confirmCount = Math.max(1, p.optInt("nThumbsUp", p.optInt("confirmCount", 0) + 1));
        item.reliability = p.optInt("reliability", 5);
        long pub = parseEpochMs(p.opt("pubMillis"));
        if (pub <= 0) pub = parseEpochMs(p.opt("timestamp"));
        if (pub <= 0) pub = parseEpochMs(p.opt("createdAt"));
        item.createdAtMs = pub > 0 ? pub : now;
        item.expiresAtMs = now + WAZE_DEFAULT_TTL_MS;
        item.title = displayTitle(item);
        return item;
    }

    private static String classifyCategory(String rawType, String rawSub, String desc) {
        String u = (rawType + " " + rawSub + " " + desc).toUpperCase(Locale.US);
        if (u.contains("CHIT_CHAT") && !u.contains("POLICE") && !u.contains("ACCIDENT") && !u.contains("EMERGENCY") && !u.contains("FLOOD")) {
            return null;
        }
        if (u.contains("POLICE") || u.contains("ROADBLOCK") || u.contains("SEKATAN") || u.contains("SJR")
                || u.contains("PDRM") || u.contains("JPJ") || u.contains("SPEED_TRAP") || u.contains("ENFORCEMENT")) {
            return CAT_POLICE;
        }
        if (u.contains("ACCIDENT") || u.contains("CRASH") || u.contains("KEMALANGAN") || u.contains("COLLISION")) {
            return CAT_ACCIDENT;
        }
        if (u.contains("EMERGENCY") || u.contains("HAZARD") || u.contains("POTHOLE") || u.contains("POT_HOLE")
                || u.contains("BACHE") || u.contains("BERLUBANG") || u.contains("WEATHER") || u.contains("FLOOD")
                || u.contains("BANJIR") || u.contains("ROAD_CLOSED") || u.contains("CLOSURE") || u.contains("SOS")
                || u.contains("CONSTRUCTION") || u.contains("ROAD_WORKS") || u.contains("OBRAS") || u.contains("STALLED")
                || u.contains("STOPPED") || u.contains("OBJECT") || u.contains("JAM")) {
            return CAT_EMERGENCY;
        }
        return null;
    }

    private static String classifySubtype(String category, String rawType, String rawSub, String desc) {
        String u = (rawType + " " + rawSub + " " + desc).toUpperCase(Locale.US);
        if (CAT_POLICE.equals(category)) {
            if (u.contains("TRAP") || u.contains("CAMERA") || u.contains("AES") || u.contains("HIDING")) {
                return "speed_trap";
            }
            return "roadblock";
        }
        if (CAT_ACCIDENT.equals(category)) {
            if (u.contains("MAJOR") || u.contains("SEVERE") || u.contains("BLOCK")) return "accident_major";
            return "accident_minor";
        }
        if (u.contains("POTHOLE") || u.contains("POT_HOLE") || u.contains("BACHE") || u.contains("BERLUBANG")) return "pothole";
        if (u.contains("FLOOD") || u.contains("BANJIR") || u.contains("WATER")) return "flood";
        if (u.contains("CLOSED") || u.contains("CLOSURE")) return "road_closed";
        if (u.contains("CONSTRUCTION") || u.contains("ROAD_WORKS") || u.contains("OBRAS") || u.contains("PEMBINAAN")) return "construction";
        if (u.contains("STALLED") || u.contains("CAR_STOPPED") || u.contains("VEHICLE_STOPPED") || u.contains("AVERIA")) return "stalled";
        if (u.contains("SOS") || u.contains("EMERGENCY")) return "sos";
        return "hazard";
    }

    private static double[] coordsArrayToLatLon(JSONArray arr) {
        double[] tmp = new double[arr.length() * 2];
        int k = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONArray pt = arr.optJSONArray(i);
            if (pt == null || pt.length() < 2) continue;
            double lon = pt.optDouble(0, Double.NaN);
            double lat = pt.optDouble(1, Double.NaN);
            if (!validLatLon(lat, lon)) continue;
            tmp[k++] = lat;
            tmp[k++] = lon;
        }
        return Arrays.copyOf(tmp, k);
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                String v = o.optString(k, "").trim();
                if (v.length() > 0 && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    private static long parseEpochMs(Object val) {
        if (val == null || val == JSONObject.NULL) return 0L;
        if (val instanceof Number) {
            long n = ((Number) val).longValue();
            return n < 100000000000L ? n * 1000L : n;
        }
        String s = String.valueOf(val).trim();
        if (s.length() == 0) return 0L;
        try {
            long n = Long.parseLong(s);
            return n < 100000000000L ? n * 1000L : n;
        } catch (Throwable ignored) { }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return fmt.parse(s.substring(0, Math.min(19, s.length()))).getTime();
        } catch (Throwable ignored) { }
        return 0L;
    }

    private static boolean validLatLon(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0
                && !(Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001);
    }

    // ───────────────────────── Proximity & Audio Evaluation ─────────────────────────

    public static void evaluateProximity(Context c, Location l, boolean mph) {
        if (c == null || l == null) return;
        updateAnchor(l.getLatitude(), l.getLongitude());
        if (!isAlertsEnabled(c)) {
            nearbyAlert = null;
            nearbyAlertDistM = -1;
            return;
        }
        List<AlertItem> alerts = getActiveAlerts(c);
        if (alerts.isEmpty()) {
            nearbyAlert = null;
            nearbyAlertDistM = -1;
            return;
        }
        double lat = l.getLatitude();
        double lon = l.getLongitude();
        float speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
        boolean hasBearing = l.hasBearing() && speedKmh > 10f;
        float carBearing = l.getBearing();
        Nav.Route activeRoute = Nav.active;

        AlertItem best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < alerts.size(); i++) {
            AlertItem a = alerts.get(i);
            double d = distM(lat, lon, a.lat, a.lon);
            double maxRadius = activeRoute != null ? 750.0 : (speedKmh > 10f ? 650.0 : 420.0);
            if (d > maxRadius) continue;

            if (activeRoute != null && activeRoute.pts != null && activeRoute.pts.length >= 4) {
                double routeGap = minDistToRouteM(activeRoute, a.lat, a.lon);
                if (routeGap > 90.0 && hasBearing) {
                    double brToAlert = bearingDeg(lat, lon, a.lat, a.lon);
                    if (angleDiff(carBearing, brToAlert) > 70.0) continue;
                }
            } else if (hasBearing && d > 55.0) {
                double brToAlert = bearingDeg(lat, lon, a.lat, a.lon);
                if (angleDiff(carBearing, brToAlert) > 75.0) continue;
            }

            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }

        nearbyAlert = best;
        nearbyAlertDistM = best != null ? bestDist : -1;

        if (best != null && best.id != null && isVoiceEnabled(c)) {
            long now = System.currentTimeMillis();
            Long lastSpoken = spokenAt.get(best.id);
            if (lastSpoken == null || now - lastSpoken > ALERT_SPEAK_COOLDOWN_MS) {
                spokenAt.put(best.id, now);
                int tone = CAT_ACCIDENT.equals(best.category) || "road_closed".equals(best.subtype)
                        ? ToneGenerator.TONE_PROP_BEEP2
                        : ToneGenerator.TONE_PROP_ACK;
                Nav.playRoadAlertAudio(c, spokenWarning(best, bestDist, mph), tone);
            }
        }
    }

    private static double minDistToRouteM(Nav.Route r, double lat, double lon) {
        double best = Double.MAX_VALUE;
        int n = r.pts.length / 2;
        int step = Math.max(1, n / 250);
        for (int i = 0; i < n; i += step) {
            double d = distM(lat, lon, r.pts[i * 2], r.pts[i * 2 + 1]);
            if (d < best) best = d;
        }
        return best;
    }

    public static double distM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2.0 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    // ───────────────────────── Disk Cache ─────────────────────────

    private static void saveCache(Context c, String format, String rawPayload) {
        try {
            JSONObject wrap = new JSONObject();
            wrap.put("ts", System.currentTimeMillis());
            wrap.put("format", format);
            wrap.put("payload", rawPayload);
            File f = new File(c.getCacheDir(), CACHE_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(wrap.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
    }

    private static void loadCacheOnce(Context c) {
        if (cacheLoaded || c == null) return;
        cacheLoaded = true;
        try {
            File f = new File(c.getCacheDir(), CACHE_FILE);
            if (!f.exists() || f.length() == 0) return;
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
            int n = fis.read(data);
            fis.close();
            if (n <= 0) return;
            JSONObject wrap = new JSONObject(new String(data, 0, n, "UTF-8"));
            long ts = wrap.optLong("ts", 0L);
            if (System.currentTimeMillis() - ts > WAZE_DEFAULT_TTL_MS) return;
            String payload = wrap.optString("payload", "");
            FeedParseResult res = parseFeedPayload(payload, "");
            if (res.error == null) {
                synchronized (LOCK) {
                    feedAlerts.clear();
                    feedAlerts.addAll(res.alerts);
                    feedJams.clear();
                    feedJams.addAll(res.jams);
                    status.format = res.format;
                    status.lastSyncAtMs = ts;
                    status.feedAlerts = res.alerts.size();
                    status.feedJams = res.jams.size();
                }
            }
        } catch (Throwable ignored) { }
    }
}
