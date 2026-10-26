package com.cronzph.salarytracker.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import com.cronzph.salarytracker.BuildConfig;
import com.cronzph.salarytracker.InstallResultReceiver;
import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updater: titingin sa GitHub Releases, magda-download ng APK, tapos i-install.
 *
 * <p>Ang check at download ay kusang nangyayari. Ang install ay <b>hindi</b> pwedeng
 * gawing silent — kailangan pa ring i-tap ng user ang "Update" sa system installer,
 * dahil ang silent install ay kailangan ng {@code INSTALL_PACKAGES} permission na
 * {@code signature|privileged} na hindi binibigay sa normal na app.
 */
public final class Updater {

    /** Public repo — ang "latest release" ang pinagkukunan ng update info. */
    private static final String REPO_API =
            "https://api.github.com/repos/lolobonjeng/salary-tracker/releases/latest";

    /** Isang check kada 24 oras para hindi ma-hit ang 60/hr na GitHub rate limit. */
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000L;

    private static final String CHANNEL_ID = "app_updates";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    /** Notification id para sa "i-tap para buksan / i-install". */
    public static final int NOTIF_ID = 1001;

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final int BUFFER = 64 * 1024;

    /** Estado ng updater. */
    public enum State {IDLE, CHECKING, DOWNLOADING, INSTALLING}

    /** Isang release na nakuha mula sa GitHub. */
    public static final class Release {
        public final String tag;
        public final String body;
        public final String apkUrl;
        public final long apkSize;

        Release(String tag, String body, String apkUrl, long apkSize) {
            this.tag = tag;
            this.body = body;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    /** Callback sa UI. Lahat ng method ay tinatawag sa MAIN thread. */
    public interface Listener {
        void onUpdateAvailable(Release r);

        void onUpToDate();

        void onProgress(int percent);

        void onDownloaded(File apk);

        /** May nagfail — resolved na ang mensahe, pwedeng direktang ipakita. */
        void onFailed(String message);
    }

    /** Exception para sa GitHub rate limit — iba ang mensahe nito. */
    private static final class RateLimited extends IOException {
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "salary-updater");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile State state = State.IDLE;
    private static WeakReference<Listener> listenerRef = new WeakReference<>(null);
    private static volatile Release pending;

    private Updater() {
    }

    // ------------------------------------------------------------------ wiring

    public static void setListener(Listener l) {
        listenerRef = new WeakReference<>(l);
    }

    public static void clearListener() {
        listenerRef = new WeakReference<>(null);
    }

    public static boolean isBusy() {
        return state != State.IDLE;
    }

    public static State getState() {
        return state;
    }

    /** Ang huling nakitang update, o null kung wala pa. */
    public static Release getPending() {
        return pending;
    }

    // -------------------------------------------------------------- entry point

    /**
     * Kusang check sa pagbukas ng app. Sinusunod ang "auto-check" setting at ang
     * 24-oras na throttle, at hindi na nag-prompt sa version na ini-skip ng user.
     */
    public static void maybeAutoCheck(Context c) {
        purgeCache(c);
        if (!Prefs.isAutoCheck(c)) return;
        long last = Prefs.getLastCheck(c);
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return;
        check(c, false);
    }

    /** Manual na check — lalampasan ang throttle at ang skip list. */
    public static void check(Context c, boolean manual) {
        if (isBusy()) return;
        state = State.CHECKING;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                Release r = fetchLatest(app);
                Prefs.setLastCheck(app, System.currentTimeMillis());

                if (r == null) {
                    // 404 — wala pang naka-publish na release.
                    finish();
                    if (manual) postFailed(app.getString(R.string.update_no_release));
                    else postUpToDate();
                    return;
                }
                if (r.apkUrl == null || r.apkUrl.isEmpty()) {
                    finish();
                    postFailed(app.getString(R.string.update_no_apk));
                    return;
                }

                boolean newer = isNewer(r.tag, BuildConfig.VERSION_NAME);
                boolean skipped = r.tag.equals(Prefs.getSkippedVersion(app));
                if (!newer || (skipped && !manual)) {
                    finish();
                    postUpToDate();
                    return;
                }

                pending = r;
                finish();
                postUpdateAvailable(r);
            } catch (RateLimited e) {
                finish();
                postFailed(app.getString(R.string.update_rate_limited));
            } catch (UnknownHostException e) {
                finish();
                postFailed(app.getString(R.string.update_offline));
            } catch (Exception e) {
                finish();
                postFailed(app.getString(R.string.update_error, describe(e)));
            }
        });
    }

    /** I-download ang APK ng release. Progress ay dumarating sa {@code onProgress}. */
    public static void download(Context c, Release r) {
        if (isBusy() || r == null) return;
        state = State.DOWNLOADING;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            File out = null;
            try {
                File dir = updateDir(app);
                purge(dir);
                out = new File(dir, "salary-tracker-" + safe(r.tag) + ".apk");

                HttpURLConnection conn = open(r.apkUrl);
                try {
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new IOException("HTTP " + code);
                    }
                    long total = conn.getContentLength();
                    if (total <= 0) total = r.apkSize;

                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[BUFFER];
                        long done = 0;
                        int lastPct = -1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) Math.min(100L, done * 100L / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    postProgress(pct);
                                }
                            }
                        }
                        fos.flush();
                        if (total > 0 && done < total) {
                            throw new IOException("kulang ang download (" + done + "/" + total + ")");
                        }
                    } finally {
                        close(in);
                        close(fos);
                    }
                } finally {
                    conn.disconnect();
                }

                String problem = verify(app, out);
                if (problem != null) {
                    delete(out);
                    finish();
                    postFailed(problem);
                    return;
                }

                finish();
                postDownloaded(app, out);
            } catch (UnknownHostException e) {
                delete(out);
                finish();
                postFailed(app.getString(R.string.update_offline));
            } catch (Exception e) {
                delete(out);
                finish();
                postFailed(app.getString(R.string.update_error, describe(e)));
            }
        });
    }

    // ----------------------------------------------------------------- install

    /** Pwede bang mag-install ng APK? (API 26+ kailangang payagan ang app.) */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Buksan ang "Install unknown apps" settings para sa app na ito. */
    public static void openInstallPermissionSettings(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /**
     * Ipasok ang na-download na APK sa installer. Sa API 26+ gumagamit ng
     * {@link PackageInstaller} session; sa API 21-25, {@code ACTION_VIEW} + FileProvider.
     */
    public static void install(Context c, File apk) {
        if (apk == null || !apk.exists() || isBusy()) return;
        if (!canInstall(c)) {
            openInstallPermissionSettings(c);
            return;
        }
        state = State.INSTALLING;
        final Context app = c.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            EXEC.execute(() -> installViaSession(app, apk));
        } else {
            MAIN.post(() -> installLegacy(app, apk));
        }
    }

    private static void installViaSession(Context c, File apk) {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());

            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                close(in);
                close(out);
            }

            Intent status = new Intent(c, InstallResultReceiver.class);
            status.setAction(InstallResultReceiver.ACTION_INSTALL_STATUS);
            status.putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId);

            // FLAG_MUTABLE ay KAILANGAN dito: ang system ang naglalagay ng
            // EXTRA_STATUS / EXTRA_INTENT. Kapag immutable, hindi dumarating
            // ang callback at tahimik na mabibigo ang install.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent cb = PendingIntent.getBroadcast(c, sessionId, status, flags);

            session.commit(cb.getIntentSender());
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
            postFailed(c.getString(R.string.update_install_failed, describe(e)));
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            finish();
        }
    }

    private static void installLegacy(Context c, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            postFailed(c.getString(R.string.update_install_failed, describe(e)));
        } finally {
            finish();
        }
    }

    /** Tinatawag ng {@link InstallResultReceiver} kapag may nagfail na install. */
    public static void reportInstallFailure(Context c, int status, String message) {
        if (isSignatureMismatch(message)) {
            postFailed(c.getString(R.string.update_sig_title) + "\n\n"
                    + c.getString(R.string.update_sig_msg));
            return;
        }
        String detail = message == null || message.isEmpty() ? String.valueOf(status) : message;
        postFailed(c.getString(R.string.update_install_failed, detail));
    }

    /** {@code INSTALL_FAILED_UPDATE_INCOMPATIBLE} — ibang signing key. Mapanganib ito. */
    public static boolean isSignatureMismatch(String message) {
        if (message == null) return false;
        String m = message.toUpperCase(Locale.US);
        return m.contains("UPDATE_INCOMPATIBLE") || m.contains("SIGNATURES DO NOT MATCH");
    }

    /**
     * Paghahambing ng signing certificate ng naka-install na app at ng APK.
     * Best-effort lang: kapag hindi mabasa ang alinman, {@code true} ang sagot
     * (fail-open) para hindi harangin ang lehitimong update.
     */
    public static boolean signaturesMatch(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (installed == null || archive == null) return true;
            if (installed.signatures == null || archive.signatures == null) return true;
            if (installed.signatures.length == 0 || archive.signatures.length == 0) return true;
            return installed.signatures[0].equals(archive.signatures[0]);
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------- apk helpers

    /** Ang APK na naka-cache mula sa nakaraang download, o null. */
    public static File cachedApk(Context c) {
        File[] files = updateDir(c).listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".apk")) return f;
        }
        return null;
    }

    public static void purgeCache(Context c) {
        purge(updateDir(c));
    }

    private static File updateDir(Context c) {
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void purge(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            delete(f);
        }
    }

    private static void delete(File f) {
        if (f != null && f.exists()) f.delete();
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * I-verify ang na-download na APK. Ang {@code versionCode} ang authoritative —
     * hindi ang git tag. Kung hindi lalampas sa naka-install, hindi ito i-install
     * (kung hindi, uulit-ulit lang ang parehong asset).
     *
     * @return mensahe ng problema, o null kung okay
     */
    private static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();

        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null) {
            return c.getString(R.string.update_error, "sirang APK");
        }
        if (!c.getPackageName().equals(archive.packageName)) {
            return c.getString(R.string.update_error, "ibang package");
        }

        long archiveCode = PackageInfoCompat.getLongVersionCode(archive);
        long installedCode;
        try {
            installedCode = PackageInfoCompat.getLongVersionCode(
                    pm.getPackageInfo(c.getPackageName(), 0));
        } catch (Exception e) {
            return c.getString(R.string.update_error, describe(e));
        }

        if (archiveCode <= installedCode) {
            return c.getString(R.string.update_same_version);
        }
        return null;
    }

    // ------------------------------------------------------------- networking

    private static Release fetchLatest(Context c) throws IOException {
        HttpURLConnection conn = open(REPO_API);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "SalaryTracker/" + BuildConfig.VERSION_NAME);
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code == 403 && "0".equals(conn.getHeaderField("X-RateLimit-Remaining"))) {
                throw new RateLimited();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }

            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            String tag = o.optString("tag_name", "");
            if (tag.isEmpty()) return null;

            String apkUrl = "";
            long apkSize = 0L;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    if (a.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "");
                        apkSize = a.optLong("size", 0L);
                        break;
                    }
                }
            }
            return new Release(tag, o.optString("body", ""), apkUrl, apkSize);
        } catch (RateLimited e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // JSONException at iba pa — hindi ma-parse ang sagot (hal. HTML error page).
            throw new IOException(describe(e));
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
        } finally {
            close(in);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ version math

    /**
     * Numeric na paghahambing kada segment — hindi {@code String.compareTo}
     * (mali ito sa "1.10" vs "1.9") at hindi {@code Double.parseDouble}
     * (mali sa "1.0-beta").
     */
    public static boolean isNewer(String remoteTag, String installedVersion) {
        int[] a = segments(remoteTag);
        int[] b = segments(installedVersion);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] segments(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (int j = 0; j < parts[i].length(); j++) {
                char ch = parts[i].charAt(j);
                if (ch >= '0' && ch <= '9') digits.append(ch);
                else break;
            }
            try {
                out[i] = Integer.parseInt(digits.toString());
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** "8.4 MB" — para sa laki ng download. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        return String.format(Locale.US, "%.1f MB", kb / 1024d);
    }

    private static String safe(String tag) {
        if (tag == null || tag.isEmpty()) return "update";
        return tag.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }

    // ----------------------------------------------------------- notifications

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(c.getString(R.string.update_channel_desc));
        nm.createNotificationChannel(ch);
    }

    /** "Tapos na ang update — i-tap para buksan." Ito ang BAL-safe na paraan. */
    public static void notifyReadyToOpen(Context c) {
        notify(c, c.getString(R.string.update_ready_title),
                c.getString(R.string.update_ready_text));
    }

    /** "Naka-download na — i-tap para i-install." */
    public static void notifyReadyToInstall(Context c) {
        notify(c, c.getString(R.string.update_ready_title),
                c.getString(R.string.update_install_ready));
    }

    private static void notify(Context c, String title, String text) {
        Context app = c.getApplicationContext();
        ensureChannel(app);

        Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (open == null) open = new Intent();
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // FLAG_IMMUTABLE — system ang sender; hindi natin kailangang i-fill ang intent.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(app, 0, open, flags);

        Notification n = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        try {
            NotificationManagerCompat.from(app).notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // Naka-deny ang POST_NOTIFICATIONS — tuloy pa rin ang install,
            // notification lang ang nawala.
        }
    }

    public static void clearRelaunchNotification(Context c) {
        NotificationManagerCompat.from(c.getApplicationContext()).cancel(NOTIF_ID);
    }

    // ------------------------------------------------------------- dispatchers

    private static void finish() {
        state = State.IDLE;
    }

    private static Listener listener() {
        return listenerRef.get();
    }

    private static void postUpToDate() {
        final Listener l = listener();
        if (l != null) MAIN.post(l::onUpToDate);
    }

    private static void postUpdateAvailable(final Release r) {
        final Listener l = listener();
        if (l != null) MAIN.post(() -> l.onUpdateAvailable(r));
    }

    private static void postProgress(final int pct) {
        final Listener l = listener();
        if (l != null) MAIN.post(() -> l.onProgress(pct));
    }

    private static void postDownloaded(final Context app, final File apk) {
        final Listener l = listener();
        if (l != null) {
            MAIN.post(() -> l.onDownloaded(apk));
        } else {
            // Walang UI na naka-attach — i-notify na lang.
            MAIN.post(() -> notifyReadyToInstall(app));
        }
    }

    private static void postFailed(final String message) {
        final Listener l = listener();
        if (l != null) MAIN.post(() -> l.onFailed(message));
    }
}
