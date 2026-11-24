package com.stradaos.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Cloud Firestore + Google Sign-In (Jetpack Credential Manager) repository for shared crowdsourced road alerts.
 */
public class CloudAlertsRepo {

    private static final String TAG = "CloudAlertsRepo";
    private static final long CLOUD_TTL_MS = 4L * 3600L * 1000L;

    public enum OperationType {
        CREATE("create"),
        UPDATE("update"),
        DELETE("delete"),
        LIST("list"),
        GET("get"),
        WRITE("write");

        final String value;
        OperationType(String value) { this.value = value; }
    }

    public interface AuthCb {
        void onSuccess(FirebaseUser user);
        void onCancelled();
        void onError(String message);
    }

    public interface ActionCb {
        void onSuccess(String alertId);
        void onError(String message);
    }

    public interface AuthStateObserver {
        void onAuthChanged(FirebaseUser user);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    private static Context appCtx;
    private static FirebaseAuth auth;
    private static FirebaseFirestore db;
    private static CredentialManager credMgr;
    private static ListenerRegistration alertsListener;
    private static String listeningUid;
    private static boolean authListenerAttached;
    private static AuthStateObserver authObserver;

    private static final FirebaseAuth.AuthStateListener AUTH_LISTENER = new FirebaseAuth.AuthStateListener() {
        @Override
        public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
            final FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null && user.getUid() != null && user.getUid().length() > 0) {
                startListening(user.getUid());
            } else {
                stopListening();
                RoadAlerts.setCloudAlerts(null);
            }
            if (authObserver != null) {
                UI.post(new Runnable() {
                    public void run() {
                        if (authObserver != null) authObserver.onAuthChanged(user);
                    }
                });
            }
        }
    };

    public static synchronized void init(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        try {
            if (FirebaseApp.getApps(appCtx).isEmpty()) {
                FirebaseApp.initializeApp(appCtx);
            }
            if (auth == null) auth = FirebaseAuth.getInstance();
            if (db == null) {
                String dbId = appCtx.getString(R.string.firestore_database_id);
                db = FirebaseFirestore.getInstance(dbId);
            }
            if (credMgr == null) {
                credMgr = CredentialManager.create(appCtx);
            }
            if (!authListenerAttached && auth != null) {
                authListenerAttached = true;
                auth.addAuthStateListener(AUTH_LISTENER);
            }
            FirebaseUser cur = auth != null ? auth.getCurrentUser() : null;
            if (cur != null && cur.getUid().length() > 0) {
                startListening(cur.getUid());
            }
        } catch (Throwable e) {
            Log.w(TAG, "Firebase init deferred/unavailable: " + e.getMessage());
        }
    }

    public static void setAuthObserver(AuthStateObserver obs) {
        authObserver = obs;
    }

    public static FirebaseUser currentUser() {
        try {
            return auth != null ? auth.getCurrentUser() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean isSignedIn() {
        FirebaseUser u = currentUser();
        return u != null && u.getUid() != null && u.getUid().length() > 0;
    }

    public static String driverDisplayLabel() {
        FirebaseUser u = currentUser();
        if (u == null) return I18n.t("Sin sesión iniciada");
        String name = u.getDisplayName();
        String email = u.getEmail();
        if (name != null && name.trim().length() > 0) return name.trim();
        if (email != null && email.trim().length() > 0) return email.trim();
        return u.getUid();
    }

    public static void attemptSilentSignIn(Activity activity) {
        if (activity == null) return;
        init(activity);
        if (isSignedIn() || credMgr == null || auth == null) return;
        try {
            String webClientId = activity.getString(R.string.default_web_client_id);
            GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(true)
                    .build();
            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build();
            credMgr.getCredentialAsync(activity, request, new CancellationSignal(), EXEC,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredentialResponse(result, null);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            // Expected when user has no authorized credential yet
                        }
                    });
        } catch (Throwable ignored) { }
    }

    public static void signInWithGoogle(final Activity activity, final AuthCb cb) {
        if (activity == null) {
            if (cb != null) cb.onError("Activity unavailable");
            return;
        }
        init(activity);
        if (credMgr == null || auth == null) {
            if (cb != null) UI.post(new Runnable() {
                public void run() { cb.onError(I18n.t("Firebase Auth no está listo")); }
            });
            return;
        }
        try {
            String webClientId = activity.getString(R.string.default_web_client_id);
            GetSignInWithGoogleOption signInOption = new GetSignInWithGoogleOption.Builder(webClientId).build();
            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build();
            credMgr.getCredentialAsync(activity, request, new CancellationSignal(), EXEC,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredentialResponse(result, cb);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                Log.w("Auth", "Google Sign-In flow cancelled or dismissed: " + e.getMessage(), e);
                                if (cb != null) UI.post(new Runnable() { public void run() { cb.onCancelled(); } });
                            } else {
                                Log.e("Auth", "Google Sign-In failed", e);
                                final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.getClass().getSimpleName();
                                if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
                            }
                        }
                    });
        } catch (Throwable e) {
            final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : I18n.t("Falta configuración de Google Sign-In");
            if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
        }
    }

    private static void handleCredentialResponse(GetCredentialResponse response, final AuthCb cb) {
        try {
            Credential cred = response.getCredential();
            if (cred instanceof CustomCredential
                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(cred.getType())) {
                GoogleIdTokenCredential googleCred = GoogleIdTokenCredential.createFrom(cred.getData());
                AuthCredential firebaseCred = GoogleAuthProvider.getCredential(googleCred.getIdToken(), null);
                auth.signInWithCredential(firebaseCred)
                        .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
                            @Override
                            public void onSuccess(final AuthResult authResult) {
                                final FirebaseUser u = authResult != null ? authResult.getUser() : auth.getCurrentUser();
                                if (cb != null) UI.post(new Runnable() { public void run() { cb.onSuccess(u); } });
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull final Exception e) {
                                Log.e("Auth", "Firebase credential sign-in failed", e);
                                final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Auth failed";
                                if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
                            }
                        });
            } else {
                if (cb != null) UI.post(new Runnable() {
                    public void run() { cb.onError(I18n.t("Tipo de credencial inesperado")); }
                });
            }
        } catch (Throwable e) {
            final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.getClass().getSimpleName();
            if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
        }
    }

    public static void signOut(final Runnable onDone) {
        stopListening();
        RoadAlerts.setCloudAlerts(null);
        if (credMgr != null) {
            try {
                credMgr.clearCredentialStateAsync(new ClearCredentialStateRequest(), new CancellationSignal(), EXEC,
                        new CredentialManagerCallback<Void, ClearCredentialException>() {
                            @Override
                            public void onResult(Void unused) {
                                finishFirebaseSignOut(onDone);
                            }

                            @Override
                            public void onError(@NonNull ClearCredentialException e) {
                                Log.w("Auth", "Failed to clear credential state: " + e.getMessage());
                                finishFirebaseSignOut(onDone);
                            }
                        });
                return;
            } catch (Throwable ignored) { }
        }
        finishFirebaseSignOut(onDone);
    }

    private static void finishFirebaseSignOut(final Runnable onDone) {
        try {
            if (auth != null) auth.signOut();
        } catch (Throwable ignored) { }
        if (onDone != null) UI.post(onDone);
    }

    public static synchronized void startListening(final String uid) {
        if (db == null || auth == null || uid == null || uid.length() == 0) return;
        if (alertsListener != null && uid.equals(listeningUid)) return;
        stopListening();
        listeningUid = uid;
        final String path = "road_alerts";
        alertsListener = db.collection(path)
                .whereEqualTo("status", "active")
                .limit(120)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(QuerySnapshot snapshot, FirebaseFirestoreException error) {
                        if (error != null) {
                            if (auth != null && auth.getCurrentUser() != null && uid.equals(auth.getCurrentUser().getUid())) {
                                handleFirestoreError(error, OperationType.LIST, path);
                            }
                            return;
                        }
                        if (snapshot == null) return;
                        long now = System.currentTimeMillis();
                        List<RoadAlerts.AlertItem> list = new ArrayList<RoadAlerts.AlertItem>();
                        for (DocumentSnapshot doc : snapshot.getDocuments()) {
                            RoadAlerts.AlertItem item = fromDoc(doc, now);
                            if (item != null) list.add(item);
                        }
                        RoadAlerts.setCloudAlerts(list);
                    }
                });
    }

    public static synchronized void stopListening() {
        if (alertsListener != null) {
            try { alertsListener.remove(); } catch (Throwable ignored) { }
            alertsListener = null;
        }
        listeningUid = null;
    }

    public static void shutdown() {
        stopListening();
        if (auth != null && authListenerAttached) {
            try { auth.removeAuthStateListener(AUTH_LISTENER); } catch (Throwable ignored) { }
            authListenerAttached = false;
        }
    }

    private static RoadAlerts.AlertItem fromDoc(DocumentSnapshot doc, long now) {
        try {
            String cat = doc.getString("category");
            if (!RoadAlerts.CAT_POLICE.equals(cat)
                    && !RoadAlerts.CAT_ACCIDENT.equals(cat)
                    && !RoadAlerts.CAT_EMERGENCY.equals(cat)) {
                return null;
            }
            Double lat = doc.getDouble("lat");
            Double lon = doc.getDouble("lon");
            if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
                return null;
            }
            RoadAlerts.AlertItem item = new RoadAlerts.AlertItem();
            item.id = doc.getId();
            item.source = "community";
            item.ownerUid = doc.getString("userId");
            item.category = cat;
            String sub = doc.getString("subtype");
            item.subtype = sub != null && sub.length() > 0 ? sub : "hazard";
            String st = doc.getString("street");
            item.street = st != null ? st : "";
            String desc = doc.getString("description");
            item.description = desc != null ? desc : "";
            String ctry = doc.getString("country");
            item.country = ctry != null ? ctry : "MY";
            Long conf = doc.getLong("confirmCount");
            Long dism = doc.getLong("dismissCount");
            item.confirmCount = conf != null ? conf.intValue() : 1;
            item.dismissCount = dism != null ? dism.intValue() : 0;
            String status = doc.getString("status");
            item.cleared = "cleared".equals(status);
            Timestamp ts = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
            item.createdAtMs = ts != null ? ts.toDate().getTime() : now;
            item.expiresAtMs = item.createdAtMs + CLOUD_TTL_MS;
            item.title = RoadAlerts.displayTitle(item);
            return item;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void submitReport(Context c, String category, String subtype, String street,
                                    String description, double lat, double lon, final ActionCb cb) {
        init(c);
        FirebaseUser u = currentUser();
        if (u == null || u.getUid() == null || u.getUid().length() == 0) {
            if (cb != null) UI.post(new Runnable() {
                public void run() { cb.onError(I18n.t("Inicia sesión con Google para reportar")); }
            });
            return;
        }
        if (db == null) {
            if (cb != null) UI.post(new Runnable() {
                public void run() { cb.onError(I18n.t("Base de datos no disponible")); }
            });
            return;
        }
        final String safeCat = (RoadAlerts.CAT_POLICE.equals(category)
                || RoadAlerts.CAT_ACCIDENT.equals(category)
                || RoadAlerts.CAT_EMERGENCY.equals(category)) ? category : RoadAlerts.CAT_EMERGENCY;
        final String safeSub = trimBounded(subtype, 40, "hazard");
        final String safeStreet = trimBounded(street, 120, "");
        final String safeDesc = trimBounded(description, 240, "");
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            if (cb != null) UI.post(new Runnable() {
                public void run() { cb.onError(I18n.t("Ubicación GPS inválida")); }
            });
            return;
        }

        final DocumentReference docRef = db.collection("road_alerts").document();
        final String docId = docRef.getId();
        final String path = docRef.getPath();

        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("userId", u.getUid());
        payload.put("category", safeCat);
        payload.put("subtype", safeSub);
        if (safeStreet.length() > 0) payload.put("street", safeStreet);
        if (safeDesc.length() > 0) payload.put("description", safeDesc);
        payload.put("country", "MY");
        payload.put("lat", lat);
        payload.put("lon", lon);
        payload.put("confirmCount", 1);
        payload.put("dismissCount", 0);
        payload.put("status", "active");
        payload.put("createdAt", FieldValue.serverTimestamp());
        payload.put("updatedAt", FieldValue.serverTimestamp());

        RoadAlerts.AlertItem pending = new RoadAlerts.AlertItem();
        pending.id = docId;
        pending.source = "community";
        pending.ownerUid = u.getUid();
        pending.category = safeCat;
        pending.subtype = safeSub;
        pending.street = safeStreet;
        pending.description = safeDesc;
        pending.country = "MY";
        pending.lat = lat;
        pending.lon = lon;
        pending.confirmCount = 1;
        pending.createdAtMs = System.currentTimeMillis();
        pending.expiresAtMs = pending.createdAtMs + CLOUD_TTL_MS;
        pending.title = RoadAlerts.displayTitle(pending);
        RoadAlerts.addLocalPending(pending);

        docRef.set(payload)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        if (cb != null) UI.post(new Runnable() { public void run() { cb.onSuccess(docId); } });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        handleFirestoreError(e, OperationType.CREATE, path);
                        RoadAlerts.dismissAlertLocally(docId);
                        final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Error saving report";
                        if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
                    }
                });
    }

    public static void voteAlert(Context c, final RoadAlerts.AlertItem item, final boolean stillThere, final ActionCb cb) {
        if (item == null || item.id == null) return;
        if (stillThere) {
            RoadAlerts.boostLocalConfirmation(item.id);
        } else {
            RoadAlerts.dismissAlertLocally(item.id);
        }

        // Only community alerts reside in Firestore road_alerts; Waze feed alerts are updated locally
        if (!"community".equals(item.source)) {
            if (cb != null) UI.post(new Runnable() { public void run() { cb.onSuccess(item.id); } });
            return;
        }
        init(c);
        FirebaseUser u = currentUser();
        if (u == null || db == null) {
            if (cb != null) UI.post(new Runnable() { public void run() { cb.onSuccess(item.id); } });
            return;
        }

        final String uid = u.getUid();
        final String votePath = "road_alerts/" + item.id + "/votes/" + uid;
        Map<String, Object> votePayload = new HashMap<String, Object>();
        votePayload.put("userId", uid);
        votePayload.put("vote", stillThere ? "confirm" : "gone");
        votePayload.put("updatedAt", FieldValue.serverTimestamp());

        db.collection("road_alerts").document(item.id)
                .collection("votes").document(uid)
                .set(votePayload)
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        handleFirestoreError(e, OperationType.WRITE, votePath);
                    }
                });

        final String alertPath = "road_alerts/" + item.id;
        Map<String, Object> updates = new HashMap<String, Object>();
        updates.put("updatedAt", FieldValue.serverTimestamp());
        if (stillThere) {
            updates.put("confirmCount", FieldValue.increment(1));
        } else {
            updates.put("dismissCount", FieldValue.increment(1));
            int nextDismiss = item.dismissCount + 1;
            if (uid.equals(item.ownerUid) || (nextDismiss >= 3 && nextDismiss > item.confirmCount)) {
                updates.put("status", "cleared");
            }
        }
        db.collection("road_alerts").document(item.id)
                .update(updates)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        if (cb != null) UI.post(new Runnable() { public void run() { cb.onSuccess(item.id); } });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        handleFirestoreError(e, OperationType.UPDATE, alertPath);
                        final String msg = e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Vote failed";
                        if (cb != null) UI.post(new Runnable() { public void run() { cb.onError(msg); } });
                    }
                });
    }

    private static String trimBounded(String v, int maxLen, String fallback) {
        if (v == null) return fallback;
        String t = v.trim();
        if (t.length() == 0) return fallback;
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    public static String handleFirestoreError(Throwable exception, OperationType operationType, String path) {
        Throwable root = exception;
        Throwable cur = exception;
        while (cur != null) {
            if (cur instanceof FirebaseFirestoreException) {
                root = cur;
                break;
            }
            cur = cur.getCause();
        }
        FirebaseUser currentUser = auth != null ? auth.getCurrentUser() : null;
        JSONArray providerArray = new JSONArray();
        if (currentUser != null) {
            for (UserInfo provider : currentUser.getProviderData()) {
                try {
                    JSONObject po = new JSONObject();
                    po.put("providerId", provider.getProviderId());
                    po.put("email", provider.getEmail());
                    providerArray.put(po);
                } catch (Throwable ignored) { }
            }
        }
        JSONObject errorJson = new JSONObject();
        try {
            JSONObject authJson = new JSONObject();
            authJson.put("userId", currentUser != null ? currentUser.getUid() : JSONObject.NULL);
            authJson.put("email", currentUser != null && currentUser.getEmail() != null ? currentUser.getEmail() : JSONObject.NULL);
            authJson.put("emailVerified", currentUser != null && currentUser.isEmailVerified());
            authJson.put("tenantId", currentUser != null && currentUser.getTenantId() != null ? currentUser.getTenantId() : JSONObject.NULL);
            authJson.put("providerInfo", providerArray);

            String msg = root != null && root.getMessage() != null ? root.getMessage() : String.valueOf(root);
            errorJson.put("error", msg);
            errorJson.put("operationType", operationType != null ? operationType.value : "write");
            errorJson.put("path", path != null ? path : JSONObject.NULL);
            errorJson.put("authInfo", authJson);
        } catch (Throwable ignored) { }
        String out = errorJson.toString();
        Log.e("FirestoreError", "Firestore Error: " + out);
        return out;
    }
}
