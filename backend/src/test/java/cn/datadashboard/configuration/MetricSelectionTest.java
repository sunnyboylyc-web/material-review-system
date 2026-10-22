package cn.datadashboard.configuration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MetricSelectionTest {
    @Test
    void boundedSelectionCanonicalizesOrderAndPreservesRequiredGroups() {
        var all = MetricSelection.allMetrics();
        assertThat(all).hasSize(28);
        assertThat(MetricSelection.dimensions()).hasSize(5);
        assertThat(MetricSelection.requiredMetrics()).hasSize(5);
        var reverse = new ArrayList<>(all);
        Collections.reverse(reverse);
        assertThat(MetricSelection.validate(reverse)).isEqualTo(all);
        assertThat(MetricSelection.selectionSha256(reverse)).isEqualTo(MetricSelection.selectionSha256(all));
        assertThat(MetricSelection.catalogSha256()).matches("[0-9a-f]{64}");
        JsonNode profile = MetricSelection.profile(MetricSelection.requiredMetrics());
        assertThat(profile.get("metrics").size()).isEqualTo(5);
        assertThat(profile.get("groups").size()).isEqualTo(5);
        for (JsonNode group : profile.get("groups")) {
            assertThat(group.get("metrics").get(0).stringValue()).isEqualTo("stat_cost_for_roi2");
            assertThat(group.get("dimensions").size()).isEqualTo(group.get("name").stringValue().equals("base") ? 5 : 4);
            assertThat(group.get("pageSize").intValue()).isEqualTo(group.get("name").stringValue().equals("base") ? 100 : 200);
        }
        ((tools.jackson.databind.node.ObjectNode) profile).put("sortField", "changed");
        assertThat(MetricSelection.profile(all).get("sortField").stringValue()).isEqualTo("stat_cost_for_roi2");
    }

    @Test
    void rejectsUnknownDuplicateNullAndRemovingEachDependency() {
        for (String required : MetricSelection.requiredMetrics()) {
            var selection = new ArrayList<>(MetricSelection.allMetrics());
            selection.remove(required);
            assertThatThrownBy(() -> MetricSelection.validate(selection)).hasMessage("METRIC_SELECTION_INVALID");
        }
        for (String invalid : List.of("unknown-field", MetricSelection.allMetrics().getFirst())) {
            var selection = new ArrayList<>(MetricSelection.allMetrics());
            selection.add(invalid);
            assertThatThrownBy(() -> MetricSelection.validate(selection)).hasMessage("METRIC_SELECTION_INVALID");
        }
        assertThatThrownBy(() -> MetricSelection.validate(null)).hasMessage("METRIC_SELECTION_INVALID");
    }
}
