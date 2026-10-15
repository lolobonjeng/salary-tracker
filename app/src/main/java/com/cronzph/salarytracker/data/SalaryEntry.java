package com.cronzph.salarytracker.data;

/**
 * One row of the salary ledger — isang araw ng trabaho.
 *
 * Katumbas ito ng isang linya sa handwritten record:
 *   DAY | SALARY | OT
 * Halimbawa: 07 | 600 | 94
 */
public class SalaryEntry {

    public static final int TYPE_WORK = 0;
    public static final int TYPE_DAY_OFF = 1;
    public static final int TYPE_ABSENT = 2;

    public long id;
    /** ISO date, yyyy-MM-dd. Unique — isang entry lang kada araw. */
    public String workDate;
    public int dayType = TYPE_WORK;
    /** Daily salary (600 sa record mo). */
    public double basePay;
    /** OT hours para sa araw na ito (e.g. 1). */
    public double otHours;
    /** Multiplier, 1.25 = regular OT (125%). */
    public double otMultiplier = 1.25d;
    /** Kapag hindi null, ito ang gagamitin imbes na auto-computed OT. */
    public Double otOverride;
    public String notes;

    public SalaryEntry() {
    }

    public boolean isWorkDay() {
        return dayType == TYPE_WORK;
    }

    /**
     * Auto OT: (dailyRate / hoursPerDay) * multiplier * otHours
     * Sa 600/day at 8 hrs: (600/8) * 1.25 * 1 = 93.75  ->  yung "94" sa record mo.
     */
    public double autoOtAmount(double hoursPerDay) {
        double hpd = hoursPerDay > 0 ? hoursPerDay : 8d;
        return (basePay / hpd) * otMultiplier * otHours;
    }

    /** Actual OT na binabayaran: override kung meron, kung wala ay auto. */
    public double otAmount(double hoursPerDay) {
        if (!isWorkDay()) return 0d;
        if (otOverride != null) return otOverride;
        return autoOtAmount(hoursPerDay);
    }

    /** Base pay na binibilang — 0 kapag day off o absent. */
    public double countedBase() {
        return isWorkDay() ? basePay : 0d;
    }

    public double total(double hoursPerDay) {
        return countedBase() + otAmount(hoursPerDay);
    }
}
