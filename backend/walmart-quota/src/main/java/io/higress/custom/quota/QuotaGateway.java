package io.higress.custom.quota;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.*;

/** No client credentials/headers or redirects are forwarded across this boundary. */
final class QuotaGateway implements AutoCloseable {
    private final QuotaSettings settings;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quota-gateway-deadline");
        t.setDaemon(true);
        return t;
    });

    QuotaGateway(QuotaSettings settings) {
        this.settings = settings;
        this.client = HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .setMaxConnTotal(40).setMaxConnPerRoute(40).setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10000).setSocketTimeout(10000).setConnectionRequestTimeout(10000).build())
                .build();
    }

    Result call(String method, String pathAndQuery, byte[] payload) throws IOException {
        URI uri = settings.gateway.resolve(pathAndQuery);
        HttpRequestBase req;
        if ("POST".equals(method)) {
            HttpPost post = new HttpPost(uri);
            post.setEntity(new ByteArrayEntity(payload));
            req = post;
        } else
            req = new HttpGet(uri);
        req.setHeader("Host", settings.gatewayHost);
        req.setHeader("x-api-key", settings.adminKey);
        req.setHeader("Content-Type", "application/json");
        ScheduledFuture<?> deadline = deadlines.schedule(req::abort, 10, TimeUnit.SECONDS);
        try (CloseableHttpResponse response = client.execute(req)) {
            byte[] bytes = response.getEntity() == null ? new byte[0]
                    : QuotaApi.readLimited(response.getEntity().getContent(), 5000000);
            return new Result(response.getStatusLine().getStatusCode(), bytes);
        } finally {
            deadline.cancel(false);
            req.releaseConnection();
        }
    }

    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }

    static class Result {
        final int status;
        final byte[] body;

        Result(int s, byte[] b) {
            status = s;
            body = b;
        }
    }
}
