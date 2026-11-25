package io.higress.custom.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class QuotaApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private QuotaApi filter;
    private HttpServer upstream;
    private String cookie, csrf;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> host = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"ok\":true,\"accounts\":[]}";
    private MutableClock clock;

    @BeforeEach
    void setup() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", e -> {
            key.set(e.getRequestHeaders().getFirst("x-api-key"));
            host.set(e.getRequestHeaders().getFirst("Host"));
            body.set(new String(QuotaApi.readLimited(e.getRequestBody(), 32768), StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Set-Cookie", "upstream-secret=bad");
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status, out.length);
            e.getResponseBody().write(out);
            e.close();
        });
        upstream.start();
        clock = new MutableClock();
        filter = new QuotaApi(new QuotaSettings("test-password-long", "SECRET_ADMIN",
                "http://127.0.0.1:" + upstream.getAddress().getPort(), "quota-portal.local", false), clock);
    }

    @AfterEach
    void stop() throws Exception {
        filter.close();
        upstream.stop(0);
    }

    MockHttpServletResponse call(String method, String path, String data, boolean authenticated, String origin,
            String token) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServerName("localhost");
        r.setServerPort(18890);
        r.addHeader("Host", "localhost:18890");
        if (origin != null)
            r.addHeader("Origin", origin);
        if (authenticated)
            r.addHeader("Cookie", cookie);
        if (token != null)
            r.addHeader("x-portal-csrf", token);
        r.setContentType("application/json");
        if (data != null)
            r.setContent(data.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse s = new MockHttpServletResponse();
        filter.doFilter(r, s, new MockFilterChain());
        return s;
    }

    MockHttpServletResponse call(String method, String path, String data) throws Exception {
        return call(method, path, data, true, "http://localhost:18890", csrf);
    }

    void login() throws Exception {
        MockHttpServletResponse s = call("POST", "/api/login", "{\"password\":\"test-password-long\"}", false,
                "http://localhost:18890", null);
        assertEquals(200, s.getStatus());
        cookie = s.getHeader("Set-Cookie").split(";")[0];
        csrf = json.readTree(s.getContentAsString()).path("csrf").asText();
        assertTrue(s.getHeader("Set-Cookie").contains("HttpOnly"));
        assertTrue(s.getHeader("Set-Cookie").contains("SameSite=Strict"));
    }

    @Test
    void unauthorizedDoesNotCallGateway() throws Exception {
        assertEquals(401, call("GET", "/api/quota/meta", null, false, null, null).getStatus());
        assertNull(key.get());
    }

    @Test
    void wrongPasswordAndCrossOriginRejected() throws Exception {
        assertEquals(401, call("POST", "/api/login", "{\"password\":\"wrong\"}", false, "http://localhost:18890", null)
                .getStatus());
        assertEquals(403, call("POST", "/api/login", "{}", false, "https://evil.test", null).getStatus());
    }

    @Test
    void authenticatedReadUsesFixedCredentials() throws Exception {
        login();
        MockHttpServletResponse s = call("GET", "/api/quota/meta", null);
        assertEquals(200, s.getStatus());
        assertEquals("SECRET_ADMIN", key.get());
        assertEquals("quota-portal.local", host.get());
        assertNull(s.getHeader("Set-Cookie"));
        assertFalse(s.getContentAsString().contains("SECRET_ADMIN"));
    }

    @Test
    void mutationRequiresCsrfAndOrigin() throws Exception {
        login();
        assertEquals(403, call("POST", "/api/quota/assign", "{}", true, "http://localhost:18890", null).getStatus());
        assertEquals(403, call("POST", "/api/quota/assign", "{}", true, null, csrf).getStatus());
    }

    @Test
    void preservesPricesAndStripsOperator() throws Exception {
        login();
        assertEquals(200, call("POST", "/api/quota/prices",
                "{\"prices\":{\"m\":{\"input_price_micros\":1234567}},\"operator\":\"forged\",\"ticket_id\":\"T1\"}")
                        .getStatus());
        JsonNode n = json.readTree(body.get());
        assertFalse(n.has("operator"));
        assertEquals(1234567, n.path("prices").path("m").path("input_price_micros").asInt());
    }

    @Test
    void unknownPathsAndMethodsRejected() throws Exception {
        login();
        assertEquals(404, call("GET", "/api/quota/admin", null).getStatus());
        assertEquals(405, call("DELETE", "/api/quota/accounts", null).getStatus());
    }

    @Test
    void invalidAndOversizedBodiesRejected() throws Exception {
        login();
        assertEquals(400, call("POST", "/api/quota/assign", "[]").getStatus());
        assertEquals(400, call("POST", "/api/quota/assign", "bad json").getStatus());
        assertEquals(413, call("POST", "/api/quota/assign", String.join("", java.util.Collections.nCopies(33000, "x")))
                .getStatus());
    }

    @Test
    void upstreamFailuresHaveSafeErrors() throws Exception {
        login();
        status = 429;
        assertEquals(429, call("GET", "/api/quota/meta", null).getStatus());
        answer = "not JSON";
        assertEquals(502, call("GET", "/api/quota/meta", null).getStatus());
        upstream.stop(0);
        assertEquals(502, call("GET", "/api/quota/meta", null).getStatus());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        login();
        assertEquals(200, call("POST", "/api/logout", null).getStatus());
        assertEquals(401, call("GET", "/api/session", null).getStatus());
    }

    @Test
    void sessionExpires() throws Exception {
        login();
        clock.now += 8 * 3600000L + 1;
        assertEquals(401, call("GET", "/api/session", null).getStatus());
    }

    @Test
    void loginAttemptsRateLimitedAndExpire() throws Exception {
        for (int i = 0; i < 10; i++)
            assertEquals(401, call("POST", "/api/login", "{}", false, "http://localhost:18890", null).getStatus());
        assertEquals(429, call("POST", "/api/login", "{}", false, "http://localhost:18890", null).getStatus());
        clock.now += 60001;
        login();
    }

    @Test
    void trailingJsonRejected() throws Exception {
        login();
        assertEquals(400, call("POST", "/api/quota/assign", "{}{}").getStatus());
        answer = "{}garbage";
        assertEquals(502, call("GET", "/api/quota/meta", null).getStatus());
    }

    @Test
    void queryContractPreservesEmptySegmentsAndLiteralPercent() throws Exception {
        login();
        for (String q : new String[] { "", "dimension=user&&subject=x", "subject=%" }) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/quota/accounts");
            r.setQueryString(q);
            r.addHeader("Cookie", cookie);
            MockHttpServletResponse out = new MockHttpServletResponse();
            filter.doFilter(r, out, new MockFilterChain());
            assertEquals(200, out.getStatus(), q);
        }
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/quota/accounts");
        r.setQueryString("redirect=https://evil.test");
        r.addHeader("Cookie", cookie);
        MockHttpServletResponse out = new MockHttpServletResponse();
        filter.doFilter(r, out, new MockFilterChain());
        assertEquals(400, out.getStatus());
    }

    @Test
    void standaloneStaticSecurityHeaders() throws Exception {
        MockHttpServletResponse out = new MockHttpServletResponse();
        new QuotaApplication().standaloneHeaders().getFilter().doFilter(new MockHttpServletRequest("GET", "/"), out,
                new MockFilterChain());
        assertEquals("DENY", out.getHeader("X-Frame-Options"));
        assertEquals("nosniff", out.getHeader("X-Content-Type-Options"));
        assertEquals("same-origin", out.getHeader("Referrer-Policy"));
        assertTrue(out.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
    }

    @Test
    void invalidConfigFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuotaSettings("short", "key", "http://localhost", "host", false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuotaSettings("long-password", "key", "http://user:pass@localhost", "host", false));
    }

    static class MutableClock extends Clock {
        long now = 1700000000000L;

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
