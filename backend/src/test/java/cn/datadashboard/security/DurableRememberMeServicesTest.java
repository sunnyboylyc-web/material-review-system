package cn.datadashboard.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DurableRememberMeServicesTest {
    @TempDir Path root;
    @Test void secureRememberCredentialSurvivesRestartAndLogoutRevokesReplay() {
        var users = new InMemoryUserDetailsManager(User.withUsername("viewer").password("hash-one").roles("VIEWER").build());
        var service = new DurableRememberMeServices(root, users);
        var auth = UsernamePasswordAuthenticationToken.authenticated(users.loadUserByUsername("viewer"), "unused", users.loadUserByUsername("viewer").getAuthorities());
        var login = new MockHttpServletRequest(); login.setParameter("remember-me", "true");
        var response = new MockHttpServletResponse();
        service.loginSuccess(login, response, auth);
        var cookie = response.getCookie("remember-me");
        assertNotNull(cookie); assertTrue(cookie.isHttpOnly()); assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite")); assertEquals(14 * 86400, cookie.getMaxAge());
        var restarted = new DurableRememberMeServices(root, users);
        var revisit = new MockHttpServletRequest(); revisit.setCookies(cookie);
        assertNotNull(restarted.autoLogin(revisit, new MockHttpServletResponse()));
        restarted.logout(revisit, new MockHttpServletResponse(), auth);
        assertNull(new DurableRememberMeServices(root, users).autoLogin(revisit, new MockHttpServletResponse()));
    }
    @Test void uncheckedDoesNotRememberAndPasswordChangeInvalidates() {
        var user = User.withUsername("viewer").password("hash-one").roles("VIEWER").build();
        var users = new InMemoryUserDetailsManager(user);
        var service = new DurableRememberMeServices(root, users);
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, "unused", user.getAuthorities());
        var login = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        service.loginSuccess(login, response, auth);
        assertNull(response.getCookie("remember-me"));
        login.setParameter("remember-me", "true"); service.loginSuccess(login, response, auth);
        var revisit = new MockHttpServletRequest(); revisit.setCookies(response.getCookie("remember-me"));
        users.updateUser(User.withUsername("viewer").password("hash-two").roles("VIEWER").build());
        assertNull(service.autoLogin(revisit, new MockHttpServletResponse()));
    }
}
