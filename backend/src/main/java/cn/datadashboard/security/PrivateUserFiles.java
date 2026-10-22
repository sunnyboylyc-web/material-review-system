package cn.datadashboard.security;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Private non-business state. No usernames or project names are used as filesystem paths. */
public final class PrivateUserFiles {
    private PrivateUserFiles() { }
    public static String id(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("USER_STATE_UNAVAILABLE"); }
    }
    public static void directory(Path path) throws java.io.IOException {
        if (Files.isSymbolicLink(path)) throw new java.io.IOException("USER_STATE_UNSAFE");
        Files.createDirectories(path);
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    public static void write(Path path, String value) throws java.io.IOException {
        directory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new java.io.IOException("USER_STATE_UNSAFE");
        Path temp = Files.createTempFile(path.getParent(), ".pending-", ".tmp");
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public static String read(Path path) throws java.io.IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 4096) throw new java.io.IOException("USER_STATE_UNSAFE");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
