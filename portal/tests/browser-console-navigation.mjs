import { chromium, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import { execFileSync } from 'node:child_process';

const base = process.env.CONSOLE_TEST_URL || 'http://127.0.0.1:18001';
const out = 'docs/evidence-console-navigation';
await fs.mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } });
const page = await context.newPage();
const errors = [], checks = [];
page.on('pageerror', error => errors.push(error.message));
const routes = [
  ['overview', '用量总览', '用量总览'],
  ['accounts', '用户与应用', '个人预算与应用统计'],
  ['departments', '部门模板', '部门模板'],
  ['tiers', '档位配置', '档位配置'],
  ['prices', '模型单价', '模型单价'],
  ['audit', '变更审计', '变更审计'],
];
try {
  const secret = JSON.parse(execFileSync('kubectl', ['--context', 'higress-dev', '-n', 'higress-system', 'get', 'secret', 'higress-console', '-o', 'json'], { encoding: 'utf8' })).data;
  const response = await context.request.post(base + '/session/login', { data: { username: Buffer.from(secret.adminUsername, 'base64').toString(), password: Buffer.from(secret.adminPassword, 'base64').toString() } });
  expect(response.ok()).toBeTruthy();
  await page.goto(base + '/walmart-quota');
  await expect(page).toHaveURL(base + '/walmart-quota/overview');
  for (const [path, label, title] of routes) {
    await page.getByRole('link', { name: label, exact: true }).click();
    await expect(page).toHaveURL(base + '/walmart-quota/' + path);
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await expect(page.locator('.walmart-quota .sidebar, .walmart-quota .topbar, .walmart-quota iframe')).toHaveCount(0);
    await expect(page.locator('.ant-menu-item-selected').getByRole('link', { name: label, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    checks.push(label + ': menu, URL, selected state, refresh, no nested shell');
  }
  await page.getByRole('link', { name: '用量总览', exact: true }).click();
  await page.getByRole('link', { name: '模型单价', exact: true }).click();
  await page.goBack();
  await expect(page.getByRole('heading', { name: '用量总览', exact: true })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole('heading', { name: '模型单价', exact: true })).toBeVisible();
  checks.push('Browser back/forward');
  await page.getByRole('link', { name: '用户与应用', exact: true }).click();
  await page.getByRole('button', { name: '批量导入', exact: true }).click();
  await expect(page.getByLabel('选择 CSV 文件')).toBeVisible();
  await page.getByRole('button', { name: '关闭导入' }).click();
  checks.push('Batch import retained');
  await page.screenshot({ path: out + '/accounts.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: '个人预算与应用统计', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy();
  await page.screenshot({ path: out + '/mobile.png', fullPage: true });
  checks.push('Mobile layout');
  expect(errors).toEqual([]);
  checks.push('No browser errors');
  await fs.writeFile(out + '/report.json', JSON.stringify({ checks, errors }, null, 2));
  console.log(JSON.stringify({ passed: checks.length, checks }, null, 2));
} finally { await browser.close(); }
