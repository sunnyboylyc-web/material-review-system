package cn.datadashboard.query;

import org.junit.jupiter.api.Test;
import cn.datadashboard.calculation.DashboardModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DouyinCumulativeTest {
    Snapshot snapshot(String account,String date,long version,String spend) {
        var d=LocalDate.parse(date);
        return new Snapshot(account,List.of(new DayEvidence(d,version,"a".repeat(64),"MCP_AUTO")),
                List.of(new Fact(d,"77","原创",d,new BigDecimal(spend),BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE)));
    }
    @Test void consecutiveAdvertisersAccumulateWithoutRewritingSourceSnapshots() {
        var a=snapshot("1001","2026-01-01",1,"100");var b=snapshot("1002","2026-01-02",2,"200");
        var merged=JdbcDashboardSnapshotReader.mergeAccounts("1001",List.of(a,b));
        assertThat(merged.days()).hasSize(2);assertThat(merged.facts().stream().map(Fact::spend).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("300");
        assertThat(a.accountId()).isEqualTo("1001");assertThat(b.accountId()).isEqualTo("1002");
        assertThat(merged.accountId()).isEqualTo("1001");
    }
    @Test void overlapEvenWithEqualHashesFailsInsteadOfCountingTwice() {
        var a=snapshot("1001","2026-01-01",1,"100");var b=snapshot("1002","2026-01-01",2,"100");
        assertThatThrownBy(()->JdbcDashboardSnapshotReader.mergeAccounts("1001",List.of(a,b))).hasMessage("DOUYIN_DUPLICATE_DAY");
        assertThatThrownBy(()->JdbcDashboardSnapshotReader.mergeAccounts("1001",List.of(a,a))).hasMessage("DUPLICATE_SOURCE_ACCOUNT");
    }
}
