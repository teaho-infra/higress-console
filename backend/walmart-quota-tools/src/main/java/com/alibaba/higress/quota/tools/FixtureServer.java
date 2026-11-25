package com.alibaba.higress.quota.tools;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import static com.alibaba.higress.quota.tools.QuotaTools.*;

final class FixtureServer {
    static HttpServer startMock(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        s.createContext("/", e -> {
            try {
                if (e.getRequestMethod().equals("GET")) {
                    reply(e, 200, "text/plain", bytes("quota mock ready"));
                    return;
                }
                if (!e.getRequestMethod().equals("POST")) {
                    reply(e, 405, "text/plain", bytes("Method not allowed"));
                    return;
                }
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = e.getRequestBody().read(b)) != -1) {
                    raw.write(b, 0, n);
                    if (raw.size() > 1048576) {
                        reply(e, 413, "text/plain", bytes("Body too large"));
                        return;
                    }
                }
                JsonNode request = raw.size() == 0 ? obj() : JSON.readTree(raw.toByteArray());
                if (!request.isObject()) {
                    reply(e, 400, "text/plain", bytes("Invalid JSON object"));
                    return;
                }
                try {
                    Thread.sleep(Math.min(1500, Math.max(0, request.path("demo_delay_ms").asLong())));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                boolean stream = request.path("stream").asBoolean();
                ObjectNode data = obj("id", "mock-quota", "object",
                        stream ? "chat.completion.chunk" : "chat.completion", "model",
                        request.path("model").asText("demo-model"), "choices",
                        stream ? arr() : arr(obj("index", 0, "message",
                                obj("role", "assistant", "content", "Local mock response"), "finish_reason", "stop")),
                        "usage", obj("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30));
                e.getResponseHeaders().set("x-mock-consumer", e.getRequestHeaders().getFirst("x-mse-consumer") == null
                        ? "" : e.getRequestHeaders().getFirst("x-mse-consumer"));
                e.getResponseHeaders().set("x-mock-credentials-leaked",
                        Boolean.toString(e.getRequestHeaders().getFirst("x-api-key") != null));
                reply(e, 200, stream ? "text/event-stream" : "application/json",
                        stream ? bytes("data: " + JSON.writeValueAsString(data) + "\n\ndata: [DONE]\n\n")
                                : JSON.writeValueAsBytes(data));
            } catch (Exception ex) {
                reply(e, 400, "text/plain", bytes("Invalid request"));
            } finally {
                e.close();
            }
        });
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        s.start();
        return s;
    }

    static HttpServer startStatic(int port, Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        s.createContext("/", e -> {
            try {
                if (!e.getRequestMethod().equals("GET") && !e.getRequestMethod().equals("HEAD")) {
                    reply(e, 405, "text/plain", bytes("Method not allowed"));
                    return;
                }
                Path p = root.resolve(e.getRequestURI().getPath().substring(1)).normalize();
                if (!p.startsWith(root) || !Files.isRegularFile(p) || !p.toRealPath().startsWith(realRoot)) {
                    reply(e, 404, "text/plain", bytes("Not found"));
                    return;
                }
                e.getResponseHeaders().set("Content-Type", "application/wasm");
                if (e.getRequestMethod().equals("HEAD")) {
                    e.getResponseHeaders().set("Content-Length", Long.toString(Files.size(p)));
                    e.sendResponseHeaders(200, -1);
                } else {
                    e.sendResponseHeaders(200, Files.size(p));
                    Files.copy(p, e.getResponseBody());
                }
            } finally {
                e.close();
            }
        });
        s.start();
        return s;
    }

    static void reply(HttpExchange e, int code, String type, byte[] b) throws IOException {
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(code, b.length);
        e.getResponseBody().write(b);
    }
}
