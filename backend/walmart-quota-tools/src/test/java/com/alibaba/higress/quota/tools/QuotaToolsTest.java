package com.alibaba.higress.quota.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.alibaba.higress.quota.tools.QuotaTools.*;

class QuotaToolsTest {
    @TempDir
    Path root;

    @Test
    void localStateLivesOutsideRemovedPortalDirectory() {
        QuotaTools t = new QuotaTools(root);
        assertEquals(root.resolve(".walmart-quota/.debug/cluster").toAbsolutePath(), t.out);
    }

    QuotaTools setup() throws Exception {
        Files.createDirectories(root.resolve("backend/walmart-quota-tools/config"));
        Path scripts = Paths.get("config").toAbsolutePath();
        if (!Files.exists(scripts))
            scripts = Paths.get("backend/walmart-quota-tools/config").toAbsolutePath();
        for (String f : new String[] { "money-config.json", "plugin-images.json" })
            Files.copy(scripts.resolve(f), root.resolve("backend/walmart-quota-tools/config/" + f));
        return new QuotaTools(root);
    }

    @Test
    void cleanInstallCreatesPrivateConfigAndReusesSecrets() throws Exception {
        QuotaTools t = setup();
        Path wasm = root.resolve("plugin.wasm");
        Files.write(wasm, bytes("fixture"));
        new Deployment(t).install(wasm, root.resolve("backend/walmart-quota-tools/config/money-config.json"), false);
        JsonNode manifest = JSON.readTree(t.out.resolve("custom.private.json").toFile()),
                cfg = manifest.path("spec").path("matchRules").path(0).path("config");
        assertEquals(750, manifest.path("spec").path("priority").asInt());
        assertEquals("wmt_portal_money_v2:", cfg.path("redis_key_prefix").asText());
        assertEquals(arr("user", "app"), cfg.path("dimensions"));
        assertFalse(cfg.path("redis").path("password").asText().isEmpty());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve(".walmart-quota/.env")));
        ObjectNode credentials = t.credentials();
        save(root.resolve(".walmart-quota/.env"), "PORTAL_PORT=19999\n");
        assertEquals(credentials, t.credentials());
        assertEquals("PORTAL_PORT=19999\n", new String(Files.readAllBytes(root.resolve(".walmart-quota/.env")), "UTF-8"));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(t.out));
    }

    @Test
    void liveInstallRejectsMissingAndMismatchedCredentialsBeforeMutation() throws Exception {
        QuotaTools t = setup();
        Path wasm = root.resolve("plugin.wasm");
        Files.write(wasm, bytes("fixture"));
        java.util.List<String> calls = new java.util.ArrayList<>();
        Deployment d = new Deployment(t, args -> {
            calls.add(String.join(" ", args));
            return obj("data",
                    obj("redis-password", java.util.Base64.getEncoder().encodeToString(bytes("other-password"))))
                            .toString();
        });
        assertThrows(IllegalStateException.class,
                () -> d.install(wasm, root.resolve("backend/walmart-quota-tools/config/money-config.json"), true));
        assertTrue(calls.isEmpty());
        assertFalse(Files.exists(t.out.resolve("credentials.json")));
        t.credentials();
        assertThrows(IllegalStateException.class,
                () -> d.install(wasm, root.resolve("backend/walmart-quota-tools/config/money-config.json"), true));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("get secret "));
        assertFalse(Files.exists(t.out.resolve("custom.private.json")));
    }

    @Test
    void migrationCreatesOnlySeparateJavaDeploymentAndService() throws Exception {
        QuotaTools t = setup();
        JsonNode manifest = new Deployment(t).fixtureManifest();
        assertEquals(2, manifest.path("items").size());
        String name = PREFIX + "-java-fixtures";
        for (JsonNode resource : manifest.path("items")) {
            assertEquals(name, resource.path("metadata").path("name").asText());
            assertEquals(name, resource.path("metadata").path("labels").path("app").asText());
        }
        JsonNode deployment = manifest.path("items").path(0);
        assertEquals(name,
                deployment.path("spec").path("template").path("metadata").path("labels").path("app").asText());
        assertEquals(2, deployment.path("spec").path("template").path("spec").path("containers").size());
        assertFalse(manifest.toString().contains("redis"));
    }

    @Test
    void bridgeMigrationPreservesOtherRegistriesAndPluginConfiguration() throws Exception {
        Deployment d = new Deployment(setup());
        ObjectNode unrelated = obj("name", "minimax", "type", "dns", "domain", "example.invalid", "port", 443,
                "protocol", "https");
        ObjectNode bridge = obj("spec", obj("registries", arr(unrelated), "other", "preserve"));
        ObjectNode updated = d.bridgeSpec(bridge);
        assertEquals(unrelated, updated.path("registries").path(0));
        assertEquals(d.redisRegistry(), updated.path("registries").path(1));
        assertEquals("preserve", updated.path("other").asText());
        assertEquals(1, bridge.path("spec").path("registries").size());
        assertEquals(updated, d.bridgeSpec(obj("spec", updated)));
        ObjectNode original = obj("spec",
                obj("priority", 750, "matchRules",
                        arr(obj("ingress", arr("route"), "config", obj("redis_key_prefix", "keep:", "redis",
                                obj("service_name", "old", "service_port", 6379, "password", "unchanged"))))));
        ObjectNode replacement = d.redisPluginSpec(original);
        assertEquals("walmart-quota-redis.dns",
                replacement.path("matchRules").path(0).path("config").path("redis").path("service_name").asText());
        ((ObjectNode) replacement.path("matchRules").path(0).path("config").path("redis")).put("service_name", "old");
        assertEquals(original.path("spec"), replacement);
    }

    @Test
    void installAfterMigrationUsesDedicatedRegistryAndJavaFixture() throws Exception {
        QuotaTools t = setup();
        ObjectNode c = t.credentials();
        Path wasm = root.resolve("plugin.wasm");
        Files.write(wasm, bytes("fixture"));
        Deployment helper = new Deployment(t);
        java.util.List<String> mutations = new java.util.ArrayList<>();
        Deployment d = new Deployment(t, args -> {
            if (args[0].equals("get")) {
                if (args[1].equals("secret"))
                    return obj("data",
                            obj("redis-password",
                                    java.util.Base64.getEncoder().encodeToString(bytes(c.path("redis").asText()))))
                                            .toString();
                if (args[1].equals("mcpbridge"))
                    return obj("spec", obj("registries", arr(helper.redisRegistry()))).toString();
                if (args[1].equals("pods")) {
                    assertEquals("app=" + PREFIX + "-java-fixtures", args[3]);
                    return obj("items", arr(obj("metadata", obj("name", "java-pod")))).toString();
                }
            }
            mutations.add(args[0]);
            return "";
        });
        d.install(wasm, root.resolve("backend/walmart-quota-tools/config/money-config.json"), true);
        JsonNode spec = JSON.readTree(t.out.resolve("custom.private.json").toFile()).path("spec");
        assertTrue(spec.path("url").asText().contains(PREFIX + "-java-fixtures."));
        assertEquals("walmart-quota-redis.dns",
                spec.path("matchRules").path(0).path("config").path("redis").path("service_name").asText());
        assertEquals(java.util.Arrays.asList("cp", "apply"), mutations);
    }

    @Test
    void pluginPatchUsesPrivateFileInsteadOfSecretProcessArguments() throws Exception {
        QuotaTools t = setup();
        t.credentials();
        final Path[] patchPath = { null };
        Deployment d = new Deployment(t, args -> {
            String command = String.join(" ", args);
            assertFalse(command.contains("private-password"));
            assertEquals("--patch-file", args[4]);
            patchPath[0] = Paths.get(args[5]);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(patchPath[0]));
            assertTrue(new String(Files.readAllBytes(patchPath[0]), "UTF-8").contains("private-password"));
            return "";
        });
        d.patchSpec("wasmplugin", "fixture", obj("spec", obj("password", "private-password")),
                obj("password", "private-password"));
        assertNotNull(patchPath[0]);
        assertFalse(Files.exists(patchPath[0]));
    }

    @Test
    void deploymentUsesJavaFixturesAndPreservesPinnedPlugins() throws Exception {
        QuotaTools t = setup();
        JsonNode manifest = new Deployment(t).manifest(t.credentials());
        String serialized = manifest.toString();
        assertFalse(serialized.contains("python"));
        assertTrue(serialized.contains("walmart-quota-tools:local"));
        assertTrue(serialized.contains("x-api-key,authorization"));
        assertTrue(serialized.contains("sha256:74474c68d29625b537908bfb5ad8a5f200616498a8957ae063ae9987bb540a52"));
        assertTrue(serialized.contains("FAIL_CLOSE"));
        assertEquals(9, manifest.path("items").size());
    }

    String request(int port, String path, String method, String body, boolean chunked) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            if (chunked)
                c.setChunkedStreamingMode(8);
            c.getOutputStream().write(bytes(body));
            c.getOutputStream().close();
        }
        assertEquals(200, c.getResponseCode());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) != -1)
                out.write(b, 0, n);
        }
        c.disconnect();
        return out.toString("UTF-8");
    }

    @Test
    void mockHandlesChunkedJsonAndSse() throws Exception {
        HttpServer s = FixtureServer.startMock(0);
        try {
            int port = s.getAddress().getPort();
            assertEquals("quota mock ready", request(port, "/", "GET", null, false));
            JsonNode body = JSON
                    .readTree(request(port, "/v1/chat/completions", "POST", "{\"model\":\"demo-model-pro\"}", true));
            assertEquals(30, body.path("usage").path("total_tokens").asInt());
            assertEquals("demo-model-pro", body.path("model").asText());
            String stream = request(port, "/", "POST", "{\"stream\":true}", false);
            assertTrue(stream.contains("chat.completion.chunk"));
            assertTrue(stream.endsWith("data: [DONE]\n\n"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void mockReportsForwardedIdentityAndRejectsMalformedJson() throws Exception {
        HttpServer server = FixtureServer.startMock(0);
        try {
            URL url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HttpURLConnection request = (HttpURLConnection) url.openConnection();
            request.setRequestMethod("POST");
            request.setDoOutput(true);
            request.setRequestProperty("x-mse-consumer", "demo-app");
            request.setRequestProperty("x-api-key", "fixture-only");
            request.getOutputStream().write(bytes("{}"));
            request.getOutputStream().close();
            assertEquals(200, request.getResponseCode());
            assertEquals("demo-app", request.getHeaderField("x-mock-consumer"));
            assertEquals("true", request.getHeaderField("x-mock-credentials-leaked"));
            request.disconnect();
            HttpURLConnection malformed = (HttpURLConnection) url.openConnection();
            malformed.setRequestMethod("POST");
            malformed.setDoOutput(true);
            malformed.getOutputStream().write(bytes("{"));
            malformed.getOutputStream().close();
            assertEquals(400, malformed.getResponseCode());
            malformed.disconnect();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void staticServerOnlyServesFilesInsideDirectory() throws Exception {
        Path dir = root.resolve("wasm");
        Files.createDirectories(dir);
        Files.write(dir.resolve("fixture.wasm"), bytes("wasm"));
        Files.write(root.resolve("secret"), bytes("secret"));
        Files.createSymbolicLink(dir.resolve("leak.wasm"), root.resolve("secret"));
        HttpServer s = FixtureServer.startStatic(0, dir);
        try {
            int port = s.getAddress().getPort();
            assertEquals("wasm", request(port, "/fixture.wasm", "GET", null, false));
            for (String p : new String[] { "/../secret", "/leak.wasm", "/" })
                assertEquals(404, ((HttpURLConnection) new URL("http://127.0.0.1:" + port + p).openConnection())
                        .getResponseCode());
        } finally {
            s.stop(0);
        }
    }
}
