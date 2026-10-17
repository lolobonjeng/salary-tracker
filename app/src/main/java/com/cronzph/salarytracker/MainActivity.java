package com.cronzph.salarytracker;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cronzph.salarytracker.data.Payout;
import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.ui.BarChartView;
import com.cronzph.salarytracker.ui.EntryRowBinder;
import com.cronzph.salarytracker.util.CutoffUtils;
import com.cronzph.salarytracker.util.Money;
import com.cronzph.salarytracker.util.Updater;
import com.cronzph.salarytracker.util.UpdaterUI;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard: net pay para sa cutoff, bar chart, at recent entries.
 * Ang "bawas" (benefits) ay kada cutoff — ikaw ang naglalagay ng halaga.
 */
public class MainActivity extends AppCompatActivity {

    private static final int MAX_RECENT = 7;

    private SalaryDbHelper db;
    private CutoffUtils.Period period;

    private TextView txtPeriod;
    private TextView txtPeriodRange;
    private TextView txtNetPay;
    private TextView txtNetSub;
    private TextView txtDaysWorked;
    private TextView txtBaseTotal;
    private TextView txtOtTotal;
    private TextView txtGrossPay;
    private TextView txtBenefits;
    private TextView txtBenefitsHint;
    private TextView txtEmpty;
    private TextView txtPayoutStatus;
    private TextView txtPayoutHint;
    private android.widget.ImageButton btnNext;
    private LinearLayout recentContainer;
    private BarChartView chart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = SalaryDbHelper.get(this);
        period = CutoffUtils.current();

        txtPeriod = findViewById(R.id.txtPeriod);
        txtPeriodRange = findViewById(R.id.txtPeriodRange);
        txtNetPay = findViewById(R.id.txtNetPay);
        txtNetSub = findViewById(R.id.txtNetSub);
        txtDaysWorked = findViewById(R.id.txtDaysWorked);
        txtBaseTotal = findViewById(R.id.txtBaseTotal);
        txtOtTotal = findViewById(R.id.txtOtTotal);
        txtGrossPay = findViewById(R.id.txtGrossPay);
        txtBenefits = findViewById(R.id.txtBenefits);
        txtBenefitsHint = findViewById(R.id.txtBenefitsHint);
        txtEmpty = findViewById(R.id.txtEmpty);
        txtPayoutStatus = findViewById(R.id.txtPayoutStatus);
        txtPayoutHint = findViewById(R.id.txtPayoutHint);
        btnNext = findViewById(R.id.btnNext);
        recentContainer = findViewById(R.id.recentContainer);
        chart = findViewById(R.id.chart);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_history) {
                startActivity(new Intent(this, HistoryActivity.class));
                return true;
            }
            if (id == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            period = period.prev();
            refresh();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            period = period.next();
            refresh();
        });
        findViewById(R.id.btnToday).setOnClickListener(v -> {
            period = CutoffUtils.current();
            refresh();
        });
        findViewById(R.id.btnViewAll).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.rowBenefits).setOnClickListener(v -> editBenefits());
        findViewById(R.id.cardPayout).setOnClickListener(v -> openPayout());

        ExtendedFloatingActionButton fab = findViewById(R.id.fabAdd);
        fab.setOnClickListener(v -> startActivity(new Intent(this, EntryActivity.class)));

        // Kusang check ng update — may 24-oras na throttle at sinusunod ang setting.
        UpdaterUI.autoCheck(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // Kung bumukas naman ang app, wala nang silbi ang "i-tap para buksan" na abiso.
        Updater.clearRelaunchNotification(this);
        UpdaterUI.resume(this);
    }

    @Override
    protected void onDestroy() {
        UpdaterUI.detach();
        super.onDestroy();
    }

    private void refresh() {
        double hoursPerDay = Prefs.getHoursPerDay(this);
        List<SalaryEntry> entries = db.between(period.startIso, period.endIso);
        SalaryDbHelper.Totals t = SalaryDbHelper.totals(entries, hoursPerDay);

        double gross = t.grandTotal();
        double benefits = db.getBenefits(period.key());
        double net = gross - benefits;

        txtPeriod.setText(period.label);
        txtPeriodRange.setText(CutoffUtils.prettyDate(period.startIso)
                + " \u2014 " + CutoffUtils.prettyDate(period.endIso));

        txtNetPay.setText(Money.peso(net));
        txtDaysWorked.setText(String.valueOf(t.daysWorked));
        txtBaseTotal.setText(Money.plain(t.baseTotal));
        txtOtTotal.setText(Money.plain(t.otTotal));
        txtGrossPay.setText(Money.plain(gross));

        if (benefits > 0d) {
            txtBenefits.setText("\u2212" + Money.plain(benefits));
            txtBenefitsHint.setVisibility(View.GONE);
        } else {
            txtBenefits.setText(Money.plain(0d));
            txtBenefitsHint.setVisibility(View.VISIBLE);
        }

        StringBuilder sub = new StringBuilder();
        sub.append(t.daysWorked).append(" days worked");
        if (t.otEntries > 0) {
            sub.append(" \u00B7 ").append(t.otEntries).append(" OT")
                    .append(" \u00B7 ").append(Money.shortPlain(t.otHoursTotal)).append(" hrs");
        }
        if (t.daysOff > 0) {
            sub.append(" \u00B7 ").append(t.daysOff).append(" day off");
        }
        if (t.daysAbsent > 0) {
            sub.append(" \u00B7 ").append(t.daysAbsent).append(" absent");
        }
        txtNetSub.setText(sub.toString());

        updatePayout();

        // Entries cannot be set in advance, so future cutoffs stay closed.
        boolean atCurrent = period.equals(CutoffUtils.current());
        btnNext.setEnabled(!atCurrent);
        btnNext.setAlpha(atCurrent ? 0.3f : 1f);

        buildChart(entries, hoursPerDay);
        buildRecent(entries, hoursPerDay);
    }

    /** Payout status for the cutoff currently on screen. */
    private void updatePayout() {
        Payout payout = db.getPayout(period.key());
        if (payout != null) {
            txtPayoutStatus.setText(getString(R.string.payout_sent_on,
                    CutoffUtils.prettyDate(payout.paidDate)));
            txtPayoutHint.setText(getString(R.string.payout_proof_attached)
                    + " \u00B7 " + Money.peso(payout.amount));
        } else {
            txtPayoutStatus.setText(R.string.payout_not_sent);
            txtPayoutHint.setText(R.string.payout_not_sent_hint);
        }
    }

    private void openPayout() {
        double hoursPerDay = Prefs.getHoursPerDay(this);
        List<SalaryEntry> entries = db.between(period.startIso, period.endIso);
        double net = SalaryDbHelper.totals(entries, hoursPerDay).grandTotal()
                - db.getBenefits(period.key());

        Intent intent = new Intent(this, PayoutActivity.class);
        intent.putExtra(PayoutActivity.EXTRA_KEY, period.key());
        intent.putExtra(PayoutActivity.EXTRA_LABEL, period.label);
        intent.putExtra(PayoutActivity.EXTRA_AMOUNT, Math.max(net, 0d));
        startActivity(intent);
    }

    /** Dialog para ilagay ang bawas ng cutoff na ito. */
    private void editBenefits() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_benefits, null);
        final TextInputEditText et = content.findViewById(R.id.etBenefits);
        TextView hint = content.findViewById(R.id.txtDialogHint);

        hint.setText(getString(R.string.benefits_dialog_period, period.label));

        double current = db.getBenefits(period.key());
        if (current > 0d) {
            et.setText(Money.input(current));
            et.setSelection(et.getText() == null ? 0 : et.getText().length());
        }

        final String key = period.key();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.benefits_dialog_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.benefits_clear, (dialog, which) -> {
                    db.setBenefits(key, 0d);
                    refresh();
                })
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    db.setBenefits(key, Money.parse(et.getText()));
                    Toast.makeText(this, R.string.benefits_saved, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private void buildChart(List<SalaryEntry> entries, double hoursPerDay) {
        Map<String, SalaryEntry> byDate = new HashMap<>();
        for (SalaryEntry e : entries) {
            byDate.put(e.workDate, e);
        }

        List<BarChartView.Bar> bars = new ArrayList<>();
        for (String iso : CutoffUtils.daysIn(period)) {
            SalaryEntry e = byDate.get(iso);
            double base = 0d;
            double ot = 0d;
            if (e != null && e.dayType == SalaryEntry.TYPE_WORK) {
                base = e.basePay;
                ot = e.otAmount(hoursPerDay);
            }
            bars.add(new BarChartView.Bar(
                    String.valueOf(CutoffUtils.dayOfMonth(iso)),
                    base, ot, CutoffUtils.isToday(iso)));
        }
        chart.setData(bars);
    }

    private void buildRecent(List<SalaryEntry> entries, double hoursPerDay) {
        recentContainer.removeAllViews();

        List<SalaryEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, new Comparator<SalaryEntry>() {
            @Override
            public int compare(SalaryEntry a, SalaryEntry b) {
                return b.workDate.compareTo(a.workDate);
            }
        });

        LayoutInflater inf = LayoutInflater.from(this);
        int count = Math.min(MAX_RECENT, sorted.size());
        for (int i = 0; i < count; i++) {
            final SalaryEntry e = sorted.get(i);
            View row = inf.inflate(R.layout.item_entry, recentContainer, false);
            EntryRowBinder.bind(row, e, hoursPerDay);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, EntryActivity.class);
                intent.putExtra(EntryActivity.EXTRA_ID, e.id);
                startActivity(intent);
            });
            recentContainer.addView(row);
        }

        txtEmpty.setVisibility(sorted.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
