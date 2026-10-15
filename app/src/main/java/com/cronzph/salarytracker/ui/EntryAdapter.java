package com.cronzph.salarytracker.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cronzph.salarytracker.R;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.util.CutoffUtils;
import com.cronzph.salarytracker.util.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** History list — naka-group kada cutoff, may gross/bawas/net sa header. */
public class EntryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnEntryClick {
        void onEntryClick(SalaryEntry entry);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ENTRY = 1;

    private static class Row {
        final CutoffUtils.Period period;
        final String headerSub;
        final double headerNet;
        final String headerDeduct;
        final SalaryEntry entry;

        Row(CutoffUtils.Period period, String headerSub, double headerNet,
            String headerDeduct, SalaryEntry entry) {
            this.period = period;
            this.headerSub = headerSub;
            this.headerNet = headerNet;
            this.headerDeduct = headerDeduct;
            this.entry = entry;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnEntryClick listener;
    private double hoursPerDay = 8d;
    private Map<String, Double> benefits = new HashMap<>();

    public EntryAdapter(OnEntryClick listener) {
        this.listener = listener;
    }

    /** entriesDesc — pinakabago muna. benefits — period key papuntang bawas. */
    public void submit(List<SalaryEntry> entriesDesc, double hoursPerDay,
                       Map<String, Double> benefits) {
        this.hoursPerDay = hoursPerDay;
        this.benefits = benefits == null ? new HashMap<String, Double>() : benefits;
        rows.clear();

        List<SalaryEntry> group = new ArrayList<>();
        String lastKey = null;
        CutoffUtils.Period lastPeriod = null;

        for (SalaryEntry e : entriesDesc) {
            CutoffUtils.Period p = CutoffUtils.periodOf(e.workDate);
            String key = p.key();
            if (!key.equals(lastKey)) {
                flush(group, lastPeriod);
                group = new ArrayList<>();
                lastKey = key;
                lastPeriod = p;
            }
            group.add(e);
        }
        flush(group, lastPeriod);

        notifyDataSetChanged();
    }

    private void flush(List<SalaryEntry> group, CutoffUtils.Period period) {
        if (group.isEmpty() || period == null) return;

        SalaryDbHelper.Totals t = SalaryDbHelper.totals(group, hoursPerDay);
        String sub = t.otEntries > 0
                ? String.format(Locale.US, "%d days \u00B7 %d OT", t.daysWorked, t.otEntries)
                : String.format(Locale.US, "%d days", t.daysWorked);

        double gross = t.grandTotal();
        Double b = benefits.get(period.key());
        double deduct = b == null ? 0d : b;
        double net = gross - deduct;

        String deductText = null;
        if (deduct > 0d) {
            deductText = "gross " + Money.plain(gross) + " \u2212 " + Money.plain(deduct);
        }

        rows.add(new Row(period, sub, net, deductText, null));
        for (SalaryEntry e : group) {
            rows.add(new Row(period, null, 0d, null, e));
        }
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).entry == null ? TYPE_HEADER : TYPE_ENTRY;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_history_header, parent, false));
        }
        return new EntryVH(inf.inflate(R.layout.item_entry, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.title.setText(row.period.label);
            h.sub.setText(row.headerSub);
            h.total.setText(Money.peso(row.headerNet));
            if (row.headerDeduct == null) {
                h.deduct.setVisibility(View.GONE);
            } else {
                h.deduct.setVisibility(View.VISIBLE);
                h.deduct.setText(row.headerDeduct);
            }
        } else {
            final EntryVH h = (EntryVH) holder;
            h.entry = row.entry;
            EntryRowBinder.bind(h.itemView, h.entry, hoursPerDay);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onEntryClick(h.entry);
                }
            });
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final TextView total;
        final TextView deduct;

        HeaderVH(View v) {
            super(v);
            title = v.findViewById(R.id.tvHeaderTitle);
            sub = v.findViewById(R.id.tvHeaderSub);
            total = v.findViewById(R.id.tvHeaderTotal);
            deduct = v.findViewById(R.id.tvHeaderDeduct);
        }
    }

    static class EntryVH extends RecyclerView.ViewHolder {
        SalaryEntry entry;

        EntryVH(View v) {
            super(v);
        }
    }
}
