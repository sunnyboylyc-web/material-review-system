package cn.datadashboard.history;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.ingest.persistence.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HistoryUploadServiceTest {
    Path repo, root, python; ProjectConfigurationStore store; FakeGateway gateway; HistoryUploadService service;
    @BeforeEach void setup() throws Exception {
        repo=Path.of("").toAbsolutePath(); if(repo.getFileName().toString().equals("backend"))repo=repo.getParent();
        root=repo.resolve("qa/runs/history-service/"+UUID.randomUUID()); Files.createDirectories(root.resolve(".local"));
        Files.createDirectories(root.resolve("tools/manual_history")); Files.createDirectories(root.resolve("contracts"));
        for(String name:List.of("convert_upload_bridge.py","convert_upload_template.py"))Files.copy(repo.resolve("tools/manual_history/"+name),root.resolve("tools/manual_history/"+name));
        for(String name:List.of("manual-history-upload-v1.json","manual-history-workbook-v1.json"))Files.copy(repo.resolve("contracts/"+name),root.resolve("contracts/"+name));
        python=Path.of(System.getProperty("history.test.python","C:/Users/jt/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"));
        Assumptions.assumeTrue(Files.isRegularFile(python));
        store=new ProjectConfigurationStore(root,root.resolve(".local/configuration"));
        store.initialize(List.of(new ProjectConfigurationStore.Project("synthetic","Synthetic","12345678",List.of("87654321"),true)));
        gateway=new FakeGateway(); service=new HistoryUploadService(root,store,gateway,python,true);
    }
    ObjectNode preview()throws Exception{try(var input=Files.newInputStream(repo.resolve("qa/runs/d085-template/synthetic-filled.xlsx"))){return service.preview("synthetic",input);}}
    @Test void gapFillCanImportAfterFirstMcpAndStillRejectsConcurrentOccupiedDay() throws Exception {
        gateway.cutoff=LocalDate.of(1900,1,1);
        ObjectNode view;
        try(var input=Files.newInputStream(repo.resolve("qa/runs/d085-template/synthetic-filled.xlsx"))) {
            view=service.preview("synthetic",input,"GAP_FILL");
        }
        assertEquals("PREVIEW_READY",view.path("status").stringValue());
        assertEquals("GAP_FILL",view.path("uploadMode").stringValue());
        gateway.existing.add(LocalDate.parse(view.path("start").stringValue()));
        assertNotEquals("COMPLETED",apply(view).path("status").stringValue());
        assertEquals(0,gateway.imports);
    }
    @Test void gapFillSuccessfulImportIsVisibleToDatabaseCoverage() throws Exception {
        gateway.cutoff=LocalDate.of(1900,1,1);
        ObjectNode view;
        try(var input=Files.newInputStream(repo.resolve("qa/runs/d085-template/synthetic-filled.xlsx"))) {
            view=service.preview("synthetic",input,"GAP_FILL");
        }
        assertEquals("COMPLETED",apply(view).path("status").stringValue());
        assertTrue(gateway.existing.contains(LocalDate.parse(view.path("start").stringValue())));
    }
    ObjectNode apply(ObjectNode view){return service.apply("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue(),true,true);}
    Path directory(ObjectNode view){return root.resolve(".local/history-uploads/"+view.path("uploadId").stringValue());}
    // Simulate records created by the previous version, which allowed multiple unresolved files.
    ObjectNode legacyClone(ObjectNode original) throws Exception {
        var mapper=new JsonMapper();String id=UUID.randomUUID().toString().replace("-", "");
        Path from=directory(original),to=from.getParent().resolve(id);Files.createDirectory(to);
        try(var paths=Files.walk(from)){for(Path path:paths.filter(p->!p.equals(from)).toList()) {
            Path target=to.resolve(from.relativize(path));if(Files.isDirectory(path))Files.createDirectory(target);else Files.copy(path,target);
        }}
        ObjectNode preview=(ObjectNode)mapper.readTree(Files.readAllBytes(to.resolve("preview.json")));preview.put("uploadId",id);
        byte[] bytes=mapper.writeValueAsBytes(preview);Files.write(to.resolve("preview.json"),bytes);
        ObjectNode envelope=(ObjectNode)mapper.readTree(Files.readAllBytes(to.resolve("state.json")));ObjectNode state=(ObjectNode)envelope.get("state");
        state.put("uploadId",id).put("previewHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        envelope.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(state))));
        Files.write(to.resolve("state.json"),mapper.writeValueAsBytes(envelope));return service.get("synthetic",id);
    }
    @Test void currentManualDatesAreSortedExactAndDoNotComeFromUploads()throws Exception {
        gateway.existing.addAll(Set.of(LocalDate.of(2025,1,1),LocalDate.of(2025,1,3),LocalDate.of(2025,2,1)));
        gateway.manual.addAll(Set.of(LocalDate.of(2025,1,3),LocalDate.of(2025,1,1)));
        var ctx=service.context("synthetic");
        assertEquals("[\"2025-01-01\",\"2025-01-03\"]",ctx.path("manualHistoryDates").toString());
        assertEquals("[\"2025-01-01\",\"2025-01-03\",\"2025-02-01\"]",ctx.path("existingDates").toString());
        assertEquals(0,ctx.path("uploads").size());
        gateway.contextFailure=true;
        assertEquals("HISTORY_UNAVAILABLE",assertThrows(HistoryUploadService.Failure.class,()->service.context("synthetic")).code);
    }
    @Test void secondPreviewRejectedUntilExplicitDiscardAndFactsRetained()throws Exception {
        var first=preview();Path facts=directory(first).resolve(first.path("start").stringValue()+"/facts.jsonl");byte[] before=Files.readAllBytes(facts);
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
        service.discard("synthetic",first.path("uploadId").stringValue(),first.path("previewHash").stringValue());
        assertArrayEquals(before,Files.readAllBytes(facts));assertEquals("PREVIEW_READY",preview().path("status").stringValue());
    }
    @Test void interruptedReceiveIsReadableCancelableAndDoesNotBlockOtherProjects()throws Exception {
        var snap=store.snapshot();var projects=new ArrayList<>(snap.document().projects());
        projects.add(new ProjectConfigurationStore.Project("other","Other","23456789",List.of("98765432"),true));
        var pre=store.preflight(snap.revision(),projects);store.apply(snap.revision(),projects,pre.candidateHash());
        byte[] partial={1,2,3};
        InputStream broken=new InputStream(){boolean sent;
            public int read()throws IOException{throw new IOException("PRIVATE_TRANSPORT_DETAIL");}
            public int read(byte[] buffer)throws IOException{
                if(sent)throw new IOException("PRIVATE_TRANSPORT_DETAIL");
                sent=true;System.arraycopy(partial,0,buffer,0,partial.length);return partial.length;
            }
        };
        var failed=service.preview("synthetic",broken);Path dir=directory(failed);
        assertEquals("VALIDATION_FAILED",failed.path("status").stringValue());
        assertEquals("UPLOAD_RECEIVE_FAILED",failed.path("diagnostic").path("code").stringValue());
        assertFalse(failed.toString().contains("PRIVATE_TRANSPORT_DETAIL"));
        assertArrayEquals(partial,Files.readAllBytes(dir.resolve("upload.xlsx")));
        assertEquals(3,failed.path("sourceSizeBytes").intValue());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(partial)),failed.path("sourceSha256").stringValue());
        assertFalse(Files.exists(dir.resolve("converted.json")));assertEquals(0,gateway.imports);
        service=new HistoryUploadService(root,store,gateway,python,true);
        assertEquals(0,service.context("synthetic").path("warnings").size());
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
        assertEquals("VALIDATION_FAILED",service.preview("other",new ByteArrayInputStream(new byte[]{1})).path("status").stringValue());
        assertEquals("CANCELLED",service.discard("synthetic",failed.path("uploadId").stringValue(),failed.path("previewHash").stringValue()).path("status").stringValue());
        assertFalse(Files.exists(dir.resolve("upload.xlsx")));
        assertEquals("PREVIEW_READY",preview().path("status").stringValue());
    }
    @Test void receiveFailureBeforeFirstByteStillHasCancelableEmptyEvidence()throws Exception {
        var failed=service.preview("synthetic",new InputStream(){public int read()throws IOException{throw new IOException("PRIVATE");}});
        assertEquals("UPLOAD_RECEIVE_FAILED",failed.path("diagnostic").path("code").stringValue());
        assertEquals(0,failed.path("sourceSizeBytes").longValue());
        assertEquals(0,service.context("synthetic").path("warnings").size());
        assertEquals("CANCELLED",service.discard("synthetic",failed.path("uploadId").stringValue(),failed.path("previewHash").stringValue()).path("status").stringValue());
    }
    @Test void otherProjectDoesNotOccupySlot()throws Exception {
        var snap=store.snapshot();var projects=new ArrayList<>(snap.document().projects());
        projects.add(new ProjectConfigurationStore.Project("other","Other","23456789",List.of("98765432"),true));
        var pre=store.preflight(snap.revision(),projects);store.apply(snap.revision(),projects,pre.candidateHash());
        preview();var other=service.preview("other",new ByteArrayInputStream(new byte[]{1}));
        assertEquals("VALIDATION_FAILED",other.path("status").stringValue());
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->service.preview("other",new ByteArrayInputStream(new byte[]{1}))).code);
    }
    @Test void simultaneousPreviewsCannotCreateTwoFilesAndFailureStillOccupiesSlot()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        InputStream input=new InputStream(){public int read(){return -1;}public int read(byte[] buffer){
            entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return -1;
        }};
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->service.preview("synthetic",input));
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals("CONVERSION_BUSY",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
                try(var dirs=Files.list(root.resolve(".local/history-uploads"))){assertEquals(1,dirs.count());}
            }finally{release.countDown();}
            assertEquals("VALIDATION_FAILED",first.get(10,TimeUnit.SECONDS).path("status").stringValue());
        }
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
    }
    @Test void legacyMultipleFailuresRequireEachExplicitDiscard()throws Exception {
        var first=service.preview("synthetic",new ByteArrayInputStream(new byte[]{1}));var second=legacyClone(first);
        assertEquals(2,service.context("synthetic").path("uploads").size());
        service.discard("synthetic",first.path("uploadId").stringValue(),first.path("previewHash").stringValue());
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
        service.discard("synthetic",second.path("uploadId").stringValue(),second.path("previewHash").stringValue());
        assertEquals("PREVIEW_READY",preview().path("status").stringValue());
    }
    @Test void partialImportAndFinalVerificationFailureCannotDiscardOrReplace()throws Exception {
        var view=preview();gateway.failSecond=true;assertEquals("IMPORT_FAILED",apply(view).path("status").stringValue());
        assertEquals("UPLOAD_DISCARD_UNSAFE",assertThrows(HistoryUploadService.Failure.class,()->service.discard("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue())).code);
        assertEquals("UPLOAD_SLOT_OCCUPIED",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
        gateway.failSecond=false;gateway.failFinalVerify=true;gateway.verifies=0;
        assertEquals("IMPORTED_CLEANUP_PENDING",apply(view).path("status").stringValue());
        assertEquals("UPLOAD_DISCARD_UNSAFE",assertThrows(HistoryUploadService.Failure.class,()->service.discard("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue())).code);
        assertTrue(Files.exists(directory(view).resolve("upload.xlsx")));
    }
    @Test void previewConfirmImportCleanupAndRestart()throws Exception{
        assertFalse(Files.exists(root.resolve(".local/history-uploads")));
        var view=preview();assertEquals("PREVIEW_READY",view.path("status").stringValue());assertEquals(2,view.path("days").size());
        assertFalse(view.has("progress"));assertFalse(view.path("active").booleanValue());
        assertEquals(0,gateway.imports);assertFalse(Files.exists(directory(view).resolve(view.path("start").stringValue()+"/normalized-manifest-v2.json")));
        var done=apply(view);assertEquals("COMPLETED",done.path("status").stringValue());assertEquals(2,gateway.imports);
        assertFalse(Files.exists(directory(view).resolve("upload.xlsx")));assertTrue(gateway.verifies>=4);
        var restarted=new HistoryUploadService(root,store,gateway,python,true);
        assertEquals("COMPLETED",restarted.get("synthetic",view.path("uploadId").stringValue()).path("status").stringValue());
        int verifies=gateway.verifies;apply(view);assertEquals(2,gateway.imports);assertEquals(verifies,gateway.verifies);
        assertFalse(done.path("active").booleanValue());assertEquals("COMPLETED",done.path("progress").path("stage").stringValue());
        assertEquals(2,done.path("progress").path("completedDays").intValue());
    }
    @Test void confirmationsRequired()throws Exception{var view=preview();assertThrows(HistoryUploadService.Failure.class,()->service.apply("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue(),true,false));assertEquals(0,gateway.imports);}
    @Test void authenticatedConfirmationKeepsOriginalActorAcrossRestartAndRetry() throws Exception {
        var view=preview(); gateway.failSecond=true;
        assertEquals("IMPORT_FAILED", service.applyAs("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue(),true,true,"AUTHENTICATED:first").path("status").stringValue());
        gateway.failSecond=false; service=new HistoryUploadService(root,store,gateway,python,true);
        assertEquals("COMPLETED", service.applyAs("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue(),true,true,"AUTHENTICATED:second").path("status").stringValue());
        for(var day:view.withArray("days")) {
            var manifest=new JsonMapper().readTree(Files.readString(directory(view).resolve(day.path("statDate").stringValue()+"/normalized-manifest-v2.json")));
            assertEquals("AUTHENTICATED:first",manifest.path("confirmationActor").stringValue());
        }
        assertEquals(2,gateway.imports);
    }
    @Test void changedConfigurationRejects()throws Exception{var view=preview();var snap=store.snapshot();var projects=snap.document().projects();var pre=store.preflight(snap.revision(),projects);store.apply(snap.revision(),projects,pre.candidateHash());assertThrows(HistoryUploadService.Failure.class,()->apply(view));assertEquals(0,gateway.imports);}
    @Test void overlapStopsWithoutOverwrite()throws Exception{var view=preview();gateway.existing.add(LocalDate.parse(view.path("start").stringValue()));assertEquals("IMPORT_FAILED",apply(view).path("status").stringValue());assertEquals(0,gateway.imports);}
    @Test void noMcpPermitsHistoryWithoutInventingDate()throws Exception{gateway.cutoff=null;var view=preview();assertTrue(view.path("firstMcpDate").isNull());assertEquals("PREVIEW_READY",view.path("status").stringValue());assertTrue(service.context("synthetic").path("blockedReason").isNull());assertEquals("COMPLETED",apply(view).path("status").stringValue());}
    @Test void mcpAppearingAfterNoMcpPreviewRequiresNewPreview()throws Exception{gateway.cutoff=null;var view=preview();gateway.cutoff=LocalDate.of(2099,1,1);assertEquals("MCP_CONTEXT_CHANGED",assertThrows(HistoryUploadService.Failure.class,()->apply(view)).code);assertEquals(0,gateway.imports);}
    @Test void conversionResourceExitHasSpecificSafeDiagnostic()throws Exception{
        Files.writeString(root.resolve("tools/manual_history/convert_upload_bridge.py"),"import sys\nsys.exit(75)\n");
        var view=preview();assertEquals("VALIDATION_FAILED",view.path("status").stringValue());assertEquals("CONVERSION_RESOURCE_LIMIT",view.path("diagnostic").path("code").stringValue());assertEquals(0,gateway.imports);
    }
    @Test void previewGuardIsGlobalAndReleased()throws Exception{
        var field=HistoryUploadService.class.getDeclaredField("PREVIEWS");field.setAccessible(true);var gate=(Semaphore)field.get(null);gate.acquire();
        try{var other=new HistoryUploadService(root,store,gateway,python,true);assertEquals("CONVERSION_BUSY",assertThrows(HistoryUploadService.Failure.class,()->other.preview("synthetic",new ByteArrayInputStream(new byte[0]))).code);assertFalse(Files.exists(root.resolve(".local/history-uploads")));}finally{gate.release();}
        assertEquals("PREVIEW_READY",preview().path("status").stringValue());assertEquals(1,gate.availablePermits());
    }
    @Test void partialFailureResumesOnlyFailedDay()throws Exception{var view=preview();gateway.failSecond=true;assertEquals("IMPORT_FAILED",apply(view).path("status").stringValue());assertTrue(Files.exists(directory(view).resolve("upload.xlsx")));gateway.failSecond=false;service=new HistoryUploadService(root,store,gateway,python,true);assertEquals("COMPLETED",apply(view).path("status").stringValue());assertEquals(2,gateway.imports);}
    @Test void sourceTamperRejects()throws Exception{var view=preview();Files.write(directory(view).resolve("upload.xlsx"),new byte[]{1});assertThrows(HistoryUploadService.Failure.class,()->apply(view));assertEquals(0,gateway.imports);}
    @Test void machineFactTamperDoesNotPublish()throws Exception{var view=preview();Files.writeString(directory(view).resolve(view.path("start").stringValue()+"/facts.jsonl"),"{}");assertEquals("IMPORT_FAILED",apply(view).path("status").stringValue());assertEquals(0,gateway.imports);}
    @Test void validationFailureCanCancel()throws Exception{var view=service.preview("synthetic",new ByteArrayInputStream(new byte[]{1,2,3}));assertEquals("VALIDATION_FAILED",view.path("status").stringValue());assertTrue(view.path("start").isNull());assertEquals("CANCELLED",service.discard("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue()).path("status").stringValue());assertFalse(Files.exists(directory(view).resolve("upload.xlsx")));}
    @Test void readonlyDoesNotCreateUploadDirectory(){var readonly=new HistoryUploadService(root,store,gateway,python,false);assertThrows(HistoryUploadService.Failure.class,()->readonly.preview("synthetic",new ByteArrayInputStream(new byte[0])));assertFalse(Files.exists(root.resolve(".local/history-uploads")));}
    @Test void progressIsReadableWhileImportHoldsLockAndDuplicateActionsAreRejected() throws Exception {
        var view=preview();gateway.entered=new CountDownLatch(1);gateway.release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(()->apply(view));
            try {
                assertTrue(gateway.entered.await(10,TimeUnit.SECONDS));
                var progress=assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->service.get("synthetic",view.path("uploadId").stringValue()));
                assertEquals("IMPORTING",progress.path("status").stringValue());assertTrue(progress.path("active").booleanValue());
                assertEquals(0,progress.path("progress").path("completedDays").intValue());
                assertEquals(view.path("start").stringValue(),progress.path("progress").path("currentDate").stringValue());
                assertTrue(service.context("synthetic").path("uploads").get(0).path("active").booleanValue());
                assertEquals("UPLOAD_BUSY",assertThrows(HistoryUploadService.Failure.class,()->apply(view)).code);
                assertEquals("UPLOAD_BUSY",assertThrows(HistoryUploadService.Failure.class,()->service.discard("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue())).code);
            } finally {gateway.release.countDown();}
            assertEquals("COMPLETED",running.get(10,TimeUnit.SECONDS).path("status").stringValue());
        }
    }
    @Test void interruptedPersistentStageIsReadWithoutMutationAndExplicitlyResumed() throws Exception {
        var view=preview();var mapper=JsonMapper.builder().build();Path path=directory(view).resolve("state.json");
        ObjectNode envelope=(ObjectNode)mapper.readTree(Files.readAllBytes(path));ObjectNode state=(ObjectNode)envelope.get("state");
        state.putObject("progress").put("stage","IMPORTING").put("completedDays",0).put("totalDays",2)
                .put("currentDate",view.path("start").stringValue()).put("startedAt","2026-10-15T00:00:00Z").put("updatedAt","2026-10-15T00:00:00Z");
        service=new HistoryUploadService(root,store,gateway,python,true);
        for(String stage:List.of("IMPORTING","VERIFYING","CLEANING")) {
            state.put("status",stage);((ObjectNode)state.get("progress")).put("stage",stage);
            envelope.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(state))));
            byte[] original=mapper.writeValueAsBytes(envelope);Files.write(path,original);
            var interrupted=service.get("synthetic",view.path("uploadId").stringValue());
            assertEquals("INTERRUPTED",interrupted.path("status").stringValue());assertFalse(interrupted.path("active").booleanValue());
            assertEquals("UPLOAD_DISCARD_UNSAFE",assertThrows(HistoryUploadService.Failure.class,()->service.discard("synthetic",view.path("uploadId").stringValue(),view.path("previewHash").stringValue())).code);
            assertArrayEquals(original,Files.readAllBytes(path));assertEquals(0,gateway.imports);
        }
        var resumed=apply(view);assertEquals("COMPLETED",resumed.path("status").stringValue());
        assertNotEquals("2026-10-15T00:00:00Z",resumed.path("progress").path("startedAt").stringValue());
    }
    @Test void failedDayRetainsStageDateAndRealCompletedCount() throws Exception {
        var view=preview();gateway.failSecond=true;var failed=apply(view);
        assertEquals("IMPORT_FAILED",failed.path("status").stringValue());assertFalse(failed.path("active").booleanValue());
        assertEquals("IMPORTING",failed.path("progress").path("stage").stringValue());assertEquals(1,failed.path("progress").path("completedDays").intValue());
        assertEquals(view.path("end").stringValue(),failed.path("progress").path("currentDate").stringValue());
    }
    @Test void finalVerificationProgressDoesNotClaimCompletionBeforeAllChecks() throws Exception {
        var view=preview();gateway.entered=new CountDownLatch(1);gateway.release=new CountDownLatch(1);gateway.blockFinalVerify=true;
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(()->apply(view));
            try {
                assertTrue(gateway.entered.await(10,TimeUnit.SECONDS));
                var progress=service.get("synthetic",view.path("uploadId").stringValue());
                assertEquals("VERIFYING",progress.path("status").stringValue());assertTrue(progress.path("active").booleanValue());
                assertEquals(0,progress.path("progress").path("completedDays").intValue());assertTrue(Files.exists(directory(view).resolve("upload.xlsx")));
            } finally {gateway.release.countDown();}
            assertEquals("COMPLETED",running.get(10,TimeUnit.SECONDS).path("status").stringValue());
        }
    }
    @Test void finalVerificationFailureRetainsCopyAndVerificationStage() throws Exception {
        var view=preview();gateway.failFinalVerify=true;var failed=apply(view);
        assertEquals("IMPORTED_CLEANUP_PENDING",failed.path("status").stringValue());assertFalse(failed.path("active").booleanValue());
        assertEquals("VERIFYING",failed.path("progress").path("stage").stringValue());assertEquals(0,failed.path("progress").path("completedDays").intValue());
        assertEquals(view.path("start").stringValue(),failed.path("progress").path("currentDate").stringValue());
        assertTrue(Files.exists(directory(view).resolve("upload.xlsx")));assertEquals(2,gateway.imports);
        assertTrue(failed.path("message").stringValue().contains("最终回读核验未通过"));
    }
    @Test void committedDayWithoutSavedVersionRecoversAfterCrashButAnotherUploadCannotAdoptIt() throws Exception {
        var original=preview(); var other=legacyClone(original); gateway.crashAfterCommit=true;
        assertThrows(AssertionError.class,()->apply(original));
        assertEquals(1,gateway.imports);
        service=new HistoryUploadService(root,store,gateway,python,true);
        assertEquals("INTERRUPTED",service.get("synthetic",original.path("uploadId").stringValue()).path("status").stringValue());
        assertEquals("IMPORT_FAILED",apply(other).path("status").stringValue());
        assertEquals(1,gateway.imports);
        assertEquals("COMPLETED",apply(original).path("status").stringValue());
        assertEquals(2,gateway.imports);assertTrue(gateway.recoveries>0);
    }
    @Test void corruptRecordsAreIsolatedAndOnlyGenericWarningsAreReturned() throws Exception {
        var good=preview();var bad=legacyClone(good);Files.writeString(directory(bad).resolve("state.json"),"private broken state");
        Path missing=root.resolve(".local/history-uploads/"+"f".repeat(32));Files.createDirectory(missing);
        var context=service.context("synthetic");
        assertEquals(1,context.path("uploads").size());assertEquals(good.path("uploadId"),context.path("uploads").get(0).path("uploadId"));
        assertEquals(2,context.path("warnings").size());
        assertEquals("UPLOAD_RECORD_UNAVAILABLE",context.path("warnings").get(0).path("code").stringValue());
        assertFalse(context.path("warnings").toString().contains(bad.path("uploadId").stringValue()));
        assertFalse(context.path("warnings").toString().contains("private"));
        assertEquals("UPLOAD_RECORD_UNAVAILABLE",context.path("blockedReason").stringValue());
        assertEquals("UPLOAD_RECORD_UNAVAILABLE",assertThrows(HistoryUploadService.Failure.class,()->preview()).code);
        assertEquals("COMPLETED",apply(good).path("status").stringValue());
    }
    @Test void timestampsOrderRecordsWithoutMutatingLegacyEvidence() throws Exception {
        var first=service.preview("synthetic",new ByteArrayInputStream(new byte[]{1}));
        var second=legacyClone(first);
        var mapper=JsonMapper.builder().build();
        // Construct legacy records with no time fields; UUID names deliberately play no role.
        for(var view:List.of(first,second)) {
            Path dir=directory(view);ObjectNode preview=(ObjectNode)mapper.readTree(Files.readAllBytes(dir.resolve("preview.json")));
            preview.remove("createdAt");Files.write(dir.resolve("preview.json"),mapper.writeValueAsBytes(preview));
            ObjectNode env=(ObjectNode)mapper.readTree(Files.readAllBytes(dir.resolve("state.json")));ObjectNode state=(ObjectNode)env.get("state");
            state.remove("createdAt");state.put("status","COMPLETED");
            state.putObject("progress").put("updatedAt",view==first?"2026-01-03T00:00:00Z":"2026-01-02T00:00:00Z");
            state.put("previewHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("preview.json")))));
            env.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(state))));
            Files.write(dir.resolve("state.json"),mapper.writeValueAsBytes(env));
            Files.setLastModifiedTime(dir.resolve("preview.json"),java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        }
        byte[] before=Files.readAllBytes(directory(first).resolve("state.json"));
        var result=service.context("synthetic").path("uploads");
        assertEquals(first.path("uploadId"),result.get(0).path("uploadId"));
        assertEquals("2026-01-01T00:00:00Z",result.get(0).path("createdAt").stringValue());
        assertEquals("2026-01-03T00:00:00Z",result.get(0).path("completedAt").stringValue());
        assertArrayEquals(before,Files.readAllBytes(directory(first).resolve("state.json")));
    }
    static class FakeGateway implements HistoryFactGateway{
        LocalDate cutoff=LocalDate.of(2099,1,1);Set<LocalDate> existing=new HashSet<>(),manual=new HashSet<>();Map<Long,PreparedFactBatch>batches=new HashMap<>();int imports,verifies,recoveries;boolean failSecond,blockFinalVerify,failFinalVerify,crashAfterCommit,contextFailure;
        CountDownLatch entered,release;
        void block(){if(entered!=null){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}}
        public Context inspect(String account){if(contextFailure)throw new IllegalStateException();return new Context(cutoff,existing,manual);}
        public FactStoreResult importNewDay(PreparedFactBatch batch){if(!blockFinalVerify)block();if(failSecond&&imports==1)throw new IllegalStateException();assertFalse(existing.contains(batch.statDate()));existing.add(batch.statDate());batches.put((long)++imports,batch);if(crashAfterCommit){crashAfterCommit=false;throw new AssertionError("simulated abrupt process loss after commit");}return new FactStoreResult(imports,FactVersionDisposition.CURRENT,FactStoreOutcome.INSERTED,batch.rows().size());}
        public OptionalLong recoverCurrentDay(PreparedFactBatch batch){for(var entry:batches.entrySet())if(entry.getValue().statDate().equals(batch.statDate())){if(!entry.getValue().equals(batch))throw new IllegalStateException("other upload");verify(batch,entry.getKey());recoveries++;return OptionalLong.of(entry.getKey());}return OptionalLong.empty();}
        public void verify(PreparedFactBatch batch,long id){if(failFinalVerify&&verifies==2)throw new IllegalStateException();if(blockFinalVerify&&verifies==2)block();assertEquals(batches.get(id).contentHash(),batch.contentHash());assertTrue(Files.exists(Path.of(batch.manifestEvidence().absolutePath())));assertTrue(Files.exists(Path.of(batch.materialEvidence().absolutePath())));verifies++;}
    }
}
