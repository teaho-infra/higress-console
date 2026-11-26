package com.alibaba.higress.quota.tools;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.alibaba.higress.quota.tools.QuotaTools.*;

/** Explicitly invoked integration checks against the isolated free model fixture only. */
final class Verification {
    static final String BASE = "/v1/chat/completions", ADMIN = BASE + "/quota";
    final QuotaTools t;
    final ObjectNode credentials, report = obj("verified_at", Instant.now().toString(), "host", HOST, "checks", arr(),
            "responses", arr(), "passed", false);
    String period;
    final String suffix = UUID.randomUUID().toString().substring(0, 8);

    Verification(QuotaTools t) throws IOException {
        this.t = t;
        credentials = (ObjectNode) JSON.readTree(t.out.resolve("credentials.json").toFile());
    }

    static class Response {
        int code;
        JsonNode body;
        String text;
        Map<String, List<String>> headers;

        String header(String key) {
            for (Map.Entry<String, List<String>> e : headers.entrySet())
                if (key.equalsIgnoreCase(e.getKey()))
                    return e.getValue().get(0);
            return "";
        }
    }

    Response call(String path, String method, JsonNode data, String key, String user, String spoof) throws Exception {
        // Restricted headers must be enabled before HttpURLConnection initialization.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:18080" + path).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("Host", HOST);
        c.setRequestProperty("Content-Type", "application/json");
        if (key != null)
            c.setRequestProperty("x-api-key", credentials.path(key).asText(key));
        if (user != null)
            c.setRequestProperty("x-codebuddy-user-id", user);
        if (spoof != null)
            c.setRequestProperty("x-mse-consumer", spoof);
        if (data != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(JSON.writeValueAsBytes(data));
            }
        }
        Response r = new Response();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1)
                b.write(buf, 0, n);
            in.close();
        }
        r.text = new String(b.toByteArray(), StandardCharsets.UTF_8);
        try {
            r.body = JSON.readTree(r.text);
        } catch (IOException e) {
            r.body = JSON.getNodeFactory().textNode(r.text);
        }
        if (r.body == null)
            r.body = NullNode.instance;
        c.disconnect();
        synchronized (report) {
            ((ArrayNode) report.path("responses"))
                    .add(obj("path", path.replaceAll("x-api-key=[^&]*", "x-api-key=REDACTED"), "method", method,
                            "status", r.code, "body", r.body));
        }
        return r;
    }

    Response get(String path) throws Exception {
        return call(path, "GET", null, "admin", null, null);
    }

    Response mutate(String action, Object... fields) throws Exception {
        ObjectNode data = obj(fields);
        if (!data.has("period"))
            data.put("period", period);
        if (!data.has("ticket_id"))
            data.put("ticket_id", "verify-" + UUID.randomUUID());
        return call(ADMIN + "/" + action, "POST", data, "admin", null, null);
    }

    Response model(String user) throws Exception {
        return model(user, "demo-model", "app", false, false, null);
    }

    Response model(String user, String model, String key, boolean stream, boolean limited, String spoof,
            Object... extra) throws Exception {
        ObjectNode data = obj("model", model, "messages", arr(obj("role", "user", "content", "local fixture")),
                "stream", stream, "stream_options", obj("include_usage", true));
        data.setAll(obj(extra));
        return call((limited ? "/limited" : "") + BASE, "POST", data, key, user, spoof);
    }

    JsonNode account(String dimension, String subject) throws Exception {
        Response r = get(ADMIN + "/?dimension=" + dimension + "&subject=" + URLEncoder.encode(subject, "UTF-8"));
        check("Read " + dimension + " " + subject, r.code == 200);
        return r.body.has("account") ? r.body.path("account") : r.body;
    }

    JsonNode account(String subject) throws Exception {
        return account("user", subject);
    }

    void check(String name, boolean ok) {
        ((ArrayNode) report.path("checks")).add(obj("name", name, "passed", ok));
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok)
            throw new AssertionError(name);
    }

    long value(JsonNode n, String k) {
        return n.path(k).asLong();
    }

    boolean same(JsonNode a, JsonNode b, String k) {
        return a.path(k).equals(b.path(k));
    }

    void pause() throws InterruptedException {
        Thread.sleep(400);
    }

    JsonNode balance(String subject, long expected) throws Exception {
        for (int i = 0; i < 20; i++) {
            JsonNode n = account(subject);
            if (value(n, "balance") == expected)
                return n;
            Thread.sleep(100);
        }
        throw new AssertionError("Balance did not converge");
    }

    void assign(String dimension, String subject, String tier, Object... extra) throws Exception {
        ObjectNode fields = obj("dimension", dimension, "subject", subject, "tier", tier);
        fields.setAll(obj(extra));
        fields.put("period", period);
        fields.put("ticket_id", "verify-" + UUID.randomUUID());
        check("Assign " + dimension + " " + subject,
                call(ADMIN + "/assign", "POST", fields, "admin", null, null).code == 200);
    }

    void resetWindow() throws Exception {
        for (String k : kube("exec", "deploy/" + PREFIX, "-c", "redis", "--", "redis-cli", "--raw", "KEYS",
                "higress-token-ratelimit:{" + PREFIX + "}:*").split("\\R"))
            if (!k.isEmpty())
                kube("exec", "deploy/" + PREFIX, "-c", "redis", "--", "redis-cli", "DEL", k);
    }

    void outage(String user) throws Exception {
        kube("exec", "deploy/" + PREFIX, "-c", "redis", "--", "redis-cli", "CLIENT", "PAUSE", "3500", "ALL");
        check("Redis outage fails closed503", model(user).code == 503);
        Thread.sleep(4000);
        check("Redis recovery restores access", model(user).code == 200);
    }

    void run(String command) throws Exception {
        try {
            Response meta = get(ADMIN + "/meta");
            check("Admin meta", meta.code == 200);
            period = meta.body.path("period").asText();
            switch (command) {
            case "seed-money":
                seed(true);
                break;
            case "seed-legacy":
                seed(false);
                break;
            case "verify-money":
                money(meta.body);
                break;
            case "verify-gateway":
                gateway(meta.body);
                break;
            default:
                throw new IllegalArgumentException(command);
            }
            report.put("passed", true);
        } finally {
            Path wasm = t.root.resolve("../higress/plugins/wasm-go/extensions/ai-quota-walmart/plugin.wasm")
                    .normalize();
            if (Files.isRegularFile(wasm))
                report.put("wasm_sha256", sha256(wasm));
            String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            for (JsonNode secret : credentials)
                text = text.replace(secret.asText(), "REDACTED");
            Path file = t.root.resolve(command.equals("verify-money") ? "docs/walmart-quota/evidence-money/gateway-report.json"
                    : command.equals("verify-gateway") ? "docs/walmart-quota/evidence/cluster-report.json"
                            : "docs/walmart-quota/evidence/" + command + "-report.json");
            save(file, text + "\n");
        }
    }

    void seed(boolean money) throws Exception {
        if (money) {
            assign("department", "研发部", "L2");
            assign("department", "产品部", "L2");
        }
        String[] users = { "user-alice", "user-bob", "user-carol" },
                tiers = money ? new String[] { "L1", "inherit", "inherit" } : new String[] { "L2", "L1", "L2" };
        for (int i = 0; i < users.length; i++) {
            if (money)
                assign("user", users[i], tiers[i], "app", i == 2 ? "second-app" : "demo-app", "department",
                        i == 2 ? "产品部" : "研发部");
            else
                assign("user", users[i], tiers[i], "app", i == 2 ? "second-app" : "demo-app");
            for (int j = 0; j < (money ? 2 : 3); j++)
                check("Seed actual mock consumption", model(users[i], money && j == 1 ? "demo-model-pro" : "demo-model",
                        i == 2 ? "second_app" : "app", !money && j == 2, false, null).code == 200);
        }
        pause();
    }

    void gateway(JsonNode meta) throws Exception {
        resetWindow();
        check("Missing key rejected", call(BASE, "GET", null, null, null, null).code == 401);
        check("Spoofed admin without key rejected",
                call(ADMIN + "/meta", "GET", null, null, null, "quota-admin").code == 401);
        check("Query string credentials disabled",
                call(BASE + "?x-api-key=" + URLEncoder.encode(credentials.path("app").asText(), "UTF-8"), "GET", null,
                        null, null, null).code == 401);
        check("App cannot administer with spoofed consumer",
                call(ADMIN + "/meta", "GET", null, "app", null, "quota-admin").code == 403);
        check("Two supported tiers", meta.path("tiers").size() == 2 && !meta.path("tiers").has("L3"));
        String alice = "verify-user-" + suffix, bob = "verify-low-" + suffix;
        assign("app", "demo-app", "L1");
        assign("app", "second-app", "L2");
        assign("user", alice, "L2", "app", "demo-app");
        assign("user", bob, "L1", "app", "demo-app");
        check("GET action override rejected",
                get(ADMIN + "?dimension=user&subject=" + alice + "&action=settle").code == 400);
        int settle = call(ADMIN + "/settle", "POST",
                obj("dimension", "user", "subject", alice, "value", -100, "period", period), "admin", null, null).code;
        check("Internal settle endpoint inaccessible", settle == 404 || settle == 405);
        JsonNode guarded = account(alice);
        for (String field : new String[] { "app", "period_start_unix" }) {
            String ticket = "bad-field-" + field + suffix;
            check("Reject reserved or invalid " + field, mutate("delta", "dimension", "user", "subject", alice, "value",
                    1, "ticket_id", ticket, field, field.equals("app") ? obj() : 0).code == 400);
            check("Rejected field leaves balance unchanged", same(account(alice), guarded, "balance"));
            check("Rejected field does not consume ticket", mutate("delta", "dimension", "user", "subject", alice,
                    "value", 0, "ticket_id", ticket).code == 200);
        }
        check("Malformed assign app rejected",
                mutate("assign", "dimension", "user", "subject", alice, "tier", "L2", "app", obj()).code == 400);
        check("Out of range delta rejected",
                mutate("delta", "dimension", "user", "subject", alice, "value", 10000000000000L).code == 400);
        check("Out of range tier quota rejected",
                mutate("tiers", "tiers",
                        obj("L1", obj("quota", 500000000, "models", arr("demo-model")), "L2",
                                obj("quota", 10000000000000L, "models", arr("demo-model")))).code == 400);
        check("Invalid mutations preserve balance", same(account(alice), guarded, "balance"));
        for (Object invalid : new Object[] { null, "", obj(), "not-allowed-model" }) {
            ObjectNode data = obj("messages", arr(obj("role", "user", "content", "blocked fixture")));
            if (invalid != null)
                data.set("model", JSON.valueToTree(invalid));
            int status = call(BASE, "POST", data, "app", alice, null).code;
            check("Model allowlist rejects invalid model", status == 400 || status == 403);
        }
        check("Rejected models preserve usage", same(account(alice), guarded, "usage"));
        JsonNode ba = account("app", "demo-app"), bu = account(alice);
        Response r = model(alice, "demo-model", "app", false, false, "quota-admin");
        check("Authenticated consumer wins", r.code == 200 && r.header("x-mock-consumer").equals("demo-app"));
        check("Credential stripped upstream", r.header("x-mock-credentials-leaked").equals("false"));
        pause();
        JsonNode au = account(alice), aa = account("app", "demo-app");
        check("Both usage counters advance",
                value(au, "usage") == value(bu, "usage") + 30 && value(aa, "usage") == value(ba, "usage") + 30);
        check("Higher user tier spends user balance",
                value(au, "balance") == value(bu, "balance") - 30 && same(aa, ba, "balance"));
        ba = account("app", "second-app");
        bu = account(bob);
        check("Higher app tier request", model(bob, "demo-model", "second_app", false, false, null).code == 200);
        pause();
        aa = account("app", "second-app");
        au = account(bob);
        check("Higher app tier spends app balance",
                value(aa, "balance") == value(ba, "balance") - 30 && same(au, bu, "balance"));
        JsonNode post = account(alice);
        check("Temporary tier action removed", mutate("tier", "dimension", "user", "subject", alice, "tier", "L2").code == 404);
        check("Wrong period rejected", mutate("delta", "dimension", "user", "subject", alice, "value", 10, "period",
                "2026-01-05").code == 409);
        check("Refresh accepted", mutate("refresh", "dimension", "user", "subject", alice).code == 200);
        check("Refresh no regrant", same(account(alice), post, "balance"));
        r = model(alice, "demo-model", "app", true, false, null);
        check("SSE mock returns usage",
                r.code == 200 && r.text.matches("(?s).*\"total_tokens\"\\s*:\\s*30.*") && r.text.contains("[DONE]"));
        pause();
        check("SSE usage accounted", value(account(alice), "usage") == value(post, "usage") + 30);
        long current = value(account(alice), "balance");
        check("Drain balance", mutate("delta", "dimension", "user", "subject", alice, "value", -current).code == 200);
        check("Exhausted balance403", model(alice).code == 403);
        check("Restore balance", mutate("delta", "dimension", "user", "subject", alice, "value", current).code == 200);
        String one = "window-one-" + suffix, two = "window-two-" + suffix;
        check("First user window request", model(one, "demo-model", "app", false, true, null).code == 200);
        pause();
        check("Second user window request", model(one, "demo-model", "app", false, true, null).code == 200);
        pause();
        check("User window429", model(one, "demo-model", "app", false, true, null).code == 429);
        check("Independent user request", model(two, "demo-model", "app", false, true, null).code == 200);
        pause();
        check("Aggregate app window429",
                model("window-three-" + suffix, "demo-model", "app", false, true, null).code == 429);
        r = get(ADMIN + "/audit");
        check("Audit records available", r.code == 200 && r.body.path("records").size() > 0);
        outage(alice);
    }

    long cost(JsonNode prices, String model) {
        JsonNode p = prices.path(model);
        return (10 * value(p, "input_price_micros") + 20 * value(p, "output_price_micros") + 999999) / 1000000;
    }

    void money(JsonNode meta) throws Exception {
        check("Money policy enabled", meta.path("budget_policy").asText().equals("personal_department_money"));
        check("Currency and integer unit",
                meta.path("currency").asText().equals("CNY") && meta.path("amount_unit").asText().equals("micro_yuan"));
        JsonNode prices = meta.path("prices"), tiers = meta.path("tiers");
        long l1 = value(tiers.path("L1"), "quota"), l2 = value(tiers.path("L2"), "quota");
        check("Configured budget defaults", l1 == 500000000L && l2 == 1500000000L);
        String dept = "finance-" + suffix, alice = "money-personal-" + suffix, bob = "money-inherit-a-" + suffix,
                carol = "money-inherit-b-" + suffix;
        check("No API key denied", call(ADMIN + "/meta", "GET", null, null, null, null).code == 401);
        check("Forged consumer cannot administer",
                call(ADMIN + "/meta", "GET", null, "app", null, "quota-admin").code == 403);
        check("Missing trusted user denied", model(null).code == 401);
        check("Unconfigured user denied", model("unconfigured-" + suffix).code == 403);
        String unconfigured = "money-unconfigured-" + suffix;
        check("Unconfigured addition cannot provision identity",
                mutate("delta", "dimension", "user", "subject", unconfigured, "value", 1000000).code == 200
                        && model(unconfigured).code == 403);
        check("Temporary tier action removed",
                mutate("tier", "dimension", "user", "subject", unconfigured, "tier", "L2").code == 404);
        check("Missing department cannot be inherited", mutate("assign", "dimension", "user", "subject",
                "bad-dept-" + suffix, "tier", "inherit", "department", "no-such-dept-" + suffix).code == 400);
        check("App cannot change prices",
                call(ADMIN + "/prices", "POST", obj("prices", obj(), "period", period, "ticket_id", "denied-" + suffix),
                        "app", null, null).code == 403);
        assign("department", dept, "L2");
        assign("user", alice, "L1", "department", dept);
        assign("user", bob, "inherit", "department", dept);
        assign("user", carol, "inherit", "department", dept);
        JsonNode a = account(alice), b = account(bob), c = account(carol);
        check("Personal L1 overrides department L2", a.path("tier").asText().equals("L1") && value(a, "limit") == l1
                && a.path("quota_source").asText().equals("personal"));
        check("Unassigned personal tier inherits department", b.path("tier").asText().equals("L2")
                && value(b, "limit") == l2 && b.path("quota_source").asText().equals("department"));
        check("Each inherited user has full independent budget",
                value(b, "balance") == l2 && value(c, "balance") == l2);
        check("Department has no shared balance", !account("department", dept).has("balance"));
        Response r = model(bob);
        check("Priced model succeeds", r.code == 200);
        check("Application key stripped upstream", r.header("x-mock-credentials-leaked").equals("false"));
        long cheap = cost(prices, "demo-model"), expensive = cost(prices, "demo-model-pro");
        b = balance(bob, l2 - cheap);
        check("Input/output prices charge exact amount", value(b, "spent") == cheap && value(b, "usage") == 30
                && value(b, "input_tokens") == 10 && value(b, "output_tokens") == 20);
        check("Other member balance untouched", value(account(carol), "balance") == l2);
        check("Personal requester always own payer", b.path("effective_dimension").asText().equals("user")
                && b.path("effective_subject").asText().equals(bob));
        long before = value(b, "balance");
        check("Second model succeeds", model(bob, "demo-model-pro", "app", false, false, null).code == 200);
        b = balance(bob, before - expensive);
        check("Different model rate changes charge", expensive != cheap && value(b, "spent") == cheap + expensive);
        before = value(b, "balance");
        check("SSE request succeeds", model(bob, "demo-model", "app", true, false, null).code == 200);
        b = balance(bob, before - cheap);
        check("SSE charges once", value(b, "usage") == 90 && value(b, "spent") == cheap * 2 + expensive);
        before = value(b, "balance");
        check("Unpriced model denied", model(bob, "missing-price", "app", false, false, null).code == 403);
        check("Missing price leaves balance intact", value(account(bob), "balance") == before);
        String ticket = "money-delta-" + suffix;
        check("Add budget",
                mutate("delta", "dimension", "user", "subject", bob, "value", 123456, "ticket_id", ticket).code == 200);
        check("Repeat addition idempotent",
                mutate("delta", "dimension", "user", "subject", bob, "value", 123456, "ticket_id", ticket).code == 200
                        && value(account(bob), "balance") == before + 123456);
        check("Conflicting ticket rejected",
                mutate("delta", "dimension", "user", "subject", bob, "value", 1, "ticket_id", ticket).code == 409);
        check("Old period rejected",
                mutate("delta", "dimension", "user", "subject", bob, "value", 1, "period", "2026-01-05").code == 409);
        check("Department cannot receive pooled additions",
                mutate("delta", "dimension", "department", "subject", dept, "value", 1).code == 400);
        b = account(bob);
        assign("department", dept, "L2");
        check("Department change preserves existing initialized budget", same(account(bob), b, "limit"));
        String fresh = "money-new-" + suffix;
        assign("user", fresh, "inherit", "department", dept);
        check("New member receives updated department template", value(account(fresh), "limit") == l2);
        assign("user", bob, "L1", "department", dept);
        JsonNode after = account(bob);
        check("Personal reassignment preserves spend and addon",
                value(after, "limit") == l1 + 123456 && same(after, b, "spent"));
        assign("user", bob, "inherit", "department", dept);
        after = account(bob);
        check("Return to inheritance preserves spend/addon", value(after, "limit") == l2 + 123456
                && same(after, b, "spent") && after.path("quota_source").asText().equals("department"));
        check("Refresh does not refill budget", mutate("refresh", "dimension", "user", "subject", bob).code == 200
                && same(account(bob), after, "balance"));
        check("Zero personal balance can be configured", mutate("delta", "dimension", "user", "subject", alice, "value",
                -value(account(alice), "balance")).code == 200);
        check("Exhaustion403 instead of department/app fallback", model(alice).code == 403);
        check("Other member still works after sibling exhaustion", model(carol).code == 200);
        int status = call(ADMIN + "/settle", "POST", obj("value", -1), "admin", null, null).code;
        check("Internal settlement route unavailable", status == 404 || status == 405);
        for (String field : new String[] { "department", "cost_micros", "payer_dimension", "period_start_unix" }) {
            before = value(account(bob), "balance");
            Object val = field.equals("department") ? obj()
                    : field.equals("payer_dimension") ? "app" : field.equals("cost_micros") ? -1 : 0;
            check("Reject forged field " + field,
                    mutate("delta", "dimension", "user", "subject", bob, "value", 1, field, val).code == 400);
            check("Rejected field leaves ledger unchanged " + field, value(account(bob), "balance") == before);
        }
        try {
            ObjectNode custom = prices.deepCopy();
            custom.set("rounding-model", obj("input_price_micros", 1, "output_price_micros", 1));
            custom.set("free-model", obj("input_price_micros", 0, "output_price_micros", 0));
            check("Price configuration saved", mutate("prices", "prices", custom).code == 200);
            JsonNode prior = account(bob);
            check("Sub-micro combined price request",
                    model(bob, "rounding-model", "app", false, false, null).code == 200);
            after = balance(bob, value(prior, "balance") - 1);
            check("Combined rounding is one micro not two", value(after, "spent") == value(prior, "spent") + 1);
            prior = after;
            check("Explicitly free model allowed with budget",
                    model(bob, "free-model", "app", false, false, null).code == 200);
            pause();
            after = account(bob);
            check("Free usage counted with zero charge",
                    value(after, "usage") == value(prior, "usage") + 30 && same(after, prior, "balance"));
            check("Exhausted account still blocked on free model",
                    model(alice, "free-model", "app", false, false, null).code == 403);
            ObjectNode changed = custom.deepCopy();
            changed.set("demo-model", obj("input_price_micros", 6000000, "output_price_micros", 12000000));
            prior = account(bob);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Response> pending = pool
                        .submit(() -> model(bob, "demo-model", "app", false, false, null, "demo_delay_ms", 1500));
                Thread.sleep(600);
                check("Reprice during in-flight request", mutate("prices", "prices", changed).code == 200);
                check("In-flight model request completed", pending.get(30, TimeUnit.SECONDS).code == 200);
            } finally {
                pool.shutdownNow();
            }
            after = balance(bob, value(prior, "balance") - cheap);
            check("In-flight request retains admission prices", value(after, "spent") == value(prior, "spent") + cheap);
            check("Next request uses changed price", model(bob).code == 200);
            after = balance(bob, value(after, "balance") - 300);
            check("Prior consumption not repriced", value(after, "spent") == value(prior, "spent") + cheap + 300);
            ObjectNode invalid = changed.deepCopy();
            invalid.set("demo-model", obj("input_price_micros", -1, "output_price_micros", 1));
            check("Negative price rejected", mutate("prices", "prices", invalid).code == 400);
            check("Invalid price leaves active prices intact",
                    get(ADMIN + "/prices").body.path("prices").path("demo-model").equals(changed.path("demo-model")));
        } finally {
            check("Restore original prices", mutate("prices", "prices", prices).code == 200);
        }
        resetWindow();
        String window = "money-window-" + suffix;
        assign("user", window, "inherit", "department", dept);
        check("First limited request", model(window, "demo-model", "app", false, true, null).code == 200);
        Thread.sleep(300);
        check("Second limited request", model(window, "demo-model", "app", false, true, null).code == 200);
        Thread.sleep(300);
        check("Token window remains429", model(window, "demo-model", "app", false, true, null).code == 429);
        r = get(ADMIN + "/audit?limit=100");
        boolean found = false;
        for (JsonNode row : r.body.path("records"))
            if (row.path("ticket_id").asText().equals(ticket))
                found = true;
        check("Money changes audited", r.code == 200 && found);
        outage(bob);
    }
}
