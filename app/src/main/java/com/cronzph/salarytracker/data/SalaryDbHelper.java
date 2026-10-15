package com.cronzph.salarytracker.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite storage ng ledger. Plain SQLiteOpenHelper lang — walang Room/kapt
 * para mabilis pa rin mag-build sa AndroidIDE.
 */
public class SalaryDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "salary_tracker.db";
    /** v1 = entries lang. v2 = dinagdag ang cutoff-level benefits. */
    private static final int DB_VERSION = 2;

    public static final String T = "entries";
    public static final String C_ID = "_id";
    public static final String C_DATE = "work_date";
    public static final String C_TYPE = "day_type";
    public static final String C_BASE = "base_pay";
    public static final String C_OT_HOURS = "ot_hours";
    public static final String C_OT_MULT = "ot_multiplier";
    public static final String C_OT_OVERRIDE = "ot_override";
    public static final String C_NOTES = "notes";

    /** Bawas (benefits) kada cutoff — ikaw ang naglalagay ng halaga. */
    public static final String TB = "cutoff_benefits";
    public static final String B_PERIOD = "period_key";
    public static final String B_AMOUNT = "amount";

    private static final String CREATE_ENTRIES = "CREATE TABLE " + T + " ("
            + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + C_DATE + " TEXT NOT NULL, "
            + C_TYPE + " INTEGER NOT NULL DEFAULT 0, "
            + C_BASE + " REAL NOT NULL DEFAULT 0, "
            + C_OT_HOURS + " REAL NOT NULL DEFAULT 0, "
            + C_OT_MULT + " REAL NOT NULL DEFAULT 1.25, "
            + C_OT_OVERRIDE + " REAL, "
            + C_NOTES + " TEXT)";

    /** Isang entry lang kada araw — kaya safe ang replace-by-date. */
    private static final String CREATE_ENTRIES_INDEX =
            "CREATE UNIQUE INDEX idx_entries_date ON " + T + "(" + C_DATE + ")";

    private static final String CREATE_BENEFITS = "CREATE TABLE " + TB + " ("
            + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + B_PERIOD + " TEXT NOT NULL UNIQUE, "
            + B_AMOUNT + " REAL NOT NULL DEFAULT 0)";

    private static SalaryDbHelper instance;

    public static synchronized SalaryDbHelper get(Context c) {
        if (instance == null) {
            instance = new SalaryDbHelper(c.getApplicationContext());
        }
        return instance;
    }

    private SalaryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_ENTRIES);
        db.execSQL(CREATE_ENTRIES_INDEX);
        db.execSQL(CREATE_BENEFITS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NON-DESTRUCTIVE: huwag buburahin ang entries ng user.
        if (oldVersion < 2) {
            db.execSQL(CREATE_BENEFITS);
        }
    }

    private static ContentValues toValues(SalaryEntry e) {
        ContentValues v = new ContentValues();
        v.put(C_DATE, e.workDate);
        v.put(C_TYPE, e.dayType);
        v.put(C_BASE, e.basePay);
        v.put(C_OT_HOURS, e.otHours);
        v.put(C_OT_MULT, e.otMultiplier);
        if (e.otOverride == null) {
            v.putNull(C_OT_OVERRIDE);
        } else {
            v.put(C_OT_OVERRIDE, e.otOverride);
        }
        v.put(C_NOTES, e.notes);
        return v;
    }

    private static SalaryEntry fromCursor(Cursor c) {
        SalaryEntry e = new SalaryEntry();
        e.id = c.getLong(c.getColumnIndexOrThrow(C_ID));
        e.workDate = c.getString(c.getColumnIndexOrThrow(C_DATE));
        e.dayType = c.getInt(c.getColumnIndexOrThrow(C_TYPE));
        e.basePay = c.getDouble(c.getColumnIndexOrThrow(C_BASE));
        e.otHours = c.getDouble(c.getColumnIndexOrThrow(C_OT_HOURS));
        e.otMultiplier = c.getDouble(c.getColumnIndexOrThrow(C_OT_MULT));
        int ovIdx = c.getColumnIndexOrThrow(C_OT_OVERRIDE);
        e.otOverride = c.isNull(ovIdx) ? null : c.getDouble(ovIdx);
        e.notes = c.getString(c.getColumnIndexOrThrow(C_NOTES));
        return e;
    }

    /** Insert o update — kung may existing na entry sa parehong petsa, papalitan. */
    public long save(SalaryEntry e) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = toValues(e);
        if (e.id > 0) {
            v.put(C_ID, e.id);
            db.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            return e.id;
        }
        return db.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void delete(long id) {
        getWritableDatabase().delete(T, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T, null, null);
        db.delete(TB, null, null);
    }

    // ----------------------------------------------------------- benefits

    /** Bawas para sa isang cutoff. 0 kung wala pang naka-set. */
    public double getBenefits(String periodKey) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TB, new String[]{B_AMOUNT},
                    B_PERIOD + "=?", new String[]{periodKey}, null, null, null);
            return c.moveToFirst() ? c.getDouble(0) : 0d;
        } finally {
            if (c != null) c.close();
        }
    }

    public void setBenefits(String periodKey, double amount) {
        ContentValues v = new ContentValues();
        v.put(B_PERIOD, periodKey);
        v.put(B_AMOUNT, amount);
        getWritableDatabase().insertWithOnConflict(TB, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Lahat ng benefits, keyed by period key — para sa history at export. */
    public Map<String, Double> allBenefits() {
        Map<String, Double> out = new HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TB,
                    new String[]{B_PERIOD, B_AMOUNT}, null, null, null, null, null);
            while (c.moveToNext()) {
                out.put(c.getString(0), c.getDouble(1));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Lahat ng entries, sunod-sunod ayon sa petsa (pinakaluma muna). */
    public List<SalaryEntry> all() {
        return query(null, null);
    }

    public List<SalaryEntry> between(String startIso, String endIso) {
        return query(C_DATE + " BETWEEN ? AND ?", new String[]{startIso, endIso});
    }

    public SalaryEntry byDate(String iso) {
        List<SalaryEntry> list = query(C_DATE + "=?", new String[]{iso});
        return list.isEmpty() ? null : list.get(0);
    }

    public SalaryEntry byId(long id) {
        List<SalaryEntry> list = query(C_ID + "=?", new String[]{String.valueOf(id)});
        return list.isEmpty() ? null : list.get(0);
    }

    private List<SalaryEntry> query(String selection, String[] args) {
        List<SalaryEntry> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, selection, args, null, null, C_DATE + " ASC");
            while (c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Buod ng isang cutoff. */
    public static class Totals {
        public int daysWorked;
        public int daysOff;
        public int daysAbsent;
        public int otEntries;
        public double baseTotal;
        public double otHoursTotal;
        public double otTotal;

        public double grandTotal() {
            return baseTotal + otTotal;
        }
    }

    public static Totals totals(List<SalaryEntry> entries, double hoursPerDay) {
        Totals t = new Totals();
        for (SalaryEntry e : entries) {
            if (e.dayType == SalaryEntry.TYPE_WORK) {
                t.daysWorked++;
                t.baseTotal += e.basePay;
                double ot = e.otAmount(hoursPerDay);
                if (ot > 0) {
                    t.otTotal += ot;
                    t.otEntries++;
                    t.otHoursTotal += e.otHours;
                }
            } else if (e.dayType == SalaryEntry.TYPE_DAY_OFF) {
                t.daysOff++;
            } else {
                t.daysAbsent++;
            }
        }
        return t;
    }
}
