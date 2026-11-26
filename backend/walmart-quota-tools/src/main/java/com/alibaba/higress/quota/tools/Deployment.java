package com.alibaba.higress.quota.tools;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import static com.alibaba.higress.quota.tools.QuotaTools.*;

final class Deployment {
    final QuotaTools t;

    interface Kubernetes {
        String call(String... args) throws Exception;
    }

    final Kubernetes cluster;

    Deployment(QuotaTools t) {
        this(t, QuotaTools::kube);
    }

    Deployment(QuotaTools t, Kubernetes cluster) {
        this.t = t;
        this.cluster = cluster;
    }

    String command(String... args) throws Exception {
        return cluster.call(args);
    }

    ObjectNode existingCredentials() throws Exception {
        Path path = t.out.resolve("credentials.json");
        if (!Files.isRegularFile(path))
            throw new IllegalStateException(
                    "Existing private credentials required; refusing to generate credentials for a live installation.");
        ObjectNode c = (ObjectNode) JSON.readTree(path.toFile());
        JsonNode secret = JSON.readTree(command("get", "secret", PREFIX, "-o", "json"));
        String encoded = secret.path("data").path("redis-password").asText();
        String actual = new String(java.util.Base64.getDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_8);
        if (actual.isEmpty() || !c.path("redis").isTextual()
                || !java.security.MessageDigest.isEqual(bytes(actual), bytes(c.path("redis").asText())))
            throw new IllegalStateException(
                    "Saved Redis credential does not match existing cluster Secret; no changes made.");
        return c;
    }

    ObjectNode resource(String kind, String name, JsonNode spec) {
        ObjectNode r = obj("apiVersion", kind.equals("Deployment") ? "apps/v1" : "v1", "kind", kind, "metadata",
                obj("name", name, "namespace", NS, "labels", obj("app", PREFIX)));
        if (spec != null)
            r.set("spec", spec);
        return r;
    }

    static final String REDIS_REGISTRY = "walmart-quota-redis", REDIS_SERVICE = REDIS_REGISTRY + ".dns";

    ObjectNode redisRegistry() {
        return obj("name", REDIS_REGISTRY, "type", "dns", "domain", PREFIX + "." + NS + ".svc.cluster.local", "port",
                6379, "protocol", "tcp");
    }

    boolean hasRedisRegistry(JsonNode bridge) {
        for (JsonNode registry : bridge.path("spec").path("registries"))
            if (REDIS_REGISTRY.equals(registry.path("name").asText())) {
                ObjectNode desired = redisRegistry();
                java.util.Iterator<String> keys = desired.fieldNames();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!desired.path(key).equals(registry.path(key)))
                        throw new IllegalStateException(
                                "Existing dedicated Redis registry conflicts with required configuration");
                }
                return true;
            }
        return false;
    }

    ObjectNode bridgeSpec(JsonNode bridge) {
        ObjectNode spec = bridge.path("spec").deepCopy();
        if (!hasRedisRegistry(bridge)) {
            if (!spec.has("registries"))
                spec.set("registries", arr());
            ((ArrayNode) spec.path("registries")).add(redisRegistry());
        }
        return spec;
    }

    ObjectNode redisPluginSpec(JsonNode plugin) {
        ObjectNode spec = plugin.path("spec").deepCopy();
        for (JsonNode rule : spec.path("matchRules")) {
            JsonNode redis = rule.path("config").path("redis");
            if (!redis.isObject())
                throw new IllegalStateException("Expected Redis configuration in every isolated plugin match rule");
            ((ObjectNode) redis).put("service_name", REDIS_SERVICE);
        }
        return spec;
    }

    void patchSpec(String kind, String name, JsonNode original, JsonNode replacement) throws Exception {
        Path patch = t.out.resolve("patch-" + java.util.UUID.randomUUID() + ".private.json");
        save(patch, arr(obj("op", "test", "path", "/spec", "value", original.path("spec")),
                obj("op", "replace", "path", "/spec", "value", replacement)));
        try {
            command("patch", kind, name, "--type=json", "--patch-file", patch.toString());
        } finally {
            Files.deleteIfExists(patch);
        }
    }

    ObjectNode redis(ObjectNode c) {
        return obj("service_name", PREFIX + "." + NS + ".svc.cluster.local", "service_port", 6379, "password",
                c.path("redis"));
    }

    ObjectNode manifest(ObjectNode c) throws Exception {
        ObjectNode labels = obj("app", PREFIX);
        ArrayNode items = arr();
        ObjectNode secret = resource("Secret", PREFIX, null);
        secret.put("type", "Opaque");
        secret.set("stringData", obj("redis-password", c.path("redis")));
        items.add(secret);
        String image = "walmart-quota-tools:local";
        ArrayNode containers = arr(
                obj("name", "mock", "image", image, "imagePullPolicy", "IfNotPresent", "args", arr("mock"), "ports",
                        arr(obj("containerPort", 8080))),
                obj("name", "redis", "image",
                        "higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/redis-stack-server:7.4.0-v3",
                        "imagePullPolicy", "IfNotPresent", "command",
                        arr("sh", "-c",
                                "exec redis-server --save \"\" --appendonly no --requirepass \"$REDISCLI_AUTH\""),
                        "env",
                        arr(obj("name", "REDISCLI_AUTH", "valueFrom",
                                obj("secretKeyRef", obj("name", PREFIX, "key", "redis-password")))),
                        "ports", arr(obj("containerPort", 6379))),
                obj("name", "wasm", "image", image, "imagePullPolicy", "IfNotPresent", "args",
                        arr("static", "--directory", "/wasm"), "ports", arr(obj("containerPort", 8090)), "volumeMounts",
                        arr(obj("name", "wasm", "mountPath", "/wasm"))));
        items.add(resource("Deployment", PREFIX,
                obj("replicas", 1, "selector", obj("matchLabels", labels), "template", obj("metadata",
                        obj("labels", labels), "spec",
                        obj("containers", containers, "volumes", arr(obj("name", "wasm", "emptyDir", obj())))))));
        items.add(resource("Service", PREFIX, obj("selector", labels, "ports", arr(obj("name", "http", "port", 8080),
                obj("name", "tcp-redis", "port", 6379), obj("name", "http-wasm", "port", 8090)))));
        ArrayNode routes = arr(PREFIX + "-main", PREFIX + "-limited");
        for (int i = 0; i < 2; i++)
            items.add(
                    obj("apiVersion", "networking.k8s.io/v1", "kind", "Ingress", "metadata",
                            obj("name", routes.get(i), "namespace", NS, "labels",
                                    obj("app", PREFIX, "higress.io/resource-definer", "higress"), "annotations", obj(
                                            "higress.io/request-header-control-remove", "x-api-key,authorization")),
                            "spec",
                            obj("ingressClassName", "higress", "rules", arr(obj("host", HOST, "http", obj("paths",
                                    arr(obj("path", i == 0 ? "/v1/chat/completions" : "/limited/v1/chat/completions",
                                            "pathType", "Prefix", "backend",
                                            obj("service", obj("name", PREFIX, "port", obj("number", 8080)))))))))));
        JsonNode images = JSON.readTree(t.root.resolve("backend/walmart-quota-tools/config/plugin-images.json").toFile());
        plugin(items, images, "transformer", "AUTHN", 410, obj("reroute", false, "reqRules",
                arr(obj("operate", "remove", "headers", arr(obj("key", "x-mse-consumer"))))), routes, null);
        plugin(items, images, "key-auth", "AUTHN", 310, obj("allow", arr("demo-app", "second-app", "quota-admin")),
                routes,
                obj("global_auth", false, "keys", arr("x-api-key"), "in_header", true, "in_query", false, "consumers",
                        arr(obj("name", "demo-app", "credential", c.path("app")),
                                obj("name", "second-app", "credential", c.path("second_app")),
                                obj("name", "quota-admin", "credential", c.path("admin")))));
        plugin(items, images, "ai-statistics", "UNSPECIFIED_PHASE", 900, obj("attributes", arr(obj("key", "user_id",
                "value_source", "request_header", "value", "x-codebuddy-user-id", "apply_to_log", true))), routes,
                null);
        plugin(items, images, "ai-token-ratelimit", "UNSPECIFIED_PHASE", 600,
                obj("rule_name", PREFIX, "rule_items",
                        arr(obj("limit_by_per_header", "x-codebuddy-user-id", "limit_keys",
                                arr(obj("key", "*", "token_per_minute", 50))),
                                obj("limit_by_per_header", "x-mse-consumer", "limit_keys",
                                        arr(obj("key", "*", "token_per_minute", 80)))),
                        "redis", redis(c), "rejected_code", 429),
                arr(PREFIX + "-limited"), null);
        return obj("apiVersion", "v1", "kind", "List", "items", items);
    }

    void plugin(ArrayNode items, JsonNode images, String name, String phase, int priority, JsonNode config,
            ArrayNode routes, JsonNode defaults) {
        String image = images.path(name).path("image").asText();
        ArrayNode rules = arr();
        for (JsonNode route : routes)
            rules.add(obj("ingress", arr(route), "config", config, "configDisable", false));
        items.add(obj("apiVersion", "extensions.higress.io/v1alpha1", "kind", "WasmPlugin", "metadata",
                obj("name", PREFIX + "-" + name, "namespace", NS, "labels",
                        obj("app", PREFIX, "higress.io/wasm-plugin-name", name, "higress.io/wasm-plugin-version",
                                image.substring(image.lastIndexOf(':') + 1), "higress.io/wasm-plugin-built-in", "true",
                                "higress.io/resource-definer", "higress")),
                "spec",
                obj("url",
                        "oci://" + image.substring(0, image.lastIndexOf(':')) + "@"
                                + images.path(name).path("manifest_digest").asText(),
                        "phase", phase, "priority", priority, "failStrategy", "FAIL_CLOSE", "defaultConfig",
                        defaults == null ? obj() : defaults, "defaultConfigDisable", defaults == null, "matchRules",
                        rules)));
    }

    void prepare(boolean apply) throws Exception {
        Path file = t.out.resolve("manifests.private.json");
        save(file, manifest(t.credentials()));
        if (apply) {
            // Replacing the pod loses this demo's ephemeral Redis ledger. Never do that implicitly.
            String existing = command("get", "deployment", PREFIX, "--ignore-not-found", "-o", "name");
            if (!existing.trim().isEmpty())
                throw new IllegalStateException(
                        "Demo deployment already exists; prepare wrote manifests but refuses to replace ephemeral Redis. Coordinate a deliberate migration first.");
            command("apply", "-f", file.toString());
        }
        System.out.println(
                apply ? "Prepared and applied isolated demo resources." : "Prepared private manifests (not applied).");
    }

    void install(Path wasm, Path config, boolean apply) throws Exception {
        if (!Files.isRegularFile(wasm))
            throw new IllegalArgumentException("WASM file does not exist");
        ObjectNode c = apply ? existingCredentials() : t.credentials(),
                cfg = (ObjectNode) JSON.readTree(config.toFile());
        cfg.set("redis", redis(c));
        boolean migrated = false;
        if (apply) {
            String bridgeText = command("get", "mcpbridge", "default", "--ignore-not-found", "-o", "json");
            if (!bridgeText.trim().isEmpty())
                migrated = hasRedisRegistry(JSON.readTree(bridgeText));
            if (migrated)
                ((ObjectNode) cfg.path("redis")).put("service_name", REDIS_SERVICE);
        }
        String fixtureService = migrated ? PREFIX + "-java-fixtures" : PREFIX;
        String filename = "quota-" + sha256(wasm) + ".wasm";
        ArrayNode rules = arr();
        for (String suffix : new String[] { "main", "limited" })
            rules.add(obj("ingress", arr(PREFIX + "-" + suffix), "config", cfg, "configDisable", false));
        ObjectNode r = obj("apiVersion", "extensions.higress.io/v1alpha1", "kind", "WasmPlugin", "metadata",
                obj("name", PREFIX + "-ai-quota-walmart", "namespace", NS, "labels",
                        obj("app", PREFIX, "higress.io/wasm-plugin-name", "ai-quota-walmart",
                                "higress.io/wasm-plugin-version", "1.0.0-alpha", "higress.io/wasm-plugin-built-in",
                                "false", "higress.io/resource-definer", "higress")),
                "spec",
                obj("url", "http://" + fixtureService + "." + NS + ".svc.cluster.local:8090/" + filename, "phase",
                        "UNSPECIFIED_PHASE", "priority", 750, "failStrategy", "FAIL_CLOSE", "defaultConfigDisable",
                        true, "matchRules", rules));
        Path file = t.out.resolve("custom.private.json");
        save(file, r);
        if (apply) {
            String pod = JSON.readTree(command("get", "pods", "-l", "app=" + fixtureService, "-o", "json"))
                    .path("items").path(0).path("metadata").path("name").asText();
            if (pod.isEmpty())
                throw new IllegalStateException("Demo pod not found");
            command("cp", wasm.toString(), pod + ":/wasm/" + filename, "-c", "wasm");
            command("apply", "-f", file.toString());
        }
        System.out.println(
                apply ? "Installed unchanged WASM artifact." : "Prepared private plugin manifest (not applied).");
    }

    ObjectNode fixtureManifest() {
        String name = PREFIX + "-java-fixtures";
        ObjectNode labels = obj("app", name);
        ObjectNode deployment = resource("Deployment", name, obj("replicas", 1, "selector", obj("matchLabels", labels),
                "template",
                obj("metadata", obj("labels", labels), "spec", obj("containers", arr(
                        obj("name", "mock", "image", "walmart-quota-tools:local", "imagePullPolicy", "IfNotPresent",
                                "args", arr("mock"), "ports", arr(obj("containerPort", 8080)), "readinessProbe",
                                obj("httpGet", obj("path", "/", "port", 8080))),
                        obj("name", "wasm", "image", "walmart-quota-tools:local", "imagePullPolicy", "IfNotPresent",
                                "args", arr("static", "--directory", "/wasm"), "ports", arr(obj("containerPort", 8090)),
                                "readinessProbe", obj("tcpSocket", obj("port", 8090)), "volumeMounts",
                                arr(obj("name", "wasm", "mountPath", "/wasm")))),
                        "volumes", arr(obj("name", "wasm", "emptyDir", obj()))))));
        ((ObjectNode) deployment.path("metadata")).set("labels", labels);
        ObjectNode service = resource("Service", name, obj("selector", labels, "ports",
                arr(obj("name", "http", "port", 8080), obj("name", "http-wasm", "port", 8090))));
        ((ObjectNode) service.path("metadata")).set("labels", labels);
        return obj("apiVersion", "v1", "kind", "List", "items", arr(deployment, service));
    }

    void migrateFixtures(Path wasm, boolean apply) throws Exception {
        if (!Files.isRegularFile(wasm))
            throw new IllegalArgumentException("WASM file does not exist");
        // Only reads cluster/local secrets; never generates or changes them.
        existingCredentials();
        String name = PREFIX + "-java-fixtures", digest = sha256(wasm), filename = "quota-" + digest + ".wasm";
        ObjectNode plugin = (ObjectNode) JSON
                .readTree(command("get", "wasmplugin", PREFIX + "-ai-quota-walmart", "-o", "json"));
        String currentUrl = plugin.path("spec").path("url").asText();
        if (!currentUrl.endsWith("/" + filename))
            throw new IllegalStateException(
                    "Provided artifact does not match installed content-addressed WASM URL; migration must preserve artifact.");
        Path backup = t.out.resolve("fixture-migration-" + java.util.UUID.randomUUID());
        Files.createDirectories(backup);
        Files.setPosixFilePermissions(backup, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        save(backup.resolve("plugin.before.private.json"), plugin);
        ObjectNode bridge = (ObjectNode) JSON.readTree(command("get", "mcpbridge", "default", "-o", "json"));
        ObjectNode window = (ObjectNode) JSON
                .readTree(command("get", "wasmplugin", PREFIX + "-ai-token-ratelimit", "-o", "json"));
        save(backup.resolve("bridge.before.private.json"), bridge);
        save(backup.resolve("window.before.private.json"), window);
        ObjectNode updatedBridge = bridgeSpec(bridge), updatedWindow = redisPluginSpec(window),
                updatedPlugin = redisPluginSpec(plugin);
        ArrayNode ingresses = arr();
        for (String suffix : new String[] { "main", "limited" }) {
            ObjectNode ingress = (ObjectNode) JSON
                    .readTree(command("get", "ingress", PREFIX + "-" + suffix, "-o", "json"));
            ingresses.add(ingress);
            save(backup.resolve(suffix + ".before.private.json"), ingress);
        }
        ArrayNode replacements = arr();
        for (JsonNode ingress : ingresses) {
            ObjectNode changed = ingress.path("spec").deepCopy();
            for (JsonNode rule : changed.path("rules")) {
                if (!HOST.equals(rule.path("host").asText()))
                    throw new IllegalStateException("Unexpected ingress host; private backups available");
                for (JsonNode path : rule.path("http").path("paths")) {
                    ObjectNode backend = (ObjectNode) path.path("backend").path("service");
                    String old = backend.path("name").asText();
                    if (!old.equals(PREFIX) && !old.equals(name))
                        throw new IllegalStateException("Unexpected backend; private backups available");
                    backend.put("name", name);
                }
            }
            replacements.add(changed);
        }
        Path manifest = backup.resolve("fixtures.json");
        save(manifest, fixtureManifest());
        if (!apply) {
            System.out.println(
                    "Prepared isolated Java fixture manifest and private backups at " + backup + " (not applied).");
            return;
        }
        command("apply", "-f", manifest.toString());
        command("rollout", "status", "deployment/" + name, "--timeout=120s");
        String pod = JSON.readTree(command("get", "pods", "-l", "app=" + name, "-o", "json")).path("items").path(0)
                .path("metadata").path("name").asText();
        if (pod.isEmpty())
            throw new IllegalStateException("Java fixture pod not found");
        command("cp", wasm.toString(), pod + ":/wasm/" + filename, "-c", "wasm");
        String url = "http://" + name + "." + NS + ".svc.cluster.local:8090/" + filename;
        command("exec", pod, "-c", "mock", "--", "java", "-jar", "/tools/quota-tools.jar", "check-artifact", "--url",
                url, "--sha256", digest);
        // Keep Redis discoverable even when no ingress references its Kubernetes service.
        // Preserve every unrelated registry and all existing plugin fields, guarded by optimistic tests.
        patchSpec("mcpbridge", "default", bridge, updatedBridge);
        updatedPlugin.put("url", url);
        patchSpec("wasmplugin", PREFIX + "-ai-token-ratelimit", window, updatedWindow);
        patchSpec("wasmplugin", PREFIX + "-ai-quota-walmart", plugin, updatedPlugin);
        int routeIndex = 0;
        for (JsonNode ingress : ingresses) {
            JsonNode changed = replacements.get(routeIndex++);
            command("patch", "ingress", ingress.path("metadata").path("name").asText(), "--type=json", "-p",
                    JSON.writeValueAsString(arr(obj("op", "test", "path", "/spec", "value", ingress.path("spec")),
                            obj("op", "replace", "path", "/spec", "value", changed))));
        }
        System.out.println(
                "Java fixtures active; original Redis deployment and service untouched. Private rollback snapshots: "
                        + backup);
    }
}
