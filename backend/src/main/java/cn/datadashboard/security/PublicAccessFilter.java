package cn.datadashboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Explicit public mode: no login credentials; browser sessions still isolate saved preferences. */
final class PublicAccessFilter extends OncePerRequestFilter {
    private static final String VISITOR = PublicAccessFilter.class.getName() + ".visitor";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException, ServletException {
        if (request.getServletPath().equals("/login") && request.getMethod().equals("GET")) {
            response.setStatus(302);
            response.setHeader("Location", request.getContextPath() + "/");
            return;
        }
        var session = request.getSession();
        String visitor;
        synchronized (session) {
            visitor = (String) session.getAttribute(VISITOR);
            if (visitor == null) {
                visitor = "public-" + UUID.randomUUID().toString().replace("-", "");
                session.setAttribute(VISITOR, visitor);
            }
        }
        var context = SecurityContextHolder.createEmptyContext();
        // Never inherit administrator or old remembered-account authority in public mode.
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(visitor, null,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("ROLE_OPERATIONS"))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
