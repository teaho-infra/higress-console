package io.higress.custom.quota;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.*;

/** Exact Portal HTTP contract, isolated from Console controller response wrapping. */
public final class QuotaApi implements Filter, AutoCloseable {
    private static final Set<String> READ = new HashSet<>(
            Arrays.asList("", "meta", "accounts", "audit", "usage", "prices"));
    private static final Set<String> WRITE = new HashSet<>(
            Arrays.asList("assign", "delta", "refresh", "tiers", "prices", "cycle", "reset"));
    private static final Set<String> QUERY = new HashSet<>(
            Arrays.asList("dimension", "subject", "app", "limit", "cursor", "offset", "period"));
    private static final Pattern COOKIE = Pattern.compile("(?:^|;\\s*)portal_session=([a-f0-9]{64})(?:;|$)");
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final QuotaSettings settings;
    private final QuotaSessions sessions;
    private final QuotaGateway gateway;
    private final java.util.function.Predicate<HttpServletRequest> consoleAuth;

    public QuotaApi(QuotaSettings settings, Clock clock) {
        this(settings, clock, null);
    }

    public QuotaApi(QuotaSettings settings, Clock clock, java.util.function.Predicate<HttpServletRequest> consoleAuth) {
        this.consoleAuth = consoleAuth;
        this.settings = settings;
        sessions = new QuotaSessions(clock);
        gateway = new QuotaGateway(settings);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!(path.equals("/api/login") || path.equals("/api/session") || path.equals("/api/logout")
                || path.startsWith("/api/quota/"))) {
            chain.doFilter(request, response);
            return;
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "same-origin");
        try {
            handle(req, res, path);
        } catch (ApiFailure e) {
            send(res, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(res, 500, error("服务处理失败，请查看服务端调试记录"));
        }
    }

    private void handle(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
        String method = req.getMethod();
        boolean mutation = !method.equals("GET") && !method.equals("HEAD");
        String expected = (settings.secureCookie ? "https" : "http") + "://" + req.getHeader("Host");
        if (mutation && !expected.equals(req.getHeader("Origin")))
            throw new ApiFailure(403, "请求来源不匹配，请刷新页面后重试");
        if (consoleAuth != null) {
            if (!consoleAuth.test(req))
                throw new ApiFailure(401, "请先登录 Console");
            if (path.equals("/api/login") || path.equals("/api/logout"))
                throw new ApiFailure(404, "请使用 Console 登录和退出");
            javax.servlet.http.HttpSession session = req.getSession();
            String csrf;
            synchronized (session) {
                csrf = (String) session.getAttribute("walmart.quota.csrf");
                if (csrf == null) {
                    csrf = java.util.UUID.randomUUID().toString();
                    session.setAttribute("walmart.quota.csrf", csrf);
                }
            }
            if (mutation && !QuotaSessions.same(req.getHeader("x-portal-csrf"), csrf))
                throw new ApiFailure(403, "会话校验失败，请刷新页面");
            if (path.equals("/api/session") && method.equals("GET")) {
                send(res, 200, json.createObjectNode().put("ok", true).put("csrf", csrf).put("auth", "console"));
                return;
            }
        } else {
            if (path.equals("/api/login") && method.equals("POST")) {
                JsonNode data = body(req);
                JsonNode pw = data.get("password");
                QuotaSessions.Session s = sessions.login(req.getRemoteAddr(),
                        pw != null && pw.isTextual() ? pw.textValue() : null, settings.password);
                res.setHeader("Set-Cookie",
                        "portal_session=" + s.id + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800"
                                + (settings.secureCookie ? "; Secure" : ""));
                send(res, 200, sessionBody(s));
                return;
            }
            Matcher matcher = COOKIE.matcher(Optional.ofNullable(req.getHeader("Cookie")).orElse(""));
            QuotaSessions.Session s = sessions.get(matcher.find() ? matcher.group(1) : null);
            if (mutation && !QuotaSessions.same(req.getHeader("x-portal-csrf"), s.csrf))
                throw new ApiFailure(403, "会话校验失败，请重新登录");
            if (path.equals("/api/session") && method.equals("GET")) {
                send(res, 200, sessionBody(s));
                return;
            }
            if (path.equals("/api/logout") && method.equals("POST")) {
                sessions.remove(s.id);
                res.setHeader("Set-Cookie", "portal_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"
                        + (settings.secureCookie ? "; Secure" : ""));
                send(res, 200, json.createObjectNode().put("ok", true));
                return;
            }
        }
        if (!path.startsWith("/api/quota/"))
            throw new ApiFailure(404, "接口不存在");
        String action = path.substring("/api/quota/".length());
        if (!READ.contains(action) && !WRITE.contains(action))
            throw new ApiFailure(404, "接口不存在");
        if (!(method.equals("GET") && READ.contains(action) || method.equals("POST") && WRITE.contains(action)))
            throw new ApiFailure(405, "请求方法不支持");
        byte[] payload = null;
        if (method.equals("POST")) {
            if (req.getContentType() == null || !req.getContentType().startsWith("application/json"))
                throw new ApiFailure(415, "请使用 application/json");
            ObjectNode data = body(req);
            data.remove("operator");
            payload = json.writeValueAsBytes(data);
        }
        String query = req.getQueryString();
        if (query != null) {
            StringBuilder normalized = new StringBuilder();
            for (String part : query.split("&")) {
                if (part.isEmpty())
                    continue;
                String[] pair = part.split("=", 2);
                String name = decodeQuery(pair[0]);
                if (!QUERY.contains(name))
                    throw new ApiFailure(400, "查询参数不支持");
                if (normalized.length() > 0)
                    normalized.append('&');
                normalized.append(java.net.URLEncoder.encode(name, "UTF-8")).append('=')
                        .append(java.net.URLEncoder.encode(decodeQuery(pair.length == 2 ? pair[1] : ""), "UTF-8"));
            }
            query = normalized.length() == 0 ? null : normalized.toString();
        }
        QuotaGateway.Result upstream;
        try {
            upstream = gateway.call(method, "/v1/chat/completions/quota" + (action.isEmpty() ? "" : "/" + action)
                    + (query == null ? "" : "?" + query), payload);
        } catch (Exception e) {
            throw new ApiFailure(502, "无法连接额度网关，请检查网关地址、端口转发和服务状态");
        }
        JsonNode result;
        try {
            result = json.readTree(upstream.body);
            if (result == null)
                throw new IOException();
        } catch (IOException e) {
            throw new ApiFailure(502, "额度网关返回非 JSON（HTTP " + upstream.status + "），请检查 Host、路由与管理插件");
        }
        send(res, upstream.status, result);
    }

    private String decodeQuery(String value) throws java.io.UnsupportedEncodingException {
        // Match URLSearchParams: a literal malformed percent is encoded as data, not a URI delimiter.
        return URLDecoder.decode(value.replaceAll("%(?![0-9a-fA-F]{2})", "%25"), "UTF-8");
    }

    private ObjectNode body(HttpServletRequest req) throws IOException {
        byte[] bytes = readLimited(req.getInputStream(), 32768);
        JsonNode data;
        try {
            data = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
        } catch (IOException e) {
            throw new ApiFailure(400, "请求体必须为 JSON");
        }
        if (data == null || !data.isObject())
            throw new ApiFailure(400, "请求体必须为对象");
        return (ObjectNode) data;
    }

    static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit)
                throw new ApiFailure(413, "请求体过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private ObjectNode sessionBody(QuotaSessions.Session s) {
        return json.createObjectNode().put("ok", true).put("csrf", s.csrf).put("operator", "portal-admin");
    }

    private ObjectNode error(String message) {
        return json.createObjectNode().put("ok", false).put("message", message);
    }

    private void send(HttpServletResponse res, int status, JsonNode data) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), data);
    }

    @Override
    public void close() throws IOException {
        gateway.close();
    }
}
