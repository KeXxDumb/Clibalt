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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centraliza toda la lectura/escritura del historial de descargas y del
 * último tema (claro/oscuro) detectado, para no repetir manejo de JSON y
 * SharedPreferences por todos lados.
 */
public class HistoryStore {

    private static final String PREFS_NAME = "cobalt_wrapper_prefs";
    private static final String HISTORY_KEY = "link_history";
    private static final String THEME_DARK_KEY = "theme_is_dark";
    private static final int MAX_HISTORY = 100;

    private static final AtomicLong syntheticIdCounter = new AtomicLong(-1);

    private final Context context;

    public HistoryStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Genera un id único para descargas que no vienen de DownloadManager (ej. blobs). */
    public static long newSyntheticId() {
        return syntheticIdCounter.getAndDecrement();
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
                        o.isNull("thumb") ? null : o.optString("thumb", null),
                        o.optString("status", HistoryEntry.STATUS_COMPLETED),
                        o.isNull("fileUri") ? null : o.optString("fileUri", null),
                        o.isNull("mime") ? null : o.optString("mime", null)
                ));
            }
        } catch (JSONException ignored) { }
        return result;
    }

    /** Crea una entrada nueva en estado "descargando". Se llama justo cuando arranca una descarga real. */
    public void startEntry(String url, long downloadId) {
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));

            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("time", new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()));
            entry.put("downloadId", downloadId);
            entry.put("thumb", (String) null);
            entry.put("status", HistoryEntry.STATUS_DOWNLOADING);
            entry.put("fileUri", (String) null);
            entry.put("mime", (String) null);

            JSONArray updated = new JSONArray();
            updated.put(entry);
            for (int i = 0; i < history.length() && i < MAX_HISTORY - 1; i++) {
                updated.put(history.get(i));
            }

            prefs().edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public void markCompleted(long downloadId, String thumbPath, String fileUri, String mimeType) {
        updateEntry(downloadId, entry -> {
            try {
                entry.put("status", HistoryEntry.STATUS_COMPLETED);
                if (thumbPath != null) entry.put("thumb", thumbPath);
                if (fileUri != null) entry.put("fileUri", fileUri);
                if (mimeType != null) entry.put("mime", mimeType);
            } catch (JSONException ignored) { }
        });
    }

    public void markFailed(long downloadId) {
        updateEntry(downloadId, entry -> {
            try {
                entry.put("status", HistoryEntry.STATUS_FAILED);
            } catch (JSONException ignored) { }
        });
    }

    public void attachThumb(long downloadId, String thumbPath) {
        updateEntry(downloadId, entry -> {
            try {
                entry.put("thumb", thumbPath);
            } catch (JSONException ignored) { }
        });
    }

    private interface EntryMutator {
        void mutate(JSONObject entry);
    }

    private void updateEntry(long downloadId, EntryMutator mutator) {
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.getJSONObject(i);
                if (entry.optLong("downloadId", Long.MIN_VALUE) == downloadId) {
                    mutator.mutate(entry);
                    break;
                }
            }
            prefs().edit().putString(HISTORY_KEY, history.toString()).apply();
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

    // ---- Tema persistido (para evitar el flash de color equivocado al abrir la app) ----

    public void saveIsDark(boolean isDark) {
        prefs().edit().putBoolean(THEME_DARK_KEY, isDark).apply();
    }

    public boolean loadIsDark() {
        return prefs().getBoolean(THEME_DARK_KEY, true);
    }
}
