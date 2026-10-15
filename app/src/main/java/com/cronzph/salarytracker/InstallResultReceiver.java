package com.cronzph.salarytracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import com.cronzph.salarytracker.util.Updater;

/**
 * Status callback ng {@link PackageInstaller} session.
 *
 * <p>Kailangang i-forward ang {@code STATUS_PENDING_USER_ACTION} pabalik sa system
 * installer — kung hindi, mananatiling pending ang session at walang mai-install.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_STATUS =
            "com.cronzph.salarytracker.INSTALL_STATUS";
    public static final String EXTRA_SESSION_ID = "local_session_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Kailangan pang mag-confirm ng user. Nasa foreground pa ang app sa
            // puntong ito (nakabukas ang progress dialog), kaya kadalasan pumapasa.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Exception e) {
                // Naka-background — notification na lang ang paraan.
                Updater.notifyReadyToInstall(context);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Ang UpdateReceiver (MY_PACKAGE_REPLACED) ang bahala sa relaunch.
            return;
        }

        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            // Kinansela ng user — hindi ito error, huwag nang guluhin.
            return;
        }

        Updater.reportInstallFailure(context, status, message);
    }
}
