package cn.datadashboard.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Only used with the trial security filter; browser headers never establish identity. */
public final class TrialRequestGuard {
    private final String origin;
    public TrialRequestGuard(String origin) {
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.getRawPath().isEmpty())
            throw new IllegalArgumentException("PUBLIC_ORIGIN_REQUIRED");
        this.origin = origin;
    }
    public boolean permits(HttpServletRequest request, boolean write, boolean operations) {
        String supplied = request.getHeader("Origin");
        return request.getUserPrincipal() != null
                && request.isUserInRole(operations ? "OPERATIONS" : "VIEWER")
                && !"cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                && (supplied == null ? !write : origin.equals(supplied));
    }
}
