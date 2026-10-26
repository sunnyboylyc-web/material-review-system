package cn.datadashboard.security;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import jakarta.servlet.http.*;

/** Signed, 14-day credential: password changes and explicit logout revoke remembered logins. */
public final class DurableRememberMeServices extends TokenBasedRememberMeServices {
    private final Path directory;
    public DurableRememberMeServices(Path directory, UserDetailsService users) {
        super(secret(directory.resolve("signing-key")), users, RememberMeTokenAlgorithm.SHA256);
        this.directory = directory;
        setTokenValiditySeconds(14 * 24 * 60 * 60);
        setUseSecureCookie(true);
        setCookieCustomizer(cookie -> { cookie.setHttpOnly(true); cookie.setAttribute("SameSite", "Lax"); });
    }
    private static synchronized String secret(Path path) {
        try {
            PrivateUserFiles.directory(path.getParent());
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) PrivateUserFiles.write(path, random());
            String value = PrivateUserFiles.read(path);
            if (!value.matches("[0-9a-f]{64}")) throw new IllegalStateException("REMEMBER_STATE_INVALID");
            return value;
        } catch (java.io.IOException e) { throw new IllegalStateException("REMEMBER_STATE_UNAVAILABLE"); }
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private Path epoch(String username) { return directory.resolve("epoch-" + PrivateUserFiles.id(username)); }
    public String signingKey() { return secret(directory.resolve("signing-key")); }
    @Override protected String makeTokenSignature(long expiry, String username, String password, RememberMeTokenAlgorithm algorithm) {
        return super.makeTokenSignature(expiry, username, password + ":" + secret(epoch(username)), algorithm);
    }
    @Override public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null) {
            try { PrivateUserFiles.write(epoch(authentication.getName()), random()); }
            catch (java.io.IOException e) { throw new IllegalStateException("REMEMBER_LOGOUT_UNAVAILABLE"); }
        }
        super.logout(request, response, authentication);
    }
}
