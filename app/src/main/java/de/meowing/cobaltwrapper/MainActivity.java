package de.meowing.cobaltwrapper;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
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

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String COBALT_URL = "https://cobalt.meowing.de";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private FrameLayout fullscreenContainer;
    private View rootLayout;

    private HistoryStore historyStore;
    private DownloadNotifier downloadNotifier;

    private String pendingSharedText = null;
    private String lastKnownLink = null;
    private String lastKnownFilename = null;
    private String lastClipboardPrompt = null;

    private long pendingBlobId = 0;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private BroadcastReceiver downloadCompleteReceiver;

    // ---------- JS inyectado ----------

    // Botón "history" en la barra de cobalt, escucha de pegado manual,
    // parche de controles nativos de video, registro de blobs reales,
    // parche de fetch (para saber el nombre real del archivo), y parche
    // del selector nativo de guardado (para archivos grandes).
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
        "  addHistoryButton();" +
        "  attachPasteListener();" +
        "  patchBlobRegistry();" +
        "  patchFetchForFilename();" +
        "  patchSaveFilePicker();" +
        "  patchVideos();" +
        "  reportThemeColor();" +
        "  setInterval(function() { addHistoryButton(); attachPasteListener(); patchVideos(); reportThemeColor(); }, 1000);" +
        "})();";

    // Detecta el color real que cobalt está pintando subiendo por los
    // elementos padres desde un punto visible, sin asumir que es html o
    // body específicamente (cobalt podría usar un div de fondo aparte).
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

    // Si es instalación nueva (sin nada guardado todavía), deja precargados
    // estos ajustes de cobalt en vez de sus valores por defecto.
    private static final String DEFAULT_SETTINGS_JS =
        "(function() {" +
        "  try {" +
        "    if (!localStorage.getItem('settings')) {" +
        "      localStorage.setItem('settings', JSON.stringify(" +
        "        {\"appearance\":{\"hideRemuxTab\":true,\"theme\":\"dark\"},\"schemaVersion\":6,\"save\":{\"savingMethod\":\"ask\"}}" +
        "      ));" +
        "    }" +
        "  } catch (e) {}" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        historyStore = new HistoryStore(this);
        downloadNotifier = new DownloadNotifier(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        rootLayout = findViewById(R.id.root_layout);
        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

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

            // Intercepta cualquier intento de abrir pestaña nueva
            // (window.open). Si era para iniciar una descarga, la
            // enrutamos a nuestro gestor; nunca se abre una pestaña real.
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

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                startDownload(url, userAgent));
    }

    // ---------- DESCARGAS ----------

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
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

        // Cookies/Referer solo tienen sentido para el propio dominio de
        // cobalt (su tunnel puede depender de sesión); mandarlos a un CDN
        // externo (twimg.com, googlevideo.com, etc.) puede activar su
        // protección anti-hotlink y tumbar la descarga.
        String host = Uri.parse(url).getHost();
        if (host != null && host.contains("meowing.de")) {
            String cookie = CookieManager.getInstance().getCookie(url);
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
        downloadNotifier.showIndeterminate(notifIdFor(downloadId), bestHistoryLabel(fileName));
        pollDownloadStatus(downloadId);
    }

    // Algunos archivos (gifs generados al vuelo, videos convertidos client-side)
    // llegan como blob: en vez de una URL http normal — el archivo solo existe
    // en la memoria del navegador. Lo leemos del registro que instalamos con
    // patchBlobRegistry() en vez de intentar re-pedirlo por red.
    private void startBlobDownload(String blobUrl) {
        pendingBlobId = HistoryStore.newSyntheticId();
        historyStore.startEntry(bestHistoryLabel("cobalt file"), pendingBlobId);
        Snackbar.make(rootLayout, R.string.snackbar_download_started, Snackbar.LENGTH_SHORT).show();
        downloadNotifier.showIndeterminate(notifIdFor(pendingBlobId), bestHistoryLabel("cobalt file"));

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
        downloadNotifier.showIndeterminate(notifIdFor(id), bestHistoryLabel("cobalt file"));

        try {
            String header = dataUri.substring(5, dataUri.indexOf(','));
            String mimeType = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            String base64Data = dataUri.substring(dataUri.indexOf(',') + 1);
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            saveDownloadedBytes(id, bytes, mimeType);
        } catch (Exception e) {
            historyStore.markFailed(id);
            downloadNotifier.showFailed(notifIdFor(id), "Download failed");
            Snackbar.make(rootLayout, "Download failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

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
                downloadNotifier.showCompleted(notifIdFor(entryId), bestHistoryLabel("cobalt file"));
                runOnUiThread(() -> Snackbar.make(rootLayout, "Download complete", Snackbar.LENGTH_SHORT).show());
            } catch (Exception e) {
                historyStore.markFailed(entryId);
                downloadNotifier.showFailed(notifIdFor(entryId), "Download failed");
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

    private int notifIdFor(long id) {
        return (int) (Math.abs(id) % Integer.MAX_VALUE);
    }

    // Sondeo activo del estado de la descarga, como respaldo del broadcast
    // del sistema (en varios fabricantes, MIUI entre ellos, ese aviso puede
    // no llegar nunca por las restricciones de batería).
    private void pollDownloadStatus(long downloadId) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts++;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                Cursor cursor = dm.query(query);

                int status = -1;
                long bytesDownloaded = -1;
                long bytesTotal = -1;
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIdx != -1) status = cursor.getInt(statusIdx);
                        int downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        if (downloadedIdx != -1) bytesDownloaded = cursor.getLong(downloadedIdx);
                        int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        if (totalIdx != -1) bytesTotal = cursor.getLong(totalIdx);
                    }
                    cursor.close();
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    handleDownloadComplete(downloadId);
                } else if (attempts < 150) {
                    if (bytesTotal > 0) {
                        int percent = (int) Math.min(100, (bytesDownloaded * 100) / bytesTotal);
                        downloadNotifier.showProgress(notifIdFor(downloadId), "Downloading…", percent);
                    }
                    handler.postDelayed(this, 2000);
                } else {
                    historyStore.markFailed(downloadId);
                    downloadNotifier.showFailed(notifIdFor(downloadId), "Download timed out");
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
                    downloadNotifier.showFailed(notifIdFor(downloadId), "Download failed");
                    int finalReason = reason;
                    runOnUiThread(() -> Snackbar.make(rootLayout,
                            "Download failed (reason " + finalReason + ")", Snackbar.LENGTH_LONG).show());
                    return;
                }

                Uri fileUri = dm.getUriForDownloadedFile(downloadId);
                String mime = dm.getMimeTypeForDownloadedFile(downloadId);
                String thumbPath = generateThumbnail(fileUri, mime, downloadId);

                historyStore.markCompleted(downloadId, thumbPath, fileUri != null ? fileUri.toString() : null, mime);
                downloadNotifier.showCompleted(notifIdFor(downloadId), bestHistoryLabel("cobalt file"));
            } catch (Exception e) {
                historyStore.markFailed(downloadId);
                downloadNotifier.showFailed(notifIdFor(downloadId), "Download failed");
            }
        }).start();
    }

    private String generateThumbnail(Uri fileUri, String mime, long id) {
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

            return thumb != null ? saveThumbToDisk(thumb, id) : null;
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

    // ---------- ENLACES COMPARTIDOS / PORTAPAPELES ----------

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

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingSharedText != null) return;
        checkClipboardForLink();
    }

    private void checkClipboardForLink() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;

            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return;
            String clipText = text.toString().trim();

            if (!clipText.startsWith("http")) return;
            if (clipText.equals(lastClipboardPrompt)) return;

            lastClipboardPrompt = clipText;
            Snackbar.make(rootLayout, "Paste copied link into cobalt?", Snackbar.LENGTH_LONG)
                    .setAction("Paste", v -> {
                        lastKnownLink = clipText;
                        lastKnownFilename = null;
                        injectSharedText(clipText);
                    })
                    .show();
        } catch (Exception ignored) { }
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

    private String bestHistoryLabel(String fallback) {
        if (lastKnownFilename != null) return lastKnownFilename;
        if (lastKnownLink != null) return lastKnownLink;
        return fallback;
    }

    // ---------- HISTORIAL ----------

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

    // ---------- PUENTE JS <-> ANDROID ----------

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

        @JavascriptInterface
        public void onLinkInjected() {
            runOnUiThread(() -> {
                if (pendingSharedText != null) {
                    pendingSharedText = null;
                    Snackbar.make(rootLayout, R.string.snackbar_link_received, Snackbar.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void onThemeColor(String rgbColor) {
            runOnUiThread(() -> applyThemeColor(rgbColor));
        }

        @JavascriptInterface
        public void onFilenameKnown(String filename) {
            lastKnownFilename = filename;
        }

        @JavascriptInterface
        public void onBlobReady(String base64Data, String mimeType) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    saveDownloadedBytes(pendingBlobId, bytes, mimeType);
                } catch (Exception e) {
                    historyStore.markFailed(pendingBlobId);
                    downloadNotifier.showFailed(notifIdFor(pendingBlobId), "Download failed");
                    String detail = e.getMessage();
                    runOnUiThread(() -> Snackbar.make(rootLayout, "Download failed: " + detail, Snackbar.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void onBlobError(String message) {
            historyStore.markFailed(pendingBlobId);
            downloadNotifier.showFailed(notifIdFor(pendingBlobId), "Download failed");
            runOnUiThread(() -> Snackbar.make(rootLayout, "Download failed: " + message, Snackbar.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void onNativeSaveCompleted(String filename) {
            runOnUiThread(() -> {
                long id = HistoryStore.newSyntheticId();
                historyStore.startEntry(bestHistoryLabel(filename), id);
                historyStore.markCompleted(id, null, null, null);
                downloadNotifier.showCompleted(notifIdFor(id), bestHistoryLabel(filename));
                Snackbar.make(rootLayout, "Download complete", Snackbar.LENGTH_SHORT).show();
            });
        }
    }

    // ---------- TEMA ----------

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
