package cn.datadashboard.security;

import cn.datadashboard.api.*;
import cn.datadashboard.calculation.DashboardModels.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSnapshotControllerTest {
    /** Frozen D187 shape: proves its checksum survives the added nullable response block. */
    record LegacyReview(String schemaVersion,String projectId,String evidenceKind,String requestedAt,
                        int topComparisonDisplayLimit,List<Period> periods,String metricsState,
                        List<Group> comparisons,List<MaterialRow> newMaterials,List<ReviewMaterialRow> topMaterials,
                        String snapshotFingerprint,java.util.Map<String,List<ReviewMaterialRow>> cohortMaterials) { }
    @TempDir Path root;
    final AtomicReference<String> account = new AtomicReference<>("1");
    final ProjectDirectory projects = new ProjectDirectory(() -> List.of(
            new ProjectDirectory.Binding("p1", "one", account.get(), true), new ProjectDirectory.Binding("p2", "two", "2", true)));
    final ReviewSnapshotController.Query query = new ReviewSnapshotController.Query("2026-10-01","2026-10-07","2026-09-01","2026-09-07",20);
    DashboardController dashboard() { return new DashboardController(projects, a -> new Snapshot(a,
            List.of(new DayEvidence(java.time.LocalDate.parse("2026-10-01"),1,"synthetic-hash","MCP_AUTO")),
            List.of(new Fact(java.time.LocalDate.parse("2026-10-01"),"synthetic-1","test",java.time.LocalDate.parse("2026-09-10"),
                    new java.math.BigDecimal("12.34"),new java.math.BigDecimal("24.68"),new java.math.BigDecimal("100"),
                    new java.math.BigDecimal("10"),new java.math.BigDecimal("2"))))); }
    @Test void roundTripRestartIsolationNoFactReadsAndBindingMismatch() {
        var controller = new ReviewSnapshotController(root, projects, dashboard());
        assertEquals(204, controller.get(() -> "alice", "p1").getStatusCode().value());
        var saved = controller.refresh(() -> "alice", "p1",query);
        assertTrue(saved.persisted());
        var restarted = new ReviewSnapshotController(root,projects,new DashboardController(projects,a -> { throw new AssertionError("RESTORE_MUST_NOT_QUERY"); }));
        assertEquals(saved.result(), restarted.get(() -> "alice","p1").getBody().result());
        assertEquals(204,restarted.get(() -> "bob","p1").getStatusCode().value());
        assertEquals(204,restarted.get(() -> "alice","p2").getStatusCode().value());
        account.set("3");
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> restarted.get(() -> "alice","p1")).getStatusCode().value());
    }
    @Test void failedCalculationInvalidQueryAndCorruptSnapshotNeverReplaceOld() throws Exception {
        var good = new ReviewSnapshotController(root,projects,dashboard());
        var old = good.refresh(() -> "alice","p1",query);
        var broken = new ReviewSnapshotController(root,projects,new DashboardController(projects,a -> { throw new IllegalStateException(); }));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->broken.refresh(()->"alice","p1",query)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->good.refresh(()->"alice","p1",new ReviewSnapshotController.Query("bad","bad","bad","bad",20))).getStatusCode().value());
        assertEquals(old.result(),good.get(()->"alice","p1").getBody().result());
        try(var paths=Files.list(root)) { Files.writeString(paths.findFirst().orElseThrow(),"{}"); }
        assertEquals(409,assertThrows(ResponseStatusException.class,()->good.get(()->"alice","p1")).getStatusCode().value());
        assertTrue(good.refresh(()->"alice","p1",query).persisted());
    }
    @Test void saveFailureReturnsComputedResultWithoutReplacingPrevious() throws Exception {
        var controller = new ReviewSnapshotController(root,projects,dashboard());
        var old = controller.refresh(()->"alice","p1",query);
        Path file;
        try(var paths=Files.list(root)) { file=paths.findFirst().orElseThrow(); }
        byte[] prior=Files.readAllBytes(file);
        // Atomic replacement over a nonempty directory must fail on supported hosts.
        Files.delete(file); Files.createDirectory(file); Files.write(file.resolve("prior.json"),prior);
        var failed=controller.refresh(()->"alice","p1",query);
        assertFalse(failed.persisted()); assertNotNull(failed.result());
        assertArrayEquals(prior,Files.readAllBytes(file.resolve("prior.json")));
    }
    @Test void newerRequestWinsEvenWhenOlderFinishesLast() throws Exception {
        var firstStarted=new CountDownLatch(1); var releaseFirst=new CountDownLatch(1); var count=new AtomicInteger();
        var controller=new ReviewSnapshotController(root,projects,new DashboardController(projects,a->{
            if(count.incrementAndGet()==1) { firstStarted.countDown(); try { assertTrue(releaseFirst.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e){ throw new RuntimeException(e); } }
            return new Snapshot(a,List.of(),List.of());
        }));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->controller.refresh(()->"alice","p1",query));
            assertTrue(firstStarted.await(10,TimeUnit.SECONDS));
            var second=controller.refresh(()->"alice","p1",query);
            releaseFirst.countDown();
            assertEquals("REVIEW_SNAPSHOT_SUPERSEDED",first.get(10,TimeUnit.SECONDS).warning());
            assertEquals(second.result(),controller.get(()->"alice","p1").getBody().result());
        } finally { releaseFirst.countDown(); }
    }
    @Test void identitiesProjectsAndWrongAccountAreRejected() {
        var controller=new ReviewSnapshotController(root,projects,dashboard());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.get(null,"p1")).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.get(()->"alice","../bad")).getStatusCode().value());
        var mismatch=new ReviewSnapshotController(root,projects,new DashboardController(projects,a->new Snapshot("wrong",List.of(),List.of())));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->mismatch.refresh(()->"alice","p1",query)).getStatusCode().value());
    }
    @Test void validJsonTamperingIsNotDisplayed() throws Exception {
        var controller=new ReviewSnapshotController(root,projects,dashboard());
        assertTrue(controller.refresh(()->"alice","p1",query).persisted());
        Path file;
        try(var paths=Files.list(root)) { file=paths.findFirst().orElseThrow(); }
        String json=Files.readString(file);
        assertTrue(json.contains("12.34"));
        Files.writeString(file,json.replace("12.34","99.99"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.get(()->"alice","p1")).getStatusCode().value());
    }

    @Test void boundedCapacityHasDistinctWarningAndKeepsPriorSnapshot() throws Exception {
        var original = new ReviewSnapshotController(root, projects, dashboard());
        var prior = original.refresh(() -> "alice", "p1", query);
        assertTrue(prior.persisted());
        Path file;
        try (var paths = Files.list(root)) { file = paths.findFirst().orElseThrow(); }
        byte[] before = Files.readAllBytes(file);
        var limited = new ReviewSnapshotController(root, projects, dashboard(), new ReviewSnapshotStorage(128, 256));
        var failed = limited.refresh(() -> "alice", "p1", query);
        assertFalse(failed.persisted());
        assertNull(failed.savedAt());
        assertNotNull(failed.result());
        assertEquals("REVIEW_SNAPSHOT_TOO_LARGE", failed.warning());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(prior.result(), original.get(() -> "alice", "p1").getBody().result());
        try (var paths = Files.list(root)) { assertEquals(1, paths.count()); }
    }

    @Test void compressedSnapshotStillValidatesChecksumAndProjectBinding() throws Exception {
        var controller = new ReviewSnapshotController(root, projects, dashboard(), new ReviewSnapshotStorage(4096, 128 * 1024));
        var saved = controller.refresh(() -> "alice", "p1", query);
        assertTrue(saved.persisted());
        Path file;
        try (var paths = Files.list(root)) { file = paths.findFirst().orElseThrow(); }
        // Force the valid envelope through the compressed reader, independent of fixture size.
        byte[] plain;
        try (var input = Files.newInputStream(file)) {
            plain = (Files.readAllBytes(file)[0] == 0x1f)
                    ? new java.util.zip.GZIPInputStream(input).readAllBytes() : input.readAllBytes();
        }
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { output.write(plain); }
        assertEquals(saved.result(), controller.get(() -> "alice", "p1").getBody().result());
        String modified = new String(plain, java.nio.charset.StandardCharsets.UTF_8).replace("12.34", "99.99");
        assertNotEquals(new String(plain, java.nio.charset.StandardCharsets.UTF_8), modified);
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            output.write(modified.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> controller.get(() -> "alice", "p1")).getStatusCode().value());
    }

    @Test void d187SnapshotWithoutTypeBlockRetainsItsOriginalChecksum() throws Exception {
        var controller=new ReviewSnapshotController(root,projects,dashboard());
        var refreshed=controller.refresh(()->"alice","p1",query);
        var r=refreshed.result();
        var legacy=new LegacyReview(r.schemaVersion(),r.projectId(),r.evidenceKind(),r.requestedAt(),r.topComparisonDisplayLimit(),r.periods(),r.metricsState(),r.comparisons(),r.newMaterials(),r.topMaterials(),r.snapshotFingerprint(),r.cohortMaterials());
        var json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        byte[] bytes=json.writeValueAsBytes(legacy);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var envelope=java.util.Map.of("version",1,"owner",PrivateUserFiles.id("alice"),"project","p1","account","1","result",legacy,"savedAt",refreshed.savedAt(),"checksum",hash);
        Path file; try(var paths=Files.list(root)) {file=paths.findFirst().orElseThrow();}
        Files.write(file,json.writeValueAsBytes(envelope));
        var restarted=new ReviewSnapshotController(root,projects,new DashboardController(projects,a->{throw new AssertionError("RESTORE_MUST_NOT_QUERY");}));
        var restored=restarted.get(()->"alice","p1").getBody().result();
        assertNull(restored.materialTypes());
        assertArrayEquals(bytes,json.writeValueAsBytes(restored));
    }

    @Test void legacyTypeSnapshotAndEnhancedAiDetailsSurviveRestartWithoutFactReads() throws Exception {
        var date=java.time.LocalDate.parse("2026-10-01");
        var source=new DashboardController(projects,a->new Snapshot(a,List.of(new DayEvidence(date,1,"synthetic","SYNTHETIC")),
                List.of(new Fact(date,"synthetic-ai","原创-ai商单部门",date,new java.math.BigDecimal("100"),
                        new java.math.BigDecimal("300"),new java.math.BigDecimal("10000"),
                        new java.math.BigDecimal("200"),new java.math.BigDecimal("10")))));
        var controller=new ReviewSnapshotController(root,projects,source);
        var saved=controller.refresh(()->"alice","p1",query);
        assertTrue(saved.persisted());
        var r=saved.result();assertEquals(1,r.materialTypes().ai().materials().get("business").size());
        assertEquals("synthetic-ai",r.materialTypes().ai().materials().get("department").getFirst().materialId());
        var restarted=new ReviewSnapshotController(root,projects,new DashboardController(projects,a->{throw new AssertionError("RESTORE_MUST_NOT_QUERY");}));
        assertEquals(r,restarted.get(()->"alice","p1").getBody().result());
        var types=r.materialTypes();
        var oldPeriods=types.periods().stream().map(p->new MaterialTypePeriod(p.key(),p.range(),p.coverage(),p.groups(),p.excludedUndatedMaterials(),p.unresolvedDateMaterials())).toList();
        var oldTypes=new MaterialTypeReview("material-type-review-1.0.0",oldPeriods,types.comparisons(),types.materials());
        var legacy=new Review(r.schemaVersion(),r.projectId(),r.evidenceKind(),r.requestedAt(),r.topComparisonDisplayLimit(),r.periods(),r.metricsState(),r.comparisons(),r.newMaterials(),r.topMaterials(),r.snapshotFingerprint(),r.cohortMaterials(),oldTypes);
        var json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(legacy)));
        var envelope=new ReviewSnapshotController.Envelope(1,PrivateUserFiles.id("alice"),"p1","1",legacy,saved.savedAt(),hash);
        Path file;try(var paths=Files.list(root)){file=paths.findFirst().orElseThrow();}
        new ReviewSnapshotStorage().write(file,envelope);
        var restored=restarted.get(()->"alice","p1").getBody().result();
        assertEquals(legacy,restored);assertNull(restored.materialTypes().ai());
        assertNull(restored.materialTypes().periods().getFirst().testRange());
    }
}
