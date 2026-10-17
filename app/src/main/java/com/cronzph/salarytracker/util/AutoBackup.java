package com.cronzph.salarytracker.util;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Awtomatikong .xlsx backup sa <b>Downloads/SalaryTracker</b>.
 *
 * <p>Bakit Downloads at hindi app-private storage: binubura ng Android ang
 * buong {@code /data/data/<package>} kapag in-uninstall ang app. Ang file sa
 * Downloads ay pag-aari ng user, kaya nananatili ito kahit i-uninstall at
 * i-reinstall — doon mo ito ma-restore gamit ang "Restore from backup".
 *
 * <p>Isang file kada araw ({@code salary-tracker-YYYY-MM-DD.xlsx}); pinapalitan
 * ito sa bawat pagbabago, at ang labis sa {@link #KEEP_FILES} ay pinupurga.
 * API 29+ ay gumagamit ng MediaStore (walang permission); API 21-28 ay
 * nangangailangan ng WRITE_EXTERNAL_STORAGE.
 */
public final class AutoBackup {

    public static final String FOLDER = "SalaryTracker";

    private static final long DEBOUNCE_MS = 2500L;
    private static final int KEEP_FILES = 14;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static Runnable pending;

    private AutoBackup() {
    }

    /** Tawagin pagkatapos ng bawat write sa database — debounced para isang beses lang. */
    public static void schedule(Context context) {
        final Context app = context.getApplicationContext();
        if (pending != null) {
            MAIN.removeCallbacks(pending);
        }
        pending = () -> {
            pending = null;
            IO.execute(() -> run(app));
        };
        MAIN.postDelayed(pending, DEBOUNCE_MS);
    }

    /**
     * Isulat agad kung may naka-pending — para hindi mawala ang backup kapag
     * umalis agad ang user sa app bago pa matapos ang debounce.
     */
    public static void flush(Context context) {
        if (pending == null) return;
        MAIN.removeCallbacks(pending);
        pending = null;
        backupNow(context);
    }

    /** Agad-agad, walang debounce — para sa manual na "Backup now". */
    public static void backupNow(Context context) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> run(app));
    }

    /** Pwede bang magsulat ngayon? (API 28 pababa ay kailangan ng permission.) */
    public static boolean canWrite(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static String displayPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
    }

    /** Ang pinakabagong auto-backup, o {@code null} kung wala pa. */
    public static Latest findLatest(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return Q.findLatest(app);
        }
        return findLatestLegacy();
    }

    /** Basahin ang isang backup file (hindi pa ito isinusulat sa database). */
    public static BackupUtils.Parsed parse(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open the backup file");
            return BackupUtils.parse(in);
        }
    }

    /** Uri + pangalan ng isang backup file. */
    public static final class Latest {
        public final Uri uri;
        public final String name;

        Latest(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    @SuppressWarnings("deprecation")
    private static Latest findLatestLegacy() {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER);
        File[] files = dir.listFiles();
        if (files == null) return null;
        File newest = null;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.startsWith("salary-tracker-") || !name.endsWith(".xlsx")) {
                continue;
            }
            if (newest == null || name.compareTo(newest.getName()) > 0) {
                newest = f;
            }
        }
        return newest == null ? null : new Latest(Uri.fromFile(newest), newest.getName());
    }

    // ------------------------------------------------------------------ worker

    private static void run(Context app) {
        try {
            if (!canWrite(app)) return;
            SalaryDbHelper db = SalaryDbHelper.get(app);
            List<SalaryEntry> entries = db.all();
            if (entries.isEmpty()) return; // walang saysay i-backup ang walang laman

            Map<String, Double> benefits = db.allBenefits();
            double hoursPerDay = Prefs.getHoursPerDay(app);
            String name = "salary-tracker-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new Date()) + ".xlsx";

            write(app, name, entries, hoursPerDay, benefits);

            Prefs.setBackupLast(app, System.currentTimeMillis());

            if (!Prefs.isBackupNotified(app)) {
                Prefs.setBackupNotified(app, true);
                final String msg = app.getString(R.string.backup_auto_done, displayPath());
                MAIN.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            Prefs.setBackupError(app, describe(e));
        }
    }

    private static void write(Context app, String name, List<SalaryEntry> entries,
                              double hoursPerDay, Map<String, Double> benefits) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Hiwalay na class: hindi ito ma-verify/ma-load sa API 28 pababa,
            // kaya walang VerifyError kahit wala ang MediaStore.Downloads doon.
            Q.write(app, name, entries, hoursPerDay, benefits);
        } else {
            writeLegacy(name, entries, hoursPerDay, benefits);
        }
    }

    /** API 29+ lang — MediaStore.Downloads. */
    private static final class Q {

        static void write(Context app, String name, List<SalaryEntry> entries,
                          double hoursPerDay, Map<String, Double> benefits) throws IOException {
            ContentResolver resolver = app.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

            // Palitan ang file ngayong araw kung meron na.
            try {
                resolver.delete(collection,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{name, "%/" + FOLDER + "/%"});
            } catch (Exception ignored) {
                // Walang existing file — tuloy lang.
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, BackupUtils.XLSX_MIME);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IOException("Cannot create file in Downloads");
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open the backup file");
                BackupUtils.write(out, entries, hoursPerDay, benefits);
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);

            prune(resolver, collection);
        }

        static Latest findLatest(Context app) {
            ContentResolver resolver = app.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME
            };
            Cursor c = null;
            try {
                c = resolver.query(collection, projection,
                        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{"salary-tracker-%.xlsx", "%/" + FOLDER + "/%"},
                        MediaStore.MediaColumns.DISPLAY_NAME + " DESC");
                if (c == null || !c.moveToFirst()) return null;
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                String name = c.getString(
                        c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                return new Latest(ContentUris.withAppendedId(collection, id), name);
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.close();
            }
        }

        /** Burahin ang lumang araw — panatilihin ang huling {@link #KEEP_FILES}. */        private static void prune(ContentResolver resolver, Uri collection) {
            String prefix = "salary-tracker-";
            String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
            Cursor c = null;
            try {
                c = resolver.query(collection, projection,
                        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{prefix + "%", "%/" + FOLDER + "/%"},
                        MediaStore.MediaColumns.DISPLAY_NAME + " DESC");
                if (c == null) return;
                int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int kept = 0;
                while (c.moveToNext()) {
                    kept++;
                    if (kept <= KEEP_FILES) continue;
                    long id = c.getLong(idCol);
                    Uri target = ContentUris.withAppendedId(collection, id);
                    try {
                        resolver.delete(target, null, null);
                    } catch (Exception ignored) {
                        // Best effort lang.
                    }
                }
            } catch (Exception ignored) {
                // Hindi kritikal ang pruning.
            } finally {
                if (c != null) c.close();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void writeLegacy(String name, List<SalaryEntry> entries,
                                    double hoursPerDay, Map<String, Double> benefits)
            throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            BackupUtils.write(out, entries, hoursPerDay, benefits);
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
