package cn.datadashboard.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static cn.datadashboard.configuration.ProjectConfigurationStore.*;

class CollectionThemeAndLineageTest {
    @TempDir Path root;
    ProjectConfigurationStore store(int hour) throws Exception {
        Files.createDirectories(root.resolve(".local"));
        return new ProjectConfigurationStore(root, root.resolve(".local/config"),
                Clock.fixed(Instant.parse("2026-10-22T" + String.format("%02d",hour-8) + ":00:00Z"), ZoneOffset.UTC));
    }
    Project p(String account,String anchor,String theme) {
        return new Project("alpha","Synthetic",account,List.of(anchor),true,"test.show","Synthetic",false,"OTHER",theme,"1001");
    }
    @Test void changeThemeAndAdvertiserRetainsAllImmutableHistoryAndStableReviewKey() throws Exception {
        var store=store(13);var a=p("1001","2001","QUANYU");var first=store.initialize(List.of(a));
        byte[] original=Files.readAllBytes(root.resolve(".local/config/revisions/"+first.revision()+".json"));
        var b=p("1001","2001","CHENGFANG");var check=store.preflight(first.revision(),List.of(b));
        var second=store.apply(first.revision(),List.of(b),check.candidateHash());
        var c=p("1002","2001","CHENGFANG");check=store.preflight(second.revision(),List.of(c));
        store.apply(second.revision(),List.of(c),check.candidateHash());
        assertThat(store.lineage("alpha").logicalAccount()).isEqualTo("1001");
        assertThat(store.lineage("alpha").accounts()).containsExactly("1001");
        assertThat(Files.readAllBytes(root.resolve(".local/config/revisions/"+first.revision()+".json"))).isEqualTo(original);
        var now=store.snapshot();
        assertThatThrownBy(()->store.preflight(now.revision(),List.of(p("1003","2002","QUANYU"))))
                .hasMessage("CONFIG_IDENTITY_MISMATCH");
    }
    @Test void serverBlocksMorningAndActiveCollectorEvenAfterNoon() throws Exception {
        var store=store(13);var initial=store.initialize(List.of(p("1001","2001","QUANYU")));
        var changed=List.of(p("1001","2001","CHENGFANG"));var check=store.preflight(initial.revision(),changed);
        var morning=store(8);
        assertThatThrownBy(()->morning.preflight(initial.revision(),changed)).hasMessage("CONFIG_COLLECTION_WINDOW");
        assertThatThrownBy(()->morning.apply(initial.revision(),changed,check.candidateHash())).hasMessage("CONFIG_COLLECTION_WINDOW");
        try(var channel=FileChannel.open(root.resolve(".local/config/configuration.lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {
            assertThat(store.collectionBusy()).isTrue();
            assertThatThrownBy(()->store.apply(initial.revision(),changed,check.candidateHash())).hasMessage("CONFIG_STALE");
        }
        assertThat(store.collectionBusy()).isFalse();
        assertThat(store.snapshot().revision()).isEqualTo(initial.revision());
    }
    @Test void sameAdvertiserCanServeAnotherDouyinWithSeparateStorage() throws Exception {
        var store=store(13);var first=store.initialize(List.of(p("1001","2001","QUANYU")));
        var changed=List.of(p("1002","2001","QUANYU"));var check=store.preflight(first.revision(),changed);
        var next=store.apply(first.revision(),changed,check.candidateHash());
        var another=new Project("beta","Other","1002",List.of("2009"),true,"other.show","Other",false,"OTHER","CHENGFANG","2009");
        var checked=store.preflight(next.revision(),List.of(changed.getFirst(),another));
        store.apply(next.revision(),List.of(changed.getFirst(),another),checked.candidateHash());
        assertThat(store.lineage("alpha").accounts()).containsExactly("1001");
        assertThat(store.lineage("beta").accounts()).containsExactly("2009");
        var stolen=new Project("beta","Other","1002",List.of("2009"),true,"other.show","Other",false,"OTHER","CHENGFANG","1001");
        assertThatThrownBy(()->store.preflight(store.snapshot().revision(),List.of(changed.getFirst(),stolen))).isInstanceOf(ConfigurationException.class);
    }
}
