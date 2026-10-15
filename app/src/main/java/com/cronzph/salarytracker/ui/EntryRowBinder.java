package com.cronzph.salarytracker.ui;

import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.util.CutoffUtils;
import com.cronzph.salarytracker.util.Money;

import java.util.Locale;

/** Ibinabahagi ng dashboard at history ang parehong row layout. */
public final class EntryRowBinder {

    private EntryRowBinder() {
    }

    public static void bind(View root, SalaryEntry e, double hoursPerDay) {
        TextView tvDay = root.findViewById(R.id.tvDay);
        TextView tvDate = root.findViewById(R.id.tvDate);
        TextView tvTypeBadge = root.findViewById(R.id.tvTypeBadge);
        TextView tvOtBadge = root.findViewById(R.id.tvOtBadge);
        TextView tvAmount = root.findViewById(R.id.tvAmount);
        TextView tvBreakdown = root.findViewById(R.id.tvBreakdown);

        int day = CutoffUtils.dayOfMonth(e.workDate);
        tvDay.setText(String.format(Locale.US, "%02d", day));
        tvDate.setText(CutoffUtils.prettyDate(e.workDate));

        boolean work = e.dayType == SalaryEntry.TYPE_WORK;

        // --- day badge colors ---
        int badgeBg;
        int badgeFg;
        if (work) {
            badgeBg = R.drawable.bg_pill_work;
            badgeFg = R.color.onPrimaryContainer;
        } else if (e.dayType == SalaryEntry.TYPE_DAY_OFF) {
            badgeBg = R.drawable.bg_pill_neutral;
            badgeFg = R.color.onSurfaceVariant;
        } else {
            badgeBg = R.drawable.bg_pill_error;
            badgeFg = R.color.onErrorContainer;
        }
        tvDay.setBackgroundResource(badgeBg);
        tvDay.setTextColor(ContextCompat.getColor(root.getContext(), badgeFg));

        // --- type badge (work days stay clean) ---
        if (e.dayType == SalaryEntry.TYPE_DAY_OFF) {
            tvTypeBadge.setVisibility(View.VISIBLE);
            tvTypeBadge.setText(R.string.type_day_off);
        } else if (e.dayType == SalaryEntry.TYPE_ABSENT) {
            tvTypeBadge.setVisibility(View.VISIBLE);
            tvTypeBadge.setText(R.string.type_absent);
        } else {
            tvTypeBadge.setVisibility(View.GONE);
        }

        // --- OT badge ---
        if (work && e.otHours > 0) {
            tvOtBadge.setVisibility(View.VISIBLE);
            tvOtBadge.setText("+" + Money.shortPlain(e.otHours) + " OT");
        } else {
            tvOtBadge.setVisibility(View.GONE);
        }

        // --- amounts ---
        if (!work) {
            tvAmount.setText("\u2014");
            tvAmount.setTextColor(ContextCompat.getColor(root.getContext(), R.color.neutral));
            tvBreakdown.setText("");
        } else {
            double ot = e.otAmount(hoursPerDay);
            tvAmount.setText(Money.plain(e.total(hoursPerDay)));
            tvAmount.setTextColor(ContextCompat.getColor(root.getContext(), R.color.onSurface));
            if (ot > 0) {
                tvBreakdown.setText(Money.shortPlain(e.basePay) + " + " + Money.shortPlain(ot) + " OT");
            } else {
                tvBreakdown.setText(Money.shortPlain(e.basePay));
            }
        }
    }
}
