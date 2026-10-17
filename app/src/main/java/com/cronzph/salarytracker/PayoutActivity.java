package com.cronzph.salarytracker;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.cronzph.salarytracker.data.Payout;
import com.cronzph.salarytracker.data.SalaryDbHelper;
import com.cronzph.salarytracker.ui.AppDialog;
import com.cronzph.salarytracker.util.CutoffUtils;
import com.cronzph.salarytracker.util.Money;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Records when a cutoff was actually paid and requires an image as proof.
 * The chosen image is copied into app storage so it survives the picker grant.
 */
public class PayoutActivity extends AppCompatActivity {

    public static final String EXTRA_KEY = "payout_key";
    public static final String EXTRA_LABEL = "payout_label";
    public static final String EXTRA_AMOUNT = "payout_amount";

    private static final String STATE_DATE = "state_date";
    private static final String STATE_PENDING = "state_pending";
    private static final String STATE_SAVED = "state_saved";

    private SalaryDbHelper db;
    private String periodKey;

    private Payout existing;
    private String selectedDate;
    /** Proof already stored for this cutoff. */
    private String proofPath;
    /** Newly copied image that is not saved yet. */
    private String pendingPath;
    private boolean saved;

    private TextView txtPeriod;
    private TextView txtPaidDate;
    private TextInputLayout tilAmount;
    private TextInputEditText etAmount;
    private ImageView imgProof;
    private TextView txtNoProof;
    private TextView txtProofError;
    private MaterialButton btnChooseImage;
    private MaterialButton btnViewProof;
    private MaterialButton btnDelete;

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                String copied = copyToStorage(uri);
                if (copied == null) {
                    Toast.makeText(this, R.string.payout_image_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pendingPath != null && !pendingPath.equals(copied)) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(pendingPath).delete();
                }
                pendingPath = copied;
                txtProofError.setVisibility(View.GONE);
                showProof(copied);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payout);

        db = SalaryDbHelper.get(this);

        periodKey = getIntent().getStringExtra(EXTRA_KEY);
        if (periodKey == null) {
            finish();
            return;
        }
        String periodLabel = getIntent().getStringExtra(EXTRA_LABEL);
        double defaultAmount = getIntent().getDoubleExtra(EXTRA_AMOUNT, 0d);

        txtPeriod = findViewById(R.id.txtPeriod);
        txtPaidDate = findViewById(R.id.txtPaidDate);
        tilAmount = findViewById(R.id.tilAmount);
        etAmount = findViewById(R.id.etAmount);
        imgProof = findViewById(R.id.imgProof);
        txtNoProof = findViewById(R.id.txtNoProof);
        txtProofError = findViewById(R.id.txtProofError);
        btnChooseImage = findViewById(R.id.btnChooseImage);
        btnViewProof = findViewById(R.id.btnViewProof);
        btnDelete = findViewById(R.id.btnDelete);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        txtPeriod.setText(periodLabel == null
                ? getString(R.string.payout_period, periodKey)
                : periodLabel);

        existing = db.getPayout(periodKey);
        if (existing != null) {
            selectedDate = existing.paidDate;
            proofPath = existing.proofPath;
            etAmount.setText(Money.input(existing.amount));
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            selectedDate = CutoffUtils.todayIso();
            if (defaultAmount > 0d) {
                etAmount.setText(Money.input(defaultAmount));
            }
        }

        if (savedInstanceState != null) {
            String restoredDate = savedInstanceState.getString(STATE_DATE);
            if (restoredDate != null) selectedDate = restoredDate;
            pendingPath = savedInstanceState.getString(STATE_PENDING);
            saved = savedInstanceState.getBoolean(STATE_SAVED, false);
        }

        updateDateLabel();
        showProof(pendingPath != null ? pendingPath : proofPath);

        findViewById(R.id.cardPaidDate).setOnClickListener(v -> pickDate());
        findViewById(R.id.frameProof).setOnClickListener(v -> {
            String current = pendingPath != null ? pendingPath : proofPath;
            if (current != null) viewProof(current);
        });
        btnChooseImage.setOnClickListener(v -> pickImage.launch("image/*"));
        btnViewProof.setOnClickListener(v -> {
            String current = pendingPath != null ? pendingPath : proofPath;
            if (current != null) viewProof(current);
        });
        btnDelete.setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DATE, selectedDate);
        outState.putString(STATE_PENDING, pendingPath);
        outState.putBoolean(STATE_SAVED, saved);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A picked image that was never saved should not pile up in storage.
        // Keep it across a configuration change, since the path is restored.
        if (!saved && !isChangingConfigurations() && pendingPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(pendingPath).delete();
        }
    }

    // ------------------------------------------------------------------ date

    private void updateDateLabel() {
        String year = selectedDate != null && selectedDate.length() >= 4
                ? selectedDate.substring(0, 4) : "";
        txtPaidDate.setText(CutoffUtils.prettyDate(selectedDate) + ", " + year);
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate);
            if (d != null) c.setTime(d);
        } catch (Exception ignored) {
            // fall back to today
        }

        DatePickerDialog dlg = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            updateDateLabel();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));

        // Salary cannot be sent in the future.
        dlg.getDatePicker().setMaxDate(System.currentTimeMillis());
        dlg.show();
    }

    // ----------------------------------------------------------------- proof

    private void showProof(String path) {
        if (path == null) {
            imgProof.setImageDrawable(null);
            imgProof.setVisibility(View.GONE);
            txtNoProof.setVisibility(View.VISIBLE);
            btnViewProof.setVisibility(View.GONE);
            btnChooseImage.setText(R.string.payout_choose_image);
            return;
        }

        Bitmap bmp = decode(path);
        if (bmp == null) {
            imgProof.setVisibility(View.GONE);
            txtNoProof.setVisibility(View.VISIBLE);
            btnViewProof.setVisibility(View.GONE);
            return;
        }

        imgProof.setImageBitmap(bmp);
        imgProof.setVisibility(View.VISIBLE);
        txtNoProof.setVisibility(View.GONE);
        btnViewProof.setVisibility(View.VISIBLE);
        btnChooseImage.setText(R.string.payout_change_image);
    }

    private Bitmap decode(String path) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);

        int sample = 1;
        while (probe.outWidth / (sample * 2) >= 900) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    private String copyToStorage(Uri uri) {
        File dir = new File(getFilesDir(), "proofs");
        if (!dir.exists() && !dir.mkdirs()) return null;

        File out = new File(dir, "proof_" + System.currentTimeMillis() + ".jpg");
        InputStream in = null;
        OutputStream os = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            os = new FileOutputStream(out);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
            }
            os.flush();
            return out.getAbsolutePath();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(os);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    private void viewProof(String path) {
        Bitmap bmp = decode(path);
        if (bmp == null) {
            Toast.makeText(this, R.string.payout_image_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageBitmap(bmp);
        view.setContentDescription(getString(R.string.payout_proof_view_title));
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(root);
        dialog.show();
    }

    // ------------------------------------------------------------------ save

    private void save() {
        tilAmount.setError(null);
        txtProofError.setVisibility(View.GONE);

        String today = CutoffUtils.todayIso();
        if (selectedDate == null || selectedDate.compareTo(today) > 0) {
            Toast.makeText(this, R.string.date_future, Toast.LENGTH_SHORT).show();
            return;
        }

        double amount = Money.parse(etAmount.getText());
        if (amount <= 0d) {
            tilAmount.setError(getString(R.string.invalid_amount));
            return;
        }

        String proof = pendingPath != null ? pendingPath : proofPath;
        if (proof == null || !new File(proof).exists()) {
            txtProofError.setVisibility(View.VISIBLE);
            return;
        }

        Payout p = existing != null ? existing : new Payout();
        p.periodKey = periodKey;
        p.paidDate = selectedDate;
        p.amount = amount;
        p.proofPath = proof;
        db.savePayout(p);

        if (pendingPath != null && proofPath != null && !proofPath.equals(pendingPath)) {
            //noinspection ResultOfMethodCallIgnored
            new File(proofPath).delete();
        }
        proofPath = proof;
        pendingPath = null;
        saved = true;

        Toast.makeText(this, R.string.payout_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        if (existing == null) return;
        new AppDialog.Builder(this)
                .icon(R.drawable.ic_delete)
                .title(R.string.payout_delete_title)
                .message(R.string.payout_delete_msg)
                .positive(R.string.action_delete, d -> {
                    db.deletePayout(periodKey);
                    if (proofPath != null) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(proofPath).delete();
                    }
                    if (pendingPath != null) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(pendingPath).delete();
                        pendingPath = null;
                    }
                    saved = true;
                    Toast.makeText(this, R.string.payout_deleted, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .negative(R.string.action_cancel, null)
                .destructive(true)
                .show();
    }
}
