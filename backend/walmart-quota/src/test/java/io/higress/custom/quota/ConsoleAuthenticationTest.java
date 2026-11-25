package io.higress.custom.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleAuthenticationTest {
    @Test
    void consoleSessionReplacesPasswordAndStillProtectsWrites() throws Exception {
        AtomicBoolean authenticated = new AtomicBoolean(false);
        QuotaSettings settings = new QuotaSettings(null, "admin-key", "http://127.0.0.1:1", "quota-portal.local", false,
                true);
        try (QuotaApi api = new QuotaApi(settings, Clock.systemUTC(), req -> authenticated.get())) {
            MockHttpSession session = new MockHttpSession();
            assertEquals(401, call(api, session, "GET", "/api/session", null).getStatus());
            assertEquals(401, call(api, session, "GET", "/api/quota/meta", null).getStatus());
            authenticated.set(true);
            MockHttpServletResponse result = call(api, session, "GET", "/api/session", null);
            assertEquals(200, result.getStatus());
            String csrf = new ObjectMapper().readTree(result.getContentAsString()).get("csrf").asText();
            assertFalse(csrf.isEmpty());
            assertNull(result.getHeader("Set-Cookie"));
            assertEquals(404, call(api, session, "POST", "/api/login", csrf).getStatus());
            assertEquals(404, call(api, session, "POST", "/api/logout", csrf).getStatus());
            assertEquals(403, call(api, session, "POST", "/api/quota/assign", null).getStatus());
            assertEquals(403, call(api, new MockHttpSession(), "POST", "/api/quota/assign", csrf).getStatus());
            // Valid CSRF reaches action validation, without requiring a Portal password/cookie.
            assertEquals(404, call(api, session, "POST", "/api/quota/unknown", csrf).getStatus());
            authenticated.set(false);
            assertEquals(401, call(api, session, "GET", "/api/session", csrf).getStatus());
            assertEquals(401, call(api, session, "POST", "/api/quota/assign", csrf).getStatus());
        }
    }

    private MockHttpServletResponse call(QuotaApi api, MockHttpSession session, String method, String path, String csrf)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setSession(session);
        req.addHeader("Host", "localhost");
        req.addHeader("Origin", "http://localhost");
        if (csrf != null)
            req.addHeader("x-portal-csrf", csrf);
        MockHttpServletResponse response = new MockHttpServletResponse();
        api.doFilter(req, response, (a, b) -> fail("Quota request escaped filter"));
        return response;
    }
}
