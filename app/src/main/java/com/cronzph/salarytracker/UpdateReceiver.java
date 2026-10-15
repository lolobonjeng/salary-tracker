package com.cronzph.salarytracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cronzph.salarytracker.util.Updater;

/**
 * Tumatanggap ng {@code ACTION_MY_PACKAGE_REPLACED} — kusang ipinapadala ng system
 * pagkatapos ma-update ang app na ito.
 *
 * <p>Susubukan nitong i-relaunch agad ang app. Pero sa Android 10+ ito ay madalas
 * na hinaharangan ng background-activity-launch rules at <b>tahimik</b> lang na
 * nabibigo — walang exception, kaya hindi natin ito ma-detect. Dahil dito, laging
 * may notification na iniiwan: ang pag-tap dito ay dumadaan sa system-sent
 * PendingIntent, na isa sa official na BAL exemption, kaya siguradong gumagana.
 */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        // Malinis na — tapos na ang install, wala nang silbi ang APK sa cache.
        Updater.purgeCache(context);

        // I-post muna ang notification, tapos saka subukan ang launch.
        // Sa ganoon, kung talagang bumukas ang app, ma-ca-cancel ito ng
        // MainActivity.onResume() bago pa mapansin ng user.
        Updater.notifyReadyToOpen(context);

        Intent open = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (open == null) return;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            // Inaasahan kapag hinarang ng BAL — nandiyan naman ang notification.
        }
    }
}
