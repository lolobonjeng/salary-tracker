package com.cronzph.salarytracker;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.ui.AppDialog;
import com.cronzph.salarytracker.ui.EntryAdapter;
import com.cronzph.salarytracker.util.BackupUtils;
import com.cronzph.salarytracker.util.ExportUtils;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Buong ledger, naka-group kada cutoff. May export/share. */
public class HistoryActivity extends AppCompatActivity implements EntryAdapter.OnEntryClick {

    private SalaryDbHelper db;
    private EntryAdapter adapter;
    private TextView txtEmpty;

    /** SAF: pipiliin ng user kung saan ise-save ang .xlsx backup. */
    private final ActivityResultLauncher<String> createBackup =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(BackupUtils.XLSX_MIME),
                    uri -> {
                        if (uri != null) writeBackup(uri);
                    });

    /** SAF: pipiliin ng user ang .xlsx backup na ire-restore. */
    private final ActivityResultLauncher<String[]> openBackup =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) readBackup(uri);
                    });

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
        final boolean empty = ascending.isEmpty();
        final double hoursPerDay = Prefs.getHoursPerDay(this);
        final Map<String, Double> benefits = db.allBenefits();

        CharSequence[] options = {
                getString(R.string.share_summary),
                getString(R.string.save_csv),
                getString(R.string.backup_excel),
                getString(R.string.backup_restore)
        };

        new AppDialog.Builder(this)
                .icon(R.drawable.ic_share)
                .title(R.string.export_share)
                .items(options, (dialog, which) -> {
                    // Ang restore ay dapat gumana kahit walang laman ang app —
                    // iyon nga ang punto ng backup.
                    if (which == 0 || which == 1) {
                        if (empty) {
                            Toast.makeText(this, R.string.nothing_to_export,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    if (which == 0) {
                        List<SalaryEntry> descending = new ArrayList<>(ascending);
                        Collections.reverse(descending);
                        String text = ExportUtils.buildSummary(descending, hoursPerDay, benefits);
                        ExportUtils.shareText(this, getString(R.string.summary_subject), text);
                    } else if (which == 1) {
                        saveCsv(ascending, hoursPerDay, benefits);
                    } else if (which == 2) {
                        if (empty) {
                            Toast.makeText(this, R.string.nothing_to_export,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String stamp = new SimpleDateFormat("yyyyMMdd", Locale.US)
                                .format(new Date());
                        createBackup.launch("salary-backup-" + stamp + ".xlsx");
                    } else {
                        // Tanggapin ang lahat — may file managers na hindi tama
                        // ang MIME na iniuulat para sa .xlsx.
                        openBackup.launch(new String[]{BackupUtils.XLSX_MIME, "*/*"});
                    }
                })
                .negative(R.string.action_cancel, null)
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

    // ------------------------------------------------------------ backup

    private void writeBackup(final Uri uri) {
        final List<SalaryEntry> ascending = db.all();
        final double hoursPerDay = Prefs.getHoursPerDay(this);
        final Map<String, Double> benefits = db.allBenefits();

        Toast.makeText(this, R.string.backup_saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new IOException("Cannot open the file");
                BackupUtils.write(out, ascending, hoursPerDay, benefits);
                final String name = displayName(uri);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, getString(R.string.backup_done, name),
                            Toast.LENGTH_LONG).show();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, getString(R.string.backup_failed, describe(e)),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                close(out);
            }
        }).start();
    }

    /**
     * Tunay na pangalan ng file. Ang {@code getLastPathSegment()} sa SAF document Uri
     * ay ang document ID (hal. "msf:1000000123"), hindi ang filename.
     */
    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // babalik sa last path segment
        } finally {
            if (c != null) c.close();
        }
        return String.valueOf(uri.getLastPathSegment());
    }

    private void readBackup(final Uri uri) {
        new Thread(() -> {
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Cannot open the file");
                final BackupUtils.Parsed parsed = BackupUtils.parse(in);
                runOnUiThread(() -> confirmRestore(parsed));
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, getString(R.string.restore_failed, describe(e)),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                close(in);
            }
        }).start();
    }

    /** Ipakita muna kung ilan ang maidaragdag bago isulat sa database. */
    private void confirmRestore(final BackupUtils.Parsed parsed) {
        if (isFinishing() || isDestroyed()) return;
        if (parsed.entries.isEmpty() && parsed.benefits.isEmpty()) {
            Toast.makeText(this, R.string.restore_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder msg = new StringBuilder(
                getString(R.string.restore_msg, parsed.entries.size(), parsed.benefits.size()));
        if (parsed.rowsSkipped > 0) {
            msg.append("\n\n").append(getString(R.string.restore_skipped, parsed.rowsSkipped));
        }

        new AppDialog.Builder(this)
                .icon(R.drawable.ic_history)
                .title(R.string.restore_title)
                .message(msg)
                .positive(R.string.restore_confirm, d -> {
                    BackupUtils.Result r = BackupUtils.apply(parsed, db);
                    Toast.makeText(this, getString(R.string.restore_done,
                            r.entriesImported, r.benefitsImported), Toast.LENGTH_LONG).show();
                    load();
                })
                .negative(R.string.action_cancel, null)
                .show();
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
