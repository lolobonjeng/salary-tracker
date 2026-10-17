package com.cronzph.salarytracker.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.cronzph.salarytracker.R;
import com.google.android.material.button.MaterialButton;

/**
 * Custom na dialog — kapalit ng {@code MaterialAlertDialogBuilder}.
 *
 * <p>Bakit sariling dialog: ang stock na Material alert ay naglalagay ng
 * text buttons sa isang hilera, kaya kapag tatlo ang action (Download /
 * Mamaya / Huwag na) ay nagiging magkakapatong at sira ang alignment sa
 * maliit na screen. Dito: isang filled na primary button sa ibaba, at ang
 * dalawang pangalawa ay text buttons sa ilalim nito.
 */
public final class AppDialog extends Dialog {

    public interface Listener {
        void onClick(AppDialog dialog);
    }

    public interface ItemListener {
        void onItemClick(AppDialog dialog, int index);
    }

    private AppDialog(Context context) {
        super(context, R.style.Theme_SalaryTracker_Dialog);
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {

        private final Context ctx;

        private CharSequence title;
        private CharSequence message;
        private int iconRes;
        private View content;
        private CharSequence[] items;
        private ItemListener itemListener;

        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private Listener positiveListener;
        private Listener negativeListener;
        private Listener neutralListener;

        private boolean destructive;
        private boolean cancelable = true;
        private DialogInterface.OnCancelListener onCancel;

        public Builder(Context context) {
            this.ctx = context;
        }

        public Builder title(@StringRes int res) {
            return title(ctx.getString(res));
        }

        public Builder title(CharSequence text) {
            this.title = text;
            return this;
        }

        public Builder message(@StringRes int res) {
            return message(ctx.getString(res));
        }

        public Builder message(CharSequence text) {
            this.message = text;
            return this;
        }

        public Builder icon(@DrawableRes int res) {
            this.iconRes = res;
            return this;
        }

        /** Custom na nilalaman — nasa ilalim ng message, sa itaas ng buttons. */
        public Builder content(View view) {
            this.content = view;
            return this;
        }

        /** Listahan ng pagpipilian (hal. export options) sa halip na message. */
        public Builder items(CharSequence[] options, ItemListener listener) {
            this.items = options;
            this.itemListener = listener;
            return this;
        }

        public Builder positive(@StringRes int res, Listener listener) {
            return positive(ctx.getString(res), listener);
        }

        public Builder positive(CharSequence text, Listener listener) {
            this.positiveText = text;
            this.positiveListener = listener;
            return this;
        }

        public Builder negative(@StringRes int res, Listener listener) {
            return negative(ctx.getString(res), listener);
        }

        public Builder negative(CharSequence text, Listener listener) {
            this.negativeText = text;
            this.negativeListener = listener;
            return this;
        }

        public Builder neutral(@StringRes int res, Listener listener) {
            return neutral(ctx.getString(res), listener);
        }

        public Builder neutral(CharSequence text, Listener listener) {
            this.neutralText = text;
            this.neutralListener = listener;
            return this;
        }

        /** Pula ang primary button — para sa delete/clear. */
        public Builder destructive(boolean value) {
            this.destructive = value;
            return this;
        }

        public Builder cancelable(boolean value) {
            this.cancelable = value;
            return this;
        }

        public Builder onCancel(DialogInterface.OnCancelListener listener) {
            this.onCancel = listener;
            return this;
        }

        public AppDialog show() {
            final AppDialog dialog = new AppDialog(ctx);
            View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_app, null, false);

            FrameLayout iconWrap = root.findViewById(R.id.iconWrap);
            ImageView imgIcon = root.findViewById(R.id.imgIcon);
            TextView txtTitle = root.findViewById(R.id.txtTitle);
            TextView txtMessage = root.findViewById(R.id.txtMessage);
            FrameLayout contentHost = root.findViewById(R.id.contentHost);
            LinearLayout optionsBox = root.findViewById(R.id.optionsBox);
            LinearLayout actionsBox = root.findViewById(R.id.actionsBox);
            View rowSecondary = root.findViewById(R.id.rowSecondary);

            MaterialButton btnPositive = root.findViewById(R.id.btnPositive);
            MaterialButton btnNeutral = root.findViewById(R.id.btnNeutral);
            MaterialButton btnNegative = root.findViewById(R.id.btnNegative);

            // --- icon
            if (iconRes != 0) {
                iconWrap.setVisibility(View.VISIBLE);
                imgIcon.setImageResource(iconRes);
            } else {
                iconWrap.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) txtTitle.getLayoutParams();
                lp.topMargin = 0;
                txtTitle.setLayoutParams(lp);
            }

            // --- title
            if (isBlank(title)) {
                txtTitle.setVisibility(View.GONE);
            } else {
                txtTitle.setText(title);
            }

            // --- message
            if (isBlank(message)) {
                txtMessage.setVisibility(View.GONE);
            } else {
                txtMessage.setText(message);
            }

            // --- custom content
            if (content != null) {
                contentHost.addView(content);
            } else {
                contentHost.setVisibility(View.GONE);
            }

            // --- option list
            if (items != null && items.length > 0) {
                LayoutInflater inf = LayoutInflater.from(ctx);
                for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    TextView row = (TextView) inf.inflate(
                            R.layout.item_dialog_option, optionsBox, false);
                    row.setText(items[i]);
                    row.setOnClickListener(v -> {
                        dialog.dismiss();
                        if (itemListener != null) itemListener.onItemClick(dialog, index);
                    });
                    optionsBox.addView(row);
                }
            } else {
                optionsBox.setVisibility(View.GONE);
            }

            // --- buttons
            bindButton(btnPositive, positiveText, positiveListener, dialog);
            if (destructive) {
                btnPositive.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.error)));
                btnPositive.setTextColor(ContextCompat.getColor(ctx, R.color.onError));
            }
            bindButton(btnNeutral, neutralText, neutralListener, dialog);
            bindButton(btnNegative, negativeText, negativeListener, dialog);

            boolean hasNeutral = !isBlank(neutralText);
            boolean hasNegative = !isBlank(negativeText);
            if (!hasNeutral && !hasNegative) {
                rowSecondary.setVisibility(View.GONE);
            } else if (!hasNeutral || !hasNegative) {
                // Isa lang ang natira — bigyan ng buong lapad.
                View survivor = hasNeutral ? btnNeutral : btnNegative;
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) survivor.getLayoutParams();
                lp.weight = 2f;
                survivor.setLayoutParams(lp);
            }

            if (isBlank(positiveText) && !hasNeutral && !hasNegative) {
                actionsBox.setVisibility(View.GONE);
            }

            dialog.setContentView(root);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            if (onCancel != null) {
                dialog.setOnCancelListener(onCancel);
            }
            dialog.show();

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.62f;
                window.setAttributes(attrs);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            return dialog;
        }

        private static void bindButton(final MaterialButton button, CharSequence text,
                                       final Listener listener, final AppDialog dialog) {
            if (isBlank(text)) {
                button.setVisibility(View.GONE);
                return;
            }
            button.setText(text);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (listener != null) listener.onClick(dialog);
            });
        }

        private static boolean isBlank(CharSequence s) {
            return s == null || s.toString().trim().isEmpty();
        }
    }
}
