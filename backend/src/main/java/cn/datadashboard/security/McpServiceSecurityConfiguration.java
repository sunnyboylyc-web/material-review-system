package cn.datadashboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Independent, stateless machine identity: never a website account or general login bypass. */
@Configuration(proxyBeanMethods = false)
public class McpServiceSecurityConfiguration {
    public static final String HEADER = "X-Material-Read-Key";

    @Bean
    @Order(-100)
    SecurityFilterChain mcpReadSecurity(HttpSecurity http,
            @Value("${scsj.mcp.upstream-token-file:${SCSJ_MCP_UPSTREAM_TOKEN_FILE:}}") String tokenFile) throws Exception {
        byte[] expected = readCredential(tokenFile);
        // A bad/duplicate service header must fail here, never fall back to a browser session/public mode.
        http.securityMatcher(request -> request.getHeader(HEADER) != null);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(c -> c.disable());
        http.csrf(c -> c.disable()); // This chain authorizes GET only; every write is denied below.
        http.formLogin(c -> c.disable());
        http.httpBasic(c -> c.disable());
        http.logout(c -> c.disable());
        http.rememberMe(c -> c.disable());
        http.addFilterBefore(new CredentialFilter(expected), AnonymousAuthenticationFilter.class);
        http.authorizeHttpRequests(a -> a
                .requestMatchers(new RegexRequestMatcher(
                        "^/api/projects(?:/[A-Za-z0-9_-]{1,100}/(?:review|review-materials|material-types|top|freshness))?(?:\\?.*)?$", "GET"))
                .hasRole("MCP_READ")
                .anyRequest().denyAll());
        http.exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> reject(r,401,"MCP_SERVICE_AUTH_REQUIRED"))
                .accessDeniedHandler((q,r,x) -> reject(r,403,"MCP_SERVICE_READ_ONLY")));
        return http.build();
    }

    private static byte[] readCredential(String file) {
        if (file == null || file.isBlank()) return null; // Feature unconfigured: service-header requests fail closed.
        try {
            Path p = Path.of(file);
            if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > 514)
                throw new IOException();
            if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = Files.getPosixFilePermissions(p);
                if (permissions.contains(PosixFilePermission.OTHERS_READ)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) throw new IOException();
            }
            String token = Files.readString(p, StandardCharsets.US_ASCII).strip();
            if (!token.matches("[A-Za-z0-9_-]{32,512}")) throw new IOException();
            return token.getBytes(StandardCharsets.US_ASCII);
        } catch (Exception failure) {
            // Do not include a secret, filesystem contents or a path in startup diagnostics.
            throw new IllegalStateException("MCP_SERVICE_CREDENTIAL_INVALID");
        }
    }

    private static void reject(HttpServletResponse r, int status, String code) throws IOException {
        r.setStatus(status); r.setContentType("application/json"); r.setHeader("Cache-Control", "no-store");
        r.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private static final class CredentialFilter extends OncePerRequestFilter {
        private final byte[] expected;
        CredentialFilter(byte[] expected) { this.expected = expected; }
        @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain)
                throws ServletException,IOException {
            var values = Collections.list(q.getHeaders(HEADER));
            if (expected == null || values.size() != 1
                    || !MessageDigest.isEqual(expected, values.getFirst().getBytes(StandardCharsets.US_ASCII))) {
                reject(r,401,"MCP_SERVICE_AUTH_REQUIRED"); return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("mcp-read-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_MCP_READ"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(q,r);
        }
    }
}
