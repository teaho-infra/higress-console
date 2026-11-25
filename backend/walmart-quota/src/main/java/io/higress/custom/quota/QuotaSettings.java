package io.higress.custom.quota;

import java.net.URI;
import org.springframework.core.env.Environment;

/** Configuration for both Console integration and the standalone compatibility server. */
public final class QuotaSettings {
    final String password, adminKey, gatewayHost;
    final URI gateway;
    final boolean secureCookie;

    public QuotaSettings(String password, String adminKey, String gatewayUrl, String gatewayHost,
            boolean secureCookie) {
        this(password, adminKey, gatewayUrl, gatewayHost, secureCookie, false);
    }

    QuotaSettings(String password, String adminKey, String gatewayUrl, String gatewayHost, boolean secureCookie,
            boolean consoleAuth) {
        if ((!consoleAuth && (password == null || password.length() < 12)) || adminKey == null
                || adminKey.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Configure PORTAL_PASSWORD (at least 12 characters) and GATEWAY_ADMIN_KEY");
        }
        URI uri = URI.create(gatewayUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid quota gateway URL");
        }
        if (gatewayHost == null || !gatewayHost.matches("[A-Za-z0-9.\\-]+(?::[0-9]+)?") || adminKey.contains("\r")
                || adminKey.contains("\n")) {
            throw new IllegalArgumentException("Invalid quota gateway authentication configuration");
        }
        this.password = password;
        this.adminKey = adminKey;
        this.gateway = uri;
        this.gatewayHost = gatewayHost;
        this.secureCookie = secureCookie;
    }

    static QuotaSettings from(Environment env) {
        return from(env, false);
    }

    static QuotaSettings from(Environment env, boolean consoleAuth) {
        return new QuotaSettings(value(env, "password", "PORTAL_PASSWORD", null),
                value(env, "admin-key", "GATEWAY_ADMIN_KEY", null),
                value(env, "gateway-url", "GATEWAY_URL", "http://127.0.0.1:18080"),
                value(env, "gateway-host", "GATEWAY_HOST", "quota-portal.local"),
                Boolean.parseBoolean(value(env, "secure-cookie", "PORTAL_SECURE_COOKIE", "false")), consoleAuth);
    }

    private static String value(Environment env, String key, String legacy, String fallback) {
        return env.getProperty("walmart.quota." + key, env.getProperty(legacy, fallback));
    }
}
