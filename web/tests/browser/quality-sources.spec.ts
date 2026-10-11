import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const sources = Array.from({ length: 14 }, (_, index) => ({
  bookSourceUrl: `https://source-${index + 1}.test/a-long-source-address`, bookSourceName: `测试书源 ${index + 1}`,
  bookSourceGroup: '小说,常用', enabled: index !== 1, enabledExplore: true,
  ruleSearch: { name: '@css:h2@text' }, ruleContent: { content: '@css:main@text' },
}));
async function fixture(page: Page) {
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    let body: Record<string, unknown> = {}; try { body = route.request().postDataJSON() || {}; } catch { /* GET */ }
    calls.push({ endpoint, body });
    const data: Record<string, unknown> = { getBookshelf: [], getBookSources: sources, getBookGroups: [], getUserInfo: { secure: false } };
    await route.fulfill({ json: { isSuccess: true, data: data[endpoint] ?? true } });
  });
  await page.goto('/#/sources');
  await expect(page.getByRole('heading', { name: '书源工作台', exact: true })).toBeVisible();
  return calls;
}

for (const close of ['Escape', '关闭按钮', '遮罩'] as const) {
  test(`source JSON draft survives cancelled ${close} close and can be explicitly discarded`, async ({ page }) => {
    await fixture(page);
    const row = page.getByRole('row').filter({ hasText: '测试书源 1' }).first();
    const edit = row.getByRole('button', { name: '编辑', exact: true }); await edit.click();
    const editor = page.getByRole('textbox', { name: '书源 JSON 编辑器', exact: true });
    const original = await editor.inputValue(); const draft = original.replace('测试书源 1', '没有保存的名称'); await editor.fill(draft);
    if (close === 'Escape') await page.keyboard.press('Escape');
    else if (close === '关闭按钮') await page.getByRole('button', { name: '关闭编辑器', exact: true }).click();
    else await page.locator('[data-slot="sheet-overlay"]').click({ position: { x: 10, y: 150 } });
    const confirm = page.getByRole('dialog', { name: '放弃未保存的修改？', exact: true });
    await expect(confirm).toBeVisible(); await confirm.getByRole('button', { name: '继续编辑', exact: true }).click();
    await expect(editor).toHaveValue(draft); await expect(editor).toBeFocused();
    await page.keyboard.press('Escape'); await confirm.getByRole('button', { name: '放弃修改', exact: true }).click();
    await expect(editor).toHaveCount(0); await expect(edit).toBeFocused(); await edit.click();
    await expect(editor).toHaveValue(original);
  });
}

test('source form/rules drafts are protected, but saving closes without a discard prompt', async ({ page }) => {
  const calls = await fixture(page);
  await page.getByRole('row').filter({ hasText: '测试书源 1' }).first().getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('tab', { name: '基础与脚本', exact: true }).click(); await page.getByLabel('书源名称', { exact: true }).fill('保存的新名称');
  await page.getByRole('tab', { name: '逐字段规则', exact: true }).click(); await page.getByLabel('书名', { exact: true }).first().fill('@css:h1@text');
  await page.keyboard.press('Escape'); const confirm = page.getByRole('dialog', { name: '放弃未保存的修改？', exact: true });
  await expect(confirm).toBeVisible(); await confirm.getByRole('button', { name: '继续编辑', exact: true }).click();
  await page.getByRole('button', { name: '保存书源', exact: true }).click(); await expect(page.getByRole('textbox', { name: '书源 JSON 编辑器', exact: true })).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: '书源编辑器', exact: true })).toHaveCount(0);
  expect(calls.find((call) => call.endpoint === 'saveBookSource')?.body).toMatchObject({ bookSourceName: '保存的新名称', ruleSearch: { name: '@css:h1@text' } });
});

test('desktop source rows put secondary actions in a keyboard accessible menu', async ({ page }) => {
  await fixture(page); const row = page.getByRole('row').filter({ hasText: '测试书源 1' }).first();
  await expect(row.getByRole('button', { name: '调试', exact: true })).toHaveCount(0);
  const more = row.getByRole('button', { name: '更多操作 · 测试书源 1', exact: true }); await more.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('menuitem', { name: '调试', exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '能力与校验', exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '书源登录', exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '删除书源', exact: true })).toBeVisible();
  await page.keyboard.press('Escape'); await expect(more).toBeFocused(); await more.click();
  await page.getByRole('menuitem', { name: '调试', exact: true }).click(); await expect(page.getByRole('dialog', { name: '调试 · 测试书源 1', exact: true })).toBeVisible();
});

for (const width of [320, 390]) {
  test(`mobile ${width}px source cards keep edit, toggle, selection and pagination in view`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 }); const calls = await fixture(page);
    const card = page.getByRole('article', { name: '书源 · 测试书源 1', exact: true }); await expect(card).toBeVisible();
    for (const control of [card.getByRole('button', { name: '编辑', exact: true }), card.getByRole('switch', { name: '启用 测试书源 1', exact: true }), card.getByRole('checkbox', { name: '选择 测试书源 1', exact: true })]) {
      await expect(control).toBeVisible(); const box = await control.boundingBox(); expect(box?.x).toBeGreaterThanOrEqual(0); expect((box?.x || 0) + (box?.width || 0)).toBeLessThanOrEqual(width);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await card.getByRole('switch').click(); expect(calls.find((call) => call.endpoint === 'saveBookSource')?.body).toMatchObject({ enabled: false });
    await card.getByRole('checkbox').click(); await expect(page.getByText('已选择 1 个书源', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '下一页', exact: true }).click(); await expect(page.getByRole('article', { name: '书源 · 测试书源 14', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '上一页', exact: true }).click(); await card.getByRole('button', { name: '编辑', exact: true }).click();
    await expect(page.getByRole('textbox', { name: '书源 JSON 编辑器', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  });
}
