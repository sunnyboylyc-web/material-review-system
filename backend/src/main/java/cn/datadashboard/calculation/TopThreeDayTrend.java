package cn.datadashboard.calculation;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Pure evaluation of top-three-day-trend-v1 (D-042), for the independent TOP only.
 *
 * <p>The caller supplies eligible spend totals for T, T-1 and T-2 from the same
 * account/material. It owns date selection, row eligibility and evidence coverage.
 * A complete day without an eligible material row is a known zero; a missing day
 * or unknown required field must be passed as {@code null}, never zero.</p>
 */
public final class TopThreeDayTrend {

    private static final BigDecimal CLEAR_INCREASE_THRESHOLD = new BigDecimal("500");
    private static final Set<String> LABELS = Set.of("明显上升", "阶梯下降", "消耗波动", "阶梯上升");

    private TopThreeDayTrend() {
    }

    public enum Status {
        VALID,
        UNKNOWN_INPUT
    }

    /** A valid result has the legacy label and both exact gaps; unknown has none. */
    public record Result(Status status, String label, BigDecimal lastGap, BigDecimal previousGap) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.VALID) {
                if (label == null || !LABELS.contains(label) || lastGap == null || previousGap == null) {
                    throw new IllegalArgumentException("VALID results require a legacy label and both gaps");
                }
            } else if (label != null || lastGap != null || previousGap != null) {
                throw new IllegalArgumentException("UNKNOWN_INPUT results cannot carry a label or gaps");
            }
        }
    }

    /** Evaluate the original ordered IFS without display rounding or a seven-day reinterpretation. */
    public static Result evaluate(BigDecimal selectedDaySpend, BigDecimal previousDaySpend,
                                  BigDecimal twoDaysAgoSpend) {
        if (selectedDaySpend == null || previousDaySpend == null || twoDaysAgoSpend == null) {
            return new Result(Status.UNKNOWN_INPUT, null, null, null);
        }

        BigDecimal lastGap = selectedDaySpend.subtract(previousDaySpend);
        BigDecimal previousGap = previousDaySpend.subtract(twoDaysAgoSpend);
        String label;
        if (lastGap.compareTo(CLEAR_INCREASE_THRESHOLD) >= 0) {
            // The following identical legacy IFS branch is redundant with this first branch.
            label = "明显上升";
        } else if (lastGap.signum() <= 0 && previousGap.signum() <= 0
                && lastGap.compareTo(previousGap) < 0) {
            label = "阶梯下降";
        } else if (lastGap.signum() <= 0 || previousGap.signum() <= 0) {
            label = "消耗波动";
        } else {
            label = "阶梯上升";
        }
        return new Result(Status.VALID, label, lastGap, previousGap);
    }
}
