package com.messexpense.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Periodically checks the selected group's event log while the app is not open. */
public final class BackgroundSyncWorker extends Worker {
    static final String UNIQUE_WORK = "mess_expense_remote_change_check";
    static final String PREFS = "mess_expense_background_sync_v1";
    static final String KEY_GROUP = "group_id";
    static final String KEY_GROUP_NAME = "group_name";
    static final String KEY_DEVICE = "device_id";
    static final String KEY_SEEN_TIME = "seen_time";
    static final String KEY_SEEN_IDS = "seen_ids";
    static final String KEY_SYNC_BASE_STATE = "sync_base_state";
    static final String KEY_SYNC_LOCAL_STATE = "sync_local_state";
    static final String KEY_SYNC_EVENT_IDS = "sync_event_ids";
    static final String KEY_UI_ACTIVE_AT = "ui_active_at";
    static final String KEY_LAST_ATTEMPT = "last_background_attempt_ms";
    static final String KEY_LAST_SUCCESS = "last_background_success_ms";
    static final String KEY_LAST_ERROR = "last_background_error";
    static final Object STATE_LOCK = new Object();
    private static final int MAX_STORED_STATE_CHARS = 512 * 1024;
    private static final String CHANNEL_ID = "mess_expense_remote_updates";
    private static final int NOTIFICATION_ID = 4107;

    public BackgroundSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply();
        final String groupId;
        final String deviceId;
        final String baselineJson;
        final String localJson;
        final Set<String> syncSeenIds;
        synchronized (STATE_LOCK) {
            if (uiRecentlyActive(prefs)) return Result.success();
            groupId = prefs.getString(KEY_GROUP, "");
            deviceId = prefs.getString(KEY_DEVICE, "");
            baselineJson = prefs.getString(KEY_SYNC_BASE_STATE, "");
            localJson = prefs.getString(KEY_SYNC_LOCAL_STATE, "");
            try { syncSeenIds = readIds(prefs.getString(KEY_SYNC_EVENT_IDS, "[]")); }
            catch (JSONException e) { return Result.success(); }
        }
        if (groupId == null || groupId.isEmpty() || deviceId == null || deviceId.isEmpty() ||
                baselineJson == null || baselineJson.isEmpty() || localJson == null || localJson.isEmpty()) return Result.success();

        try {
            if (FirebaseApp.getApps(context).isEmpty()) return Result.success();
            FirebaseApp app = FirebaseApp.getInstance();
            FirebaseAuth auth = FirebaseAuth.getInstance(app);
            if (auth.getCurrentUser() == null) return Result.success();
            FirebaseFirestore firestore = FirebaseFirestore.getInstance(app);
            QuerySnapshot snapshot = Tasks.await(
                    firestore.collection("groups").document(groupId).collection("events").orderBy("time").get(),
                    25, TimeUnit.SECONDS);
            if (isStopped()) return Result.success();

            JSONObject baseline = new JSONObject(baselineJson);
            JSONObject local = new JSONObject(localJson);
            if (!validState(baseline) || !validState(local)) return Result.success();
            JSONArray allEvents = new JSONArray();
            JSONArray externalPatches = new JSONArray();
            JSONArray ownPatches = new JSONArray();
            Set<String> allIds = new HashSet<>();

            String seenTime;
            Set<String> notifiedIds;
            synchronized (STATE_LOCK) {
                if (!groupId.equals(prefs.getString(KEY_GROUP, "")) || uiRecentlyActive(prefs)) return Result.success();
                seenTime = prefs.getString(KEY_SEEN_TIME, "");
                try { notifiedIds = readIds(prefs.getString(KEY_SEEN_IDS, "[]")); }
                catch (JSONException e) { notifiedIds = new HashSet<>(); }
            }
            String newestTime = seenTime == null ? "" : seenTime;
            Set<String> newestIds = new HashSet<>();
            if (seenTime != null && !seenTime.isEmpty()) newestIds.addAll(notifiedIds);
            int newRemoteCount = 0;

            for (DocumentSnapshot document : snapshot.getDocuments()) {
                String id = document.getId();
                String time = document.getString("time");
                String device = document.getString("device");
                Object rawPatch = document.get("patch");
                if (id == null || time == null || device == null || !(rawPatch instanceof java.util.List)) continue;
                Object decodedPatch = FirestoreArrayCodec.decode(rawPatch);
                if (!(decodedPatch instanceof java.util.List)) continue;
                JSONArray patch = toJsonArray((java.util.List<?>) decodedPatch);
                if (patch.length() == 0 || !validPatch(patch)) continue;
                JSONObject event = new JSONObject();
                event.put("id", id); event.put("time", time); event.put("device", device); event.put("patch", patch);
                allEvents.put(event);
                allIds.add(id);
                if (!syncSeenIds.contains(id)) {
                    if (device.equals(deviceId)) ownPatches.put(patch);
                    else externalPatches.put(patch);
                }

                if (time.compareTo(newestTime) > 0) { newestTime = time; newestIds.clear(); }
                if (time.equals(newestTime)) newestIds.add(id);
                boolean unseenForNotification = seenTime == null || time.compareTo(seenTime) > 0 ||
                        (time.equals(seenTime) && !notifiedIds.contains(id));
                if (unseenForNotification && !deviceId.equals(device)) newRemoteCount++;
            }

            JSONObject remote = replay(allEvents);
            if (remote == null || !validState(remote)) return Result.success();
            JSONObject baseWithOwn = applyPatches(baseline, ownPatches);
            JSONArray localOps = diff(baseWithOwn, local);
            JSONArray remoteOps = flattenPatches(externalPatches);
            boolean collision = overlaps(localOps, remoteOps);
            JSONObject merged = null;
            if (!collision && externalPatches.length() > 0) {
                merged = applyOperations(remote, localOps);
                if (!validState(merged)) return Result.success();
            }
            if (!collision && externalPatches.length() == 0 && ownPatches.length() > 0) {
                merged = applyOperations(remote, localOps);
                if (!validState(merged)) return Result.success();
            }

            if (newRemoteCount > 0 && canNotify(context)) {
                showNotification(context, prefs.getString(KEY_GROUP_NAME, "your shared group"), newRemoteCount);
            }
            synchronized (STATE_LOCK) {
                if (isStopped() || !groupId.equals(prefs.getString(KEY_GROUP, "")) || uiRecentlyActive(prefs)) return Result.success();
                android.content.SharedPreferences.Editor edit = prefs.edit();
                if (!collision && merged != null) {
                    // Keep foreground sync's conflict baseline, but advance durable local state and
                    // baseline together so reopened UI sees remote changes plus local pending edits.
                    if (jsonSize(remote) > MAX_STORED_STATE_CHARS || jsonSize(merged) > MAX_STORED_STATE_CHARS) return Result.success();
                    edit.putString(KEY_SYNC_BASE_STATE, remote.toString())
                            .putString(KEY_SYNC_LOCAL_STATE, merged.toString())
                            .putString(KEY_SYNC_EVENT_IDS, toJson(allIds));
                }
                if (newRemoteCount == 0 || canNotify(context)) {
                    edit.putString(KEY_SEEN_TIME, newestTime).putString(KEY_SEEN_IDS, toJson(newestIds));
                }
                edit.commit();
            }
            prefs.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply();
            return Result.success();
        } catch (Exception e) {
            if (isStopped()) return Result.success();
            String message = e.getLocalizedMessage();
            if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
            if (message.length() > 240) message = message.substring(0, 240);
            prefs.edit().putString(KEY_LAST_ERROR, message).apply();
            return Result.retry();
        }
    }

    private static boolean uiRecentlyActive(android.content.SharedPreferences prefs) {
        long activeAt = prefs.getLong(KEY_UI_ACTIVE_AT, 0L);
        return activeAt > 0 && System.currentTimeMillis() - activeAt < TimeUnit.MINUTES.toMillis(2);
    }

    private static int jsonSize(JSONObject value) { return value.toString().length(); }

    private static JSONArray toJsonArray(java.util.List<?> source) throws JSONException {
        JSONArray result = new JSONArray();
        for (Object value : source) result.put(toJsonValue(value));
        return result;
    }

    private static Object toJsonValue(Object value) throws JSONException {
        if (value instanceof java.util.Map) {
            JSONObject object = new JSONObject();
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet())
                object.put(String.valueOf(entry.getKey()), toJsonValue(entry.getValue()));
            return object;
        }
        if (value instanceof java.util.List) return toJsonArray((java.util.List<?>) value);
        return value == null ? JSONObject.NULL : value;
    }

    private static JSONObject replay(JSONArray events) throws JSONException {
        JSONObject result = null;
        for (int i = 0; i < events.length(); i++) {
            JSONArray patch = events.getJSONObject(i).getJSONArray("patch");
            result = applyOperations(result, patch);
        }
        return result;
    }

    private static JSONObject applyPatches(JSONObject base, JSONArray patches) throws JSONException {
        JSONObject result = base == null ? null : new JSONObject(base.toString());
        for (int i = 0; i < patches.length(); i++) result = applyOperations(result, patches.getJSONArray(i));
        return result;
    }

    private static JSONArray flattenPatches(JSONArray patches) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < patches.length(); i++) {
            JSONArray patch = patches.getJSONArray(i);
            for (int j = 0; j < patch.length(); j++) result.put(patch.getJSONObject(j));
        }
        return result;
    }

    private static JSONObject applyOperations(JSONObject base, JSONArray operations) throws JSONException {
        JSONObject result = base == null ? null : new JSONObject(base.toString());
        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.getJSONObject(i);
            String path = op.getString("path");
            Object value = op.get("value");
            if (path.isEmpty()) { result = value instanceof JSONObject ? new JSONObject(value.toString()) : null; continue; }
            if (result == null) continue;
            String[] keys = path.split("\\.", -1);
            Object cursor = result;
            for (int k = 0; k < keys.length - 1; k++) {
                String key = keys[k];
                if (cursor instanceof JSONObject) cursor = ((JSONObject) cursor).opt(key);
                else if (cursor instanceof JSONArray) cursor = ((JSONArray) cursor).opt(parseIndex(key));
                else { cursor = null; break; }
                if (cursor == null || cursor == JSONObject.NULL) break;
            }
            String last = keys[keys.length - 1];
            if (cursor instanceof JSONObject) ((JSONObject) cursor).put(last, cloneJson(value));
            else if (cursor instanceof JSONArray) { int idx = parseIndex(last); if (idx >= 0 && idx < ((JSONArray) cursor).length()) ((JSONArray) cursor).put(idx, cloneJson(value)); }
        }
        return result;
    }

    private static int parseIndex(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return -1; } }
    private static Object cloneJson(Object value) throws JSONException {
        if (value instanceof JSONObject) return new JSONObject(value.toString());
        if (value instanceof JSONArray) return new JSONArray(value.toString());
        return value;
    }

    private static JSONArray diff(Object before, Object after) throws JSONException {
        JSONArray out = new JSONArray();
        diffInto(before, after, "", out);
        return out;
    }

    private static void diffInto(Object before, Object after, String path, JSONArray out) throws JSONException {
        if (jsonEquals(before, after)) return;
        if (before instanceof JSONArray && after instanceof JSONArray && ((JSONArray) before).length() == ((JSONArray) after).length()) {
            JSONArray a = (JSONArray) before, b = (JSONArray) after;
            for (int i = 0; i < b.length(); i++) diffInto(a.opt(i), b.opt(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, out);
        } else if (before instanceof JSONObject && after instanceof JSONObject) {
            JSONObject a = (JSONObject) before, b = (JSONObject) after;
            java.util.Iterator<String> it = a.keys(); boolean sameKeys = a.length() == b.length();
            while (sameKeys && it.hasNext()) sameKeys = b.has(it.next());
            if (sameKeys) {
                it = b.keys(); while (it.hasNext()) { String key = it.next(); diffInto(a.opt(key), b.opt(key), path.isEmpty() ? key : path + "." + key, out); }
            } else addOperation(out, path, after);
        } else addOperation(out, path, after);
    }

    private static void addOperation(JSONArray out, String path, Object value) throws JSONException {
        out.put(new JSONObject().put("path", path).put("value", cloneJson(value)));
    }

    private static boolean overlaps(JSONArray localOps, JSONArray remoteOps) throws JSONException {
        for (int i = 0; i < localOps.length(); i++) for (int j = 0; j < remoteOps.length(); j++) {
            String a = localOps.getJSONObject(i).getString("path"), b = remoteOps.getJSONObject(j).getString("path");
            if (pathsOverlap(a, b)) return true;
        }
        return false;
    }

    private static boolean pathsOverlap(String a, String b) {
        String[] x = a.split("\\.", -1), y = b.split("\\.", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) if (!x[i].equals(y[i])) return false;
        return true;
    }

    private static boolean jsonEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            java.util.Iterator<String> it = x.keys(); while (it.hasNext()) { String k = it.next(); if (!y.has(k) || !jsonEquals(x.opt(k), y.opt(k))) return false; }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b; if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!jsonEquals(x.opt(i), y.opt(i))) return false;
            return true;
        }
        if (a == JSONObject.NULL || b == JSONObject.NULL) return a == b;
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number)a).doubleValue(), ((Number)b).doubleValue()) == 0;
        return a.equals(b);
    }

    private static boolean validPatch(JSONArray patch) throws JSONException {
        if (patch.length() == 0) return false;
        for (int i = 0; i < patch.length(); i++) {
            JSONObject op = patch.getJSONObject(i); String path = op.optString("path", null);
            if (path == null || !op.has("value")) return false;
            Object v = op.get("value");
            if (path.isEmpty()) { if (!(v instanceof JSONObject) || !validState((JSONObject)v)) return false; }
            else if (path.equals("startDate")) { if (!(v instanceof String) || (!((String)v).isEmpty() && !((String)v).matches("\\d{4}-\\d{2}-\\d{2}"))) return false; }
            else if (path.equals("members")) { if (!(v instanceof JSONArray) || !validMembers((JSONArray)v)) return false; }
            else if (path.equals("meals")) { if (!(v instanceof JSONArray) || !validMeals((JSONArray)v, -1)) return false; }
            else if (path.equals("spend")) { if (!(v instanceof JSONArray) || !validSpend((JSONArray)v, -1)) return false; }
            else if (path.matches("members\\.(?:0|[1-9]\\d?)")) { if (!(v instanceof String) || ((String)v).length() > 100) return false; }
            else if (path.matches("meals\\.(?:0|[1-9]\\d?)\\.[0-6]\\.[01]")) { if (!validNumber(v)) return false; }
            else if (path.matches("spend\\.(?:0|[1-9]\\d?)\\.(?:morning|night|extra)")) { if (!validNumber(v)) return false; }
            else return false;
        }
        return true;
    }

    private static boolean validState(JSONObject s) {
        if (s == null) return false;
        String date = s.optString("startDate", "");
        JSONArray members = s.optJSONArray("members"), meals = s.optJSONArray("meals"), spend = s.optJSONArray("spend");
        return (date.isEmpty() || date.matches("\\d{4}-\\d{2}-\\d{2}")) && members != null && members.length() > 0 &&
                members.length() <= 100 && validMembers(members) && validMeals(meals, members.length()) && validSpend(spend, members.length());
    }

    private static boolean validMembers(JSONArray members) {
        if (members == null || members.length() == 0 || members.length() > 100) return false;
        for (int i = 0; i < members.length(); i++) { Object v = members.opt(i); if (!(v instanceof String) || ((String)v).length() > 100) return false; }
        return true;
    }
    private static boolean validMeals(JSONArray meals, int expected) {
        if (meals == null || meals.length() == 0 || meals.length() > 100 || (expected >= 0 && meals.length() != expected)) return false;
        for (int i = 0; i < meals.length(); i++) {
            JSONArray row = meals.optJSONArray(i); if (row == null || row.length() != 7) return false;
            for (int d = 0; d < 7; d++) { JSONArray day = row.optJSONArray(d); if (day == null || day.length() != 2 || !validNumber(day.opt(0)) || !validNumber(day.opt(1))) return false; }
        }
        return true;
    }
    private static boolean validSpend(JSONArray spend, int expected) {
        if (spend == null || spend.length() == 0 || spend.length() > 100 || (expected >= 0 && spend.length() != expected)) return false;
        for (int i = 0; i < spend.length(); i++) {
            JSONObject row = spend.optJSONObject(i); if (row == null || !validNumber(row.opt("morning")) || !validNumber(row.opt("night")) || !validNumber(row.opt("extra"))) return false;
        }
        return true;
    }
    private static boolean validNumber(Object v) {
        if (!(v instanceof Number)) return false; double n = ((Number)v).doubleValue(); return Double.isFinite(n) && n >= 0;
    }

    private static Set<String> readIds(String json) throws JSONException {
        Set<String> ids = new HashSet<>();
        JSONArray array = new JSONArray(json == null ? "[]" : json);
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static String toJson(Set<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        return array.toString();
    }

    private static boolean canNotify(Context context) {
        // Create the channel before checking it. If the user disabled this channel, do not
        // advance the notification high-water mark: those unseen updates can be announced
        // after the channel is re-enabled.
        createChannel(context);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL_ID);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    private static void showNotification(Context context, String groupName, int count) {
        createChannel(context);
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent contentIntent = launchIntent == null ? null : PendingIntent.getActivity(context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        String title = count == 1 ? "New shared tracker update" : "New shared tracker updates";
        String body = count == 1 ? "A group member updated " + safeGroupName(groupName) + ". Open Mess Expense to apply it."
                : count + " new changes arrived in " + safeGroupName(groupName) + ". Open Mess Expense to apply them.";
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (contentIntent != null) notification.setContentIntent(contentIntent);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build());
    }

    private static String safeGroupName(String name) {
        if (name == null || name.trim().isEmpty()) return "your shared group";
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "Shared tracker updates", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
