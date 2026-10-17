package com.cronzph.salarytracker.data;

/**
 * Payout record for one cutoff: when the salary was actually sent and the
 * attached proof image. One row per cutoff.
 */
public class Payout {

    public long id;
    /** Same key used by the cutoff: "yyyy-MM-dd..yyyy-MM-dd". */
    public String periodKey;
    /** ISO date, yyyy-MM-dd. Never in the future. */
    public String paidDate;
    /** Amount actually sent. */
    public double amount;
    /** Absolute path of the copied proof image inside app storage. */
    public String proofPath;

    public Payout() {
    }

    public boolean hasProof() {
        return proofPath != null && !proofPath.isEmpty();
    }
}
