package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricArithmeticTest {

    private static final List<BiFunction<BigDecimal, BigDecimal, MetricArithmetic.Result>> METRICS =
            List.of(MetricArithmetic::ctr, MetricArithmetic::cvr, MetricArithmetic::roi,
                    MetricArithmetic::cpc, MetricArithmetic::cpm,
                    MetricArithmetic::comparisonIndex, MetricArithmetic::periodChange);

    @Test
    void computesRatesFromPopulationTotalsNotAverageDailyRates() {
        // Synthetic days have unequal denominators: CTRs 10% and 1%.
        BigDecimal clicks = decimal("10").add(decimal("90"));
        BigDecimal impressions = decimal("100").add(decimal("9000"));

        MetricArithmetic.Result result = MetricArithmetic.ctr(clicks, impressions);

        assertValid(result, "0.01098901098901098901098901098901099");
        assertThat(result.value()).isNotEqualByComparingTo("0.055");
        assertValid(MetricArithmetic.cvr(decimal("25"), clicks), "0.25");
        assertValid(MetricArithmetic.roi(decimal("270"), decimal("60")), "4.5");
        assertValid(MetricArithmetic.cpc(decimal("60"), clicks), "0.6");
        assertValid(MetricArithmetic.cpm(decimal("60"), decimal("12000")), "5");
    }

    @Test
    void preservesTrueZeroWithKnownNonzeroDenominator() {
        for (BiFunction<BigDecimal, BigDecimal, MetricArithmetic.Result> metric : METRICS.subList(0, 6)) {
            assertValid(metric.apply(decimal("0.00"), decimal("10")), "0");
        }
        assertValid(MetricArithmetic.periodChange(decimal("10"), decimal("10")), "0");
    }

    @Test
    void distinguishesComparisonIndexFromPeriodChange() {
        assertValid(MetricArithmetic.comparisonIndex(decimal("120"), decimal("100")), "1.2");
        assertValid(MetricArithmetic.periodChange(decimal("120"), decimal("100")), "0.2");
        assertValid(MetricArithmetic.comparisonIndex(decimal("80"), decimal("100")), "0.8");
        assertValid(MetricArithmetic.periodChange(decimal("80"), decimal("100")), "-0.2");
        assertValid(MetricArithmetic.periodChange(BigDecimal.ZERO, decimal("100")), "-1");
    }

    @Test
    void rejectsEveryKnownZeroDenominatorWithoutFabricatingNumericZero() {
        for (BiFunction<BigDecimal, BigDecimal, MetricArithmetic.Result> metric : METRICS) {
            assertUnavailable(metric.apply(decimal("10"), decimal("0.000")),
                    MetricArithmetic.Status.ZERO_DENOMINATOR);
            assertUnavailable(metric.apply(BigDecimal.ZERO, BigDecimal.ZERO),
                    MetricArithmetic.Status.ZERO_DENOMINATOR);
        }
    }

    @Test
    void preservesUnknownOperandsForEveryMetric() {
        for (BiFunction<BigDecimal, BigDecimal, MetricArithmetic.Result> metric : METRICS) {
            assertUnavailable(metric.apply(null, decimal("10")), MetricArithmetic.Status.UNKNOWN_INPUT);
            assertUnavailable(metric.apply(decimal("10"), null), MetricArithmetic.Status.UNKNOWN_INPUT);
            assertUnavailable(metric.apply(null, null), MetricArithmetic.Status.UNKNOWN_INPUT);
        }
    }

    @Test
    void unknownInputTakesPrecedenceOverKnownZeroDenominator() {
        for (BiFunction<BigDecimal, BigDecimal, MetricArithmetic.Result> metric : METRICS) {
            assertUnavailable(metric.apply(null, BigDecimal.ZERO), MetricArithmetic.Status.UNKNOWN_INPUT);
        }
    }

    @Test
    void preservesLargeIntegersAndFractionalMoneyWithoutDoubleConversion() {
        BigDecimal large = decimal("9007199254740993.123456789");

        assertValid(MetricArithmetic.roi(large, BigDecimal.ONE), "9007199254740993.123456789");
        assertValid(MetricArithmetic.cpm(large, decimal("1000")), "9007199254740993.123456789");
    }

    @Test
    void subtractsAndMultipliesExactlyBeforeDecimal128Division() {
        BigDecimal previous = decimal("1000000000000000000000000000000000000");
        BigDecimal current = previous.add(decimal("0.00000000000000000001"));

        assertValid(MetricArithmetic.periodChange(current, previous), "1E-56");

        BigDecimal preciseSpend = decimal("1.23456789012345678901234567890123456789");
        BigDecimal expectedCpm = preciseSpend.multiply(decimal("1000"))
                .divide(decimal("7"), MathContext.DECIMAL128);
        MetricArithmetic.Result cpm = MetricArithmetic.cpm(preciseSpend, decimal("7"));
        assertThat(cpm.status()).isEqualTo(MetricArithmetic.Status.VALID);
        assertThat(cpm.value()).isEqualByComparingTo(expectedCpm);
    }

    @Test
    void repeatingDecimalsUseDecimal128WithoutDisplayRounding() {
        assertValid(MetricArithmetic.cpc(BigDecimal.ONE, decimal("3")),
                "0.3333333333333333333333333333333333");
    }

    @Test
    void resultCannotHideUnavailableStatusBehindNumericZeroOrNullSuccess() {
        assertThatThrownBy(() -> new MetricArithmetic.Result(MetricArithmetic.Status.VALID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricArithmetic.Result(MetricArithmetic.Status.UNKNOWN_INPUT, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricArithmetic.Result(MetricArithmetic.Status.ZERO_DENOMINATOR, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricArithmetic.Result(null, BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void assertValid(MetricArithmetic.Result result, String expected) {
        assertThat(result.status()).isEqualTo(MetricArithmetic.Status.VALID);
        assertThat(result.value()).isEqualByComparingTo(expected);
    }

    private static void assertUnavailable(MetricArithmetic.Result result, MetricArithmetic.Status expected) {
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.value()).isNull();
    }
}
