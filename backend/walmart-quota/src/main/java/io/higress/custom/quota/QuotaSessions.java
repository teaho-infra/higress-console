package io.higress.custom.quota;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

final class QuotaSessions {
    static final long TTL = 8 * 3600000L;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    QuotaSessions(Clock clock) {
        this.clock = clock;
    }

    synchronized Session login(String ip, String supplied, String password) {
        purge();
        Attempt a = attempts.get(ip);
        if (a != null && a.count >= 10)
            throw new ApiFailure(429, "登录尝试过多，请一分钟后重试");
        if (!same(supplied, password)) {
            if (a == null) {
                if (attempts.size() >= 10000)
                    throw new ApiFailure(429, "登录尝试过多，请稍后重试");
                a = new Attempt(clock.millis() + 60000);
                attempts.put(ip, a);
            }
            a.count++;
            throw new ApiFailure(401, "管理密码不正确");
        }
        attempts.remove(ip);
        if (sessions.size() >= 1000)
            throw new ApiFailure(503, "会话数量已达上限");
        Session s = new Session(token(32), token(24), clock.millis() + TTL);
        sessions.put(s.id, s);
        return s;
    }

    synchronized Session get(String id) {
        purge();
        Session s = sessions.get(id);
        if (s == null)
            throw new ApiFailure(401, "请先登录额度管理门户");
        return s;
    }

    synchronized void remove(String id) {
        sessions.remove(id);
    }

    private void purge() {
        long now = clock.millis();
        sessions.values().removeIf(s -> s.expires <= now);
        attempts.values().removeIf(a -> a.until <= now);
    }

    static boolean same(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String token(int size) {
        byte[] b = new byte[size];
        random.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte v : b)
            s.append(String.format("%02x", v & 255));
        return s.toString();
    }

    static final class Session {
        final String id, csrf;
        final long expires;

        Session(String id, String csrf, long expires) {
            this.id = id;
            this.csrf = csrf;
            this.expires = expires;
        }
    }

    static final class Attempt {
        int count;
        final long until;

        Attempt(long until) {
            this.until = until;
        }
    }
}
