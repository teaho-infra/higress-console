import { chromium, expect } from "@playwright/test";
import fs from "node:fs/promises";
process.loadEnvFile(".env");
const base = "http://127.0.0.1:" + (process.env.PORTAL_PORT || 18890);
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 1050 },
});
const page = await context.newPage();
const checks = [];
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const check = (name, passed = true, detail) => {
  checks.push({ name, passed, detail });
  if (!passed) throw new Error(name);
  console.log("PASS " + name);
};
try {
  const unauth = await context.request.get(base + "/api/quota/meta");
  check("Anonymous management API denied", unauth.status() === 401);
  await page.goto(base);
  await page.getByLabel("管理员密码").fill(process.env.PORTAL_PASSWORD);
  await page.getByRole("button", { name: "进入额度管理" }).click();
  await expect(
    page.getByRole("heading", { name: "用量总览", exact: true })
  ).toBeVisible();
  await expect(page.getByText("user-alice", { exact: true })).toBeVisible();
  check("Login and real gateway accounts loaded");
  await page.screenshot({
    path: "docs/evidence/portal-overview.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "用户与应用", exact: true }).click();
  await page.getByLabel("搜索账户").fill("user-alice");
  await expect(page.getByText("user-alice", { exact: true })).toBeVisible();
  await expect(page.getByText("user-bob", { exact: true })).toHaveCount(0);
  check("Account search filters by subject");
  await page
    .getByRole("button", { name: "配置 user-alice", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "临时加量", exact: true }).click();
  await page.getByLabel("追加 Token").fill("120");
  const ticket = "BROWSER-" + Date.now();
  await page.getByLabel("变更工单号").fill(ticket);
  await page.screenshot({
    path: "docs/evidence/portal-quota-editor.png",
    fullPage: true,
  });
  const before = await (
    await context.request.get(
      base + "/api/quota/?dimension=user&subject=user-alice"
    )
  ).json();
  await page.getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("status")).toContainText("配置已保存");
  const after = await (
    await context.request.get(
      base + "/api/quota/?dimension=user&subject=user-alice"
    )
  ).json();
  check(
    "Browser grant increased actual balance by 120",
    after.balance - before.balance === 120
  );
  await page.getByRole("button", { name: "变更审计", exact: true }).click();
  await expect(page.getByText(ticket, { exact: true })).toBeVisible();
  check("Actual audit contains browser ticket");
  await page.screenshot({
    path: "docs/evidence/portal-audit.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "档位配置", exact: true }).click();
  await page.getByRole("button", { name: "编辑档位模板", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "保存档位模板", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  check("Tier template editor saved through gateway");
  const originalMeta = await (
    await context.request.get(base + "/api/quota/meta")
  ).json();
  await page.getByRole("button", { name: "编辑档位模板", exact: true }).click();
  const inputs = page.getByLabel("允许的模型");
  for (let i = 0; i < 3; i++) await inputs.nth(i).fill("");
  await page.getByRole("button", { name: "保存档位模板", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText("可用模型：全部模型")).toHaveCount(3);
  check("Unrestricted model lists render and save without crash");
  await page.getByRole("button", { name: "编辑档位模板", exact: true }).click();
  await page.getByRole("button", { name: "保存档位模板", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  check("Quota-only edit accepts default unrestricted lists");
  await page.screenshot({
    path: "docs/evidence/portal-tiers.png",
    fullPage: true,
  });
  const session = await (
    await context.request.get(base + "/api/session")
  ).json();
  const restore = await context.request.post(base + "/api/quota/tiers", {
    headers: { Origin: base, "x-portal-csrf": session.csrf },
    data: {
      tiers: originalMeta.tiers,
      ticket_id: "BROWSER-RESTORE-" + Date.now(),
      period: originalMeta.period,
    },
  });
  check("Original template restored after browser regression", restore.ok());

  await page.getByRole("button", { name: "用量总览", exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "docs/evidence/portal-mobile.png",
    fullPage: true,
  });
  const widths = await page.evaluate(() => ({
    body: document.documentElement.scrollWidth,
    viewport: innerWidth,
  }));
  check(
    "Mobile page has no horizontal document overflow",
    widths.body <= widths.viewport,
    widths
  );
  check("No browser JavaScript errors", errors.length === 0, errors);
  await page.setViewportSize({ width: 1440, height: 1050 });
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(page.getByLabel("管理员密码")).toBeVisible();
  check("Logout returns to login");
} catch (e) {
  checks.push({
    name: "Browser run failure",
    passed: false,
    detail: e.message,
  });
  await page
    .screenshot({ path: "docs/evidence/portal-failure.png", fullPage: true })
    .catch(() => {});
  console.error(e.message);
  process.exitCode = 1;
} finally {
  await fs.writeFile(
    "docs/evidence/browser-report.json",
    JSON.stringify(
      {
        at: new Date().toISOString(),
        checks,
        passed: checks.every((x) => x.passed),
      },
      null,
      2
    ) + "\n"
  );
  await browser.close();
}
