package com.maoz.theanswer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Updates from manage-control: the newest published version on the app's android line, built and
 * signed by the repo's workflow on every merge to main. The APK is streamed into a PackageInstaller
 * session and committed only when it hashes to the sha256 the backend stored; Android shows its own
 * install confirmation.
 */
final class Updater {
    static final String SLUG = "the-answer";  // the app's slug in manage-control
    static final String LATEST = "https://api.weisscivitech.com/api/v1/storefront/apps/" + SLUG + "/latest?platform=android";
    static final String ACTION_STATUS = "com.maoz.theanswer.INSTALL_STATUS";

    private Updater() {}

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
                JSONObject file = rel.optJSONObject("download");
                String version = rel.optString("version", "");
                if (file == null || compare(version, versionName(a)) <= 0) {
                    if (verbose) toast(a, "האפליקציה מעודכנת (גרסה " + versionName(a) + ")");
                    return;
                }
                String url = file.getString("url"), sha256 = file.getString("sha256");
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                        .setTitle("יש גרסה חדשה: " + version)
                        .setMessage("מותקנת " + versionName(a) + ". להתקין עכשיו?")
                        .setPositiveButton("עדכן", (d, w) -> download(a, url, sha256))
                        .setNegativeButton("לא עכשיו", null)
                        .show());
            } catch (Exception e) {
                if (verbose) toast(a, "בדיקת עדכון נכשלה: " + e.getMessage());
            }
        }).start();
    }

    /** Dotted versions compared number by number (1.10.0 > 1.9.3); an unreadable part counts as 0. */
    static int compare(String x, String y) {
        String[] p = x.split("\\."), q = y.split("\\.");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int d = Long.compare(num(p, i), num(q, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static long num(String[] parts, int i) {
        try {
            return i < parts.length ? Long.parseLong(parts[i].replaceAll("\\D.*", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void download(Activity a, String url, String sha256) {
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
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = new DigestInputStream(c.getInputStream(), digest);
                         OutputStream out = session.openWrite("update.apk", 0, c.getContentLengthLong())) {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) hex.append(String.format("%02x", b));
                    if (!hex.toString().equalsIgnoreCase(sha256)) {
                        session.abandon();
                        toast(a, "הקובץ שהורד לא תואם לחתימה שבשרת. העדכון בוטל.");
                        return;
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
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
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
