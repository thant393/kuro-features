package tw.kurocore.kurogram.features.impl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;

import tw.kurocore.kurogram.feature.ContactChanges;

/** Contact Changes: detects name / profile photo changes of the contacts and keeps the log and the snapshot. */
public final class ContactChangesImpl implements ContactChanges {

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nk_contact_changes", Context.MODE_PRIVATE);
    }

    @Override
    public ArrayList<Entry> log() {
        ArrayList<Entry> logEntries = new ArrayList<>();
        String saved = prefs().getString("log", "[]");
        try {
            JSONArray arr = new JSONArray(saved);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Entry entry = new Entry();
                entry.userId = obj.optLong("uid");
                entry.name = obj.optString("name");
                entry.type = obj.optString("type");
                entry.oldValue = obj.optString("old");
                entry.newValue = obj.optString("new");
                entry.time = obj.optLong("time");
                logEntries.add(entry);
            }
        } catch (JSONException ignored) {
        }
        Collections.reverse(logEntries);
        return logEntries;
    }

    @Override
    public void clearLog() {
        saveLog(new ArrayList<>());
    }

    @Override
    public boolean checkForChanges(int account) {
        ArrayList<Entry> entries = log();
        if (detect(account, entries)) {
            saveLog(entries);
            return true;
        }
        return false;
    }

    private void saveLog(ArrayList<Entry> logEntries) {
        JSONArray arr = new JSONArray();
        ArrayList<Entry> chronological = new ArrayList<>(logEntries);
        Collections.reverse(chronological);
        int start = Math.max(0, chronological.size() - 200);
        for (int i = start; i < chronological.size(); i++) {
            Entry entry = chronological.get(i);
            JSONObject obj = new JSONObject();
            try {
                obj.put("uid", entry.userId);
                obj.put("name", entry.name);
                obj.put("type", entry.type);
                obj.put("old", entry.oldValue);
                obj.put("new", entry.newValue);
                obj.put("time", entry.time);
                arr.put(obj);
            } catch (JSONException ignored) {
            }
        }
        prefs().edit().putString("log", arr.toString()).apply();
    }

    private JSONObject loadSnapshot() {
        String saved = prefs().getString("snapshot", "{}");
        try {
            return new JSONObject(saved);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveSnapshot(JSONObject snapshot) {
        prefs().edit().putString("snapshot", snapshot.toString()).apply();
    }

    private boolean detect(int currentAccount, ArrayList<Entry> logEntries) {
        JSONObject snapshot = loadSnapshot();
        boolean changed = false;

        for (TLRPC.TL_contact contact : ContactsController.getInstance(currentAccount).contacts) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(contact.user_id);
            if (user == null || UserObject.isDeleted(user) || UserObject.isUserSelf(user)) {
                continue;
            }
            String key = String.valueOf(user.id);
            String currentName = ContactsController.formatName(user.first_name, user.last_name);
            long photoId = (user.photo != null) ? user.photo.photo_id : 0;

            JSONObject prevData = snapshot.optJSONObject(key);
            if (prevData == null) {
                JSONObject newData = new JSONObject();
                try {
                    newData.put("name", currentName);
                    newData.put("photoId", photoId);
                    snapshot.put(key, newData);
                } catch (JSONException ignored) {
                }
                changed = true;
                continue;
            }

            String prevName = prevData.optString("name");
            long prevPhotoId = prevData.optLong("photoId");

            if (!prevName.equals(currentName)) {
                addLogEntry(logEntries, user.id, currentName, "name", prevName, currentName);
                try {
                    prevData.put("name", currentName);
                } catch (JSONException ignored) {
                }
                changed = true;
            }

            if (prevPhotoId != photoId) {
                addLogEntry(logEntries, user.id, currentName, "photo", null, null);
                try {
                    prevData.put("photoId", photoId);
                } catch (JSONException ignored) {
                }
                changed = true;
            }
        }

        if (changed) {
            saveSnapshot(snapshot);
        }
        return changed;
    }

    private void addLogEntry(ArrayList<Entry> logEntries, long userId, String name, String type, String oldValue, String newValue) {
        Entry entry = new Entry();
        entry.userId = userId;
        entry.name = name;
        entry.type = type;
        entry.oldValue = oldValue;
        entry.newValue = newValue;
        entry.time = System.currentTimeMillis();
        logEntries.add(0, entry);
    }
}
