package co.edu.corhuila.barbersaas.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token on every route under /api/ except starting owner onboarding, which
 * is a sign-up and needs none (DEC-WF-01). The gateway only checks a token is present.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "auth.caller";
    static final String OWNER_ONBOARDING = "/api/v1/sagas/owner-onboarding";

    private final Rs256Verifier verifier;
    private final ObjectMapper json;

    public AuthFilter(Rs256Verifier verifier, ObjectMapper json) {
        this.verifier = verifier;
        this.json = json;
    }

    public static Caller caller(HttpServletRequest request) {
        return (Caller) request.getAttribute(CALLER_ATTRIBUTE);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || (path.equals(OWNER_ONBOARDING) && "POST".equals(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7) {
            reject(response, "a bearer token is required");
            return;
        }
        try {
            request.setAttribute(CALLER_ATTRIBUTE, verifier.verify(header.substring(7), Instant.now()));
        } catch (Rs256Verifier.InvalidTokenException e) {
            reject(response, "the token is invalid or has expired");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(ApiError.UNAUTHORIZED, message));
    }
}
