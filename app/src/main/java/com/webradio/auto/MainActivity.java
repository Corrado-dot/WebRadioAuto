package com.webradio.auto;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity
        implements RadioMediaService.CommandListener {

    private static final String TAG = "MainActivity";

    // ============================================================
    // CONFIGURAZIONE - Cambia questo URL col tuo server
    // ============================================================
    private static final String WEBAPP_URL = "https://shopmio.altervista.org/radio/";
    // ============================================================

    private WebView webView;
    private Handler mainHandler;
    private boolean webViewReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(false);
        }

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webViewReady = true;
                injectBridgeScript();
            }
        });

        webView.loadUrl(WEBAPP_URL);

        Intent serviceIntent = new Intent(this, RadioMediaService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        RadioMediaService.setCommandListener(this);
    }

    private void injectBridgeScript() {
        String js = "(function() {" +
            "  if (window._androidBridgeReady) return;" +
            "  window._androidBridgeReady = true;" +
            "  window.onAndroidCommand = function(cmd) {" +
            "    try {" +
            "      if (cmd === 'next') {" +
            "        if (typeof window.nextStation === 'function') window.nextStation();" +
            "        else if (typeof nextStation === 'function') nextStation();" +
            "      } else if (cmd === 'prev') {" +
            "        if (typeof window.prevStation === 'function') window.prevStation();" +
            "        else if (typeof prevStation === 'function') prevStation();" +
            "      } else if (cmd === 'play') {" +
            "        var p = document.getElementById('player');" +
            "        if (p && p.paused) {" +
            "          if (typeof window.currentIndex !== 'undefined' && window.currentIndex !== null) {" +
            "            p.play();" +
            "          } else if (typeof window.stations !== 'undefined' && window.stations.length > 0) {" +
            "            if (typeof window.playStation === 'function') window.playStation(0);" +
            "          }" +
            "        }" +
            "      } else if (cmd === 'pause') {" +
            "        var p = document.getElementById('player');" +
            "        if (p && !p.paused) p.pause();" +
            "      }" +
            "    } catch(e) {}" +
            "  };" +
            "  var p = document.getElementById('player');" +
            "  if (p) {" +
            "    ['playing','pause','ended'].forEach(function(evt) {" +
            "      p.addEventListener(evt, function() {" +
            "        try {" +
            "          var st = document.getElementById('station-name');" +
            "          var ti = document.getElementById('title');" +
            "          AndroidBridge.updateState(" +
            "            st ? st.textContent : ''," +
            "            ti ? ti.textContent : ''," +
            "            !p.paused && !p.ended" +
            "          );" +
            "        } catch(e) {}" +
            "      });" +
            "    });" +
            "  }" +
            "  setInterval(function() {" +
            "    try {" +
            "      var p = document.getElementById('player');" +
            "      var st = document.getElementById('station-name');" +
            "      var ti = document.getElementById('title');" +
            "      AndroidBridge.updateState(" +
            "        st ? st.textContent : ''," +
            "        ti ? ti.textContent : ''," +
            "        p ? (!p.paused && !p.ended) : false" +
            "      );" +
            "    } catch(e) {}" +
            "  }, 3000);" +
            "  AndroidBridge.onBridgeReady();" +
            "})();";

        webView.evaluateJavascript(js, null);
    }

    private void execJs(String command) {
        if (!webViewReady || webView == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(
                        "if(typeof onAndroidCommand==='function')onAndroidCommand('" + command + "');",
                        null);
                } catch (Exception e) {
                    Log.e(TAG, "JS error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void onPrevious() { execJs("prev"); }

    @Override
    public void onNext() { execJs("next"); }

    @Override
    public void onPlay() { execJs("play"); }

    @Override
    public void onPause() { execJs("pause"); }

    private class AndroidBridge {
        @JavascriptInterface
        public void updateState(String station, String title, boolean playing) {
            RadioMediaService.updateNowPlaying(station, title, playing);
            RadioMediaService svc = RadioMediaService.getInstance();
            if (svc != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() { svc.updateNotification(); }
                });
            }
        }

        @JavascriptInterface
        public void onBridgeReady() {
            RadioMediaService svc = RadioMediaService.getInstance();
            if (svc != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() { svc.updateNotification(); }
                });
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                execJs("prev"); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                execJs("next"); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                execJs("play"); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                execJs("pause"); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                execJs("play"); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                execJs("prev"); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                execJs("next"); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioMediaService.setCommandListener(this);
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        RadioMediaService.setCommandListener(null);
        RadioMediaService svc = RadioMediaService.getInstance();
        if (svc != null) svc.stopService();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }
}
