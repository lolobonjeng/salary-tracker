package com.cronzph.salarytracker.util;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.ui.AppDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Ang UI layer ng {@link Updater}. Nakatira dito ang mga dialog — hindi sa
 * {@code Updater} — para hindi humawak ng Activity ang static na logic
 * (leak) at para hindi mag-show ng dialog sa patay nang Activity.
 */
public final class UpdaterUI {

    private static final int REQ_NOTIF = 4401;

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    /** true kapag ang user mismo ang nag-trigger — doon lang tayo magpapakita ng error. */
    private static volatile boolean userInitiated;
    private static volatile boolean dialogShowing;

    /** Para hindi paulit-ulit i-offer ang parehong APK. */
    private static String offeredPath;

    private static AppDialog progressDialog;
    private static TextView progressText;
    private static LinearProgressIndicator progressBar;

    private UpdaterUI() {
    }

    private static final Updater.Listener LISTENER = new Updater.Listener() {
        @Override
        public void onUpdateAvailable(Updater.Release r) {
            Activity a = activityRef.get();
            if (!alive(a)) return;
            showUpdateAvailable(a, r);
        }

        @Override
        public void onUpToDate() {
            Activity a = activityRef.get();
            boolean show = userInitiated;
            userInitiated = false;
            if (!show || !alive(a)) return;
            Toast.makeText(a, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onProgress(int percent) {
            if (progressBar != null) {
                progressBar.setProgressCompat(percent, true);
            }
            if (progressText != null) {
                progressText.setText(progressText.getContext()
                        .getString(R.string.update_downloading, percent));
            }
        }

        @Override
        public void onDownloaded(File apk) {
            dismissProgress();
            Activity a = activityRef.get();
            // Kung wala nang buhay na Activity, ang notification ang bahala.
            if (!alive(a)) return;
            proceedToInstall(a, apk);
        }

        @Override
        public void onFailed(String message) {
            dismissProgress();
            Activity a = activityRef.get();
            boolean show = userInitiated;
            userInitiated = false;
            // Tahimik lang kapag kusang check — huwag guluhin ang user sa
            // "walang internet" tuwing bubuksan ang app.
            if (!show || !alive(a)) return;
            new AppDialog.Builder(a)
                    .icon(R.drawable.ic_update)
                    .title(R.string.update_check)
                    .message(message)
                    .positive(R.string.action_ok, null)
                    .show();
        }
    };

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    // ------------------------------------------------------------------ wiring

    public static void attach(Activity a) {
        activityRef = new WeakReference<>(a);
        Updater.setListener(LISTENER);
    }

    public static void detach() {
        activityRef = new WeakReference<>(null);
        dismissProgress();
        Updater.clearListener();
    }

    /** Kusang check sa pagbukas — tahimik kapag walang bago o may error. */
    public static void autoCheck(Activity a) {
        attach(a);
        userInitiated = false;
        Updater.maybeAutoCheck(a);
    }

    /**
     * Tinatawag sa onResume. I-reregister ang listener (kung ibang screen ang
     * huling nag-detach) at io-offer ang install kung may naka-cache na APK.
     */
    public static void resume(Activity a) {
        attach(a);
        maybeOfferInstall(a);
    }

    /** Manual na check mula sa Settings — may feedback sa user. */
    public static void checkManually(Activity a) {
        attach(a);
        if (Updater.isBusy()) return;
        userInitiated = true;
        Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        Updater.check(a, true);
    }

    /** Tinatawag sa onResume — kung may naka-cache na APK, i-offer ang install. */
    public static void maybeOfferInstall(Activity a) {
        if (!alive(a) || dialogShowing || Updater.isBusy()) return;
        File apk = Updater.cachedApk(a);
        if (apk == null) return;
        String path = apk.getAbsolutePath();
        if (path.equals(offeredPath)) return;
        offeredPath = path;

        if (!Updater.canInstall(a)) {
            new AppDialog.Builder(a)
                    .icon(R.drawable.ic_update)
                    .title(R.string.update_ready_title)
                    .message(R.string.update_need_unknown_sources)
                    .positive(R.string.action_ok,
                            d -> Updater.openInstallPermissionSettings(a))
                    .negative(R.string.action_cancel, null)
                    .show();
            return;
        }

        new AppDialog.Builder(a)
                .icon(R.drawable.ic_update)
                .title(R.string.update_ready_title)
                .message(R.string.update_install_ready)
                .positive(R.string.update_install, d -> proceedToInstall(a, apk))
                .negative(R.string.action_cancel, null)
                .show();
    }

    // ----------------------------------------------------------------- dialogs

    private static void showUpdateAvailable(final Activity a, final Updater.Release r) {
        if (!alive(a)) return;

        // Version at size ay nasa title/message na ng dialog — dito na lang
        // ang changelog, para walang doble at walang mahabang blangko.
        View content = null;
        String body = r.body == null ? "" : r.body.trim();
        if (!body.isEmpty()) {
            content = LayoutInflater.from(a).inflate(R.layout.dialog_update, null);
            ((TextView) content.findViewById(R.id.txtUpdChangelog)).setText(body);
        }

        dialogShowing = true;
        new AppDialog.Builder(a)
                .icon(R.drawable.ic_update)
                .title(a.getString(R.string.update_available_title, r.tag))
                .message(a.getString(R.string.update_size, Updater.formatSize(r.apkSize)))
                .content(content)
                .positive(R.string.update_download, d -> {
                    dialogShowing = false;
                    startDownload(a, r);
                })
                .neutral(R.string.update_skip, d -> {
                    dialogShowing = false;
                    Prefs.setSkippedVersion(a, r.tag);
                })
                .negative(R.string.update_later, d -> dialogShowing = false)
                .onCancel(d -> dialogShowing = false)
                .show();
    }

    private static void startDownload(final Activity a, final Updater.Release r) {
        userInitiated = true;

        // Huling foreground moment bago tayo ilabas ng installer — hilingin na
        // ang notification permission dito.
        requestNotificationPermission(a);

        View content = LayoutInflater.from(a).inflate(R.layout.dialog_download, null);
        progressText = content.findViewById(R.id.txtDlPercent);
        progressBar = content.findViewById(R.id.progressDl);
        progressBar.setProgressCompat(0, false);
        progressText.setText(a.getString(R.string.update_downloading, 0));

        dialogShowing = true;
        progressDialog = new AppDialog.Builder(a)
                .icon(R.drawable.ic_update)
                .title(R.string.update_download_title)
                .content(content)
                .cancelable(false)
                .show();

        Updater.download(a, r);
    }

    private static void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        progressText = null;
        progressBar = null;
        dialogShowing = false;
    }

    private static void proceedToInstall(final Activity a, final File apk) {
        if (!Updater.signaturesMatch(a, apk)) {
            // Hindi ito magtatagumpay — tatanggi ang installer dahil iba ang key.
            // Hindi natin ito itutuloy: ipaliwanag na lang at i-clear ang APK.
            new AppDialog.Builder(a)
                    .icon(R.drawable.ic_update)
                    .title(R.string.update_sig_title)
                    .message(R.string.update_sig_msg)
                    .positive(R.string.action_ok, d -> Updater.purgeCache(a))
                    .onCancel(d -> Updater.purgeCache(a))
                    .show();
            return;
        }
        Updater.install(a, apk);
    }

    private static void requestNotificationPermission(Activity a) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ActivityCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(a,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }
}
