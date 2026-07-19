package de.meowing.cobaltwrapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private String pendingSharedText = null;

    private static final String COBALT_URL = "https://cobalt.meowing.de";
    private static final String PREFS_NAME = "cobalt_wrapper_prefs";
    private static final String HISTORY_KEY = "link_history";
    private static final int MAX_HISTORY = 100;

    private BroadcastReceiver downloadCompleteReceiver;

    // --- JS que se inyecta en cada página cargada ---
    // 1) agrega el botón "historial" dentro de la barra de cobalt (#sidebar-info)
    // 2) escucha el evento "paste" del campo de texto principal para registrar enlaces pegados a mano
    private static final String INJECTED_JS =
        "(function() {" +
        "  function addHistoryButton() {" +
        "    if (document.getElementById('sidebar-tab-history')) return;" +
        "    var container = document.getElementById('sidebar-info');" +
        "    if (!container) return;" +
        "    var a = document.createElement('a');" +
        "    a.id = 'sidebar-tab-history';" +
        "    a.className = 'sidebar-tab svelte-y3bn2e';" +
        "    a.setAttribute('role', 'tab');" +
        "    a.setAttribute('aria-selected', 'false');" +
        "    a.href = 'javascript:void(0)';" +
        "    a.innerHTML = '<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"9\"></circle><polyline points=\"12 7 12 12 15 15\"></polyline></svg><span class=\"tab-title svelte-y3bn2e\">historial</span>';" +
        "    a.onclick = function() { AndroidBridge.openHistory(); };" +
        "    container.insertBefore(a, container.firstChild);" +
        "  }" +
        "  function attachPasteListener() {" +
        "    var el = document.querySelector('input[type=text], input:not([type]), textarea, input[type=url], input[type=search]');" +
        "    if (!el || el.dataset.historyBound) return;" +
        "    el.dataset.historyBound = '1';" +
        "    el.addEventListener('paste', function(e) {" +
        "      setTimeout(function() {" +
        "        var val = el.value || '';" +
        "        if (val.indexOf('http') === 0) { AndroidBridge.onLinkEntered(val); }" +
        "      }, 50);" +
        "    });" +
        "  }" +
        "  addHistoryButton();" +
        "  attachPasteListener();" +
        "  setInterval(function() { addHistoryButton(); attachPasteListener(); }, 2000);" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        pendingSharedText = extractSharedText(getIntent());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(INJECTED_JS, null);
                if (pendingSharedText != null) {
                    injectSharedText(pendingSharedText);
                    saveToHistory(pendingSharedText, -1);
                    pendingSharedText = null;
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String fileName = Uri.parse(url).getLastPathSegment();
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long downloadId = dm.enqueue(request);

            // Registramos la descarga en el historial ligada a este downloadId;
            // la miniatura se completará luego, cuando termine de descargar.
            saveToHistory(lastKnownLink != null ? lastKnownLink : fileName, downloadId);
        });

        registerDownloadCompleteReceiver();

        webView.loadUrl(COBALT_URL);
    }

    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != -1) {
                    generateThumbnailForDownload(id);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadCompleteReceiver != null) {
            try {
                unregisterReceiver(downloadCompleteReceiver);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String shared = extractSharedText(intent);
        if (shared != null) {
            injectSharedText(shared);
            saveToHistory(shared, -1);
        }
    }

    private String extractSharedText(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            return intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        return null;
    }

    private void injectSharedText(String text) {
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
        String js =
            "(function() {" +
            "  var el = document.querySelector('input[type=text], input:not([type]), textarea, input[type=url], input[type=search]');" +
            "  if (el) {" +
            "    el.focus();" +
            "    var proto = window.HTMLInputElement.prototype;" +
            "    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;" +
            "    setter.call(el, '" + escaped + "');" +
            "    el.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
            "  }" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // ---------- PUENTE JS <-> ANDROID ----------

    private String lastKnownLink = null;

    public class WebAppInterface {
        @JavascriptInterface
        public void openHistory() {
            runOnUiThread(MainActivity.this::showHistoryDialog);
        }

        @JavascriptInterface
        public void onLinkEntered(String url) {
            lastKnownLink = url;
            runOnUiThread(() -> saveToHistory(url, -1));
        }
    }

    // ---------- HISTORIAL ----------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveToHistory(String url, long downloadId) {
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

    private void clearHistory() {
        prefs().edit().putString(HISTORY_KEY, "[]").apply();
        // Limpiamos también las miniaturas guardadas en disco
        File thumbDir = new File(getFilesDir(), "thumbs");
        File[] files = thumbDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    // Se llama cuando una descarga termina; busca la entrada del historial
    // que corresponde a ese downloadId y le genera/adjunta una miniatura real.
    private void generateThumbnailForDownload(long downloadId) {
        new Thread(() -> {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                Uri fileUri = dm.getUriForDownloadedFile(downloadId);
                if (fileUri == null) return;

                String mime = dm.getMimeTypeForDownloadedFile(downloadId);
                Bitmap thumb = null;

                if (mime != null && mime.startsWith("video/")) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(this, fileUri);
                    thumb = retriever.getFrameAtTime(1_000_000);
                    retriever.release();
                } else if (mime != null && mime.startsWith("audio/")) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(this, fileUri);
                    byte[] art = retriever.getEmbeddedPicture();
                    if (art != null) {
                        thumb = BitmapFactory.decodeByteArray(art, 0, art.length);
                    }
                    retriever.release();
                } else if (mime != null && mime.startsWith("image/")) {
                    InputStream is = getContentResolver().openInputStream(fileUri);
                    if (is != null) {
                        thumb = BitmapFactory.decodeStream(is);
                        is.close();
                    }
                }

                if (thumb != null) {
                    String path = saveThumbToDisk(thumb, downloadId);
                    attachThumbToHistory(downloadId, path);
                }
            } catch (Exception ignored) {
                // Si no se puede generar la miniatura, simplemente se omite
            }
        }).start();
    }

    private String saveThumbToDisk(Bitmap bitmap, long downloadId) throws Exception {
        File dir = new File(getFilesDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "thumb_" + downloadId + ".jpg");

        int size = 200;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size, size, true);

        FileOutputStream fos = new FileOutputStream(out);
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        fos.close();
        return out.getAbsolutePath();
    }

    private void attachThumbToHistory(long downloadId, String thumbPath) {
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

    private void showHistoryDialog() {
        List<JSONObject> entries = new ArrayList<>();
        try {
            JSONArray history = new JSONArray(prefs().getString(HISTORY_KEY, "[]"));
            for (int i = 0; i < history.length(); i++) {
                entries.add(history.getJSONObject(i));
            }
        } catch (JSONException ignored) { }

        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Historial")
                    .setMessage("Todavía no hay enlaces registrados.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }

        ListView listView = new ListView(this);
        listView.setAdapter(new HistoryAdapter(entries));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Historial de enlaces")
                .setView(listView)
                .setNegativeButton("Borrar historial", (d, w) -> clearHistory())
                .setPositiveButton("Cerrar", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String url = entries.get(position).optString("url", "");
            injectSharedText(url);
            dialog.dismiss();
        });

        dialog.show();
    }

    private class HistoryAdapter extends BaseAdapter {
        private final List<JSONObject> items;

        HistoryAdapter(List<JSONObject> items) {
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.history_item, parent, false);
            }

            JSONObject entry = items.get(position);
            TextView timeView = row.findViewById(R.id.time);
            TextView urlView = row.findViewById(R.id.url);
            ImageView thumbView = row.findViewById(R.id.thumb);

            timeView.setText(entry.optString("time", ""));
            urlView.setText(entry.optString("url", ""));

            String thumbPath = entry.optString("thumb", null);
            if (thumbPath != null && !thumbPath.equals("null") && new File(thumbPath).exists()) {
                thumbView.setImageBitmap(BitmapFactory.decodeFile(thumbPath));
            } else {
                thumbView.setImageBitmap(null);
                thumbView.setBackgroundColor(0xFFE0E0E0);
            }

            return row;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
