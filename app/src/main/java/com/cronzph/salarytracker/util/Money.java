package com.cronzph.salarytracker.util;

import java.text.DecimalFormat;

/** Peso formatting helpers. */
public final class Money {

    private static final String PESO = "\u20B1"; // ₱
    private static final DecimalFormat PLAIN = new DecimalFormat("#,##0.00");
    private static final DecimalFormat SHORT = new DecimalFormat("#,##0.##");
    /** Walang grouping separator — ligtas i-set sa EditText at i-parse pabalik. */
    private static final DecimalFormat INPUT = new DecimalFormat("#.####");

    private Money() {
    }

    /** 1234.5 -> "₱1,234.50" */
    public static String peso(double v) {
        return PESO + PLAIN.format(v);
    }

    /** 1234.5 -> "1,234.50" */
    public static String plain(double v) {
        return PLAIN.format(v);
    }

    /** 1234.5 -> "1,234.5", 600.0 -> "600". Para sa display. */
    public static String shortPlain(double v) {
        return SHORT.format(v);
    }

    /**
     * 1234.5 -> "1234.5", 600.0 -> "600".
     * Walang comma kaya hindi masisira ng parser kapag nasa EditText.
     */
    public static String input(double v) {
        return INPUT.format(v);
    }

    /**
     * Kabaligtaran ng input(). Tinitiis din ang locale at grouping separator:
     * "1,5" -> 1.5, "1,234.5" -> 1234.5, "" -> 0.
     */
    public static double parse(CharSequence cs) {
        if (cs == null) return 0d;
        String s = cs.toString().trim();
        if (s.isEmpty()) return 0d;
        if (s.indexOf(',') >= 0) {
            s = s.indexOf('.') >= 0 ? s.replace(",", "") : s.replace(',', '.');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
