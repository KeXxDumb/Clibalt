package de.meowing.cobaltwrapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String COBALT_URL = "https://cobalt.meowing.de";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearProgressIndicator progressIndicator;
    private FrameLayout fullscreenContainer;
    private View rootLayout;

    private HistoryStore historyStore;
    private String pendingSharedText = null;
    private String lastKnownLink = null;
    private String lastKnownFilename = null;

    /** Prioriza el nombre real que cobalt le dio al archivo, luego el enlace pegado, y por último un genérico. */
    private String bestHistoryLabel(String fallback) {
        if (lastKnownFilename != null) return lastKnownFilename;
        if (lastKnownLink != null) return lastKnownLink;
        return fallback;
    }

    // Id sintético de la descarga por blob que está en curso (solo una a la vez).
    private long pendingBlobId = 0;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private BroadcastReceiver downloadCompleteReceiver;

    // JS inyectado en cada página: agrega el botón "history" en la barra de cobalt,
    // escucha pegados manuales en el campo de texto, limpia los controles nativos
    // de descarga/compartir de cualquier <video>, y reporta el color de tema real.
    private static final String INJECTED_JS =
        "(function() {" +
        "  function patchVideos() {" +
        "    var videos = document.querySelectorAll('video:not([data-history-patched])');" +
        "    for (var i = 0; i < videos.length; i++) {" +
        "      var v = videos[i];" +
        "      v.setAttribute('data-history-patched', '1');" +
        "      v.setAttribute('controlsList', 'nodownload noremoteplayback');" +
        "      try { v.disablePictureInPicture = true; } catch (e) {}" +
        "    }" +
        "  }" +
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
        "    a.innerHTML = '<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"9\"></circle><polyline points=\"12 7 12 12 15 15\"></polyline></svg><span class=\"tab-title svelte-y3bn2e\">history</span>';" +
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
        "  function patchBlobRegistry() {" +
        "    if (window.__blobPatchInstalled) return;" +
        "    window.__blobPatchInstalled = true;" +
        "    window.__cobaltBlobs = {};" +
        "    var origCreate = URL.createObjectURL.bind(URL);" +
        "    URL.createObjectURL = function(blob) {" +
        "      var url = origCreate(blob);" +
        "      window.__cobaltBlobs[url] = blob;" +
        "      return url;" +
        "    };" +
        "  }" +
        "  function patchFetchForFilename() {" +
        "    if (window.__fetchPatched) return;" +
        "    window.__fetchPatched = true;" +
        "    var origFetch = window.fetch;" +
        "    window.fetch = function() {" +
        "      var args = arguments;" +
        "      return origFetch.apply(this, args).then(function(response) {" +
        "        try {" +
        "          var reqUrl = (args[0] && args[0].url) ? args[0].url : args[0];" +
        "          if (typeof reqUrl === 'string' && reqUrl.indexOf('cobalt-api') !== -1) {" +
        "            response.clone().json().then(function(data) {" +
        "              var filename = (data && data.filename) || (data && data.output && data.output.filename);" +
        "              if (filename) { AndroidBridge.onFilenameKnown(filename); }" +
        "            }).catch(function() {});" +
        "          }" +
        "        } catch (e) {}" +
        "        return response;" +
        "      });" +
        "    };" +
        "  }" +
        "  function patchSaveFilePicker() {" +
        "    if (!window.showSaveFilePicker || window.__saveFPPatched) return;" +
        "    window.__saveFPPatched = true;" +
        "    var origPicker = window.showSaveFilePicker.bind(window);" +
        "    window.showSaveFilePicker = function(options) {" +
        "      return origPicker(options).then(function(handle) {" +
        "        var name = (options && options.suggestedName) || handle.name || 'cobalt file';" +
        "        var origCreateWritable = handle.createWritable.bind(handle);" +
        "        handle.createWritable = function() {" +
        "          return origCreateWritable().then(function(writable) {" +
        "            var origClose = writable.close.bind(writable);" +
        "            writable.close = function() {" +
        "              return origClose().then(function(res) {" +
        "                AndroidBridge.onNativeSaveCompleted(name);" +
        "                return res;" +
        "              });" +
        "            };" +
        "            return writable;" +
        "          });" +
        "        };" +
        "        return handle;" +
        "      });" +
        "    };" +
        "  }" +
        "  patchSaveFilePicker();" +
        "  patchFetchForFilename();" +
        "  patchBlobRegistry();" +
        "  patchVideos();" +
        "  reportThemeColor();" +
        "  setInterval(function() { addHistoryButton(); attachPasteListener(); patchVideos(); reportThemeColor(); }, 1000);" +
        "})();";

    // Detecta el color de fondo real que cobalt está pintando (sigue su tema
    // auto/light/dark tal cual esté configurado, o el modo del sistema si
    // cobalt está en "auto") y lo reporta a Android para sincronizar las
    // barras de estado/navegación y el propio historial.
    // Si la app se abre por primera vez (todavía no hay nada guardado),
    // precarga estos ajustes en vez de dejar que cobalt use los suyos por
    // defecto. Si el usuario ya cambió algo, esto no toca nada.
    private static final String DEFAULT_SETTINGS_JS =
        "(function() {" +
        "  try {" +
        "    if (!localStorage.getItem('settings')) {" +
        "      localStorage.setItem('settings', JSON.stringify(" +
        "        {\"appearance\":{\"hideRemuxTab\":true,\"theme\":\"auto\"},\"schemaVersion\":6,\"save\":{\"savingMethod\":\"ask\"}}" +
        "      ));" +
        "    }" +
        "  } catch (e) {}" +
        "})();";

    private static final String THEME_DETECT_JS =
        "function reportThemeColor() {" +
        "  function isTransparent(c) { return !c || c === 'rgba(0, 0, 0, 0)' || c === 'transparent'; }" +
        "  var el = document.elementFromPoint(10, 10) || document.body;" +
        "  var bg = null;" +
        "  var guard = 0;" +
        "  while (el && guard < 15) {" +
        "    var c = window.getComputedStyle(el).backgroundColor;" +
        "    if (!isTransparent(c)) { bg = c; break; }" +
        "    el = el.parentElement;" +
        "    guard++;" +
        "  }" +
        "  if (!bg) bg = window.getComputedStyle(document.body).backgroundColor;" +
        "  if (bg && bg !== window.__lastReportedBg) {" +
        "    window.__lastReportedBg = bg;" +
        "    AndroidBridge.onThemeColor(bg);" +
        "  }" +
        "}";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        historyStore = new HistoryStore(this);

        rootLayout = findViewById(R.id.root_layout);
        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressIndicator = findViewById(R.id.progress_indicator);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        // Aplica de inmediato el último tema conocido (guardado de una sesión
        // anterior) para que la pantalla de carga no "flashee" en claro si
        // cobalt estaba en oscuro.
        ThemeState.apply(historyStore.loadIsDark());
        applySystemBarsFromThemeState();
        rootLayout.setBackgroundColor(ThemeState.background);
        webView.setBackgroundColor(ThemeState.background);

        setupWebView();

        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        pendingSharedText = extractSharedText(getIntent());

        registerDownloadCompleteReceiver();

        webView.loadUrl(COBALT_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Sin esto, WebView nunca le informa a la página cuándo el sistema
        // está en modo oscuro, así que cualquier CSS "prefers-color-scheme"
        // (como el modo "auto" de cobalt) siempre ve "claro" sin importar
        // el modo real del teléfono.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        }

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(DEFAULT_SETTINGS_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                view.evaluateJavascript(THEME_DETECT_JS + INJECTED_JS, null);
                if (pendingSharedText != null) {
                    lastKnownLink = pendingSharedText;
                    lastKnownFilename = null;
                    injectSharedText(pendingSharedText);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress >= 100) {
                    progressIndicator.setVisibility(View.GONE);
                } else {
                    progressIndicator.setVisibility(View.VISIBLE);
                    progressIndicator.setProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view);
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.setVisibility(View.GONE);
                fullscreenContainer.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                webView.setVisibility(View.VISIBLE);
            }

            // Intercepta cualquier intento de abrir pestaña nueva (window.open).
            // Si la página trataba de iniciar una descarga, la enrutamos a
            // nuestro propio gestor; nunca se abre una pestaña real ni un
            // selector nativo de Android.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView probeWebView = new WebView(MainActivity.this);
                probeWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView probeView, WebResourceRequest request) {
                        startDownload(request.getUrl().toString(), webView.getSettings().getUserAgentString());
                        probeView.destroy();
                        return true;
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(probeWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> startDownload(url, userAgent));
    }

    /**
     * Punto único de entrada para cualquier intento de descarga, sea cual sea
     * el origen (DownloadListener o window.open interceptado). Nunca deja
     * que una excepción tumbe la app: los esquemas no soportados por
     * DownloadManager (blob:, data:) se manejan aparte.
     */
    private void startDownload(String url, String userAgent) {
        try {
            if (url.startsWith("blob:")) {
                startBlobDownload(url);
            } else if (url.startsWith("data:")) {
                startDataUriDownload(url);
            } else {
                startHttpDownload(url, userAgent);
            }
        } catch (Exception e) {
            Snackbar.make(rootLayout, "Couldn't start this download", Snackbar.LENGTH_LONG).show();
        }
    }

    private void startHttpDownload(String url, String userAgent) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // DownloadManager hace una petición de red aparte, sin la sesión de
        // la página. Esto solo tiene sentido para enlaces del propio cobalt
        // (su tunnel puede depender de sesión); para un CDN externo como
        // video.twimg.com, mandar un Referer/cookie ajeno puede activar su
        // protección anti-hotlink y hacer que la descarga sea rechazada.
        String host = Uri.parse(url).getHost();
        boolean isCobaltHost = host != null && host.contains("meowing.de");
        if (isCobaltHost) {
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("cookie", cookie);
            request.addRequestHeader("Referer", COBALT_URL);
        }
        if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);

        String fileName = Uri.parse(url).getLastPathSegment();
        if (fileName == null || fileName.isEmpty()) fileName = "cobalt_download";
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        historyStore.startEntry(bestHistoryLabel(fileName), downloadId);
        Snackbar.make(rootLayout, R.string.snackbar_download_started, Snackbar.LENGTH_SHORT).show();
        pollDownloadStatus(downloadId);
    }

    // Algunos archivos (ej. gifs generados en el momento) llegan como blob:
    // en vez de una URL http normal. DownloadManager no puede leerlos
    // directamente y antes esto crasheaba la app; ahora se leen desde la
    // propia página vía JS y se guardan manualmente.
    private void startBlobDownload(String blobUrl) {
        pendingBlobId = HistoryStore.newSyntheticId();
        historyStore.startEntry(bestHistoryLabel("cobalt file"), pendingBlobId);
        Snackbar.make(rootLayout, R.string.snackbar_download_started, Snackbar.LENGTH_SHORT).show();

        // No volvemos a pedir la URL por red (el archivo puede no existir en
        // ningún servidor, como pasa con los gifs generados por
        // "local-processing" de cobalt). En vez de eso, leemos el Blob real
        // que quedó guardado en memoria cuando se creó, vía el registro que
        // instalamos en patchBlobRegistry().
        String escapedUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'");
        String js =
            "(function() {" +
            "  var blob = window.__cobaltBlobs ? window.__cobaltBlobs['" + escapedUrl + "'] : null;" +
            "  if (!blob) { AndroidBridge.onBlobError('blob not found in registry (already released?)'); return; }" +
            "  var reader = new FileReader();" +
            "  reader.onloadend = function() {" +
            "    var base64 = reader.result.split(',')[1];" +
            "    AndroidBridge.onBlobReady(base64, blob.type || 'application/octet-stream');" +
            "  };" +
            "  reader.onerror = function() { AndroidBridge.onBlobError('FileReader error'); };" +
            "  reader.readAsDataURL(blob);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void startDataUriDownload(String dataUri) {
        long id = HistoryStore.newSyntheticId();
        historyStore.startEntry(bestHistoryLabel("cobalt file"), id);
        Snackbar.make(rootLayout, R.string.snackbar_download_started, Snackbar.LENGTH_SHORT).show();

        try {
            String header = dataUri.substring(5, dataUri.indexOf(','));
            String mimeType = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            String base64Data = dataUri.substring(dataUri.indexOf(',') + 1);
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            saveDownloadedBytes(id, bytes, mimeType);
        } catch (Exception e) {
            historyStore.markFailed(id);
            Snackbar.make(rootLayout, "Download failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    /** Escribe los bytes ya descargados (blob/data URI) en la carpeta pública de Descargas. */
    private void saveDownloadedBytes(long entryId, byte[] bytes, String mimeType) {
        new Thread(() -> {
            try {
                String extension = guessExtension(mimeType);
                String fileName = "cobalt_" + System.currentTimeMillis() + extension;
                Uri resultUri;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    resultUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (resultUri == null) throw new Exception("MediaStore insert failed");
                    OutputStream out = getContentResolver().openOutputStream(resultUri);
                    if (out == null) throw new Exception("Couldn't open output stream");
                    out.write(bytes);
                    out.close();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File file = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();
                    resultUri = Uri.fromFile(file);
                }

                String thumbPath = null;
                if (mimeType != null && mimeType.startsWith("image/")) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) thumbPath = saveThumbToDisk(bitmap, entryId);
                }

                historyStore.markCompleted(entryId, thumbPath, resultUri.toString(), mimeType);
                runOnUiThread(() -> Snackbar.make(rootLayout, "Download complete", Snackbar.LENGTH_SHORT).show());
            } catch (Exception e) {
                historyStore.markFailed(entryId);
                String detail = e.getMessage();
                runOnUiThread(() -> Snackbar.make(rootLayout, "Download failed: " + detail, Snackbar.LENGTH_LONG).show());
            }
        }).start();
    }

    private String guessExtension(String mimeType) {
        if (mimeType == null) return "";
        if (mimeType.contains("gif")) return ".gif";
        if (mimeType.contains("png")) return ".png";
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) return ".jpg";
        if (mimeType.contains("mp4")) return ".mp4";
        if (mimeType.contains("webm")) return ".webm";
        if (mimeType.contains("mp3") || mimeType.contains("mpeg")) return ".mp3";
        return "";
    }

    /**
     * Consulta activamente el estado de la descarga cada 2 segundos hasta
     * que termine (bien o mal). Es un respaldo de ACTION_DOWNLOAD_COMPLETE:
     * en varios fabricantes (MIUI y similares) ese aviso del sistema puede
     * no llegar nunca por las restricciones agresivas de batería, dejando
     * la entrada del historial atascada en "downloading" para siempre.
     */
    private void pollDownloadStatus(long downloadId) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts++;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                int status = -1;
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIdx != -1) status = cursor.getInt(statusIdx);
                    }
                    cursor.close();
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    handleDownloadComplete(downloadId);
                } else if (attempts < 150) { // ~5 minutos como máximo
                    handler.postDelayed(this, 2000);
                } else {
                    historyStore.markFailed(downloadId);
                    Snackbar.make(rootLayout, "Download timed out", Snackbar.LENGTH_LONG).show();
                }
            }
        }, 1500);
    }

    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != -1) handleDownloadComplete(id);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    private void handleDownloadComplete(long downloadId) {
        new Thread(() -> {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                Cursor cursor = dm.query(query);
                if (cursor == null) return;

                int status = -1;
                int reason = -1;
                if (cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (statusIdx != -1) status = cursor.getInt(statusIdx);
                    int reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                    if (reasonIdx != -1) reason = cursor.getInt(reasonIdx);
                }
                cursor.close();

                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    historyStore.markFailed(downloadId);
                    int finalReason = reason;
                    runOnUiThread(() -> Snackbar.make(rootLayout,
                            "Download failed (reason " + finalReason + ")", Snackbar.LENGTH_LONG).show());
                    return;
                }

                Uri fileUri = dm.getUriForDownloadedFile(downloadId);
                String mime = dm.getMimeTypeForDownloadedFile(downloadId);
                String thumbPath = generateThumbnail(downloadId, fileUri, mime);

                historyStore.markCompleted(downloadId, thumbPath, fileUri != null ? fileUri.toString() : null, mime);
            } catch (Exception e) {
                historyStore.markFailed(downloadId);
            }
        }).start();
    }

    private String generateThumbnail(long downloadId, Uri fileUri, String mime) {
        if (fileUri == null) return null;
        try {
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
                if (art != null) thumb = BitmapFactory.decodeByteArray(art, 0, art.length);
                retriever.release();
            } else if (mime != null && mime.startsWith("image/")) {
                InputStream is = getContentResolver().openInputStream(fileUri);
                if (is != null) {
                    thumb = BitmapFactory.decodeStream(is);
                    is.close();
                }
            }

            return thumb != null ? saveThumbToDisk(thumb, downloadId) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String saveThumbToDisk(Bitmap bitmap, long id) throws Exception {
        File dir = new File(getFilesDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "thumb_" + id + ".jpg");

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true);
        FileOutputStream fos = new FileOutputStream(out);
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        fos.close();
        return out.getAbsolutePath();
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
            pendingSharedText = shared;
            lastKnownLink = shared;
            lastKnownFilename = null;
            injectSharedText(shared);
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
        // Reintenta hasta 30 segundos por si el campo de texto de cobalt
        // todavía no está montado (conexión lenta) en el momento del intento.
        String js =
            "(function() {" +
            "  var value = '" + escaped + "';" +
            "  var attempts = 0;" +
            "  var timer = setInterval(function() {" +
            "    attempts++;" +
            "    var el = document.querySelector('input[type=text], input:not([type]), textarea, input[type=url], input[type=search]');" +
            "    if (el) {" +
            "      el.focus();" +
            "      var proto = window.HTMLInputElement.prototype;" +
            "      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;" +
            "      setter.call(el, value);" +
            "      el.dispatchEvent(new Event('input', { bubbles: true }));" +
            "      el.dispatchEvent(new Event('change', { bubbles: true }));" +
            "      clearInterval(timer);" +
            "      AndroidBridge.onLinkInjected();" +
            "    }" +
            "    if (attempts > 100) clearInterval(timer);" +
            "  }, 300);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void showHistorySheet() {
        HistoryBottomSheet sheet = new HistoryBottomSheet();
        sheet.setListener(new HistoryBottomSheet.OnHistoryActionListener() {
            @Override
            public void onLinkSelected(String url) {
                injectSharedText(url);
            }

            @Override
            public void onOpenFile(String fileUri, String mimeType) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(fileUri), mimeType != null ? mimeType : "*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Snackbar.make(rootLayout, "Couldn't open this file", Snackbar.LENGTH_SHORT).show();
                }
            }
        });
        sheet.show(getSupportFragmentManager(), "history");
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void openHistory() {
            runOnUiThread(MainActivity.this::showHistorySheet);
        }

        @JavascriptInterface
        public void onLinkEntered(String url) {
            lastKnownLink = url;
            lastKnownFilename = null;
        }

        // Se llama cuando el enlace realmente quedó insertado en la caja de
        // texto de cobalt. Solo mostramos el aviso y limpiamos el pendiente
        // si de verdad veníamos de un "compartir" (evita mostrar el mensaje
        // al reinyectar un enlace desde el historial, por ejemplo).
        @JavascriptInterface
        public void onLinkInjected() {
            runOnUiThread(() -> {
                if (pendingSharedText != null) {
                    pendingSharedText = null;
                    Snackbar.make(rootLayout, R.string.snackbar_link_received, Snackbar.LENGTH_SHORT).show();
                }
            });
        }

        // Nombre real que cobalt le puso al archivo (extraído de su propia
        // respuesta JSON), para que el historial no diga "cobalt file".
        @JavascriptInterface
        public void onFilenameKnown(String filename) {
            lastKnownFilename = filename;
        }

        // Algunos archivos (videos grandes de YouTube, por ejemplo) se
        // guardan directo con el selector nativo de Android
        // (showSaveFilePicker), sin pasar por blob ni por DownloadManager.
        // No manejamos nosotros el archivo, pero al menos queda registrado.
        @JavascriptInterface
        public void onNativeSaveCompleted(String filename) {
            runOnUiThread(() -> {
                long id = HistoryStore.newSyntheticId();
                historyStore.startEntry(bestHistoryLabel(filename), id);
                historyStore.markCompleted(id, null, null, null);
                Snackbar.make(rootLayout, "Download complete", Snackbar.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onThemeColor(String rgbColor) {
            runOnUiThread(() -> applyThemeColor(rgbColor));
        }

        @JavascriptInterface
        public void onBlobReady(String base64Data, String mimeType) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    saveDownloadedBytes(pendingBlobId, bytes, mimeType);
                } catch (Exception e) {
                    historyStore.markFailed(pendingBlobId);
                    String detail = e.getMessage();
                    runOnUiThread(() -> Snackbar.make(rootLayout, "Download failed: " + detail, Snackbar.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void onBlobError(String message) {
            historyStore.markFailed(pendingBlobId);
            runOnUiThread(() -> Snackbar.make(rootLayout, "Download failed: " + message, Snackbar.LENGTH_LONG).show());
        }
    }

    // Sincroniza la barra de estado y de navegación con el color real que
    // cobalt está pintando, y ajusta el color de los íconos del sistema
    // para mantener buen contraste en cualquier tema (auto/light/dark).
    // También guarda el resultado para que el próximo arranque de la app
    // ya abra con el tema correcto desde el primer fotograma.
    private void applyThemeColor(String rgbColor) {
        try {
            int color = parseCssColor(rgbColor);

            double luminance = (0.299 * Color.red(color)
                    + 0.587 * Color.green(color)
                    + 0.114 * Color.blue(color)) / 255.0;
            boolean lightBackground = luminance > 0.5;

            ThemeState.apply(!lightBackground);
            historyStore.saveIsDark(!lightBackground);

            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);

            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightBackground) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);

            rootLayout.setBackgroundColor(color);
            webView.setBackgroundColor(color);
        } catch (Exception ignored) { }
    }

    /** Reaplica el color de barras cacheado (usado al arrancar, antes de que la página reporte el real). */
    private void applySystemBarsFromThemeState() {
        getWindow().setStatusBarColor(ThemeState.background);
        getWindow().setNavigationBarColor(ThemeState.background);

        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!ThemeState.isDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    // Convierte "rgb(20, 20, 20)" o "rgba(20, 20, 20, 1)" (lo que devuelve
    // getComputedStyle) en un color de Android.
    private int parseCssColor(String rgb) {
        String nums = rgb.substring(rgb.indexOf('(') + 1, rgb.indexOf(')'));
        String[] parts = nums.split(",");
        int r = (int) Float.parseFloat(parts[0].trim());
        int g = (int) Float.parseFloat(parts[1].trim());
        int b = (int) Float.parseFloat(parts[2].trim());
        return Color.rgb(r, g, b);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
