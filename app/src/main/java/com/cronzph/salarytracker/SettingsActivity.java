package com.cronzph.salarytracker;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cronzph.salarytracker.data.Prefs;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.data.SalaryEntry;
import com.cronzph.salarytracker.util.Money;
import com.cronzph.salarytracker.util.UpdaterUI;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Pay settings, default entry template, at data management. */
public class SettingsActivity extends AppCompatActivity {

    private SalaryDbHelper db;

    private TextInputLayout tilRate;
    private TextInputLayout tilMult;
    private TextInputLayout tilHours;
    private TextInputLayout tilDefOt;
    private TextInputEditText etRate;
    private TextInputEditText etMult;
    private TextInputEditText etHours;
    private TextInputEditText etDefOt;
    private MaterialButtonToggleGroup toggleDefType;
    private TextView tvHourly;
    private TextView tvOtRate;
    private MaterialSwitch swAutoCheck;
    private TextView tvUpdateVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        db = SalaryDbHelper.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tilRate = findViewById(R.id.tilRate);
        tilMult = findViewById(R.id.tilMult);
        tilHours = findViewById(R.id.tilHours);
        tilDefOt = findViewById(R.id.tilDefOt);
        etRate = findViewById(R.id.etRate);
        etMult = findViewById(R.id.etMult);
        etHours = findViewById(R.id.etHours);
        etDefOt = findViewById(R.id.etDefOt);
        toggleDefType = findViewById(R.id.toggleDefType);
        tvHourly = findViewById(R.id.tvHourly);
        tvOtRate = findViewById(R.id.tvOtRate);
        swAutoCheck = findViewById(R.id.swAutoCheck);
        tvUpdateVersion = findViewById(R.id.tvUpdateVersion);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateDerived();
            }
        };
        etRate.addTextChangedListener(watcher);
        etMult.addTextChangedListener(watcher);
        etHours.addTextChangedListener(watcher);

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.cardClear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.cardReset).setOnClickListener(v -> resetDefaults());

        // Auto-check toggle — i-set muna ang state bago i-attach ang listener
        // para hindi ito mag-trigger sa load.
        swAutoCheck.setChecked(Prefs.isAutoCheck(this));
        swAutoCheck.setOnCheckedChangeListener((b, checked) -> Prefs.setAutoCheck(this, checked));

        findViewById(R.id.cardUpdate).setOnClickListener(v -> UpdaterUI.checkManually(this));

        tvUpdateVersion.setText(getString(R.string.update_current, BuildConfig.VERSION_NAME));
        ((TextView) findViewById(R.id.tvAbout))
                .setText(getString(R.string.settings_about_desc, BuildConfig.VERSION_NAME));

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kung may naka-cache na APK (hal. hindi natuloy ang install), i-offer ulit.
        UpdaterUI.resume(this);
    }

    @Override
    protected void onDestroy() {
        UpdaterUI.detach();
        super.onDestroy();
    }

    private void load() {
        etRate.setText(Money.input(Prefs.getDailyRate(this)));
        etMult.setText(Money.input(Prefs.getOtMultiplier(this)));
        etHours.setText(Money.input(Prefs.getHoursPerDay(this)));
        etDefOt.setText(Money.input(Prefs.getDefaultOtHours(this)));
        toggleDefType.check(typeToButtonId(Prefs.getDefaultDayType(this)));
        updateDerived();
    }

    private void updateDerived() {
        double rate = Money.parse(etRate.getText());
        double mult = Money.parse(etMult.getText());
        double hours = Money.parse(etHours.getText());
        if (hours <= 0) hours = Prefs.DEFAULT_HOURS;

        double hourly = rate / hours;
        tvHourly.setText(Money.peso(hourly));
        tvOtRate.setText(Money.peso(hourly * mult));
    }

    private int typeToButtonId(int dayType) {
        if (dayType == SalaryEntry.TYPE_DAY_OFF) return R.id.btnDefDayOff;
        if (dayType == SalaryEntry.TYPE_ABSENT) return R.id.btnDefAbsent;
        return R.id.btnDefWork;
    }

    private int currentDefaultType() {
        int checked = toggleDefType.getCheckedButtonId();
        if (checked == R.id.btnDefDayOff) return SalaryEntry.TYPE_DAY_OFF;
        if (checked == R.id.btnDefAbsent) return SalaryEntry.TYPE_ABSENT;
        return SalaryEntry.TYPE_WORK;
    }

    private void save() {
        double rate = Money.parse(etRate.getText());
        double mult = Money.parse(etMult.getText());
        double hours = Money.parse(etHours.getText());
        double defOt = Money.parse(etDefOt.getText());

        tilRate.setError(null);
        tilMult.setError(null);
        tilHours.setError(null);
        tilDefOt.setError(null);

        if (rate <= 0) {
            tilRate.setError(getString(R.string.invalid_amount));
            return;
        }
        if (mult <= 0) {
            tilMult.setError(getString(R.string.invalid_amount));
            return;
        }
        if (hours <= 0) {
            tilHours.setError(getString(R.string.invalid_amount));
            return;
        }
        if (defOt < 0) {
            tilDefOt.setError(getString(R.string.invalid_amount));
            return;
        }

        Prefs.setDailyRate(this, rate);
        Prefs.setOtMultiplier(this, mult);
        Prefs.setHoursPerDay(this, hours);
        Prefs.setDefaultOtHours(this, defOt);
        Prefs.setDefaultDayType(this, currentDefaultType());

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void resetDefaults() {
        Prefs.setDailyRate(this, Prefs.DEFAULT_RATE);
        Prefs.setOtMultiplier(this, Prefs.DEFAULT_OT_MULT);
        Prefs.setHoursPerDay(this, Prefs.DEFAULT_HOURS);
        Prefs.setDefaultOtHours(this, Prefs.DEFAULT_OT_HOURS);
        Prefs.setDefaultDayType(this, Prefs.DEFAULT_DAY_TYPE);
        load();
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_clear_title)
                .setMessage(R.string.confirm_clear_msg)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    db.deleteAll();
                    Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
