package com.maoz.theanswer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from the public GitHub releases of maozwe/the-answer-app: tag "v<versionCode>" with an
 * .apk asset, built and signed by the repo's workflow on every merge to main. The new APK is
 * streamed into a PackageInstaller session; Android shows its own install confirmation.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/maozwe/the-answer-app/releases/latest";
    static final String ACTION_STATUS = "com.maoz.theanswer.INSTALL_STATUS";

    private Updater() {}

    static long versionCode(Activity a) {
        try {
            PackageInfo p = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String versionName(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** verbose: also say "up to date" and report errors (button); otherwise only offer a newer version. */
    static void check(Activity a, boolean verbose) {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                long latest = Long.parseLong(rel.getString("tag_name").replaceAll("\\D", ""));
                if (latest <= versionCode(a)) {
                    if (verbose) toast(a, "האפליקציה מעודכנת (גרסה " + versionName(a) + ")");
                    return;
                }
                String apk = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) apk = asset.getString("browser_download_url");
                }
                if (apk == null) throw new IllegalStateException("אין קובץ APK בגרסה " + rel.getString("tag_name"));
                String url = apk;
                String notes = rel.optString("body", "").trim();
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                        .setTitle("יש גרסה חדשה: " + rel.optString("name", rel.optString("tag_name")))
                        .setMessage(notes.isEmpty() ? "להתקין עכשיו?" : notes)
                        .setPositiveButton("עדכן", (d, w) -> download(a, url))
                        .setNegativeButton("לא עכשיו", null)
                        .show());
            } catch (Exception e) {
                if (verbose) toast(a, "בדיקת עדכון נכשלה: " + e.getMessage());
            }
        }).start();
    }

    static void download(Activity a, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            toast(a, "אשר להתקין עדכונים מהאפליקציה הזו, חזור ולחץ שוב \"עדכן\"");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        toast(a, "מוריד עדכון...");
        new Thread(() -> {
            try {
                PackageInstaller installer = a.getPackageManager().getPackageInstaller();
                int id = installer.createSession(new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    HttpURLConnection c = open(url);
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("update.apk", 0, c.getContentLengthLong())) {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent status = new Intent(a, MainActivity.class).setAction(ACTION_STATUS)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getActivity(a, 0, status, flags).getIntentSender());
                }
            } catch (Exception e) {
                toast(a, "העדכון נכשל: " + e.getMessage());
            }
        }).start();
    }

    /** The installer's answer, delivered to MainActivity.onNewIntent. */
    static void onStatus(Activity a, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            toast(a, "ההתקנה לא הושלמה: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "TheAnswerApp");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);  // release assets redirect to GitHub's download host (https)
        if (c.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void toast(Activity a, String msg) {
        a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }
}
