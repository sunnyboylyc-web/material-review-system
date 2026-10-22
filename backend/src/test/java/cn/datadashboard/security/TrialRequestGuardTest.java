package cn.datadashboard.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class TrialRequestGuardTest {
    @Test void headersCannotEstablishIdentityAndWritesRequireExactOrigin() {
        var guard = new TrialRequestGuard("https://trial.example");
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://trial.example");
        request.addHeader("X-User-Role", "OPERATIONS");
        assertFalse(guard.permits(request, true, true));
        request.setUserPrincipal(() -> "test");
        request.addUserRole("VIEWER");
        assertTrue(guard.permits(request, false, false));
        assertFalse(guard.permits(request, true, true));
        request.addUserRole("OPERATIONS");
        assertTrue(guard.permits(request, true, true));
        request.removeHeader("Origin");
        assertFalse(guard.permits(request, true, true));
        assertTrue(guard.permits(request, false, true));
        request.addHeader("Origin", "https://untrusted.example");
        assertFalse(guard.permits(request, false, true));
    }
    @Test void invalidPublicOriginsFailClosed() {
        for (String origin : new String[]{"http://trial.example", "https://trial.example/path", "https://user@trial.example", "https://trial.example?x=1"})
            assertThrows(IllegalArgumentException.class, () -> new TrialRequestGuard(origin));
    }
}
