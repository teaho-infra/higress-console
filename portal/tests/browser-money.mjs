import { chromium, expect } from "@playwright/test";
import fs from "node:fs/promises";
process.loadEnvFile(".env");
const base = "http://127.0.0.1:" + (process.env.PORTAL_PORT || 18890),
  out = "docs/evidence-money";
await fs.mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
    viewport: { width: 1440, height: 1050 },
  }),
  page = await context.newPage();
const checks = [],
  errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const check = (name, ok = true) => {
  checks.push({ name, passed: ok });
  if (!ok) throw new Error(name);
  console.log("PASS " + name);
};
const shot = (name) =>
  page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
try {
  check(
    "Anonymous prices denied",
    (await context.request.get(base + "/api/quota/prices")).status() === 401
  );
  await page.goto(base);
  await page.getByLabel("管理员密码").fill(process.env.PORTAL_PASSWORD);
  await page.getByRole("button", { name: "进入额度管理" }).click();
  await expect(
    page.getByRole("heading", { name: "用量总览", exact: true })
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "配置额度", exact: true })
  ).toBeEnabled();
  const meta = await (
    await context.request.get(base + "/api/quota/meta")
  ).json();
  check(
    "Real gateway monetary policy active",
    meta.budget_policy === "personal_department_money" &&
      meta.amount_unit === "micro_yuan"
  );
  await shot("overview");
  await page.getByRole("button", { name: "部门模板", exact: true }).click();
  await page.getByRole("button", { name: "配置额度", exact: true }).click();
  const subject = "browser-dept-" + Date.now();
  await page.getByLabel("主体标识").fill(subject);
  await page.getByLabel("变更工单号").fill(subject);
  await page.getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText(subject, { exact: true })).toBeVisible();
  check("Department template created using browser");
  await shot("departments");
  await page.getByRole("button", { name: "用户与应用", exact: true }).click();
  await page.getByRole("button", { name: "配置额度", exact: true }).click();
  const user = "browser-user-" + Date.now();
  await page.getByLabel("主体标识").fill(user);
  await page.getByLabel("所属部门", { exact: false }).selectOption(subject);
  await expect(
    page.getByRole("button", { name: "继承部门", exact: true })
  ).toHaveAttribute("aria-pressed", "true");
  check("New user defaults to department inheritance");
  await page.getByRole("button", { name: /继承部门/ }).click();
  await page.getByLabel("变更工单号").fill(user);
  await page.getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByLabel("搜索账户").fill(user);
  await page.getByRole("button", { name: "配置 " + user, exact: true }).click();
  await page.getByRole("button", { name: "临时加量", exact: true }).click();
  await page.getByLabel("追加金额（元）").fill("0.123456");
  await page.getByLabel("变更工单号").fill("DELTA-" + Date.now());
  const before = await (
    await context.request.get(
      base + "/api/quota/?dimension=user&subject=" + user
    )
  ).json();
  await shot("personal-budget");
  await page.getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  const after = await (
    await context.request.get(
      base + "/api/quota/?dimension=user&subject=" + user
    )
  ).json();
  check(
    "Exact monetary delta persisted",
    after.balance - before.balance === 123456
  );
  check(
    "Department source remains personal balance",
    after.quota_source === "department" && after.effective_dimension === "user"
  );
  await page.getByRole("button", { name: "模型单价", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "模型单价", exact: true })
  ).toBeVisible();
  await shot("model-prices");
  const first = page.getByLabel("输入单价").first();
  if (await first.count()) {
    const old = await first.inputValue();
    await first.fill("0.0000001");
    await page.getByRole("button", { name: "保存模型单价" }).click();
    await expect(page.getByRole("alert")).toContainText("最多 6 位小数");
    await first.fill(old);
  }
  const priceResponse = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/quota/prices") && r.request().method() === "POST"
  );
  await page.getByRole("button", { name: "保存模型单价" }).click();
  check(
    "Model price editor saves real gateway configuration",
    (await priceResponse).ok()
  );
  await expect(page.getByRole("status")).toContainText("配置已保存");
  await page.getByRole("button", { name: "用量总览", exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await shot("mobile");
  check(
    "Mobile has no document overflow",
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth
    )
  );
  check("No JavaScript errors", errors.length === 0);
} catch (e) {
  checks.push({ name: "Browser failure", passed: false, detail: e.message });
  await shot("failure");
  console.error(e);
  process.exitCode = 1;
} finally {
  await fs.writeFile(
    out + "/browser-report.json",
    JSON.stringify(
      {
        at: new Date().toISOString(),
        checks,
        errors,
        passed: checks.every((c) => c.passed),
      },
      null,
      2
    )
  );
  await browser.close();
}
