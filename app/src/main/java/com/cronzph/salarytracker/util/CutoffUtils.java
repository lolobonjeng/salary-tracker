package com.cronzph.salarytracker.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Semi-monthly cutoff math (Filipino payroll convention):
 *   1st cutoff = 1 - 15
 *   2nd cutoff = 16 - katapusan ng buwan
 */
public final class CutoffUtils {

    public static final int FIRST = 1;
    public static final int SECOND = 2;

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };
    private static final String[] DAYS = {
            "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
    };

    private CutoffUtils() {
    }

    /** Isang panahon ng sweldo. */
    public static final class Period {
        public final int year;
        public final int month; // 0-based, gaya ng Calendar
        public final int cutoff;
        public final String startIso;
        public final String endIso;
        public final String label;

        Period(int year, int month, int cutoff, String startIso, String endIso, String label) {
            this.year = year;
            this.month = month;
            this.cutoff = cutoff;
            this.startIso = startIso;
            this.endIso = endIso;
            this.label = label;
        }

        public boolean isFirst() {
            return cutoff == FIRST;
        }

        /** Stable key para i-store ang cutoff-level na data (hal. benefits). */
        public String key() {
            return startIso + ".." + endIso;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Period)) return false;
            Period other = (Period) o;
            return year == other.year && month == other.month && cutoff == other.cutoff;
        }

        @Override
        public int hashCode() {
            return (year * 100 + month) * 10 + cutoff;
        }

        public Period prev() {
            if (cutoff == SECOND) return periodFor(year, month, FIRST);
            Calendar c = cal(year, month, 1);
            c.add(Calendar.MONTH, -1);
            return periodFor(c.get(Calendar.YEAR), c.get(Calendar.MONTH), SECOND);
        }

        public Period next() {
            if (cutoff == FIRST) return periodFor(year, month, SECOND);
            Calendar c = cal(year, month, 1);
            c.add(Calendar.MONTH, 1);
            return periodFor(c.get(Calendar.YEAR), c.get(Calendar.MONTH), FIRST);
        }
    }

    private static Calendar cal(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d);
        return c;
    }

    public static String iso(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    public static Period periodFor(int year, int month, int cutoff) {
        if (cutoff == FIRST) {
            return new Period(year, month, FIRST,
                    iso(cal(year, month, 1)),
                    iso(cal(year, month, 15)),
                    MONTHS[month] + " 1-15, " + year);
        }
        int last = cal(year, month, 1).getActualMaximum(Calendar.DAY_OF_MONTH);
        return new Period(year, month, SECOND,
                iso(cal(year, month, 16)),
                iso(cal(year, month, last)),
                MONTHS[month] + " 16-" + last + ", " + year);
    }

    /** Cutoff na kinabibilangan ng araw ngayon. */
    public static Period current() {
        Calendar now = Calendar.getInstance();
        int cutoff = now.get(Calendar.DAY_OF_MONTH) <= 15 ? FIRST : SECOND;
        return periodFor(now.get(Calendar.YEAR), now.get(Calendar.MONTH), cutoff);
    }

    /** Lahat ng petsa sa loob ng period, sunod-sunod. */
    public static List<String> daysIn(Period p) {
        List<String> out = new ArrayList<>();
        Calendar c = cal(p.year, p.month, p.cutoff == FIRST ? 1 : 16);
        int guard = 0;
        while (guard++ < 40) {
            String d = iso(c);
            out.add(d);
            if (d.equals(p.endIso)) break;
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    public static String todayIso() {
        return iso(Calendar.getInstance());
    }

    /** "2026-10-07" -> "Wed, Oct 7" */
    public static String prettyDate(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            if (d == null) return iso;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", "
                    + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH);
        } catch (Exception ex) {
            return iso;
        }
    }

    public static int dayOfMonth(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            if (d == null) return 0;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return c.get(Calendar.DAY_OF_MONTH);
        } catch (Exception ex) {
            return 0;
        }
    }

    public static boolean isToday(String iso) {
        return todayIso().equals(iso);
    }

    /** Aling cutoff ang kinabibilangan ng isang petsa. */
    public static Period periodOf(String iso) {
        Calendar c = Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            if (d == null) return current();
            c.setTime(d);
        } catch (Exception ex) {
            return current();
        }
        int cutoff = c.get(Calendar.DAY_OF_MONTH) <= 15 ? FIRST : SECOND;
        return periodFor(c.get(Calendar.YEAR), c.get(Calendar.MONTH), cutoff);
    }
}
