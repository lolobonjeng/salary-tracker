package com.cronzph.salarytracker.util;

import android.content.Context;
import android.content.Intent;

import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Text summary + CSV export. Walang external dependency. */
public final class ExportUtils {

    private ExportUtils() {
    }

    /**
     * Human-readable summary, naka-group kada cutoff (pinakabago muna),
     * may gross / bawas / net.
     */
    public static String buildSummary(List<SalaryEntry> entriesDesc, double hoursPerDay,
                                      Map<String, Double> benefits) {
        StringBuilder sb = new StringBuilder("SALARY SUMMARY\n");
        if (entriesDesc.isEmpty()) {
            sb.append("\n(no entries)\n");
            return sb.toString();
        }

        List<SalaryEntry> group = new ArrayList<>();
        String lastKey = null;
        CutoffUtils.Period lastPeriod = null;

        for (SalaryEntry e : entriesDesc) {
            CutoffUtils.Period p = CutoffUtils.periodOf(e.workDate);
            String key = p.key();
            if (!key.equals(lastKey)) {
                appendGroup(sb, group, lastPeriod, hoursPerDay, benefits);
                group = new ArrayList<>();
                lastKey = key;
                lastPeriod = p;
            }
            group.add(e);
        }
        appendGroup(sb, group, lastPeriod, hoursPerDay, benefits);
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, List<SalaryEntry> group,
                                    CutoffUtils.Period p, double hoursPerDay,
                                    Map<String, Double> benefits) {
        if (group.isEmpty() || p == null) return;

        SalaryDbHelper.Totals t = SalaryDbHelper.totals(group, hoursPerDay);
        double gross = t.grandTotal();
        double deduct = benefits == null || !benefits.containsKey(p.key())
                ? 0d : benefits.get(p.key());

        sb.append('\n').append(p.label).append('\n');
        sb.append("----------------------------\n");
        sb.append("Days worked : ").append(t.daysWorked).append('\n');
        sb.append("Base pay    : ").append(Money.plain(t.baseTotal)).append('\n');
        sb.append("Overtime    : ").append(Money.plain(t.otTotal))
                .append("  (").append(Money.shortPlain(t.otHoursTotal)).append(" hrs)\n");
        sb.append("Gross       : ").append(Money.plain(gross)).append('\n');
        if (deduct > 0d) {
            sb.append("Deductions  : -").append(Money.plain(deduct)).append('\n');
        }
        sb.append("NET PAY     : ").append(Money.plain(gross - deduct)).append('\n');
        if (t.daysOff > 0) {
            sb.append("Day off     : ").append(t.daysOff).append('\n');
        }
        if (t.daysAbsent > 0) {
            sb.append("Absent      : ").append(t.daysAbsent).append('\n');
        }
    }

    /**
     * CSV para sa spreadsheet. entriesAsc — sunod-sunod ayon sa petsa.
     * Sa dulo, may cutoff summary na may gross/bawas/net.
     */
    public static String buildCsv(List<SalaryEntry> entriesAsc, double hoursPerDay,
                                  Map<String, Double> benefits) {
        StringBuilder sb = new StringBuilder();
        sb.append("date,day_type,base_pay,ot_hours,ot_multiplier,ot_amount,total,notes\n");
        for (SalaryEntry e : entriesAsc) {
            sb.append(e.workDate).append(',')
                    .append(typeName(e.dayType)).append(',')
                    .append(num(e.basePay)).append(',')
                    .append(num(e.otHours)).append(',')
                    .append(num(e.otMultiplier)).append(',')
                    .append(num(e.otAmount(hoursPerDay))).append(',')
                    .append(num(e.total(hoursPerDay))).append(',')
                    .append(csvField(e.notes)).append('\n');
        }

        // ---- cutoff summary ----
        sb.append("\n# CUTOFF SUMMARY\n");
        sb.append("period,gross,benefits,net\n");
        for (Map.Entry<CutoffUtils.Period, Double> entry : groupGross(entriesAsc, hoursPerDay)
                .entrySet()) {
            CutoffUtils.Period p = entry.getKey();
            double gross = entry.getValue();
            double deduct = benefits == null || !benefits.containsKey(p.key())
                    ? 0d : benefits.get(p.key());
            sb.append(csvField(p.label)).append(',')
                    .append(num(gross)).append(',')
                    .append(num(deduct)).append(',')
                    .append(num(gross - deduct)).append('\n');
        }
        return sb.toString();
    }

    /** Gross kada cutoff, sa pagkakasunod ng petsa. */
    private static Map<CutoffUtils.Period, Double> groupGross(List<SalaryEntry> entriesAsc,
                                                              double hoursPerDay) {
        Map<CutoffUtils.Period, Double> out = new LinkedHashMap<>();
        for (SalaryEntry e : entriesAsc) {
            CutoffUtils.Period p = CutoffUtils.periodOf(e.workDate);
            Double current = out.get(p);
            out.put(p, (current == null ? 0d : current) + e.total(hoursPerDay));
        }
        return out;
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String typeName(int dayType) {
        if (dayType == SalaryEntry.TYPE_DAY_OFF) return "Day off";
        if (dayType == SalaryEntry.TYPE_ABSENT) return "Absent";
        return "Work";
    }

    private static String csvField(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** Isusulat sa app-specific storage — walang permission na kailangan. */
    public static File saveCsv(Context c, String csv) throws IOException {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File dir = new File(base, "exports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "salary-" + stamp + ".csv");

        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(csv.getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
        return out;
    }

    public static void shareText(Context c, String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, text);
        c.startActivity(Intent.createChooser(i, c.getString(R.string.share_via)));
    }
}
