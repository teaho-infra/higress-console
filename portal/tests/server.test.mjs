import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { createPortal } from "./java-server.mjs";
let portal,
  upstream,
  url,
  received = [],
  session;
before(async () => {
  upstream = http.createServer((req, res) => {
    let b = "";
    req.on("data", (x) => (b += x));
    req.on("end", () => {
      received.push({ path: req.url, headers: req.headers, body: b });
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true, period: "2026-11-09", accounts: [] }));
    });
  });
  await new Promise((r) => upstream.listen(0, "127.0.0.1", r));
  portal = createPortal({
    password: "test-password-long",
    gatewayUrl: `http://127.0.0.1:${upstream.address().port}`,
    gatewayHost: "quota-portal.local",
    adminKey: "NEVER_EXPOSE_THIS_KEY",
    secureCookie: false,
    staticDir: "/nonexistent",
  });
  await new Promise((r) => portal.listen(0, "127.0.0.1", r));
  url = `http://127.0.0.1:${portal.address().port}`;
});
after(async () => {
  await Promise.all([
    new Promise((r) => portal.close(r)),
    new Promise((r) => upstream.close(r)),
  ]);
});
async function login() {
  const r = await fetch(url + "/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", Origin: url },
    body: JSON.stringify({ password: "test-password-long" }),
  });
  assert.equal(r.status, 200);
  const d = await r.json();
  session = { cookie: r.headers.get("set-cookie").split(";")[0], csrf: d.csrf };
  return r;
}
test("unauthenticated management reads are denied without calling gateway", async () => {
  const n = received.length;
  assert.equal((await fetch(url + "/api/quota/accounts")).status, 401);
  assert.equal(received.length, n);
});
test("incorrect password denied", async () => {
  assert.equal(
    (
      await fetch(url + "/api/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", Origin: url },
        body: '{"password":"bad"}',
      })
    ).status,
    401
  );
});
test("cross-origin login denied", async () => {
  assert.equal(
    (
      await fetch(url + "/api/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Origin: "http://evil.test",
        },
        body: '{"password":"test-password-long"}',
      })
    ).status,
    403
  );
});
test("authenticated read injects server-only admin key and fixed host", async () => {
  await login();
  const r = await fetch(url + "/api/quota/accounts?dimension=user", {
    headers: { cookie: session.cookie },
  });
  assert.equal(r.status, 200);
  assert.ok(!(await r.text()).includes("NEVER_EXPOSE"));
  assert.equal(received.at(-1).headers["x-api-key"], "NEVER_EXPOSE_THIS_KEY");
  assert.equal(received.at(-1).headers.host, "quota-portal.local");
  assert.equal(
    received.at(-1).path,
    "/v1/chat/completions/quota/accounts?dimension=user"
  );
});
test("mutation needs CSRF and same origin", async () => {
  await login();
  for (const headers of [
    { cookie: session.cookie },
    {
      cookie: session.cookie,
      "x-portal-csrf": session.csrf,
      Origin: "http://evil.test",
    },
  ]) {
    assert.equal(
      (
        await fetch(url + "/api/quota/cycle", {
          method: "POST",
          headers: { "Content-Type": "application/json", ...headers },
          body: "{}",
        })
      ).status,
      403
    );
  }
});
test("supported mutation preserves payload; operator cannot be spoofed", async () => {
  await login();
  const r = await fetch(url + "/api/quota/cycle", {
    method: "POST",
    headers: {
      cookie: session.cookie,
      "x-portal-csrf": session.csrf,
      Origin: url,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      days: 7,
      period: "2026-11-09",
      ticket_id: "T1",
      operator: "spoofed",
    }),
  });
  assert.equal(r.status, 200);
  assert.equal(
    Object.hasOwn(JSON.parse(received.at(-1).body), "operator"),
    false
  );
  assert.equal(received.at(-1).path, "/v1/chat/completions/quota/cycle");
});
test("arbitrary upstream paths and unsupported methods blocked", async () => {
  await login();
  for (const path of [
    "/api/quota/../../config_dump",
    "/api/quota/admin",
    "/api/quota/meta/extra",
  ])
    assert.equal(
      (await fetch(url + path, { headers: { cookie: session.cookie } })).status,
      404
    );
  assert.equal(
    (
      await fetch(url + "/api/quota/accounts", {
        method: "DELETE",
        headers: {
          cookie: session.cookie,
          Origin: url,
          "x-portal-csrf": session.csrf,
        },
      })
    ).status,
    405
  );
});
test("logout invalidates session", async () => {
  await login();
  assert.equal(
    (
      await fetch(url + "/api/logout", {
        method: "POST",
        headers: {
          cookie: session.cookie,
          "x-portal-csrf": session.csrf,
          Origin: url,
        },
      })
    ).status,
    200
  );
  assert.equal(
    (
      await fetch(url + "/api/quota/meta", {
        headers: { cookie: session.cookie },
      })
    ).status,
    401
  );
});
test("fails startup without a strong password or admin key", () => {
  assert.throws(() =>
    createPortal({
      password: "123",
      adminKey: "x",
      gatewayUrl: "http://localhost",
    })
  );
  assert.throws(() =>
    createPortal({
      password: "long-password",
      adminKey: "",
      gatewayUrl: "http://localhost",
    })
  );
});

test("prices GET and POST preserve server authentication and CSRF boundary", async () => {
  assert.equal((await fetch(url + "/api/quota/prices")).status, 401);
  await login();
  assert.equal(
    (
      await fetch(url + "/api/quota/prices", {
        headers: { cookie: session.cookie },
      })
    ).status,
    200
  );
  const data = {
    prices: {
      "demo-model": { input_price_micros: 1234567, output_price_micros: 0 },
    },
    period: "2026-11-09",
    ticket_id: "PRICE-1",
  };
  assert.equal(
    (
      await fetch(url + "/api/quota/prices", {
        method: "POST",
        headers: {
          cookie: session.cookie,
          Origin: url,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(data),
      })
    ).status,
    403
  );
  const response = await fetch(url + "/api/quota/prices", {
    method: "POST",
    headers: {
      cookie: session.cookie,
      Origin: url,
      "x-portal-csrf": session.csrf,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  });
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(received.at(-1).body), data);
  assert.equal(received.at(-1).headers["x-api-key"], "NEVER_EXPOSE_THIS_KEY");
});
test("department queries and personal inherit assignments pass unchanged", async () => {
  await login();
  assert.equal(
    (
      await fetch(url + "/api/quota/accounts?dimension=department", {
        headers: { cookie: session.cookie },
      })
    ).status,
    200
  );
  const data = {
    dimension: "user",
    subject: "alice",
    department: "research",
    tier: "inherit",
    period: "2026-11-09",
    ticket_id: "DEPT-1",
  };
  const r = await fetch(url + "/api/quota/assign", {
    method: "POST",
    headers: {
      cookie: session.cookie,
      Origin: url,
      "x-portal-csrf": session.csrf,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  });
  assert.equal(r.status, 200);
  assert.deepEqual(JSON.parse(received.at(-1).body), data);
});
import { toMicros, fromMicros, MAX_MICROS } from "../src/money.mjs";
test("money conversion uses exact decimal arithmetic and never rounds silently", () => {
  for (const [decimal, integer] of [
    ["0", 0],
    ["0.000001", 1],
    ["1.234567", 1234567],
    ["9999999.999999", MAX_MICROS],
    ["0.29", 290000],
  ]) {
    assert.equal(toMicros(decimal), integer);
    assert.equal(toMicros(fromMicros(integer)), integer);
  }
  assert.equal(toMicros("-0.000001", { signed: true }), -1);
  assert.throws(() => toMicros("9007199254.740991"));
  assert.throws(() => toMicros("10000000"));
  for (const invalid of [
    "1.0000001",
    "1e3",
    "Infinity",
    "NaN",
    "1,000",
    "-1",
    "",
    " 1",
    "1.",
    "999999999999999999999",
  ])
    assert.throws(() => toMicros(invalid));
  assert.throws(() => toMicros(0.1));
  assert.throws(() => fromMicros(1.1));
});
