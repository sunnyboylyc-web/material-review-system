package cn.datadashboard.ingest.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionPlacementPolicyTest {

    private final VersionPlacementPolicy policy = new VersionPlacementPolicy();

    @Test
    void firstVersionBecomesCurrent() {
        assertThat(policy.decide(new DayVersionPointers(null, null)))
                .isEqualTo(VersionPlacement.FIRST_CURRENT);
    }

    @Test
    void secondDifferentVersionReplacesCurrentAndKeepsPrevious() {
        assertThat(policy.decide(new DayVersionPointers(10L, null)))
                .isEqualTo(VersionPlacement.REPLACE_CURRENT);
    }

    @Test
    void thirdDifferentVersionIsIsolated() {
        assertThat(policy.decide(new DayVersionPointers(11L, 10L)))
                .isEqualTo(VersionPlacement.ISOLATED);
    }

    @Test
    void rejectsCorruptPointers() {
        assertThatThrownBy(() -> policy.decide(new DayVersionPointers(null, 10L)))
                .isInstanceOf(IllegalStateException.class);
    }
}
