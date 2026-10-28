package com.messexpense.app;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.Timestamp;

import java.security.SecureRandom;
import java.util.Date;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bridges the local tracker UI to Firebase Auth and an owner-restricted Firestore event log. */
public final class FirebaseBridge {
    private static final int REQUEST_GOOGLE_SIGN_IN = 7402;
    private static final String GROUPS_PATH = "groups";
    private static final String INVITES_PATH = "invites";
    private static final String USERS_PATH = "users";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Activity activity;
    private final WebView webView;
    private FirebaseAuth auth;
    private FirebaseFirestore firestore;
    private GoogleSignInClient signInClient;
    private String pendingSignInCallback;
    private ListenerRegistration listener;
    private String listenerGroupId;
    private boolean listenerAcknowledged;

    FirebaseBridge(@NonNull Activity activity, @NonNull WebView webView) {
        this.activity = activity;
        this.webView = webView;
        FirebaseApp app = FirebaseApp.initializeApp(activity.getApplicationContext());
        if (app != null) {
            auth = FirebaseAuth.getInstance(app);
            firestore = FirebaseFirestore.getInstance(app);
        }
    }

    private String missingConfigMessage() {
        return "Firebase sync is unavailable right now. Your data remains saved on this phone.";
    }

    private boolean configured(String callbackId) {
        if (auth != null && firestore != null) return true;
        callback(callbackId, error(missingConfigMessage()));
        return false;
    }

    @JavascriptInterface
    public void getConnectionInfo(String callbackId) {
        if (auth == null || firestore == null) {
            callback(callbackId, success("\"connected\":false,\"configured\":false,\"message\":" + JSONObject.quote(missingConfigMessage())));
            return;
        }
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) callback(callbackId, success("\"connected\":false"));
        else callback(callbackId, success("\"connected\":true,\"account\":" + JSONObject.quote(user.getEmail() == null ? "" : user.getEmail())));
    }

    @JavascriptInterface
    public void connectTracker(String callbackId) {
        if (!configured(callbackId)) return;
        int webClientIdId = activity.getResources().getIdentifier("default_web_client_id", "string", activity.getPackageName());
        if (webClientIdId == 0) {
            callback(callbackId, error("Google sign-in is unavailable right now. Your data remains saved on this phone."));
            return;
        }
        String webClientId = activity.getString(webClientIdId);
        if (webClientId.trim().isEmpty()) {
            callback(callbackId, error("Google sign-in is unavailable right now. Your data remains saved on this phone."));
            return;
        }
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                .build();
        signInClient = GoogleSignIn.getClient(activity, options);
        pendingSignInCallback = callbackId;
        try {
            activity.startActivityForResult(signInClient.getSignInIntent(), REQUEST_GOOGLE_SIGN_IN);
        } catch (Exception e) {
            pendingSignInCallback = null;
            callback(callbackId, error("Could not open Google sign-in: " + safeMessage(e)));
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_GOOGLE_SIGN_IN || pendingSignInCallback == null) return;
        final String callbackId = pendingSignInCallback;
        pendingSignInCallback = null;
        if (data == null) {
            callback(callbackId, error("Google sign-in was cancelled."));
            return;
        }
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            if (account == null || account.getIdToken() == null) {
                callback(callbackId, error("Google did not return an ID token. Check the Firebase Android app and Google web OAuth client configuration."));
                return;
            }
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            auth.signInWithCredential(credential).addOnCompleteListener(activity, task -> {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().getUser() == null) {
                    callback(callbackId, error(task.getException() == null ? "Firebase sign-in failed." : safeMessage(task.getException())));
                    return;
                }
                FirebaseUser user = task.getResult().getUser();
                callback(callbackId, success("\"account\":" + JSONObject.quote(user.getEmail() == null ? "" : user.getEmail())));
            });
        } catch (ApiException e) {
            callback(callbackId, error("Google sign-in failed (" + e.getStatusCode() + "). Check the Firebase OAuth setup and the signing certificate SHA-1."));
        } catch (Exception e) {
            callback(callbackId, error("Google sign-in failed: " + safeMessage(e)));
        }
    }

    @JavascriptInterface
    public void readEvents(String callbackId, String groupId) {
        if (!validGroupId(groupId, callbackId)) return;
        if (!configured(callbackId)) return;
        if (!signedIn(callbackId)) return;
        events(groupId).orderBy("time").get().addOnSuccessListener(activity, snapshot -> {
            try {
                JSONArray rows = new JSONArray();
                for (QueryDocumentSnapshot doc : snapshot) {
                    JSONObject patch = new JSONObject();
                    Object rawPatch = doc.get("patch");
                    JSONArray patchArray = toJsonArray(rawPatch);
                    JSONArray row = new JSONArray();
                    row.put(doc.getId());
                    row.put(doc.getString("time"));
                    row.put(doc.getString("device"));
                    row.put(patchArray.toString());
                    rows.put(row);
                }
                callback(callbackId, success("\"rows\":" + rows));
            } catch (Exception e) {
                callback(callbackId, error("Could not read Firebase sync data: " + safeMessage(e)));
            }
        }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void appendEvent(String callbackId, String groupId, String eventJson) {
        if (!validGroupId(groupId, callbackId)) return;
        if (!configured(callbackId)) return;
        if (!signedIn(callbackId)) return;
        try {
            JSONObject event = new JSONObject(eventJson);
            String id = event.optString("id", "");
            String time = event.optString("time", "");
            String device = event.optString("device", "");
            JSONArray patch = event.optJSONArray("patch");
            if (!id.matches("[A-Za-z0-9_-]{1,100}") || time.isEmpty() || device.isEmpty() || patch == null || patch.length() == 0) {
                callback(callbackId, error("The tracker generated an invalid Firebase sync event."));
                return;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("id", id);
            data.put("time", time);
            data.put("device", device);
            data.put("patch", toNativeList(patch));
            events(groupId).document(id).set(data).addOnSuccessListener(activity, unused -> callback(callbackId, success("")))
                    .addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
        } catch (Exception e) {
            callback(callbackId, error("Could not save Firebase sync event: " + safeMessage(e)));
        }
    }

    @JavascriptInterface
    public void startListening(String callbackId, String groupId) {
        if (!validGroupId(groupId, callbackId)) return;
        if (!configured(callbackId)) return;
        if (!signedIn(callbackId)) return;
        if (listener != null && groupId.equals(listenerGroupId)) {
            callback(callbackId, success(""));
            return;
        }
        stopListening();
        listenerGroupId = groupId;
        listenerAcknowledged = false;
        listener = events(groupId).addSnapshotListener(activity, (snapshot, error) -> {
            if (error != null) {
                if (!listenerAcknowledged) callback(callbackId, error(firebaseError(error)));
                else evaluateJavascript("if(window.onFirebaseListenerError)window.onFirebaseListenerError(" + JSONObject.quote(firebaseError(error)) + ");");
                stopListening();
                return;
            }
            if (!listenerAcknowledged) {
                listenerAcknowledged = true;
                callback(callbackId, success(""));
            } else {
                evaluateJavascript("if(window.onFirebaseChanged)window.onFirebaseChanged();");
            }
        });
    }

    @JavascriptInterface
    public void stopListening() {
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        listenerAcknowledged = false;
        listenerGroupId = null;
    }

    private boolean signedIn(String callbackId) {
        if (auth.getCurrentUser() != null) return true;
        callback(callbackId, error("Connect the shared Google account to Firebase first."));
        return false;
    }

    private com.google.firebase.firestore.CollectionReference events(String groupId) {
        return firestore.collection(GROUPS_PATH).document(groupId).collection("events");
    }

    private boolean validGroupId(String groupId, String callbackId) {
        if (!configured(callbackId) || !signedIn(callbackId)) return false;
        if (groupId == null || !groupId.matches("[A-Za-z0-9_-]{10,100}")) {
            callback(callbackId, error("Choose a valid group before syncing."));
            return false;
        }
        return true;
    }

    @JavascriptInterface
    public void listGroups(String callbackId) {
        if (!configured(callbackId) || !signedIn(callbackId)) return;
        String uid = auth.getCurrentUser().getUid();
        firestore.collection(USERS_PATH).document(uid).collection("groups").get()
                .addOnSuccessListener(activity, snapshot -> {
                    JSONArray groups = new JSONArray();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        JSONObject item = new JSONObject();
                        try {
                            item.put("id", doc.getId());
                            item.put("name", doc.getString("name") == null ? "Mess group" : doc.getString("name"));
                            item.put("role", doc.getString("role") == null ? "member" : doc.getString("role"));
                            groups.put(item);
                        } catch (JSONException ignored) {}
                    }
                    callback(callbackId, success("\"groups\":" + groups));
                }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void createGroup(String callbackId, String groupName) {
        if (!configured(callbackId) || !signedIn(callbackId)) return;
        String name = groupName == null ? "" : groupName.trim();
        if (name.isEmpty() || name.length() > 50) {
            callback(callbackId, error("Enter a group name between 1 and 50 characters."));
            return;
        }
        FirebaseUser user = auth.getCurrentUser();
        String uid = user.getUid();
        DocumentReference group = firestore.collection(GROUPS_PATH).document();
        Map<String, Object> groupData = new HashMap<>();
        groupData.put("name", name);
        groupData.put("ownerUid", uid);
        groupData.put("createdAt", Timestamp.now());
        Map<String, Object> member = new HashMap<>();
        member.put("role", "owner");
        member.put("joinedAt", Timestamp.now());
        Map<String, Object> index = new HashMap<>();
        index.put("name", name);
        index.put("role", "owner");
        index.put("joinedAt", Timestamp.now());
        firestore.batch()
                .set(group, groupData)
                .set(group.collection("members").document(uid), member)
                .set(firestore.collection(USERS_PATH).document(uid).collection("groups").document(group.getId()), index)
                .commit().addOnSuccessListener(activity, unused -> {
                    JSONObject result = new JSONObject();
                    try { result.put("group", new JSONObject().put("id", group.getId()).put("name", name).put("role", "owner")); }
                    catch (JSONException ignored) {}
                    callback(callbackId, success("\"group\":" + result.optJSONObject("group")));
                }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void createInvite(String callbackId, String groupId) {
        if (!validGroupId(groupId, callbackId)) return;
        String uid = auth.getCurrentUser().getUid();
        DocumentReference memberRef = firestore.collection(GROUPS_PATH).document(groupId).collection("members").document(uid);
        memberRef.get().addOnSuccessListener(activity, member -> {
            if (!member.exists() || !"owner".equals(member.getString("role"))) {
                callback(callbackId, error("Only the group owner can create invite codes."));
                return;
            }
            final String token = secureInviteCode();
            final long expiresMillis = System.currentTimeMillis() + 48L * 60L * 60L * 1000L;
            Map<String, Object> invite = new HashMap<>();
            invite.put("groupId", groupId);
            invite.put("groupName", "");
            invite.put("createdBy", uid);
            invite.put("createdAt", Timestamp.now());
            invite.put("expiresAt", new Timestamp(new Date(expiresMillis)));
            invite.put("active", true);
            invite.put("consumedBy", null);
            firestore.collection(GROUPS_PATH).document(groupId).get().addOnSuccessListener(activity, group -> {
                if (!group.exists()) { callback(callbackId, error("This group no longer exists.")); return; }
                invite.put("groupName", group.getString("name") == null ? "Mess group" : group.getString("name"));
                firestore.collection(INVITES_PATH).document(token).set(invite)
                        .addOnSuccessListener(activity, unused -> callback(callbackId,
                                success("\"code\":" + JSONObject.quote(token) + ",\"expiresAt\":" + expiresMillis)))
                        .addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
            }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
        }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void listInvites(String callbackId, String groupId) {
        if (!validGroupId(groupId, callbackId)) return;
        String uid = auth.getCurrentUser().getUid();
        firestore.collection(GROUPS_PATH).document(groupId).collection("members").document(uid).get()
                .addOnSuccessListener(activity, member -> {
                    if (!member.exists() || !"owner".equals(member.getString("role"))) {
                        callback(callbackId, error("Only the group owner can manage invite codes."));
                        return;
                    }
                    firestore.collection(INVITES_PATH).whereEqualTo("groupId", groupId).get()
                            .addOnSuccessListener(activity, snapshot -> {
                                JSONArray invites = new JSONArray();
                                long now = System.currentTimeMillis();
                                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                                    Timestamp expiry = doc.getTimestamp("expiresAt");
                                    if (!Boolean.TRUE.equals(doc.getBoolean("active")) || doc.getString("consumedBy") != null || expiry == null || expiry.toDate().getTime() <= now) continue;
                                    JSONObject item = new JSONObject();
                                    try { item.put("id", doc.getId()); item.put("expiresAt", expiry.toDate().getTime()); invites.put(item); }
                                    catch (JSONException ignored) {}
                                }
                                callback(callbackId, success("\"invites\":" + invites));
                            }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
                }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void revokeInvite(String callbackId, String groupId, String inviteId) {
        if (!validGroupId(groupId, callbackId)) return;
        String uid = auth.getCurrentUser().getUid();
        firestore.collection(GROUPS_PATH).document(groupId).collection("members").document(uid).get()
                .addOnSuccessListener(activity, member -> {
                    if (!member.exists() || !"owner".equals(member.getString("role"))) {
                        callback(callbackId, error("Only the group owner can revoke invite codes."));
                        return;
                    }
                    firestore.collection(INVITES_PATH).document(inviteId).update("active", false)
                            .addOnSuccessListener(activity, unused -> callback(callbackId, success("")))
                            .addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
                }).addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    @JavascriptInterface
    public void joinGroup(String callbackId, String rawCode) {
        if (!configured(callbackId) || !signedIn(callbackId)) return;
        String code = rawCode == null ? "" : rawCode.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[0-9A-F]{32}")) {
            callback(callbackId, error("Enter the 32-character invite code exactly as shared."));
            return;
        }
        FirebaseUser user = auth.getCurrentUser();
        String uid = user.getUid();
        DocumentReference inviteRef = firestore.collection(INVITES_PATH).document(code);
        firestore.runTransaction(transaction -> {
            DocumentSnapshot invite = transaction.get(inviteRef);
            if (!invite.exists() || !Boolean.TRUE.equals(invite.getBoolean("active")) ||
                    invite.getTimestamp("expiresAt") == null || invite.getTimestamp("expiresAt").toDate().getTime() <= System.currentTimeMillis() ||
                    invite.getString("consumedBy") != null) {
                throw new FirebaseFirestoreException("Invite code is invalid, expired, or already used.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
            }
            String groupId = invite.getString("groupId");
            String groupName = invite.getString("groupName");
            if (groupId == null || !groupId.matches("[A-Za-z0-9_-]{10,100}")) {
                throw new FirebaseFirestoreException("Invite code is invalid.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
            }
            DocumentReference memberRef = firestore.collection(GROUPS_PATH).document(groupId).collection("members").document(uid);
            DocumentSnapshot member = transaction.get(memberRef);
            if (member.exists()) throw new FirebaseFirestoreException("You are already a member of this group.", FirebaseFirestoreException.Code.ALREADY_EXISTS);
            transaction.update(inviteRef, "consumedBy", uid);
            Map<String, Object> memberData = new HashMap<>();
            memberData.put("role", "member");
            memberData.put("joinedAt", Timestamp.now());
            memberData.put("inviteId", code);
            transaction.set(memberRef, memberData);
            Map<String, Object> index = new HashMap<>();
            index.put("name", groupName == null ? "Mess group" : groupName);
            index.put("role", "member");
            index.put("joinedAt", Timestamp.now());
            transaction.set(firestore.collection(USERS_PATH).document(uid).collection("groups").document(groupId), index);
            try {
                return new JSONObject().put("id", groupId).put("name", groupName == null ? "Mess group" : groupName).put("role", "member");
            } catch (JSONException jsonError) {
                throw new FirebaseFirestoreException("Could not prepare group details.", FirebaseFirestoreException.Code.INTERNAL);
            }
        }).addOnSuccessListener(activity, group -> callback(callbackId, success("\"group\":" + group)))
          .addOnFailureListener(activity, e -> callback(callbackId, error(firebaseError(e))));
    }

    private String secureInviteCode() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xff));
        return out.toString();
    }

    private JSONArray toJsonArray(Object value) throws JSONException {
        if (!(value instanceof List)) return new JSONArray();
        JSONArray array = new JSONArray();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) array.put(new JSONObject((Map<?, ?>) item));
            else if (item instanceof List) array.put(toJsonArray(item));
            else array.put(item);
        }
        return array;
    }

    private List<Object> toNativeList(JSONArray array) throws JSONException {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof JSONObject) values.add(toNativeMap((JSONObject) item));
            else if (item instanceof JSONArray) values.add(toNativeList((JSONArray) item));
            else values.add(item == JSONObject.NULL ? null : item);
        }
        return values;
    }

    private Map<String, Object> toNativeMap(JSONObject object) throws JSONException {
        Map<String, Object> values = new HashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object item = object.get(key);
            if (item instanceof JSONObject) values.put(key, toNativeMap((JSONObject) item));
            else if (item instanceof JSONArray) values.put(key, toNativeList((JSONArray) item));
            else values.put(key, item == JSONObject.NULL ? null : item);
        }
        return values;
    }

    private String firebaseError(Exception e) {
        String message = safeMessage(e);
        if (e instanceof FirebaseFirestoreException &&
                ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.UNAVAILABLE) {
            return "Offline or Firebase unavailable. Sync did not complete; your data remains saved on this phone.";
        }
        if (message.toLowerCase().contains("permission")) return "Firebase denied access. Check Firestore rules allow only signed-in group members.";
        return "Firebase sync failed: " + message;
    }

    private String safeMessage(Throwable e) {
        String message = e.getLocalizedMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private String success(String fields) { return "{\"ok\":true" + (fields.isEmpty() ? "" : "," + fields) + "}"; }
    private String error(String message) { return "{\"ok\":false,\"error\":" + JSONObject.quote(message) + "}"; }

    private void callback(String callbackId, String result) {
        if (callbackId == null || callbackId.isEmpty()) return;
        evaluateJavascript("if(window.onFirebaseNativeResult)window.onFirebaseNativeResult(" + JSONObject.quote(callbackId) + "," + result + ");");
    }

    private void evaluateJavascript(String script) {
        activity.runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }
}
