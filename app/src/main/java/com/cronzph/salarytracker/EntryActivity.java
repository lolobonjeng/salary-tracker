package com.cronzph.salarytracker;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.ui.AppDialog;
import com.cronzph.salarytracker.ui.SweetIconView;
import com.cronzph.salarytracker.util.CutoffUtils;
import com.cronzph.salarytracker.util.Money;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Add / edit ng isang araw sa ledger. */
public class EntryActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "entry_id";

    private SalaryDbHelper db;

    private SalaryEntry editing;
    private String selectedDate;

    private MaterialToolbar toolbar;
    private MaterialButtonToggleGroup toggle;
    private TextInputLayout tilBase;
    private TextInputEditText etBase;
    private TextInputLayout tilOtHours;
    private TextInputEditText etOtHours;
    private TextInputLayout tilOtAmount;
    private TextInputEditText etOtAmount;
    private TextInputEditText etNotes;
    private MaterialSwitch swOverride;
    private MaterialButton btnDelete;
    private TextView txtDate;
    private TextView txtOtAuto;
    private TextView txtDayTotal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry);

        db = SalaryDbHelper.get(this);

        toolbar = findViewById(R.id.toolbar);
        toggle = findViewById(R.id.toggleDayType);
        tilBase = findViewById(R.id.tilBase);
        etBase = findViewById(R.id.etBase);
        tilOtHours = findViewById(R.id.tilOtHours);
        etOtHours = findViewById(R.id.etOtHours);
        tilOtAmount = findViewById(R.id.tilOtAmount);
        etOtAmount = findViewById(R.id.etOtAmount);
        etNotes = findViewById(R.id.etNotes);
        swOverride = findViewById(R.id.swOverride);
        btnDelete = findViewById(R.id.btnDelete);
        txtDate = findViewById(R.id.txtDate);
        txtOtAuto = findViewById(R.id.txtOtAuto);
        txtDayTotal = findViewById(R.id.txtDayTotal);

        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.cardDate).setOnClickListener(v -> pickDate());

        // --- edit mode? ---
        long entryId = getIntent().getLongExtra(EXTRA_ID, -1L);
        if (entryId > 0) {
            editing = db.byId(entryId);
            if (editing != null) {
                selectedDate = editing.workDate;
            }
        }
        if (selectedDate == null) {
            // New entries always start on today. Future dates are not allowed,
            // so there is nothing to pre-fill beyond the current day.
            selectedDate = CutoffUtils.todayIso();
            SalaryEntry existing = db.byDate(selectedDate);
            if (existing != null) {
                editing = existing;
            }
        }

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                recompute();
            }
        };
        etBase.addTextChangedListener(watcher);
        etOtHours.addTextChangedListener(watcher);
        etOtAmount.addTextChangedListener(watcher);

        swOverride.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateFieldState();
            recompute();
        });

        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateFieldState();
                recompute();
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());

        populate();
    }

    // ------------------------------------------------------------------ setup

    private void populate() {
        boolean isEdit = editing != null && editing.id > 0;
        toolbar.setTitle(isEdit ? R.string.entry_title_edit : R.string.entry_title_new);
        btnDelete.setVisibility(isEdit ? View.VISIBLE : View.GONE);

        if (editing != null) {
            etBase.setText(Money.input(editing.basePay));
            etOtHours.setText(editing.otHours > 0 ? Money.input(editing.otHours) : "");
            etNotes.setText(editing.notes == null ? "" : editing.notes);
            swOverride.setChecked(editing.otOverride != null);
            etOtAmount.setText(editing.otOverride != null ? Money.input(editing.otOverride) : "");
            selectType(editing.dayType);
        } else {
            // Default entry template (Settings) — para Save lang nang Save.
            etBase.setText(Money.input(Prefs.getDailyRate(this)));
            double defOt = Prefs.getDefaultOtHours(this);
            etOtHours.setText(defOt > 0 ? Money.input(defOt) : "");
            etNotes.setText("");
            swOverride.setChecked(false);
            etOtAmount.setText("");
            selectType(Prefs.getDefaultDayType(this));
        }

        updateDateLabel();
        updateFieldState();
        recompute();
    }

    private void selectType(int dayType) {
        int id = R.id.btnWork;
        if (dayType == SalaryEntry.TYPE_DAY_OFF) {
            id = R.id.btnDayOff;
        } else if (dayType == SalaryEntry.TYPE_ABSENT) {
            id = R.id.btnAbsent;
        }
        toggle.check(id);
    }

    private int currentType() {
        int checked = toggle.getCheckedButtonId();
        if (checked == R.id.btnDayOff) return SalaryEntry.TYPE_DAY_OFF;
        if (checked == R.id.btnAbsent) return SalaryEntry.TYPE_ABSENT;
        return SalaryEntry.TYPE_WORK;
    }

    private void updateFieldState() {
        boolean work = currentType() == SalaryEntry.TYPE_WORK;
        boolean override = swOverride.isChecked();

        tilBase.setEnabled(work);
        tilOtHours.setEnabled(work);
        swOverride.setEnabled(work);

        boolean showAmount = work && override;
        tilOtAmount.setVisibility(showAmount ? View.VISIBLE : View.GONE);
        tilOtAmount.setEnabled(showAmount);
    }

    private void updateDateLabel() {
        String year = selectedDate != null && selectedDate.length() >= 4
                ? selectedDate.substring(0, 4) : "";
        txtDate.setText(CutoffUtils.prettyDate(selectedDate) + ", " + year);
    }

    /**
     * OT = (daily salary / hours per day) x multiplier x OT hours
     * Halimbawa: (600 / 8) x 1.25 x 1 = 93.75
     */
    private void recompute() {
        double hoursPerDay = Prefs.getHoursPerDay(this);
        double multiplier = Prefs.getOtMultiplier(this);

        double base = Money.parse(etBase.getText());
        double otHours = Money.parse(etOtHours.getText());
        double hourly = base / hoursPerDay;
        double auto = hourly * multiplier * otHours;

        boolean work = currentType() == SalaryEntry.TYPE_WORK;

        if (!work) {
            txtOtAuto.setText(R.string.ot_not_workday);
        } else if (otHours <= 0d) {
            txtOtAuto.setText(R.string.ot_no_ot);
        } else {
            txtOtAuto.setText(getString(R.string.ot_auto_hint,
                    Money.peso(hourly),
                    Money.shortPlain(multiplier),
                    Money.shortPlain(otHours),
                    Money.peso(auto)));
        }

        double ot = swOverride.isChecked() ? Money.parse(etOtAmount.getText()) : auto;
        txtDayTotal.setText(Money.peso(work ? base + ot : 0d));
    }

    // ------------------------------------------------------------------ date

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate);
            if (d != null) c.setTime(d);
        } catch (Exception ignored) {
            // fall back sa today
        }

        DatePickerDialog dlg = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            String iso = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            selectedDate = iso;

            SalaryEntry existing = db.byDate(iso);
            if (existing != null && (editing == null || existing.id != editing.id)) {
                editing = existing;
                Toast.makeText(this, R.string.date_has_entry, Toast.LENGTH_SHORT).show();
                populate();
                return;
            }
            updateDateLabel();
            recompute();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));

        // No advance entries: the picker stops at today.
        dlg.getDatePicker().setMaxDate(System.currentTimeMillis());
        dlg.show();
    }

    // ------------------------------------------------------------------ save

    private void save() {
        int dayType = currentType();
        boolean work = dayType == SalaryEntry.TYPE_WORK;
        double base = Money.parse(etBase.getText());
        double otHours = Money.parse(etOtHours.getText());
        boolean override = swOverride.isChecked();
        String overrideRaw = etOtAmount.getText() == null ? "" : etOtAmount.getText().toString().trim();

        tilBase.setError(null);
        tilOtAmount.setError(null);

        if (selectedDate == null || selectedDate.compareTo(CutoffUtils.todayIso()) > 0) {
            Toast.makeText(this, R.string.date_future, Toast.LENGTH_SHORT).show();
            return;
        }

        if (work && base <= 0) {
            tilBase.setError(getString(R.string.invalid_amount));
            return;
        }
        if (work && override && overrideRaw.isEmpty()) {
            tilOtAmount.setError(getString(R.string.invalid_amount));
            return;
        }

        SalaryEntry e = editing != null ? editing : new SalaryEntry();
        e.workDate = selectedDate;
        e.dayType = dayType;
        e.basePay = base;
        e.otHours = otHours;
        e.otMultiplier = Prefs.getOtMultiplier(this);
        e.otOverride = override ? Money.parse(etOtAmount.getText()) : null;
        e.notes = etNotes.getText() == null ? "" : etNotes.getText().toString().trim();

        db.save(e);

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        if (editing == null || editing.id <= 0) return;
        new AppDialog.Builder(this)
                .sweet(SweetIconView.TYPE_WARNING)
                .title(R.string.confirm_delete_title)
                .message(getString(R.string.confirm_delete_msg,
                        CutoffUtils.prettyDate(editing.workDate)))
                .positive(R.string.action_delete, d -> {
                    db.delete(editing.id);
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .negative(R.string.action_cancel, null)
                .destructive(true)
                .show();
    }
}
