package cn.datadashboard.files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class OperationsFileStoreTest {
    @TempDir Path dir;
    @Test void opaqueRoundTripAndDuplicateNames() throws Exception {
        var store=new OperationsFileStore(dir);
        var first=store.begin("开发备份.zip",3);
        store.chunk(first.id(),0,new ByteArrayInputStream(new byte[]{1,2,3}));
        var saved=store.finish(first.id());
        assertEquals("READY",saved.status()); assertEquals(64,saved.sha256().length());
        assertArrayEquals(new byte[]{1,2,3},store.open(first.id()).readAllBytes());
        assertNotEquals(first.id(),store.begin("开发备份.zip",3).id());
        assertEquals(saved,store.finish(first.id()));
        assertThrows(IllegalStateException.class,()->store.cancel(first.id()));
    }
    @Test void rejectPathsOffsetsIncompleteAndOverflow() throws Exception {
        var store=new OperationsFileStore(dir);
        assertThrows(IllegalArgumentException.class,()->store.begin("../secret",1));
        assertThrows(IllegalArgumentException.class,()->store.open("../secret"));
        var e=store.begin("test.bin",3);
        assertThrows(IllegalStateException.class,()->store.finish(e.id()));
        assertThrows(IllegalStateException.class,()->store.chunk(e.id(),1,new ByteArrayInputStream(new byte[]{1})));
        assertThrows(IllegalArgumentException.class,()->store.chunk(e.id(),0,new ByteArrayInputStream(new byte[]{1,2,3,4})));
        assertEquals(0,store.list().getFirst().received());
        store.cancel(e.id()); assertTrue(store.list().isEmpty());
    }
    @Test void restartKeepsPendingAndReady() throws Exception {
        var store=new OperationsFileStore(dir); var e=store.begin("test",3);
        store.chunk(e.id(),0,new ByteArrayInputStream(new byte[]{1,2,3}));
        store=new OperationsFileStore(dir); assertEquals(3,store.list().getFirst().received());
        store.finish(e.id()); assertEquals("READY",new OperationsFileStore(dir).list().getFirst().status());
    }
    @Test void asynchronousVerificationEventuallyPublishesAndIsRepeatSafe() throws Exception {
        var store=new OperationsFileStore(dir); var e=store.begin("test",3);
        store.chunk(e.id(),0,new ByteArrayInputStream(new byte[]{1,2,3}));
        assertEquals("VERIFYING",store.startFinish(e.id()).status());
        for(int i=0;i<100 && !store.list().getFirst().status().equals("READY");i++) Thread.sleep(10);
        assertEquals("READY",store.list().getFirst().status());
        assertEquals("READY",store.startFinish(e.id()).status());
    }
    @Test void multipleChunksRemainByteExact() throws Exception {
        var store=new OperationsFileStore(dir);
        byte[] block=new byte[OperationsFileStore.CHUNK_BYTES]; new java.util.Random(42).nextBytes(block);
        var e=store.begin("large.zip",block.length+3L);
        store.chunk(e.id(),0,new ByteArrayInputStream(block));
        store.chunk(e.id(),block.length,new ByteArrayInputStream(new byte[]{7,8,9}));
        store.finish(e.id());
        try(var in=store.open(e.id())) { assertArrayEquals(block,in.readNBytes(block.length)); assertArrayEquals(new byte[]{7,8,9},in.readAllBytes()); }
    }
    @Test void savedDeletionIsExactAndRepeatSafe() throws Exception {
        var store=new OperationsFileStore(dir);
        var first=store.begin("same.zip",3); var other=store.begin("same.zip",3);
        for(var e:java.util.List.of(first,other)) {
            store.chunk(e.id(),0,new ByteArrayInputStream(new byte[]{1,2,3})); store.finish(e.id());
        }
        store.deleteSaved(first.id());
        assertFalse(Files.exists(dir.resolve(first.id()+".bin")));
        assertFalse(Files.exists(dir.resolve(first.id()+".json")));
        assertEquals(other.id(),store.list().getFirst().id());
        assertArrayEquals(new byte[]{1,2,3},store.open(other.id()).readAllBytes());
        store.deleteSaved(first.id());
        assertThrows(IllegalArgumentException.class,()->store.deleteSaved("../escape"));
        var pending=store.begin("pending.zip",3);
        assertThrows(IllegalStateException.class,()->store.deleteSaved(pending.id()));
    }
    @Test void interruptedDeletionCanBeListedAndExplicitlyRetried() throws Exception {
        var store=new OperationsFileStore(dir); var first=store.begin("same.zip",3);
        store.chunk(first.id(),0,new ByteArrayInputStream(new byte[]{1,2,3})); store.finish(first.id());
        var metadata=dir.resolve(first.id()+".json");
        Files.writeString(metadata,Files.readString(metadata).replace("\"READY\"","\"DELETING\""));
        Files.delete(dir.resolve(first.id()+".bin"));
        var restarted=new OperationsFileStore(dir);
        assertEquals("DELETING",restarted.list().getFirst().status());
        assertEquals(64,restarted.list().getFirst().sha256().length());
        assertThrows(IllegalStateException.class,()->restarted.startFinish(first.id()));
        restarted.deleteSaved(first.id()); assertTrue(restarted.list().isEmpty());
    }
}
