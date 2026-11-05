package tw.kurocore.kurogram.features.impl;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import tw.kurocore.kurogram.feature.SpecialContacts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

/** Special Contacts: storage plus the background monitor (observers, change detection, notifications, history). */
public final class SpecialContactsImpl implements SpecialContacts, NotificationCenter.NotificationCenterDelegate {

    private static final String PREFS = "kuro_special_contacts";
    private static final String CHANNEL_ID = "kuro_special_contacts";
    private static final int MAX_ACTIONS = 100;

    private boolean started;

    private static class State {
        Boolean online;
        long photoId;
        String username;
    }

    private final HashMap<Long, State> states = new HashMap<>();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void onAppStart(Context app) {
        AndroidUtilities.runOnUIThread(() -> {
            if (started) {
                return;
            }
            started = true;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter nc = NotificationCenter.getInstance(a);
                nc.addObserver(this, NotificationCenter.updateInterfaces);
                nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
                nc.addObserver(this, NotificationCenter.messagesRead);
            }
        });
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ArrayList<Long> loadContactIds() {
        ArrayList<Long> ids = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("contacts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                ids.add(arr.getLong(i));
            }
        } catch (JSONException ignored) {
        }
        return ids;
    }

    @Override
    public boolean isEnabled() {
        return prefs().getBoolean("enabled", true);
    }

    @Override
    public void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
    }

    @Override
    public boolean isBackground() {
        return prefs().getBoolean("background", false);
    }

    @Override
    public void setBackground(boolean background) {
        prefs().edit().putBoolean("background", background).apply();
    }

    @Override
    public ArrayList<Long> contactIds() {
        return loadContactIds();
    }

    @Override
    public void saveContactIds(java.util.List<Long> ids) {
        JSONArray arr = new JSONArray();
        for (Long id : ids) {
            arr.put((long) id);
        }
        prefs().edit().putString("contacts", arr.toString()).apply();
    }

    @Override
    public JSONObject settings(long userId) {
        String saved = prefs().getString("settings_" + userId, null);
        if (saved != null) {
            try {
                return new JSONObject(saved);
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    @Override
    public void saveSettings(long userId, JSONObject settings) {
        prefs().edit().putString("settings_" + userId, settings.toString()).apply();
    }

    @Override
    public void removeSettings(long userId) {
        prefs().edit().remove("settings_" + userId).apply();
    }

    private static JSONObject loadSettings(long userId) {
        String saved = prefs().getString("settings_" + userId, null);
        if (saved != null) {
            try {
                return new JSONObject(saved);
            } catch (JSONException ignored) {
            }
        }
        return new JSONObject();
    }

    private static boolean defaultFor(String key) {
        return key.equals("goingOnline") || key.equals("readingMessage")
                || key.equals("changingProfilePicture") || key.equals("changingUsername");
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (!prefs().getBoolean("enabled", true)) {
                return;
            }
            ArrayList<Long> ids = loadContactIds();
            if (ids.isEmpty()) {
                return;
            }
            if (id == NotificationCenter.updateInterfaces) {
                if (args.length == 0 || !(args[0] instanceof Integer)) {
                    return;
                }
                int mask = (Integer) args[0];
                int relevant = MessagesController.UPDATE_MASK_STATUS | MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME;
                if ((mask & relevant) == 0) {
                    return;
                }
                for (long userId : ids) {
                    checkUser(account, userId);
                }
            } else if (id == NotificationCenter.messagesRead) {
                if (args.length < 2 || !(args[1] instanceof org.telegram.messenger.support.LongSparseIntArray)) {
                    return;
                }
                org.telegram.messenger.support.LongSparseIntArray outbox = (org.telegram.messenger.support.LongSparseIntArray) args[1];
                for (int i = 0; i < outbox.size(); i++) {
                    long dialogId = outbox.keyAt(i);
                    if (ids.contains(dialogId)) {
                        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
                        if (user != null) {
                            notifyAction(account, user, "readingMessage", "read your message");
                        }
                    }
                }
            } else if (id == NotificationCenter.didReceiveNewMessages) {
                if (args.length < 2 || !(args[0] instanceof Long) || !(args[1] instanceof ArrayList)) {
                    return;
                }
                long dialogId = (Long) args[0];
                if (!ids.contains(dialogId)) {
                    return;
                }
                ArrayList<?> list = (ArrayList<?>) args[1];
                int now = ConnectionsManager.getInstance(account).getCurrentTime();
                for (Object o : list) {
                    if (o instanceof MessageObject) {
                        MessageObject m = (MessageObject) o;
                        if (!m.isOut() && m.messageOwner != null && now - m.messageOwner.date < 120) {
                            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
                            if (user != null) {
                                notifyAction(account, user, "sendingMessage", "sent you a message");
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean isOnline(int account, TLRPC.User user) {
        return user.status instanceof TLRPC.TL_userStatusOnline
                && user.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
    }

    private void checkUser(int account, long userId) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user == null) {
            return;
        }
        State s = states.get(userId);
        if (s == null) {
            s = new State();
            states.put(userId, s);
        }

        if (user.status != null) {
            boolean online = isOnline(account, user);
            if (s.online != null && s.online != online) {
                notifyAction(account, user, online ? "goingOnline" : "goingOffline", online ? "is now online" : "went offline");
            }
            s.online = online;
        }

        long photoId = user.photo != null ? user.photo.photo_id : 0;
        if (photoId != 0) {
            if (s.photoId != 0 && s.photoId != photoId) {
                notifyAction(account, user, "changingProfilePicture", "changed their profile picture");
            }
            s.photoId = photoId;
        }

        String username = UserObject.getPublicUsername(user);
        if (username != null && !username.isEmpty()) {
            if (s.username != null && !s.username.isEmpty() && !s.username.equals(username)) {
                notifyAction(account, user, "changingUsername", "changed username to @" + username);
            }
            s.username = username;
        }
    }

    private void notifyAction(int account, TLRPC.User user, String key, String text) {
        JSONObject settings = loadSettings(user.id);
        if (!settings.optBoolean(key, defaultFor(key))) {
            return;
        }
        addAction(user.id, text);
        if (settings.optBoolean("actionsNotification", true)) {
            String name = ContactsController.formatName(user.first_name, user.last_name);
            showNotification(account, user, name, text);
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Special Contacts", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void showNotification(int account, TLRPC.User user, String name, String text) {
        Context ctx = ApplicationLoader.applicationContext;
        ensureChannel(ctx);
        Intent intent = new Intent(ctx, LaunchActivity.class);
        intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("userId", user.id);
        intent.putExtra("currentAccount", account);
        int notifId = Objects.hash(user.id, text);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, intent, flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(name)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);
        NotificationManagerCompat.from(ctx).notify(notifId, b.build());
    }

    @Override
    public ArrayList<Action> actions(long userId) {
        ArrayList<Action> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("actions_" + userId, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Action a = new Action();
                a.text = o.optString("t");
                a.time = o.optLong("time");
                list.add(a);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private void addAction(long userId, String text) {
        ArrayList<Action> list = actions(userId);
        Action a = new Action();
        a.text = text;
        a.time = System.currentTimeMillis();
        list.add(0, a);
        while (list.size() > MAX_ACTIONS) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (Action x : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("t", x.text);
                o.put("time", x.time);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        prefs().edit().putString("actions_" + userId, arr.toString()).apply();
    }

    @Override
    public String lastActionText(long userId) {
        ArrayList<Action> list = actions(userId);
        if (list.isEmpty()) {
            return null;
        }
        Action a = list.get(0);
        return a.text + " \u2022 " + SpecialContacts.formatAgo(a.time);
    }
}
