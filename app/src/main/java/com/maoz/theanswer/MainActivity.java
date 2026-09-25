package com.maoz.theanswer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.webkit.ValueCallback;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Shows the contract search page (web.py on the server, reached over Tailscale) full screen.
 * The page calls window.AndroidApp.copy(text) and .openExternal(url): the Android clipboard and
 * the Claude app (or the browser) instead of the WebView's limited clipboard and popups.
 */
public class MainActivity extends Activity {
    static final String DEFAULT_URL = "http://100.71.25.67:8765/";
    private WebView web;
    private SharedPreferences prefs;
    private boolean errorShown, failed;
    private int retries;
    private ValueCallback<Uri[]> fileCallback;  // the page's <input type=file>, waiting for the picker
    private static final int PICK_FILES = 7;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " TheAnswerApp/1");
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");  // the page checks the extension; DXF has no reliable MIME type
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(pick, "בחר קבצים"), PICK_FILES);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri target = req.getUrl();
                if (target.getHost() != null && target.getHost().equals(Uri.parse(serverUrl()).getHost())) {
                    return false;  // the search page itself
                }
                openExternal(target.toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                failed = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                failed = true;
                if (retries++ < 2) {  // the server restarting (an update) drops the connection for a few seconds
                    view.postDelayed(() -> view.loadUrl(serverUrl()), 3000);
                    return;
                }
                retries = 0;
                showConnectionError(String.valueOf(err.getDescription()));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                errorShown = false;
                if (!failed) retries = 0;
            }
        });
        if (state != null) web.restoreState(state); else web.loadUrl(serverUrl());
        if (state == null) Updater.check(this, false);  // offers a newer release, silent otherwise
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_STATUS.equals(intent.getAction())) Updater.onStatus(this, intent);
    }

    private String serverUrl() {
        return prefs.getString("url", DEFAULT_URL);
    }

    private void openExternal(String url) {
        if (!url.startsWith("https://")) return;  // only web links leave the app
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "לא נמצאה אפליקציה לפתיחת הקישור", Toast.LENGTH_LONG).show();
        }
    }

    private void showConnectionError(String detail) {
        if (errorShown || isFinishing()) return;
        errorShown = true;
        web.loadData("<html dir='rtl'><body style='font-family:sans-serif;padding:24px'>"
                + "<h3>אין חיבור לשרת החיפוש</h3><p>בדוק ש-Tailscale מחובר בטלפון ושהשרת דולק.</p></body></html>",
                "text/html; charset=utf-8", "UTF-8");
        new AlertDialog.Builder(this)
                .setTitle("אין חיבור לשרת")
                .setMessage("כתובת: " + serverUrl() + "\n" + detail + "\n\nבדוק ש-Tailscale מחובר.")
                .setPositiveButton("נסה שוב", (d, w) -> { errorShown = false; web.loadUrl(serverUrl()); })
                .setNeutralButton("שנה כתובת", (d, w) -> askServerUrl())
                .setCancelable(false)
                .show();
    }

    private void askServerUrl() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(serverUrl());
        new AlertDialog.Builder(this)
                .setTitle("כתובת שרת החיפוש")
                .setView(input)
                .setPositiveButton("שמור", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.endsWith("/")) url += "/";
                    prefs.edit().putString("url", url).apply();
                    errorShown = false;
                    web.loadUrl(url);
                })
                .setNegativeButton("ביטול", (d, w) -> { errorShown = false; web.loadUrl(serverUrl()); })
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || fileCallback == null) return;
        Uri[] picked = null;
        if (result == RESULT_OK && data != null) {
            ClipData many = data.getClipData();
            if (many != null) {
                picked = new Uri[many.getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = many.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** Called from the page's JavaScript. */
    class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("prompt", text));
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
        }

        @JavascriptInterface
        public void checkUpdate() {
            Updater.check(MainActivity.this, true);
        }

        @JavascriptInterface
        public String version() {
            return Updater.versionName(MainActivity.this);
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(MainActivity.this::askServerUrl);
        }
    }
}
