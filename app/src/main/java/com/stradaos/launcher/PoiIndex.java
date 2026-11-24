package com.stradaos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Índice SQLite de POIs sin conexión de StradaOS.
 * Combina tres capas locales persistentes:
 *  1) "builtin": catálogo regional y global integrado (assets/nav/poi_seed.tsv)
 *  2) "cache": lugares y búsquedas en línea guardadas automáticamente
 *  3) "map": ciudades y POIs extraídos del archivo .map de Mapsforge cargado
 */
public final class PoiIndex {

    private static final String DB_NAME = "strada_poi_v1.db";
    private static final int DB_VERSION = 1;
    private static final String PREF_NAME = "poi_index_prefs";
    private static final String PREF_SEED_VER = "seed_ver";
    private static final String PREF_MAP_SIG = "map_sig";
    private static final int SEED_VERSION = 1;
    private static final int MAX_CACHE_ROWS = 8000;
    private static final int MAX_MAP_ROWS = 15000;

    static final String SRC_BUILTIN = "builtin";
    static final String SRC_CACHE = "cache";
    static final String SRC_MAP = "map";

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static DbHelper helper;
    private static volatile boolean seedDone;
    static volatile boolean indexing;

    public static final class Stats {
        public int builtinCount;
        public int cacheCount;
        public int mapCount;
        public String indexedMapSig;
        public boolean indexing;

        public int total() {
            return builtinCount + cacheCount + mapCount;
        }
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context c) {
            super(c.getApplicationContext(), DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pois ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "dedup_key TEXT UNIQUE NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "name_fold TEXT NOT NULL,"
                    + "alt_tokens TEXT NOT NULL DEFAULT '',"
                    + "subtitle TEXT NOT NULL DEFAULT '',"
                    + "category TEXT NOT NULL DEFAULT 'place',"
                    + "lat REAL NOT NULL,"
                    + "lon REAL NOT NULL,"
                    + "source TEXT NOT NULL,"
                    + "boost INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL DEFAULT 0"
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pois_name_fold ON pois(name_fold)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pois_cat ON pois(category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pois_src ON pois(source)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_pois_latlon ON pois(lat, lon)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS pois");
            onCreate(db);
        }
    }

    private static DbHelper db(Context c) {
        if (helper == null) {
            synchronized (LOCK) {
                if (helper == null) helper = new DbHelper(c.getApplicationContext());
            }
        }
        return helper;
    }

    /** Inicializa el catálogo integrado en segundo plano sin bloquear el arranque. */
    public static void initIfNeeded(final Context c) {
        if (c == null || seedDone) return;
        final Context app = c.getApplicationContext();
        IO_EXEC.execute(new Runnable() {
            public void run() {
                ensureInitialized(app);
            }
        });
    }

    /** Garantiza sincrónicamente que la base y el catálogo integrado estén cargados. */
    public static void ensureInitialized(Context c) {
        if (c == null || seedDone) return;
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            if (seedDone) return;
            SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            DbHelper h = db(app);
            SQLiteDatabase wdb = h.getWritableDatabase();
            if (sp.getInt(PREF_SEED_VER, 0) == SEED_VERSION) {
                seedDone = true;
                return;
            }
            BufferedReader br = null;
            wdb.beginTransaction();
            try {
                wdb.delete("pois", "source=?", new String[]{SRC_BUILTIN});
                SQLiteStatement st = wdb.compileStatement(
                        "INSERT OR REPLACE INTO pois (dedup_key, name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                InputStream is = app.getAssets().open("nav/poi_seed.tsv");
                br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                long now = System.currentTimeMillis();
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    String[] parts = line.split("\t");
                    if (parts.length < 5) continue;
                    String name = parts[0].trim();
                    String cat = parts[1].trim().toLowerCase(Locale.US);
                    String sub = parts[2].trim();
                    double lat = Double.parseDouble(parts[3].trim());
                    double lon = Double.parseDouble(parts[4].trim());
                    String alt = parts.length > 5 ? parts[5].trim() : "";
                    String nameFold = normFold(name);
                    String altFold = normFold(alt + " " + sub);
                    if (nameFold.length() == 0) continue;
                    int boost = boostForCat(cat);
                    st.clearBindings();
                    st.bindString(1, dedupKey(nameFold, lat, lon));
                    st.bindString(2, name);
                    st.bindString(3, nameFold);
                    st.bindString(4, altFold);
                    st.bindString(5, sub);
                    st.bindString(6, cat);
                    st.bindDouble(7, lat);
                    st.bindDouble(8, lon);
                    st.bindString(9, SRC_BUILTIN);
                    st.bindLong(10, boost);
                    st.bindLong(11, now);
                    st.executeInsert();
                }
                wdb.setTransactionSuccessful();
                sp.edit().putInt(PREF_SEED_VER, SEED_VERSION).apply();
                seedDone = true;
            } catch (Throwable ignored) {
            } finally {
                try { wdb.endTransaction(); } catch (Throwable ignored) { }
                try { if (br != null) br.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Busca texto libre en el índice local (builtin + cache + map). */
    public static List<Nav.Place> search(Context c, String query, final double biasLat, final double biasLon,
                                         boolean mph, int limit) {
        List<Nav.Place> out = new ArrayList<Nav.Place>();
        if (c == null || query == null) return out;
        ensureInitialized(c);
        final String qNorm = normFold(query);
        if (qNorm.length() == 0) return out;
        final String[] tokens = qNorm.split(" ");
        String longest = tokens[0];
        for (String t : tokens) {
            if (t.length() > longest.length()) longest = t;
        }
        if (longest.length() == 0) return out;
        String likePat = "%" + escapeLike(longest) + "%";
        final boolean hasBias = !Double.isNaN(biasLat) && !Double.isNaN(biasLon);

        List<Row> rows = new ArrayList<Row>();
        Cursor cur = null;
        try {
            SQLiteDatabase rdb = db(c).getReadableDatabase();
            cur = rdb.rawQuery(
                    "SELECT name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost "
                            + "FROM pois WHERE name_fold LIKE ? ESCAPE '\\' OR alt_tokens LIKE ? ESCAPE '\\' "
                            + "ORDER BY boost DESC, updated_at DESC LIMIT 220",
                    new String[]{likePat, likePat});
            while (cur.moveToNext()) {
                Row r = new Row();
                r.name = cur.getString(0);
                r.nameFold = cur.getString(1);
                r.altTokens = cur.getString(2);
                r.sub = cur.getString(3);
                r.cat = cur.getString(4);
                r.lat = cur.getDouble(5);
                r.lon = cur.getDouble(6);
                r.source = cur.getString(7);
                r.boost = cur.getInt(8);
                if (matchesAllTokens(r, tokens)) {
                    r.score = scoreRow(r, qNorm, tokens, hasBias, biasLat, biasLon);
                    rows.add(r);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }

        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                return Double.compare(b.score, a.score);
            }
        });

        int cap = Math.min(limit > 0 ? limit : 20, rows.size());
        for (int i = 0; i < cap; i++) {
            out.add(toPlace(rows.get(i), biasLat, biasLon, mph));
        }
        return out;
    }

    /** Busca categorías rápidas ("fuel", "health", "parking", "food") dentro del índice local. */
    public static List<Nav.Place> searchCategory(Context c, String cat, final double biasLat, final double biasLon,
                                                 boolean mph, int limit) {
        List<Nav.Place> out = new ArrayList<Nav.Place>();
        if (c == null || cat == null) return out;
        ensureInitialized(c);

        String catWhere = catSqlFilter(cat);
        boolean hasBias = !Double.isNaN(biasLat) && !Double.isNaN(biasLon);
        List<Row> rows = new ArrayList<Row>();

        if (hasBias) {
            queryCatBox(c, catWhere, biasLat - 0.95, biasLat + 0.95, biasLon - 0.95, biasLon + 0.95, rows);
            if (rows.size() < 6) {
                rows.clear();
                queryCatBox(c, catWhere, -90.0, 90.0, -180.0, 180.0, rows);
            }
        } else {
            queryCatBox(c, catWhere, -90.0, 90.0, -180.0, 180.0, rows);
        }

        if (hasBias) {
            Collections.sort(rows, new Comparator<Row>() {
                public int compare(Row a, Row b) {
                    return Double.compare(Nav.approxDist(biasLat, biasLon, a.lat, a.lon),
                            Nav.approxDist(biasLat, biasLon, b.lat, b.lon));
                }
            });
        }
        int cap = Math.min(limit > 0 ? limit : 16, rows.size());
        for (int i = 0; i < cap; i++) {
            out.add(toPlace(rows.get(i), biasLat, biasLon, mph));
        }
        return out;
    }

    /** Busca el lugar indexado más cercano cuando se toca el mapa sin conexión. */
    public static Nav.Place nearestPlace(Context c, double lat, double lon, double maxMeters) {
        if (c == null || Double.isNaN(lat) || Double.isNaN(lon)) return null;
        ensureInitialized(c);
        double deg = Math.max(0.05, maxMeters / 111000.0);
        Cursor cur = null;
        Row best = null;
        double bestDist = maxMeters;
        try {
            SQLiteDatabase rdb = db(c).getReadableDatabase();
            cur = rdb.rawQuery(
                    "SELECT name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost "
                            + "FROM pois WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 200",
                    new String[]{
                            String.valueOf(lat - deg), String.valueOf(lat + deg),
                            String.valueOf(lon - deg), String.valueOf(lon + deg)
                    });
            while (cur.moveToNext()) {
                double la = cur.getDouble(5), lo = cur.getDouble(6);
                double d = Nav.approxDist(lat, lon, la, lo);
                if (d < bestDist) {
                    bestDist = d;
                    best = new Row();
                    best.name = cur.getString(0);
                    best.nameFold = cur.getString(1);
                    best.altTokens = cur.getString(2);
                    best.sub = cur.getString(3);
                    best.cat = cur.getString(4);
                    best.lat = la;
                    best.lon = lo;
                    best.source = cur.getString(7);
                    best.boost = cur.getInt(8);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (best == null) return null;
        return toPlace(best, lat, lon, false);
    }

    /** Guarda resultados en línea en la caché local persistente (en segundo plano). */
    public static void recordResults(final Context c, final List<Nav.Place> places) {
        if (c == null || places == null || places.isEmpty()) return;
        final List<Nav.Place> copy = new ArrayList<Nav.Place>(places.size());
        for (int i = 0; i < places.size(); i++) {
            Nav.Place p = places.get(i);
            if (p == null || p.offline || p.name == null || p.name.trim().length() < 2) continue;
            if (Double.isNaN(p.lat) || Double.isNaN(p.lon)) continue;
            Nav.Place cp = new Nav.Place();
            cp.name = p.name.trim();
            cp.sub = p.sub != null ? p.sub.trim() : "";
            cp.lat = p.lat;
            cp.lon = p.lon;
            copy.add(cp);
        }
        if (copy.isEmpty()) return;
        final Context app = c.getApplicationContext();
        IO_EXEC.execute(new Runnable() {
            public void run() {
                ensureInitialized(app);
                synchronized (LOCK) {
                    SQLiteDatabase wdb = db(app).getWritableDatabase();
                    wdb.beginTransaction();
                    try {
                        SQLiteStatement st = wdb.compileStatement(
                                "INSERT OR REPLACE INTO pois (dedup_key, name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost, updated_at) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                        long now = System.currentTimeMillis();
                        for (int i = 0; i < copy.size(); i++) {
                            Nav.Place p = copy.get(i);
                            String nameFold = normFold(p.name);
                            if (nameFold.length() < 2) continue;
                            String dk = dedupKey(nameFold, p.lat, p.lon);
                            // No sobrescribir catálogo integrado con caché inferior
                            if (existsSource(wdb, dk, SRC_BUILTIN)) continue;
                            String cat = inferCategory(p.name, p.sub);
                            String sub = p.sub != null ? p.sub : "";
                            String altFold = normFold(sub);
                            st.clearBindings();
                            st.bindString(1, dk);
                            st.bindString(2, p.name);
                            st.bindString(3, nameFold);
                            st.bindString(4, altFold);
                            st.bindString(5, sub);
                            st.bindString(6, cat);
                            st.bindDouble(7, p.lat);
                            st.bindDouble(8, p.lon);
                            st.bindString(9, SRC_CACHE);
                            st.bindLong(10, Math.max(12, boostForCat(cat)));
                            st.bindLong(11, now);
                            st.executeInsert();
                        }
                        pruneCacheIfNeeded(wdb);
                        wdb.setTransactionSuccessful();
                    } catch (Throwable ignored) {
                    } finally {
                        try { wdb.endTransaction(); } catch (Throwable ignored) { }
                    }
                }
            }
        });
    }

    /** Indexa ciudades y POIs cercanos de un archivo .map de Mapsforge en segundo plano. */
    public static void indexMapFileAsync(final Context c, final OfflineMap fm,
                                         final double nearLat, final double nearLon, final boolean force) {
        if (c == null || fm == null) return;
        final Context app = c.getApplicationContext();
        final String sig = fm.signature();
        IO_EXEC.execute(new Runnable() {
            public void run() {
                ensureInitialized(app);
                SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                if (!force && sig.equals(sp.getString(PREF_MAP_SIG, ""))) return;
                indexing = true;
                try {
                    ArrayList<OfflineMap.SearchHit> hits = fm.extractPoiCatalog(nearLat, nearLon, MAX_MAP_ROWS);
                    synchronized (LOCK) {
                        SQLiteDatabase wdb = db(app).getWritableDatabase();
                        wdb.beginTransaction();
                        try {
                            wdb.delete("pois", "source=?", new String[]{SRC_MAP});
                            SQLiteStatement st = wdb.compileStatement(
                                    "INSERT OR REPLACE INTO pois (dedup_key, name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost, updated_at) "
                                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                            long now = System.currentTimeMillis();
                            HashSet<String> seen = new HashSet<String>(hits.size());
                            for (int i = 0; i < hits.size(); i++) {
                                OfflineMap.SearchHit h = hits.get(i);
                                if (h.name == null || h.name.trim().length() == 0) continue;
                                String nameFold = normFold(h.name);
                                if (nameFold.length() == 0) continue;
                                String dk = dedupKey(nameFold, h.lat, h.lon);
                                if (!seen.add(dk) || existsSource(wdb, dk, SRC_BUILTIN)) continue;
                                String cat = mapTagToCategory(h.tag);
                                String sub = prettyCategorySubtitle(h.tag);
                                st.clearBindings();
                                st.bindString(1, dk);
                                st.bindString(2, h.name.trim());
                                st.bindString(3, nameFold);
                                st.bindString(4, normFold(h.tag + " " + sub));
                                st.bindString(5, sub);
                                st.bindString(6, cat);
                                st.bindDouble(7, h.lat);
                                st.bindDouble(8, h.lon);
                                st.bindString(9, SRC_MAP);
                                st.bindLong(10, Math.max(15, boostForCat(cat)));
                                st.bindLong(11, now);
                                st.executeInsert();
                            }
                            wdb.setTransactionSuccessful();
                            sp.edit().putString(PREF_MAP_SIG, sig).apply();
                        } catch (Throwable ignored) {
                        } finally {
                            try { wdb.endTransaction(); } catch (Throwable ignored) { }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    indexing = false;
                }
            }
        });
    }

    /** Elimina las entradas cacheadas de búsquedas en línea (mantiene catálogo integrado y .map). */
    public static void clearCache(final Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            try {
                db(app).getWritableDatabase().delete("pois", "source=?", new String[]{SRC_CACHE});
            } catch (Throwable ignored) { }
        }
    }

    /** Devuelve los conteos actuales por capa de origen para Diagnóstico y Ajustes. */
    public static Stats getStats(Context c) {
        Stats st = new Stats();
        st.indexing = indexing;
        if (c == null) return st;
        ensureInitialized(c);
        SharedPreferences sp = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        st.indexedMapSig = sp.getString(PREF_MAP_SIG, "");
        Cursor cur = null;
        try {
            SQLiteDatabase rdb = db(c).getReadableDatabase();
            cur = rdb.rawQuery("SELECT source, COUNT(*) FROM pois GROUP BY source", null);
            while (cur.moveToNext()) {
                String src = cur.getString(0);
                int cnt = cur.getInt(1);
                if (SRC_BUILTIN.equals(src)) st.builtinCount = cnt;
                else if (SRC_CACHE.equals(src)) st.cacheCount = cnt;
                else if (SRC_MAP.equals(src)) st.mapCount = cnt;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return st;
    }

    // ───────────────────────── utilidades internas ─────────────────────────

    private static final class Row {
        String name, nameFold, altTokens, sub, cat, source;
        double lat, lon, score;
        int boost;
    }

    private static void queryCatBox(Context c, String catWhere, double minLa, double maxLa,
                                    double minLo, double maxLo, List<Row> out) {
        Cursor cur = null;
        try {
            SQLiteDatabase rdb = db(c).getReadableDatabase();
            cur = rdb.rawQuery(
                    "SELECT name, name_fold, alt_tokens, subtitle, category, lat, lon, source, boost "
                            + "FROM pois WHERE (" + catWhere + ") AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? "
                            + "ORDER BY boost DESC, updated_at DESC LIMIT 80",
                    new String[]{
                            String.valueOf(minLa), String.valueOf(maxLa),
                            String.valueOf(minLo), String.valueOf(maxLo)
                    });
            while (cur.moveToNext()) {
                Row r = new Row();
                r.name = cur.getString(0);
                r.nameFold = cur.getString(1);
                r.altTokens = cur.getString(2);
                r.sub = cur.getString(3);
                r.cat = cur.getString(4);
                r.lat = cur.getDouble(5);
                r.lon = cur.getDouble(6);
                r.source = cur.getString(7);
                r.boost = cur.getInt(8);
                out.add(r);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
    }

    private static String catSqlFilter(String cat) {
        if ("fuel".equals(cat)) {
            return "category IN ('fuel','charging_station','services','rest_area')";
        }
        if ("health".equals(cat)) {
            return "category IN ('hospital','clinic','pharmacy','doctors','health')";
        }
        if ("parking".equals(cat)) {
            return "category IN ('parking','services','rest_area')";
        }
        if ("food".equals(cat)) {
            return "category IN ('restaurant','cafe','fast_food','food_court','supermarket','convenience','services','rest_area','food','mall')";
        }
        return "category = '" + cat.replace("'", "") + "'";
    }

    private static Nav.Place toPlace(Row r, double biasLat, double biasLon, boolean mph) {
        Nav.Place p = new Nav.Place();
        p.name = r.name;
        p.lat = r.lat;
        p.lon = r.lon;
        p.offline = true;
        String srcBadge = SRC_CACHE.equals(r.source) ? I18n.t("Caché local")
                : SRC_MAP.equals(r.source) ? I18n.t("Mapa local")
                : I18n.t("Índice local");
        StringBuilder sb = new StringBuilder();
        if (r.sub != null && r.sub.trim().length() > 0) sb.append(r.sub.trim());
        else if (r.cat != null && r.cat.length() > 0) sb.append(prettyCategorySubtitle(r.cat));
        if (!Double.isNaN(biasLat) && !Double.isNaN(biasLon)) {
            double d = Nav.approxDist(biasLat, biasLon, r.lat, r.lon);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Nav.fmtStepDist(d, mph));
        }
        if (sb.length() > 0) sb.append(" · ");
        sb.append(srcBadge);
        p.sub = sb.toString();
        return p;
    }

    private static boolean matchesAllTokens(Row r, String[] tokens) {
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.length() == 0) continue;
            if (!r.nameFold.contains(t) && !r.altTokens.contains(t)) return false;
        }
        return true;
    }

    private static double scoreRow(Row r, String qNorm, String[] tokens, boolean hasBias, double biasLat, double biasLon) {
        double sc = r.boost * 0.4;
        if (r.nameFold.equals(qNorm)) sc += 140;
        else if (r.nameFold.startsWith(qNorm)) sc += 100;
        else if (r.nameFold.contains(qNorm)) sc += 70;
        else sc += 40;

        int hitName = 0;
        for (String t : tokens) {
            if (t.length() > 0 && r.nameFold.contains(t)) hitName++;
        }
        sc += hitName * 10.0;
        if (SRC_BUILTIN.equals(r.source)) sc += 8;
        else if (SRC_CACHE.equals(r.source)) sc += 6;
        if (hasBias) {
            double km = Nav.approxDist(biasLat, biasLon, r.lat, r.lon) / 1000.0;
            if (km <= 100.0) sc += 36.0 - km * 0.25;
            else sc += Math.max(-18.0, 12.0 / (1.0 + (km - 100.0) * 0.015));
        }
        return sc;
    }

    private static boolean existsSource(SQLiteDatabase db, String dedupKey, String source) {
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT 1 FROM pois WHERE dedup_key=? AND source=? LIMIT 1",
                    new String[]{dedupKey, source});
            return cur.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cur != null) cur.close();
        }
    }

    private static void pruneCacheIfNeeded(SQLiteDatabase db) {
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT COUNT(*) FROM pois WHERE source=?", new String[]{SRC_CACHE});
            if (cur.moveToFirst() && cur.getInt(0) > MAX_CACHE_ROWS) {
                int excess = cur.getInt(0) - MAX_CACHE_ROWS + 500;
                db.execSQL("DELETE FROM pois WHERE id IN (SELECT id FROM pois WHERE source=? ORDER BY updated_at ASC LIMIT ?)",
                        new Object[]{SRC_CACHE, excess});
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
    }

    static String normFold(String s) {
        if (s == null) return "";
        return OfflineMap.fold(s).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String dedupKey(String nameFold, double lat, double lon) {
        long qLa = Math.round(lat * 500.0);
        long qLo = Math.round(lon * 500.0);
        return nameFold + "|" + qLa + "|" + qLo;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int boostForCat(String cat) {
        if ("city".equals(cat)) return 55;
        if ("aerodrome".equals(cat) || "airport".equals(cat)) return 50;
        if ("town".equals(cat)) return 42;
        if ("services".equals(cat) || "rest_area".equals(cat)) return 38;
        if ("hospital".equals(cat) || "mall".equals(cat) || "station".equals(cat)) return 36;
        if ("toll".equals(cat) || "university".equals(cat) || "mosque".equals(cat)) return 30;
        if ("fuel".equals(cat) || "suburb".equals(cat) || "tourism".equals(cat)) return 26;
        return 18;
    }

    private static String mapTagToCategory(String tag) {
        if (tag == null) return "place";
        if ("charging_station".equals(tag)) return "fuel";
        if ("doctors".equals(tag)) return "clinic";
        if ("department_store".equals(tag)) return "mall";
        return tag;
    }

    private static String prettyCategorySubtitle(String tag) {
        if ("city".equals(tag)) return I18n.t("Ciudad");
        if ("town".equals(tag) || "village".equals(tag) || "hamlet".equals(tag)) return I18n.t("Pueblo");
        if ("suburb".equals(tag) || "neighbourhood".equals(tag) || "quarter".equals(tag)) return I18n.t("Barrio");
        if ("aerodrome".equals(tag) || "airport".equals(tag)) return I18n.t("Aeropuerto");
        if ("station".equals(tag)) return I18n.t("Terminal");
        if ("services".equals(tag) || "rest_area".equals(tag)) return I18n.t("Área de servicio");
        if ("toll".equals(tag) || "toll_booth".equals(tag)) return I18n.t("Peaje");
        if ("mall".equals(tag)) return I18n.t("Centro comercial");
        if ("hospital".equals(tag)) return I18n.t("Hospital");
        if ("clinic".equals(tag) || "doctors".equals(tag)) return I18n.t("Clínica");
        if ("pharmacy".equals(tag)) return I18n.t("Farmacia");
        if ("fuel".equals(tag) || "charging_station".equals(tag)) return I18n.t("Estación de servicio");
        if ("parking".equals(tag)) return I18n.t("Parqueadero");
        if ("restaurant".equals(tag) || "fast_food".equals(tag) || "food_court".equals(tag)) return I18n.t("Restaurante");
        if ("cafe".equals(tag)) return I18n.t("Cafetería");
        if ("supermarket".equals(tag) || "convenience".equals(tag)) return I18n.t("Supermercado");
        if ("hotel".equals(tag)) return I18n.t("Hotel");
        if ("university".equals(tag)) return I18n.t("Universidad");
        if ("mosque".equals(tag) || "place_of_worship".equals(tag)) return I18n.t("Mezquita");
        if ("tourism".equals(tag) || "attraction".equals(tag)) return I18n.t("Turismo");
        return I18n.t("Lugar");
    }

    private static String inferCategory(String name, String sub) {
        String s = normFold((name != null ? name : "") + " " + (sub != null ? sub : ""));
        if (s.contains("petronas") || s.contains("shell") || s.contains("caltex") || s.contains("bhp") || s.contains("gasolin") || s.contains("fuel") || s.contains("stesen minyak") || s.contains("charging")) return "fuel";
        if (s.contains("hospital") || s.contains("klinik") || s.contains("clinic") || s.contains("farmacia") || s.contains("pharmacy") || s.contains("salud")) return "health";
        if (s.contains("parking") || s.contains("parqueadero") || s.contains("parkir") || s.contains("tempat letak")) return "parking";
        if (s.contains("r r") || s.contains("rnr") || s.contains("rest area") || s.contains("area de servicio") || s.contains("halaman rehat")) return "services";
        if (s.contains("restaurant") || s.contains("restoran") || s.contains("cafe") || s.contains("kafe") || s.contains("mcdonald") || s.contains("kfc") || s.contains("food") || s.contains("supermarket") || s.contains("supermercado")) return "food";
        if (s.contains("airport") || s.contains("aeropuerto") || s.contains("lapangan terbang") || s.contains("klia")) return "aerodrome";
        if (s.contains("mall") || s.contains("plaza") || s.contains("centro comercial") || s.contains("pavilion") || s.contains("klcc") || s.contains("megamall")) return "mall";
        if (s.contains("tol") || s.contains("peaje")) return "toll";
        if (s.contains("sentral") || s.contains("station") || s.contains("stesen") || s.contains("terminal")) return "station";
        return "place";
    }
}
