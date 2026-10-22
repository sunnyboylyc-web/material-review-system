package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopThreeDayTrendTest {

    @Test
    void inputsAreSelectedDayThenPreviousDayThenTwoDaysAgo() {
        TopThreeDayTrend.Result result = TopThreeDayTrend.evaluate(
                decimal("800"), decimal("200"), decimal("100"));

        assertValid(result, "明显上升", "600", "100");
    }

    @ParameterizedTest
    @CsvSource({
            "500, -700, 明显上升",
            "500, 0, 明显上升",
            "500, 100, 明显上升",
            "500.00000000000000000001, -700, 明显上升",
            "499.99999999999999999999, -700, 消耗波动",
            "499.99999999999999999999, 100, 阶梯上升",
            "-200, -100, 阶梯下降",
            "-100, -200, 消耗波动",
            "-100, -100.000, 消耗波动",
            "-100, 0, 阶梯下降",
            "-100, 100, 消耗波动",
            "0, -100, 消耗波动",
            "0, 0, 消耗波动",
            "0.000, 100, 消耗波动",
            "100, -100, 消耗波动",
            "100, 0.000, 消耗波动",
            "100, 100, 阶梯上升",
            "1E-20, 1E-20, 阶梯上升",
            "-1E-20, 0, 阶梯下降"
    })
    void followsLegacyOrderedBranchesAtThresholdAndEveryGapSign(
            String lastGap, String previousGap, String expectedLabel) {
        BigDecimal oldestSpend = decimal("10000");
        BigDecimal priorSpend = oldestSpend.add(decimal(previousGap));
        BigDecimal selectedSpend = priorSpend.add(decimal(lastGap));

        assertValid(TopThreeDayTrend.evaluate(selectedSpend, priorSpend, oldestSpend),
                expectedLabel, lastGap, previousGap);
    }

    @Test
    void exactSubtractionPreservesHugeTotalsAndSubCentDifferences() {
        BigDecimal oldestSpend = decimal("100000000000000000000000000000000000000000000000000.123456789");
        BigDecimal priorSpend = oldestSpend.add(decimal("0.0000000000000000000000000000000000001"));
        BigDecimal selectedSpend = priorSpend.add(decimal("499.9999999999999999999999999999999999999"));

        assertValid(TopThreeDayTrend.evaluate(selectedSpend, priorSpend, oldestSpend),
                "阶梯上升", "499.9999999999999999999999999999999999999", "1E-37");

        selectedSpend = priorSpend.add(decimal("500.0000000000000000000000000000000000001"));
        assertValid(TopThreeDayTrend.evaluate(selectedSpend, priorSpend, oldestSpend),
                "明显上升", "500.0000000000000000000000000000000000001", "1E-37");
    }

    @Test
    void completeKnownZeroDaysAreNotUnknownAndKeepOriginalFluctuationLabel() {
        assertValid(TopThreeDayTrend.evaluate(BigDecimal.ZERO, decimal("0.000"), BigDecimal.ZERO),
                "消耗波动", "0", "0");
        assertValid(TopThreeDayTrend.evaluate(decimal("100"), BigDecimal.ZERO, BigDecimal.ZERO),
                "消耗波动", "100", "0");
    }

    @Test
    void everyUnknownCombinationSuppressesLabelAndBothGaps() {
        // Even an otherwise decisive latest rise cannot ignore an unknown oldest day.
        BigDecimal[] known = {decimal("800"), decimal("200"), decimal("100")};
        for (int mask = 1; mask < 8; mask++) {
            TopThreeDayTrend.Result result = TopThreeDayTrend.evaluate(
                    (mask & 1) == 0 ? known[0] : null,
                    (mask & 2) == 0 ? known[1] : null,
                    (mask & 4) == 0 ? known[2] : null);

            assertThat(result.status()).as("unknown input mask %s", mask)
                    .isEqualTo(TopThreeDayTrend.Status.UNKNOWN_INPUT);
            assertThat(result.label()).isNull();
            assertThat(result.lastGap()).isNull();
            assertThat(result.previousGap()).isNull();
        }
    }

    @Test
    void resultRequiresKnownLegacyLabelAndBothGapsForValidState() {
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.VALID, null, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.VALID, "平稳", BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.VALID, "消耗波动", null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.VALID, "消耗波动", BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultCannotCarryPartialNumbersOrLabelInUnknownStateOrHaveNullStatus() {
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.UNKNOWN_INPUT, "消耗波动", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.UNKNOWN_INPUT, null, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(
                TopThreeDayTrend.Status.UNKNOWN_INPUT, null, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopThreeDayTrend.Result(null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void assertValid(TopThreeDayTrend.Result result, String expectedLabel,
                                    String expectedLastGap, String expectedPreviousGap) {
        assertThat(result.status()).isEqualTo(TopThreeDayTrend.Status.VALID);
        assertThat(result.label()).isEqualTo(expectedLabel);
        assertThat(result.lastGap()).isEqualByComparingTo(expectedLastGap);
        assertThat(result.previousGap()).isEqualByComparingTo(expectedPreviousGap);
    }
}
