import http from "node:http";
import https from "node:https";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
const readPaths = new Set(["meta", "accounts", "audit", "usage", "prices", ""]);
const writePaths = new Set([
  "assign",
  "tier",
  "delta",
  "refresh",
  "tiers",
  "prices",
]);
const mime = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};
function same(a, b) {
  const x = Buffer.from(a),
    y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}
async function body(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > 32768)
      throw Object.assign(new Error("请求体过大"), { status: 413 });
  }
  try {
    return JSON.parse(raw || "{}");
  } catch {
    throw Object.assign(new Error("请求体必须为 JSON"), { status: 400 });
  }
}
function gatewayRequest(url, method, headers, payload) {
  return new Promise((resolve, reject) => {
    const req = (url.protocol === "https:" ? https : http).request(
      url,
      { method, headers, signal: AbortSignal.timeout(10000) },
      (res) => {
        const chunks = [];
        let size = 0;
        res.on("data", (chunk) => {
          size += chunk.length;
          if (size > 5_000_000) {
            req.destroy(new Error("Response too large"));
            return;
          }
          chunks.push(chunk);
        });
        res.on("error", reject);
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            text: Buffer.concat(chunks).toString("utf8"),
          })
        );
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}
export function createPortal(config) {
  if (!config.password || config.password.length < 12 || !config.adminKey)
    throw new Error(
      "Set PORTAL_PASSWORD (at least 12 characters) and GATEWAY_ADMIN_KEY"
    );
  const gateway = new URL(config.gatewayUrl);
  if (
    !["http:", "https:"].includes(gateway.protocol) ||
    gateway.username ||
    gateway.password
  )
    throw new Error("Invalid gateway URL");
  const sessions = new Map(),
    attempts = new Map();
  const ttl = 8 * 3600000;
  const send = (res, status, data) => {
    res.writeHead(status, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    });
    res.end(JSON.stringify(data));
  };
  return http.createServer(async (req, res) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "same-origin");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
    );
    const now = Date.now();
    for (const [id, s] of sessions) if (s.expires <= now) sessions.delete(id);
    for (const [id, a] of attempts) if (a.until <= now) attempts.delete(id);
    try {
      const u = new URL(req.url, "http://portal.local");
      const isMutation = !["GET", "HEAD"].includes(req.method);
      const origin = req.headers.origin;
      const expected =
        (config.secureCookie ? "https" : "http") + "://" + req.headers.host;
      if (u.pathname.startsWith("/api/") && isMutation && origin !== expected)
        return send(res, 403, {
          ok: false,
          message: "请求来源不匹配，请刷新页面后重试",
        });
      if (u.pathname === "/api/login" && req.method === "POST") {
        const ip = req.socket.remoteAddress;
        const a = attempts.get(ip) || { count: 0, until: now + 60000 };
        if (a.count >= 10)
          return send(res, 429, {
            ok: false,
            message: "登录尝试过多，请一分钟后重试",
          });
        const data = await body(req);
        if (
          typeof data.password !== "string" ||
          !same(data.password, config.password)
        ) {
          a.count++;
          attempts.set(ip, a);
          return send(res, 401, { ok: false, message: "管理密码不正确" });
        }
        attempts.delete(ip);
        if (sessions.size >= 1000)
          return send(res, 503, { ok: false, message: "会话数量已达上限" });
        const id = crypto.randomBytes(32).toString("hex"),
          csrf = crypto.randomBytes(24).toString("hex");
        sessions.set(id, { csrf, expires: now + ttl });
        res.setHeader(
          "Set-Cookie",
          `portal_session=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800${
            config.secureCookie ? "; Secure" : ""
          }`
        );
        return send(res, 200, { ok: true, csrf, operator: "portal-admin" });
      }
      if (u.pathname.startsWith("/api/")) {
        const id = /(?:^|;\s*)portal_session=([a-f0-9]+)/.exec(
          req.headers.cookie || ""
        )?.[1];
        const session = sessions.get(id);
        if (!session)
          return send(res, 401, { ok: false, message: "请先登录额度管理门户" });
        if (
          isMutation &&
          !same(String(req.headers["x-portal-csrf"] || ""), session.csrf)
        )
          return send(res, 403, {
            ok: false,
            message: "会话校验失败，请重新登录",
          });
        if (u.pathname === "/api/session" && req.method === "GET")
          return send(res, 200, {
            ok: true,
            csrf: session.csrf,
            operator: "portal-admin",
          });
        if (u.pathname === "/api/logout" && req.method === "POST") {
          sessions.delete(id);
          res.setHeader(
            "Set-Cookie",
            "portal_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"
          );
          return send(res, 200, { ok: true });
        }
        if (!u.pathname.startsWith("/api/quota/"))
          return send(res, 404, { ok: false, message: "接口不存在" });
        const action = u.pathname.slice("/api/quota/".length);
        if (!readPaths.has(action) && !writePaths.has(action))
          return send(res, 404, { ok: false, message: "接口不存在" });
        if (
          !(
            (readPaths.has(action) && req.method === "GET") ||
            (writePaths.has(action) && req.method === "POST")
          )
        )
          return send(res, 405, { ok: false, message: "请求方法不支持" });
        let payload;
        if (req.method === "POST") {
          if (
            !String(req.headers["content-type"] || "").startsWith(
              "application/json"
            )
          )
            return send(res, 415, {
              ok: false,
              message: "请使用 application/json",
            });
          const data = await body(req);
          if (!data || typeof data !== "object" || Array.isArray(data))
            return send(res, 400, { ok: false, message: "请求体必须为对象" });
          const { operator: ignoredOperator, ...fields } = data;
          payload = JSON.stringify(fields); // The gateway derives the audit actor from authenticated Key Auth.
        }
        const dest = new URL(
          "/v1/chat/completions/quota" + (action ? "/" + action : ""),
          gateway
        );
        for (const [k, v] of u.searchParams) {
          if (
            ![
              "dimension",
              "subject",
              "app",
              "limit",
              "cursor",
              "offset",
              "period",
            ].includes(k)
          )
            return send(res, 400, { ok: false, message: "查询参数不支持" });
          dest.searchParams.append(k, v);
        }
        let upstream;
        try {
          upstream = await gatewayRequest(
            dest,
            req.method,
            {
              Host: config.gatewayHost,
              "x-api-key": config.adminKey,
              "Content-Type": "application/json",
            },
            payload
          );
        } catch {
          return send(res, 502, {
            ok: false,
            message: "无法连接额度网关，请检查网关地址、端口转发和服务状态",
          });
        }
        const text = upstream.text;
        let data;
        try {
          data = JSON.parse(text);
        } catch {
          return send(res, 502, {
            ok: false,
            message: `额度网关返回非 JSON（HTTP ${upstream.status}），请检查 Host、路由与管理插件`,
          });
        }
        // Never pass arbitrary upstream headers or authentication challenges to the browser.
        return send(res, upstream.status, data);
      }
      if (!["GET", "HEAD"].includes(req.method)) {
        res.writeHead(405);
        return res.end();
      }
      const root = path.resolve(config.staticDir || "dist");
      const file = path.resolve(
        root,
        "." +
          decodeURIComponent(u.pathname === "/" ? "/index.html" : u.pathname)
      );
      if (!file.startsWith(root + path.sep)) {
        res.writeHead(404);
        return res.end();
      }
      try {
        const bytes = await fs.readFile(file);
        res.setHeader(
          "Content-Type",
          mime[path.extname(file)] || "application/octet-stream"
        );
        res.setHeader("Cache-Control", "no-cache");
        res.end(req.method === "HEAD" ? undefined : bytes);
      } catch {
        res.writeHead(404);
        res.end("Not found");
      }
    } catch (error) {
      send(res, error.status || 500, {
        ok: false,
        message: error.status
          ? error.message
          : "服务处理失败，请查看服务端调试记录",
      });
    }
  });
}
