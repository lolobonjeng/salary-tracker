package com.cronzph.salarytracker.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;

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
    /** v1 = entries only. v2 = cutoff-level deductions. v3 = payout records. */
    private static final int DB_VERSION = 3;

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

    /** Salary payout per cutoff: date sent + proof image. */
    public static final String TP = "payouts";
    public static final String P_PERIOD = "period_key";
    public static final String P_DATE = "paid_date";
    public static final String P_AMOUNT = "amount";
    public static final String P_PROOF = "proof_path";

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

    private static final String CREATE_PAYOUTS = "CREATE TABLE " + TP + " ("
            + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + P_PERIOD + " TEXT NOT NULL UNIQUE, "
            + P_DATE + " TEXT NOT NULL, "
            + P_AMOUNT + " REAL NOT NULL DEFAULT 0, "
            + P_PROOF + " TEXT NOT NULL)";

    private static SalaryDbHelper instance;

    public static synchronized SalaryDbHelper get(Context c) {
        if (instance == null) {
            instance = new SalaryDbHelper(c.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;

    private SalaryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_ENTRIES);
        db.execSQL(CREATE_ENTRIES_INDEX);
        db.execSQL(CREATE_BENEFITS);
        db.execSQL(CREATE_PAYOUTS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NON-DESTRUCTIVE: huwag buburahin ang entries ng user.
        if (oldVersion < 2) {
            db.execSQL(CREATE_BENEFITS);
        }
        if (oldVersion < 3) {
            db.execSQL(CREATE_PAYOUTS);
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
        db.delete(TP, null, null);
        deleteAllProofFiles();
    }

    private void deleteAllProofFiles() {
        File dir = new File(appContext.getFilesDir(), "proofs");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
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

    // ------------------------------------------------------------- payouts

    /** Payout record for one cutoff, or null if the salary is not marked as sent. */
    public Payout getPayout(String periodKey) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TP, null,
                    P_PERIOD + "=?", new String[]{periodKey}, null, null, null);
            return c.moveToFirst() ? payoutFromCursor(c) : null;
        } finally {
            if (c != null) c.close();
        }
    }

    public long savePayout(Payout p) {
        ContentValues v = new ContentValues();
        v.put(P_PERIOD, p.periodKey);
        v.put(P_DATE, p.paidDate);
        v.put(P_AMOUNT, p.amount);
        v.put(P_PROOF, p.proofPath);
        SQLiteDatabase db = getWritableDatabase();
        if (p.id > 0) {
            v.put(C_ID, p.id);
            db.insertWithOnConflict(TP, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            return p.id;
        }
        return db.insertWithOnConflict(TP, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deletePayout(String periodKey) {
        getWritableDatabase().delete(TP, P_PERIOD + "=?", new String[]{periodKey});
    }

    private static Payout payoutFromCursor(Cursor c) {
        Payout p = new Payout();
        p.id = c.getLong(c.getColumnIndexOrThrow(C_ID));
        p.periodKey = c.getString(c.getColumnIndexOrThrow(P_PERIOD));
        p.paidDate = c.getString(c.getColumnIndexOrThrow(P_DATE));
        p.amount = c.getDouble(c.getColumnIndexOrThrow(P_AMOUNT));
        p.proofPath = c.getString(c.getColumnIndexOrThrow(P_PROOF));
        return p;
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
