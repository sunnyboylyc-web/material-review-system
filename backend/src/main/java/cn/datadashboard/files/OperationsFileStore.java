package cn.datadashboard.files;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Private opaque file storage. Never interprets, extracts or executes uploaded content. */
public final class OperationsFileStore {
    public static final int CHUNK_BYTES = 4 * 1024 * 1024;
    private static final long HEADROOM = 1024L * 1024 * 1024;
    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    private final java.util.Set<String> verifying = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService verifier = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"private-file-verifier"); t.setDaemon(true); return t; });
    public record Entry(String id, String name, long bytes, long received, String status, String sha256, String createdAt) {}
    public OperationsFileStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root)) throw new IOException("UNSAFE_STORAGE");
        privateMode(this.root,true);
    }
    private static void privateMode(Path p, boolean directory) throws IOException {
        if (Files.getFileStore(p).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(p,
                java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
    private Path path(String id, String suffix) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("INVALID_FILE_ID");
        Path p = root.resolve(id + suffix);
        if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("UNSAFE_FILE");
        return p;
    }
    private Entry read(String id) throws IOException {
        return json.readValue(Files.readString(path(id, ".json")), Entry.class);
    }
    private void save(Entry e) throws IOException {
        Path temp = path(e.id(), ".meta-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temp, json.writeValueAsString(e), StandardOpenOption.CREATE_NEW);
        privateMode(temp,false);
        try { Files.move(temp, path(e.id(), ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
    public synchronized List<Entry> list() throws IOException {
        List<Entry> result = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{32}\\.json")).toList()) {
                Entry e = read(p.getFileName().toString().substring(0,32));
                if (!e.status().equals("READY") && !e.status().equals("DELETING")) e = new Entry(e.id(),e.name(),e.bytes(),Files.size(Files.exists(path(e.id(),".bin")) ? path(e.id(),".bin") : path(e.id(),".part")),e.status().equals("VERIFYING") && !verifying.contains(e.id()) ? "INTERRUPTED" : e.status(),"",e.createdAt());
                result.add(e);
            }
        }
        result.sort(Comparator.comparing(Entry::createdAt).reversed());
        return result;
    }
    public synchronized Entry begin(String name, long bytes) throws IOException {
        if (name == null || name.isBlank() || name.length() > 180 || name.chars().anyMatch(c -> Character.isISOControl(c) || c=='/' || c=='\\') || bytes <= 0)
            throw new IllegalArgumentException("INVALID_FILE");
        long reserved = 0;
        for (Entry e : list()) if (e.status().equals("UPLOADING")) reserved = Math.addExact(reserved, Math.max(0,e.bytes()-e.received()));
        long available = Files.getFileStore(root).getUsableSpace();
        if (bytes > available - HEADROOM - reserved) throw new IllegalStateException("DISK_RESERVE_REQUIRED");
        String id = UUID.randomUUID().toString().replace("-", "");
        Files.createFile(path(id,".part"));
        privateMode(path(id,".part"),false);
        Entry e = new Entry(id,name,bytes,0,"UPLOADING","",Instant.now().toString());
        save(e);
        return e;
    }
    public synchronized Entry chunk(String id, long offset, InputStream input) throws IOException {
        Entry e = read(id);
        if (!e.status().equals("UPLOADING")) throw new IllegalStateException("UPLOAD_NOT_PENDING");
        Path part = path(id,".part");
        if (Files.size(part) != offset) throw new IllegalStateException("OFFSET_MISMATCH");
        if (Files.getFileStore(root).getUsableSpace() < HEADROOM + CHUNK_BYTES) throw new IllegalStateException("DISK_RESERVE_REQUIRED");
        long count = 0;
        try (OutputStream out = Files.newOutputStream(part,StandardOpenOption.APPEND)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n=input.read(buffer))!=-1) {
                count += n;
                if (count > CHUNK_BYTES || count > e.bytes()-offset) throw new IllegalArgumentException("CHUNK_TOO_LARGE");
                out.write(buffer,0,n);
            }
        } catch (IOException | RuntimeException failure) {
            try (var channel = FileChannel.open(part, StandardOpenOption.WRITE)) { channel.truncate(offset); }
            throw failure;
        }
        return new Entry(e.id(),e.name(),e.bytes(),offset+count,e.status(),"",e.createdAt());
    }
    public synchronized Entry startFinish(String id) throws IOException {
        Entry e=read(id);
        if (e.status().equals("DELETING")) throw new IllegalStateException("FILE_DELETING");
        if (e.status().equals("READY") || verifying.contains(id)) return e;
        Path source=Files.exists(path(id,".bin"))?path(id,".bin"):path(id,".part");
        if (Files.size(source)!=e.bytes()) throw new IllegalStateException("UPLOAD_INCOMPLETE");
        Entry pending=new Entry(id,e.name(),e.bytes(),e.bytes(),"VERIFYING","",e.createdAt());
        save(pending); verifying.add(id);
        verifier.submit(()-> { try { finish(id); } catch(Exception failure) { synchronized(this) { try { save(new Entry(id,e.name(),e.bytes(),e.bytes(),"FAILED","",e.createdAt())); } catch(IOException ignored) {} } } finally { verifying.remove(id); } });
        return pending;
    }
    public Entry finish(String id) throws Exception {
        Entry e=read(id);
        if (e.status().equals("DELETING")) throw new IllegalStateException("FILE_DELETING");
        if (e.status().equals("READY")) return e;
        Path part=path(id,".part"), data=path(id,".bin");
        // A crash after the rename is recoverable by repeating finalization.
        Path source=Files.exists(data)?data:part;
        if (Files.size(source)!=e.bytes()) throw new IllegalStateException("UPLOAD_INCOMPLETE");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream in=Files.newInputStream(source)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        if (source.equals(part)) Files.move(part,data,StandardCopyOption.ATOMIC_MOVE);
        Entry ready=new Entry(id,e.name(),e.bytes(),e.bytes(),"READY",HexFormat.of().formatHex(digest.digest()),e.createdAt());
        synchronized(this) { save(ready); } return ready;
    }
    public synchronized void cancel(String id) throws IOException {
        Entry e=read(id);
        if (!e.status().equals("UPLOADING") || Files.exists(path(id,".bin"))) throw new IllegalStateException("CANNOT_CANCEL_SAVED_FILE");
        Files.deleteIfExists(path(id,".part")); Files.delete(path(id,".json"));
    }
    /** Exact saved-object deletion; durable intent makes an interrupted deletion retryable. */
    public synchronized void deleteSaved(String id) throws IOException {
        Path metadata = path(id,".json"), data = path(id,".bin");
        if (!Files.exists(metadata) && !Files.exists(data) && !Files.exists(path(id,".part"))) return;
        Entry e = read(id);
        if (!id.equals(e.id()) || verifying.contains(id) ||
                (!e.status().equals("READY") && !e.status().equals("DELETING")))
            throw new IllegalStateException("CANNOT_DELETE_UNSAVED_FILE");
        if (e.status().equals("READY")) {
            ready(id);
            save(new Entry(id,e.name(),e.bytes(),e.received(),"DELETING",e.sha256(),e.createdAt()));
        }
        Files.deleteIfExists(data);
        Files.delete(metadata);
    }
    public synchronized Entry ready(String id) throws IOException {
        Entry e=read(id);
        if (!e.status().equals("READY") || Files.size(path(id,".bin"))!=e.bytes()) throw new IllegalStateException("FILE_NOT_READY");
        return e;
    }
    public InputStream open(String id) throws IOException { ready(id); return Files.newInputStream(path(id,".bin")); }
}
