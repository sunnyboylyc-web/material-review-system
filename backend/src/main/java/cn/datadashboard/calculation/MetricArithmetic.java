package cn.datadashboard.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import java.util.List;

/**
 * Pure arithmetic for confirmed-metric-arithmetic-v1.
 *
 * <p>Callers must supply totals from the same account, date range, evidence
 * versions and eligible rows. This class neither selects those rows nor
 * determines coverage. A {@link Status#VALID} result may still have partial
 * coverage; query integration must carry that metadata separately.</p>
 *
 * <p>Ratios are returned as ratios, not display percentages. No display
 * rounding is performed. Unknown operands must be passed as {@code null},
 * never replaced with zero.</p>
 */
public final class MetricArithmetic {

    private static final BigDecimal ONE_THOUSAND = new BigDecimal("1000");

    private MetricArithmetic() {
    }

    public enum Status {
        VALID,
        UNKNOWN_INPUT,
        ZERO_DENOMINATOR
    }

    /** A numeric value exists if and only if status is VALID. */
    public record Result(Status status, BigDecimal value, boolean partial, List<String> partialReasons) {
        public Result(Status status, BigDecimal value) { this(status, value, false, List.of()); }
        public Result {
            Objects.requireNonNull(status, "status");
            partialReasons = List.copyOf(partialReasons);
            if ((status == Status.VALID) != (value != null)) {
                throw new IllegalArgumentException("Only VALID results have a numeric value");
            }
        }
    }

    /** Total clicks divided by total impressions; not an average of daily CTRs. */
    public static Result ctr(BigDecimal clicks, BigDecimal impressions) {
        return divide(clicks, impressions);
    }

    /** Total orders divided by total clicks. */
    public static Result cvr(BigDecimal orders, BigDecimal clicks) {
        return divide(orders, clicks);
    }

    /** Total payment GMV divided by total spend. */
    public static Result roi(BigDecimal payGmv, BigDecimal spend) {
        return divide(payGmv, spend);
    }

    /** Total spend divided by total clicks. */
    public static Result cpc(BigDecimal spend, BigDecimal clicks) {
        return divide(spend, clicks);
    }

    /** One thousand times total spend divided by total impressions. */
    public static Result cpm(BigDecimal spend, BigDecimal impressions) {
        if (spend == null || impressions == null) {
            return new Result(Status.UNKNOWN_INPUT, null);
        }
        return divide(spend.multiply(ONE_THOUSAND), impressions);
    }

    /** Current / previous; 100 to 120 returns 1.2, to be displayed as 120%. */
    public static Result comparisonIndex(BigDecimal currentValue, BigDecimal previousValue) {
        return divide(currentValue, previousValue);
    }

    /** (Current - previous) / previous; 100 to 120 returns 0.2, not 1.2. */
    public static Result periodChange(BigDecimal currentSpend, BigDecimal previousSpend) {
        if (currentSpend == null || previousSpend == null) {
            return new Result(Status.UNKNOWN_INPUT, null);
        }
        return divide(currentSpend.subtract(previousSpend), previousSpend);
    }

    private static Result divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) {
            return new Result(Status.UNKNOWN_INPUT, null);
        }
        if (denominator.signum() == 0) {
            return new Result(Status.ZERO_DENOMINATOR, null);
        }
        return new Result(Status.VALID, numerator.divide(denominator, MathContext.DECIMAL128));
    }
}
