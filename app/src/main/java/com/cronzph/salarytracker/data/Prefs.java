package com.cronzph.salarytracker.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Mga setting na hindi kasama sa ledger: daily rate, OT multiplier, hours per day. */
public final class Prefs {

    private static final String FILE = "salary_prefs";
    private static final String KEY_RATE = "daily_rate";
    private static final String KEY_OT_MULT = "ot_multiplier";
    private static final String KEY_HOURS = "hours_per_day";
    private static final String KEY_DEF_TYPE = "default_day_type";
    private static final String KEY_DEF_OT = "default_ot_hours";
    private static final String KEY_UPD_AUTO = "update_auto_check";
    private static final String KEY_UPD_LAST = "update_last_check";
    private static final String KEY_UPD_SKIP = "update_skipped_version";

    /** Default mula sa record: 600/day. */
    public static final double DEFAULT_RATE = 600d;
    /** Regular OT = 125% ng hourly rate. */
    public static final double DEFAULT_OT_MULT = 1.25d;
    public static final double DEFAULT_HOURS = 8d;
    public static final int DEFAULT_DAY_TYPE = SalaryEntry.TYPE_WORK;
    public static final double DEFAULT_OT_HOURS = 0d;
    public static final boolean DEFAULT_UPD_AUTO = true;
    public static final long DEFAULT_UPD_LAST = 0L;
    public static final String DEFAULT_UPD_SKIP = "";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static double getDailyRate(Context c) {
        return sp(c).getFloat(KEY_RATE, (float) DEFAULT_RATE);
    }

    public static void setDailyRate(Context c, double v) {
        sp(c).edit().putFloat(KEY_RATE, (float) v).apply();
    }

    public static double getOtMultiplier(Context c) {
        return sp(c).getFloat(KEY_OT_MULT, (float) DEFAULT_OT_MULT);
    }

    public static void setOtMultiplier(Context c, double v) {
        sp(c).edit().putFloat(KEY_OT_MULT, (float) v).apply();
    }

    public static double getHoursPerDay(Context c) {
        double v = sp(c).getFloat(KEY_HOURS, (float) DEFAULT_HOURS);
        return v > 0 ? v : DEFAULT_HOURS;
    }

    public static void setHoursPerDay(Context c, double v) {
        sp(c).edit().putFloat(KEY_HOURS, (float) v).apply();
    }

    /** Hourly rate na pinagbasehan ng OT. */
    public static double hourlyRate(Context c) {
        return getDailyRate(c) / getHoursPerDay(c);
    }

    // ------------------------------------------------- default entry template

    /** Day type na naka-prefill sa bagong entry. */
    public static int getDefaultDayType(Context c) {
        return sp(c).getInt(KEY_DEF_TYPE, DEFAULT_DAY_TYPE);
    }

    public static void setDefaultDayType(Context c, int v) {
        sp(c).edit().putInt(KEY_DEF_TYPE, v).apply();
    }

    /** OT hours na naka-prefill sa bagong entry. */
    public static double getDefaultOtHours(Context c) {
        return sp(c).getFloat(KEY_DEF_OT, (float) DEFAULT_OT_HOURS);
    }

    public static void setDefaultOtHours(Context c, double v) {
        sp(c).edit().putFloat(KEY_DEF_OT, (float) v).apply();
    }

    // --------------------------------------------------------- auto updater

    /** Kung kusang titingin ng update tuwing bubuksan ang app. */
    public static boolean isAutoCheck(Context c) {
        return sp(c).getBoolean(KEY_UPD_AUTO, DEFAULT_UPD_AUTO);
    }

    public static void setAutoCheck(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_UPD_AUTO, v).apply();
    }

    /** Millis ng huling check — basehan ng throttle. */
    public static long getLastCheck(Context c) {
        return sp(c).getLong(KEY_UPD_LAST, DEFAULT_UPD_LAST);
    }

    public static void setLastCheck(Context c, long millis) {
        sp(c).edit().putLong(KEY_UPD_LAST, millis).apply();
    }

    /** Tag ng version na ayaw nang i-prompt ("" = wala). */
    public static String getSkippedVersion(Context c) {
        return sp(c).getString(KEY_UPD_SKIP, DEFAULT_UPD_SKIP);
    }

    public static void setSkippedVersion(Context c, String tag) {
        sp(c).edit().putString(KEY_UPD_SKIP, tag == null ? DEFAULT_UPD_SKIP : tag).apply();
    }
}
