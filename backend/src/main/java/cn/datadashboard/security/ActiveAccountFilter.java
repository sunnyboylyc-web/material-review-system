package cn.datadashboard.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Session authentication is cached by Spring; recheck deletion on every subsequent request. */
final class ActiveAccountFilter extends OncePerRequestFilter {
    private final PrivateAccountStore accounts;
    ActiveAccountFilter(PrivateAccountStore accounts){this.accounts=accounts;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var identity=SecurityContextHolder.getContext().getAuthentication();
        if(identity!=null&&identity.isAuthenticated()&&!(identity instanceof AnonymousAuthenticationToken)) {
            try { accounts.loadUserByUsername(identity.getName()); }
            catch(UsernameNotFoundException unavailable) {
                var session=request.getSession(false); if(session!=null)session.invalidate();
                SecurityContextHolder.clearContext();
                response.setHeader("Cache-Control","no-store");
                if(request.getServletPath().startsWith("/api/")){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"LOGIN_REQUIRED\"}");}
                else {response.setStatus(302);response.setHeader("Location",request.getContextPath()+"/login");}
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
