import { chromium, expect } from "@playwright/test";
import fs from "node:fs/promises";
process.loadEnvFile(".env");
const out = "docs/evidence-import",
  base = "http://127.0.0.1:18890";
await fs.mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true }),
  context = await browser.newContext({
    viewport: { width: 1440, height: 1050 },
  }),
  page = await context.newPage();
const checks = [],
  sent = [];
const check = (name, passed) => {
  checks.push({ name, passed });
  if (!passed) throw new Error(name);
  console.log("PASS " + name);
};
try {
  await page.goto(base);
  await page.getByLabel("管理员密码").fill(process.env.PORTAL_PASSWORD);
  await page.getByRole("button", { name: "进入额度管理" }).click();
  await page.getByRole("button", { name: "用户与应用", exact: true }).click();
  await page.getByRole("button", { name: "批量导入", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "批量导入个人预算" })
  ).toBeVisible();
  const downloadEvent = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载 CSV 模板" }).click();
  const template = await downloadEvent;
  check(
    "CSV template downloads with Chinese headers",
    (await fs.readFile(await template.path(), "utf8")).includes(
      "用户标识,所属部门,档位"
    )
  );
  const id = "import-" + Date.now(),
    one = id + "-personal",
    two = id + "-inherit";
  let abort = true;
  await page.route("**/api/quota/assign", async (route) => {
    const body = route.request().postDataJSON();
    sent.push(body);
    if (body.subject === two && abort) {
      abort = false;
      await route.abort("failed");
    } else await route.continue();
  });
  await page
    .getByLabel("选择 CSV 文件")
    .setInputFiles({
      name: "invalid.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("用户标识,所属部门,档位\na,,L1\na,,L2\n"),
    });
  await expect(page.getByRole("alert")).toContainText("重复");
  check("Invalid file rejected before writes", sent.length === 0);
  const dept = await (
    await context.request.get(
      base + "/api/quota/accounts?dimension=department&limit=200"
    )
  ).json();
  const department =
    dept.accounts.find((d) => d.subject === "研发部")?.subject ||
    dept.accounts[0]?.subject;
  if (!department) throw new Error("No department fixture");
  await page
    .getByLabel("选择 CSV 文件")
    .setInputFiles({
      name: "users.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(
        `用户标识,所属部门,档位\n${one},,L1\n${two},${department},\n`
      ),
    });
  await expect(
    page.getByRole("button", { name: "确认导入", exact: true })
  ).toBeDisabled();
  await expect(
    page.getByRole("dialog").getByText(two, { exact: true })
  ).toBeVisible();
  check("Preview has no server writes", sent.length === 0);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "确认导入", exact: true }).click();
  await expect(page.getByRole("dialog").getByRole("status")).toContainText(
    "成功 1 条，失败 1 条"
  );
  check("Partial failure reported accurately", sent.length === 2);
  const failedBody = structuredClone(sent[1]);
  await page.getByRole("button", { name: "重试失败及未完成项" }).click();
  await expect(page.getByRole("dialog").getByRole("status")).toContainText(
    "成功 2 条，失败 0 条"
  );
  check(
    "Retry sends only failed row with unchanged ticket",
    sent.length === 3 && JSON.stringify(sent[2]) === JSON.stringify(failedBody)
  );
  for (const [subject, tier, source] of [
    [one, "L1", "personal"],
    [
      two,
      dept.accounts.find((d) => d.subject === department).tier,
      "department",
    ],
  ]) {
    const result = await (
      await context.request.get(
        base + "/api/quota/?dimension=user&subject=" + subject
      )
    ).json();
    check(
      "Real plugin persisted " + source,
      result.tier === tier &&
        result.quota_source === source &&
        result.effective_subject === subject
    );
  }
  const resultEvent = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载结果与工单" }).click();
  const result = JSON.parse(
    await fs.readFile(await (await resultEvent).path(), "utf8")
  );
  check(
    "Download contains per-row audit tickets",
    result.jobs.length === 2 &&
      result.jobs.every(
        (j) =>
          j.status === "success" && j.body.ticket_id.startsWith(result.batch)
      )
  );
  await page.screenshot({ path: out + "/import-results.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  check(
    "Import mobile has no page overflow",
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth
    )
  );
  await page.screenshot({ path: out + "/import-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "关闭导入" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
} catch (e) {
  checks.push({ name: "Browser failure", passed: false, detail: e.message });
  await page.screenshot({ path: out + "/failure.png", fullPage: true });
  console.error(e);
  process.exitCode = 1;
} finally {
  await fs.writeFile(
    out + "/browser-report.json",
    JSON.stringify(
      {
        at: new Date().toISOString(),
        passed: checks.every((c) => c.passed),
        checks,
      },
      null,
      2
    )
  );
  await browser.close();
}
