package com.cronzph.salarytracker.util;

import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Excel (.xlsx) backup at restore.
 *
 * <p>Ang restore ay <b>merge</b> — hindi kailanman buburahin ang existing na data.
 * Kung may kaparehong petsa, ang nasa backup ang mananaig (ang {@code save()} ay
 * {@code CONFLICT_REPLACE} sa unique na index ng petsa).
 */
public final class BackupUtils {

    public static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // Ang mga header na ito rin ang pang-detect ng sheet at pang-map ng column.
    private static final String H_DATE = "Date";
    private static final String H_TYPE = "Day type";
    private static final String H_BASE = "Base pay";
    private static final String H_OT_HOURS = "OT hours";
    private static final String H_OT_MULT = "OT multiplier";
    private static final String H_OT_OVERRIDE = "OT override";
    private static final String H_OT_AMOUNT = "OT amount";
    private static final String H_TOTAL = "Total";
    private static final String H_NOTES = "Notes";

    private static final String H_PSTART = "Period start";
    private static final String H_PEND = "Period end";
    private static final String H_AMOUNT = "Amount";

    private BackupUtils() {
    }

    // ================================================================== write

    /** Isulat ang buong ledger bilang .xlsx — dalawang sheet: Entries at Benefits. */
    public static void write(OutputStream out, List<SalaryEntry> entriesAsc,
                             double hoursPerDay, Map<String, Double> benefits) throws IOException {
        List<XlsxUtils.Sheet> sheets = new ArrayList<>();
        sheets.add(entriesSheet(entriesAsc, hoursPerDay));
        sheets.add(benefitsSheet(benefits));
        XlsxUtils.write(out, sheets);
    }

    private static XlsxUtils.Sheet entriesSheet(List<SalaryEntry> entriesAsc, double hoursPerDay) {
        XlsxUtils.Sheet s = new XlsxUtils.Sheet("Entries");
        s.row(H_DATE, H_TYPE, H_BASE, H_OT_HOURS, H_OT_MULT, H_OT_OVERRIDE,
                H_OT_AMOUNT, H_TOTAL, H_NOTES);
        if (entriesAsc != null) {
            for (SalaryEntry e : entriesAsc) {
                s.row(
                        e.workDate,
                        typeName(e.dayType),
                        e.basePay,
                        e.otHours,
                        e.otMultiplier,
                        e.otOverride == null ? "" : e.otOverride,
                        e.otAmount(hoursPerDay),
                        e.total(hoursPerDay),
                        e.notes == null ? "" : e.notes);
            }
        }
        return s;
    }

    private static XlsxUtils.Sheet benefitsSheet(Map<String, Double> benefits) {
        XlsxUtils.Sheet s = new XlsxUtils.Sheet("Benefits");
        s.row(H_PSTART, H_PEND, H_AMOUNT);
        if (benefits == null || benefits.isEmpty()) return s;

        List<String> keys = new ArrayList<>(benefits.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a == null ? -1 : (b == null ? 1 : a.compareTo(b));
            }
        });

        for (String key : keys) {
            String[] parts = splitKey(key);
            if (parts == null) continue;
            Double amount = benefits.get(key);
            s.row(parts[0], parts[1], amount == null ? 0d : amount);
        }
        return s;
    }

    /** "2026-09-16..2026-09-30" -> {"2026-09-16", "2026-09-30"} */
    private static String[] splitKey(String key) {
        if (key == null) return null;
        int i = key.indexOf("..");
        if (i <= 0 || i + 2 >= key.length()) return null;
        return new String[]{key.substring(0, i), key.substring(i + 2)};
    }

    // ================================================================ restore

    /** Nabasa na backup — hindi pa naisusulat sa database. */
    public static final class Parsed {
        public final List<SalaryEntry> entries = new ArrayList<>();
        public final Map<String, Double> benefits = new LinkedHashMap<>();
        public int rowsSkipped;
    }

    /** Bilang ng naisulat na sa database. */
    public static final class Result {
        public int entriesImported;
        public int benefitsImported;
    }

    /**
     * Basahin ang backup. Wala pang isinusulat sa database — kaya makikita mo
     * muna kung ilan ang laman bago mo kumpirmahin.
     *
     * @throws IOException kung hindi mabasa o hindi ito Salary Tracker backup
     */
    public static Parsed parse(InputStream in) throws IOException {
        List<XlsxUtils.Sheet> sheets = XlsxUtils.read(in);
        if (sheets.isEmpty()) {
            throw new IOException("walang worksheet sa file");
        }

        Parsed parsed = new Parsed();
        boolean foundEntries = false;

        for (XlsxUtils.Sheet sheet : sheets) {
            Map<String, Integer> cols = headerMap(sheet);
            if (cols.containsKey(H_DATE) && cols.containsKey(H_TYPE)) {
                foundEntries = true;
                readEntries(sheet, cols, parsed);
            } else if (cols.containsKey(H_PSTART) && cols.containsKey(H_AMOUNT)) {
                readBenefits(sheet, cols, parsed);
            }
        }

        if (!foundEntries) {
            throw new IOException("hindi ito Salary Tracker backup");
        }
        return parsed;
    }

    /**
     * Isulat sa database — <b>merge lang</b>. Hindi kailanman buburahin ang
     * existing. Kung may kaparehong petsa, ang nasa backup ang mananaig
     * (ang {@code save()} ay {@code CONFLICT_REPLACE} sa unique na index ng petsa).
     */
    public static Result apply(Parsed parsed, SalaryDbHelper db) {
        Result res = new Result();
        for (SalaryEntry e : parsed.entries) {
            db.save(e);
            res.entriesImported++;
        }
        for (Map.Entry<String, Double> en : parsed.benefits.entrySet()) {
            db.setBenefits(en.getKey(), en.getValue());
            res.benefitsImported++;
        }
        return res;
    }

    private static void readEntries(XlsxUtils.Sheet sheet, Map<String, Integer> cols,
                                    Parsed parsed) {
        for (int r = 1; r < sheet.rows.size(); r++) {
            List<Object> row = sheet.rows.get(r);
            if (row == null) continue;

            String date = isoDate(cell(row, cols, H_DATE));
            if (date == null) {
                parsed.rowsSkipped++;
                continue;
            }

            SalaryEntry e = new SalaryEntry();
            e.workDate = date;
            e.dayType = parseType(str(cell(row, cols, H_TYPE)));
            e.basePay = num(cell(row, cols, H_BASE));
            e.otHours = num(cell(row, cols, H_OT_HOURS));

            double mult = num(cell(row, cols, H_OT_MULT));
            e.otMultiplier = mult > 0 ? mult : 1.25d;

            Object override = cell(row, cols, H_OT_OVERRIDE);
            e.otOverride = isBlank(override) ? null : num(override);

            String notes = str(cell(row, cols, H_NOTES));
            e.notes = notes == null ? "" : notes;

            parsed.entries.add(e);
        }
    }

    private static void readBenefits(XlsxUtils.Sheet sheet, Map<String, Integer> cols,
                                     Parsed parsed) {
        for (int r = 1; r < sheet.rows.size(); r++) {
            List<Object> row = sheet.rows.get(r);
            if (row == null) continue;

            String start = isoDate(cell(row, cols, H_PSTART));
            String end = isoDate(cell(row, cols, H_PEND));
            Object amount = cell(row, cols, H_AMOUNT);
            if (start == null || end == null || isBlank(amount)) {
                parsed.rowsSkipped++;
                continue;
            }
            parsed.benefits.put(start + ".." + end, num(amount));
        }
    }

    // ================================================================ helpers

    private static Map<String, Integer> headerMap(XlsxUtils.Sheet sheet) {
        Map<String, Integer> out = new HashMap<>();
        if (sheet.rows.isEmpty()) return out;
        List<Object> first = sheet.rows.get(0);
        if (first == null) return out;
        for (int i = 0; i < first.size(); i++) {
            String key = str(first.get(i));
            if (key != null && !key.trim().isEmpty()) {
                out.put(key.trim(), i);
            }
        }
        return out;
    }

    private static Object cell(List<Object> row, Map<String, Integer> cols, String name) {
        Integer i = cols.get(name);
        if (i == null || i >= row.size()) return null;
        return row.get(i);
    }

    private static String str(Object o) {
        if (o == null) return null;
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(o);
    }

    private static double num(Object o) {
        if (o == null) return 0d;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0d;
        }
    }

    private static boolean isBlank(Object o) {
        if (o == null) return true;
        if (o instanceof Number || o instanceof Boolean) return false;
        return String.valueOf(o).trim().isEmpty();
    }

    private static int parseType(String s) {
        if (s == null) return SalaryEntry.TYPE_WORK;
        String v = s.trim().toLowerCase(Locale.US);
        if (v.startsWith("day off") || v.startsWith("dayoff") || v.equals("off")) {
            return SalaryEntry.TYPE_DAY_OFF;
        }
        if (v.startsWith("absent")) return SalaryEntry.TYPE_ABSENT;
        return SalaryEntry.TYPE_WORK;
    }

    /**
     * ISO date mula sa cell. Tumatanggap ng text ("2026-09-28") at ng Excel date
     * serial (numero) — kung buksan mo ang backup sa Excel at i-save ulit, ang
     * petsa ay nagiging serial number.
     */
    private static String isoDate(Object o) {
        if (o == null) return null;
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d < 20000d || d > 80000d) return null;
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            cal.clear();
            cal.set(1899, Calendar.DECEMBER, 30);
            cal.add(Calendar.DAY_OF_YEAR, (int) Math.round(d));
            return String.format(Locale.US, "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
        }
        String s = String.valueOf(o).trim();
        if (s.length() >= 10
                && Character.isDigit(s.charAt(0))
                && s.charAt(4) == '-'
                && s.charAt(7) == '-') {
            return s.substring(0, 10);
        }
        return null;
    }

    private static String typeName(int dayType) {
        if (dayType == SalaryEntry.TYPE_DAY_OFF) return "Day off";
        if (dayType == SalaryEntry.TYPE_ABSENT) return "Absent";
        return "Work";
    }
}
