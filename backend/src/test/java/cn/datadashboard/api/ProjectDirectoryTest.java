package cn.datadashboard.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDirectoryTest {
    @Test void categoryComesFromTheSameBindingAndReclassificationPreservesAccount() {
        var active = new AtomicReference<>(List.of(new ProjectDirectory.Binding("one", "Same", "100", true, "SMALL_APPLIANCE")));
        var directory = new ProjectDirectory(active::get);
        assertEquals("SMALL_APPLIANCE", directory.projects().getFirst().get("category"));
        active.set(List.of(new ProjectDirectory.Binding("one", "Same", "100", true, "LARGE_APPLIANCE")));
        assertEquals("LARGE_APPLIANCE", directory.projects().getFirst().get("category"));
        assertEquals("100", directory.account("one"));
        assertEquals("OTHER", ProjectDirectory.single("100").projects().getFirst().get("category"));
    }
    @Test void enabledListAndResolutionFollowSameServerConfigurationAndNeverUseNameAsIdentity() {
        var active = new AtomicReference<>(List.of(new ProjectDirectory.Binding("one", "同名", "100", true),
                new ProjectDirectory.Binding("two", "同名", "200", true)));
        var reads = new AtomicInteger();
        var directory = new ProjectDirectory(() -> { reads.incrementAndGet(); return active.get(); });
        assertEquals("200", directory.account("two"));
        assertEquals(1, reads.get());
        assertEquals(2, directory.projects().size());
        assertFalse(directory.projects().getFirst().containsKey("accountId"));
        active.set(List.of(new ProjectDirectory.Binding("one", "同名", "100", false),
                new ProjectDirectory.Binding("two", "同名", "200", true)));
        assertThrows(ProjectDirectory.ProjectNotEnabled.class, () -> directory.account("one"));
        assertEquals("two", directory.projects().getFirst().get("id"));
    }
}
