package com.alibaba.higress.quota.tools;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.*;

/** Standalone local demo tooling. Never prints credentials or HTTP payloads. */
public final class QuotaTools {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PREFIX = "walmart-quota-portal", NS = "higress-system", HOST = "quota-portal.local";
    final Path root, out;

    QuotaTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
        out = this.root.resolve(".debug/cluster");
    }

    static ObjectNode obj(Object... kv) {
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2)
            n.set((String) kv[i], JSON.valueToTree(kv[i + 1]));
        return n;
    }

    static ArrayNode arr(Object... values) {
        ArrayNode a = JSON.createArrayNode();
        for (Object v : values)
            a.add(JSON.valueToTree(v));
        return a;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static void save(Path p, Object value) throws IOException {
        Files.createDirectories(p.getParent());
        if (!Files.exists(p))
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        Files.write(p, value instanceof String ? bytes((String) value)
                : JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    ObjectNode credentials() throws IOException {
        Files.createDirectories(out);
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwx------"));
        Path file = out.resolve("credentials.json");
        ObjectNode c;
        if (Files.exists(file))
            c = (ObjectNode) JSON.readTree(file.toFile());
        else {
            c = obj();
            SecureRandom random = new SecureRandom();
            for (String key : Arrays.asList("app", "second_app", "admin", "redis", "portal")) {
                byte[] b = new byte[32];
                random.nextBytes(b);
                c.put(key, Base64.getUrlEncoder().withoutPadding().encodeToString(b));
            }
        }
        for (String key : Arrays.asList("app", "second_app", "admin", "redis", "portal"))
            if (!c.path(key).isTextual() || c.path(key).asText().isEmpty())
                throw new IOException("Invalid private credentials field: " + key);
        save(file, c);
        Path env = root.resolve(".env");
        // Preserve existing private environment overrides and secrets across prepares.
        if (!Files.exists(env))
            save(env, "PORTAL_PASSWORD=" + c.path("portal").asText() + "\nGATEWAY_ADMIN_KEY=" + c.path("admin").asText()
                    + "\nGATEWAY_URL=http://127.0.0.1:18080\nGATEWAY_HOST=" + HOST + "\nPORTAL_PORT=18890\n");
        else
            Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
        return c;
    }

    static String process(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = p.getInputStream().read(buf)) != -1)
            b.write(buf, 0, n);
        if (p.waitFor() != 0)
            throw new IOException(
                    "Command failed: " + command[0] + " (output withheld to protect private configuration)");
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static String kube(String... args) throws Exception {
        List<String> c = new ArrayList<>(Arrays.asList("kubectl", "--context", "higress-dev", "-n", NS));
        c.addAll(Arrays.asList(args));
        return process(c.toArray(new String[0]));
    }

    static String sha256(Path p) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder s = new StringBuilder();
        for (byte b : digest)
            s.append(String.format("%02x", b & 255));
        return s.toString();
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--"))
                throw new IllegalArgumentException("Expected option");
            m.put(k, i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(
                    "Commands: prepare, install --wasm FILE, migrate-fixtures --wasm FILE, mock, static --directory DIR, seed-money, seed-legacy, verify-money, verify-gateway. Common: --root portal. Deployment applies only with --apply; verification requires --allow-mutations.");
            return;
        }
        Map<String, String> o = options(args);
        QuotaTools t = new QuotaTools(Paths.get(o.getOrDefault("--root", "portal")));
        switch (args[0]) {
        case "check-artifact":
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(required(o, "--url"))
                    .openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != 200)
                throw new IOException("Artifact HTTP check failed");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1)
                    hash.update(buffer, 0, read);
            } finally {
                connection.disconnect();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : hash.digest())
                hex.append(String.format("%02x", b & 255));
            if (!hex.toString().equals(required(o, "--sha256")))
                throw new IOException("Artifact HTTP digest mismatch");
            System.out.println("Artifact HTTP digest verified.");
            break;
        case "migrate-fixtures":
            new Deployment(t).migrateFixtures(Paths.get(required(o, "--wasm")), o.containsKey("--apply"));
            break;
        case "prepare":
            new Deployment(t).prepare(o.containsKey("--apply"));
            break;
        case "install":
            new Deployment(t).install(Paths.get(required(o, "--wasm")), o.containsKey("--config")
                    ? Paths.get(o.get("--config")) : t.root.resolve("scripts/money-config.json"),
                    o.containsKey("--apply"));
            break;
        case "mock":
            FixtureServer.startMock(Integer.parseInt(o.getOrDefault("--port", "8080")));
            break;
        case "static":
            FixtureServer.startStatic(Integer.parseInt(o.getOrDefault("--port", "8090")),
                    Paths.get(required(o, "--directory")));
            break;
        case "seed-money":
        case "seed-legacy":
        case "verify-money":
        case "verify-gateway":
            if (!o.containsKey("--allow-mutations"))
                throw new IllegalArgumentException(
                        "This command mutates demo quota/Redis state. Pass --allow-mutations explicitly.");
            new Verification(t).run(args[0]);
            break;
        default:
            throw new IllegalArgumentException("Unknown command");
        }
    }

    static String required(Map<String, String> o, String key) {
        if (!o.containsKey(key))
            throw new IllegalArgumentException("Required " + key);
        return o.get(key);
    }
}
