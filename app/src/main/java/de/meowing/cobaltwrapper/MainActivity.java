package de.meowing.cobaltwrapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.DownloadManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
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

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private BroadcastReceiver downloadCompleteReceiver;

    // JS inyectado en cada página: agrega el botón "history" en la barra de cobalt,
    // escucha pegados manuales en el campo de texto, y limpia los controles nativos
    // de descarga/compartir de cualquier <video>.
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
        "  patchVideos();" +
        "  setInterval(function() { addHistoryButton(); attachPasteListener(); patchVideos(); }, 1000);" +
        "})();";

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                view.evaluateJavascript(INJECTED_JS, null);
                if (pendingSharedText != null) {
                    injectSharedText(pendingSharedText);
                    historyStore.add(pendingSharedText, -1);
                    Snackbar.make(rootLayout, R.string.snackbar_link_received, Snackbar.LENGTH_SHORT).show();
                    pendingSharedText = null;
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
                        startDownload(request.getUrl().toString());
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

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> startDownload(url));
    }

    private void startDownload(String url) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String fileName = Uri.parse(url).getLastPathSegment();
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        historyStore.add(lastKnownLink != null ? lastKnownLink : fileName, downloadId);
        Snackbar.make(rootLayout, R.string.snackbar_download_started, Snackbar.LENGTH_SHORT).show();
    }

    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != -1) generateThumbnailForDownload(id);
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
            historyStore.add(shared, -1);
            Snackbar.make(rootLayout, R.string.snackbar_link_received, Snackbar.LENGTH_SHORT).show();
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
        // Reintenta durante unos segundos por si el campo de texto de cobalt
        // todavía no está montado en el momento en que termina de cargar la página.
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
            "    }" +
            "    if (attempts > 20) clearInterval(timer);" +
            "  }, 300);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void showHistorySheet() {
        HistoryBottomSheet sheet = new HistoryBottomSheet();
        sheet.setListener(this::injectSharedTextFromHistory);
        sheet.show(getSupportFragmentManager(), "history");
    }

    private void injectSharedTextFromHistory(String url) {
        injectSharedText(url);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void openHistory() {
            runOnUiThread(MainActivity.this::showHistorySheet);
        }

        @JavascriptInterface
        public void onLinkEntered(String url) {
            lastKnownLink = url;
            runOnUiThread(() -> historyStore.add(url, -1));
        }
    }

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
                    if (art != null) thumb = BitmapFactory.decodeByteArray(art, 0, art.length);
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
                    historyStore.attachThumb(downloadId, path);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private String saveThumbToDisk(Bitmap bitmap, long downloadId) throws Exception {
        File dir = new File(getFilesDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "thumb_" + downloadId + ".jpg");

        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true);
        FileOutputStream fos = new FileOutputStream(out);
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        fos.close();
        return out.getAbsolutePath();
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
