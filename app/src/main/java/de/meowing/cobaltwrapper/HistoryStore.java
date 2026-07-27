package de.meowing.cobaltwrapper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Centraliza toda la lectura/escritura del historial de enlaces,
 * para no repetir manejo de JSON y SharedPreferences por todos lados.
 */
public class HistoryStore {

    private static final String PREFS_NAME = "cobalt_wrapper_prefs";
    private static final String HISTORY_KEY = "link_history";
    private static final int MAX_HISTORY = 100;

    private final Context context;

    public HistoryStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<HistoryEntry> loadAll() {
        List<HistoryEntry> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new HistoryEntry(
                        o.optString("url", ""),
                        o.optString("time", ""),
                        o.optLong("downloadId", -1),
                        o.isNull("thumb") ? null : o.optString("thumb", null)
                ));
            }
        } catch (JSONException ignored) { }
        return result;
    }

    public void add(String url, long downloadId) {
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));

            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("time", new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()));
            entry.put("downloadId", downloadId);
            entry.put("thumb", (String) null);

            JSONArray updated = new JSONArray();
            updated.put(entry);
            for (int i = 0; i < history.length() && i < MAX_HISTORY - 1; i++) {
                updated.put(history.get(i));
            }

            prefs().edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public void removeAt(int index) {
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < history.length(); i++) {
                if (i != index) updated.put(history.get(i));
            }
            prefs().edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public void clear() {
        prefs().edit().putString(HISTORY_KEY, "[]").apply();
        File thumbDir = new File(context.getFilesDir(), "thumbs");
        File[] files = thumbDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    public void attachThumb(long downloadId, String thumbPath) {
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.getJSONObject(i);
                if (entry.optLong("downloadId", -1) == downloadId) {
                    entry.put("thumb", thumbPath);
                    break;
                }
            }
            prefs().edit().putString(HISTORY_KEY, history.toString()).apply();
        } catch (JSONException ignored) { }
    }
}
