import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const book = { bookUrl: 'https://reader-tools.test/book', tocUrl: 'https://reader-tools.test/book/toc', name: '阅读工具测试书', author: '阅读作者', kind: '很长的分类和评分元数据不应当呈现为阅读区按钮', origin: 'https://reader-tools.test', type: 0, durChapterIndex: 0, totalChapterNum: 2 };
const chapters = [{ index: 0, title: '第一章', url: '/one', isVip: true }, { index: 1, title: '第二章', url: '/two' }];
type Call = { path: string; body: Record<string, unknown> };

async function fixture(page: Page, options: { events?: boolean; handled?: boolean; clipboard?: 'missing' | 'rejected'; interaction?: boolean } = {}) {
  await page.addInitScript(({ clipboard }) => {
    Object.defineProperty(document, 'hidden', { get: () => true });
    if (clipboard) {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard === 'missing' ? undefined : { writeText: async () => { throw new Error('Permission denied'); } } });
      Object.defineProperty(document, 'execCommand', { configurable: true, value: (command: string) => {
        if (command !== 'copy') return false;
        (window as unknown as { copiedText: string }).copiedText = (document.activeElement as HTMLTextAreaElement).value;
        return true;
      } });
    }
  }, { clipboard: options.clipboard });
  const calls: Call[] = [];
  await page.route('**/reader3/**', async (route) => {
    const path = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() || {} : {};
    calls.push({ path, body });
    const values: Record<string, unknown> = {
      getBookshelf: [book], getBookSources: [{ bookSourceUrl: book.origin, bookSourceName: '工具书源', enabled: true }], getBookGroups: [], getUserInfo: { secure: false }, getReplaceRules: [], getBookmarks: [], getChapterList: chapters,
      getBookContentDetails: { content: '第一段正文。\n第二段用于选择的正文。', chapter: chapters[Number(body.index) || 0], type: 0 },
      getSourceCapabilities: { eventListener: options.events ?? true, customButton: '书源自定义操作', pay: true, review: true, reviewQuote: true },
      runSourceAction: { handled: options.handled ?? false, result: '动作返回的实际结果', actions: options.interaction && body.event === 'clickCustomButton' ? [{ id: 'custom-browser', type: 'browser', title: '书源登录', url: 'https://login.test', bookSourceUrl: book.origin }] : [] },
      openSourceBrowser: { id: 'browser-session', url: 'https://login.test', title: '书源登录', width: 1024, height: 768, screenshot: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==' },
      getSourceReviews: { reviews: [{ content: '实际选文段评', postTime: '今天' }] }, drainSourceInteractions: { actions: [] },
    };
    await route.fulfill({ json: { isSuccess: true, data: values[path] ?? true } });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '继续阅读', exact: true }).click();
  await expect(page.locator('.reader-content')).toContainText('第二段用于选择的正文。');
  return calls;
}

async function openMenu(page: Page) {
  await page.getByRole('button', { name: '更多阅读操作', exact: true }).click();
  await expect(page.getByRole('menu')).toBeVisible();
}

test('book tools open from a compact menu and action results never occupy chapter content', async ({ page }) => {
  const calls = await fixture(page);
  await expect(page.getByText('书籍链接与书源动作', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: book.kind, exact: true })).toHaveCount(0);
  await openMenu(page);
  await page.getByRole('menuitem', { name: '书源自定义操作', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('动作返回的实际结果');
  await expect(page.locator('.reader-main')).not.toContainText('动作返回的实际结果');
  expect(calls.find((call) => call.path === 'runSourceAction' && call.body.event === 'clickCustomButton')?.body).toMatchObject({ bookSourceUrl: book.origin, book, chapter: chapters[0] });
});

test('paragraph reviews keep the selected text and paragraph index after opening the menu', async ({ page }) => {
  const calls = await fixture(page);
  await page.locator('.reader-content p').nth(1).evaluate((paragraph) => {
    const selection = window.getSelection()!; const range = document.createRange();
    range.selectNodeContents(paragraph); selection.removeAllRanges(); selection.addRange(range);
  });
  await openMenu(page);
  await page.getByRole('menuitem', { name: '查看段评', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('实际选文段评');
  expect(calls.find((call) => call.path === 'getSourceReviews')?.body).toMatchObject({ paragraph: '第二段用于选择的正文。', index: 1 });
});

for (const clipboard of ['missing', 'rejected'] as const) test(`copy book and catalog links works when HTTP clipboard is ${clipboard}`, async ({ page }) => {
  const calls = await fixture(page, { events: false, clipboard });
  await openMenu(page);
  await expect(page.getByRole('menuitem', { name: '点击书名', exact: true })).toHaveCount(0);
  await page.getByRole('menuitem', { name: '复制书籍链接', exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { copiedText: string }).copiedText)).toBe(book.bookUrl);
  await expect(page.getByText('书籍链接已复制', { exact: true })).toBeVisible();
  await openMenu(page);
  await page.getByRole('menuitem', { name: '复制目录链接', exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { copiedText: string }).copiedText)).toBe(book.tocUrl);
  expect(calls.some((call) => call.path === 'runSourceAction')).toBe(false);
});

test('supported click and long click source events survive while read lifecycle remains active', async ({ page }) => {
  const calls = await fixture(page);
  await expect.poll(() => calls.some((call) => call.body.event === 'startRead')).toBe(true);
  await expect.poll(() => calls.some((call) => call.body.event === 'saveRead')).toBe(true);
  await openMenu(page);
  await page.getByRole('menuitem', { name: '点击书名', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.body.event === 'clickBookName')).toBe(true);
  await openMenu(page);
  await page.getByRole('menuitem', { name: '长按作者', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.body.event === 'longClickAuthor')).toBe(true);
  await page.getByRole('button', { name: '返回书架', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.body.event === 'endRead')).toBe(true);
});

test('a handled source copy action overrides the native link copy', async ({ page }) => {
  const calls = await fixture(page, { handled: true, clipboard: 'missing' });
  await openMenu(page);
  await page.getByRole('menuitem', { name: '复制书籍链接', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.body.event === 'clickCopyBookUrl')).toBe(true);
  expect(await page.evaluate(() => (window as unknown as { copiedText?: string }).copiedText)).toBeUndefined();
});

test('a browser source interaction opens without a competing action result dialog', async ({ page }) => {
  await fixture(page, { interaction: true });
  await openMenu(page);
  await page.getByRole('menuitem', { name: '书源自定义操作', exact: true }).click();
  await expect(page.getByAltText('书源浏览器页面')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(page.getByRole('heading', { name: '书源动作结果', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('paid actions still require confirmation after moving into the menu', async ({ page }) => {
  const calls = await fixture(page);
  await openMenu(page);
  await page.getByRole('menuitem', { name: '付费章节操作', exact: true }).click();
  expect(calls.some((call) => call.body.action === 'pay')).toBe(false);
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.body.action === 'pay' && call.body.confirm === true)).toBe(true);
  await expect(page.getByRole('heading', { name: '书源动作结果', exact: true })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(1);
});
