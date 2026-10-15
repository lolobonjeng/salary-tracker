package com.cronzph.salarytracker;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.ui.EntryAdapter;
import com.cronzph.salarytracker.util.ExportUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Buong ledger, naka-group kada cutoff. May export/share. */
public class HistoryActivity extends AppCompatActivity implements EntryAdapter.OnEntryClick {

    private SalaryDbHelper db;
    private EntryAdapter adapter;
    private TextView txtEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        db = SalaryDbHelper.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share) {
                showExportDialog();
                return true;
            }
            return false;
        });

        txtEmpty = findViewById(R.id.txtEmpty);
        RecyclerView rv = findViewById(R.id.rvHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EntryAdapter(this);
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        List<SalaryEntry> ascending = db.all();

        List<SalaryEntry> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);

        adapter.submit(descending, Prefs.getHoursPerDay(this), db.allBenefits());
        txtEmpty.setVisibility(ascending.isEmpty() ? TextView.VISIBLE : TextView.GONE);
    }

    @Override
    public void onEntryClick(SalaryEntry entry) {
        Intent i = new Intent(this, EntryActivity.class);
        i.putExtra(EntryActivity.EXTRA_ID, entry.id);
        startActivity(i);
    }

    private void showExportDialog() {
        final List<SalaryEntry> ascending = db.all();
        if (ascending.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_export, Toast.LENGTH_SHORT).show();
            return;
        }

        final double hoursPerDay = Prefs.getHoursPerDay(this);
        final Map<String, Double> benefits = db.allBenefits();
        CharSequence[] options = {
                getString(R.string.share_summary),
                getString(R.string.save_csv)
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_share)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        List<SalaryEntry> descending = new ArrayList<>(ascending);
                        Collections.reverse(descending);
                        String text = ExportUtils.buildSummary(descending, hoursPerDay, benefits);
                        ExportUtils.shareText(this, getString(R.string.summary_subject), text);
                    } else {
                        saveCsv(ascending, hoursPerDay, benefits);
                    }
                })
                .show();
    }

    private void saveCsv(List<SalaryEntry> ascending, double hoursPerDay,
                         Map<String, Double> benefits) {
        String csv = ExportUtils.buildCsv(ascending, hoursPerDay, benefits);
        try {
            File out = ExportUtils.saveCsv(this, csv);
            Toast.makeText(this, getString(R.string.csv_saved, out.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } catch (IOException ex) {
            Toast.makeText(this, R.string.csv_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
