import { expect, test, type Page, type Route } from '@playwright/test';

const book = { bookUrl: 'https://books.test/details', name: '目录验收书', author: '作者', origin: 'https://source.test', originName: '测试书源', totalChapterNum: 0 };
const chapters = ['第一章', '第二章', '第三章'].map((title, index) => ({ title, index, url: `${book.bookUrl}/${index}` }));

for (const width of [1440, 390, 320]) test(`all shelf detail actions remain inside the dialog at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 });
  await fixture(page, {}, true);
  const dialog = await details(page, true);
  await expect(dialog.getByText('共 3 章', { exact: true })).toBeVisible();
  const box = (await dialog.boundingBox())!;
  const actions = await dialog.locator('[data-slot="dialog-footer"] button').evaluateAll((buttons) => buttons.map((button) => {
    const rect = button.getBoundingClientRect();
    return { left: rect.left, right: rect.right, label: button.getAttribute('aria-label') || button.textContent };
  }));
  for (const action of actions) {
    expect(action.left, action.label || '').toBeGreaterThanOrEqual(box.x);
    expect(action.right, action.label || '').toBeLessThanOrEqual(box.x + box.width);
  }
});
type Handler = (route: Route) => Promise<void>;
async function fixture(page: Page, overrides: Record<string, Handler> = {}, shelf = false) {
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = route.request().method() === 'POST' ? route.request().postDataJSON() || {} : {};
    calls.push({ endpoint, body });
    if (overrides[endpoint]) return overrides[endpoint](route);
    const defaults: Record<string, unknown> = {
      getBookshelf: shelf ? [book] : [], getBookSources: [{ bookSourceUrl: book.origin, bookSourceName: '测试书源', enabled: true }],
      getBookGroups: [], getUserInfo: { secure: false }, drainSourceInteractions: { actions: [] },
      searchBookMulti: { list: [book], lastIndex: 0 }, getBookInfo: book, getChapterList: chapters, saveBook: { ...book, ...body },
    };
    await route.fulfill({ json: { isSuccess: true, data: defaults[endpoint] ?? true } });
  });
  return calls;
}
async function details(page: Page, shelf = false) {
  await page.goto(shelf ? '/#/' : '/#/search');
  if (!shelf) {
    await page.getByLabel('书名 / 作者', { exact: true }).fill(book.name);
    await page.getByRole('button', { name: '搜索', exact: true }).click();
  }
  await page.locator('.book-title-button').click();
  return page.getByRole('dialog', { name: '书籍详情' });
}

test('search book details count the real catalog when metadata has no chapter count', async ({ page }) => {
  const calls = await fixture(page);
  const dialog = await details(page);
  await expect(dialog.getByText('共 3 章', { exact: true })).toBeVisible();
  expect(calls.find((call) => call.endpoint === 'getChapterList')?.body).toMatchObject({ url: book.bookUrl, bookSourceUrl: book.origin, fromBookInfo: true });
  await dialog.getByRole('button', { name: '加入书架', exact: true }).click();
  expect(calls.find((call) => call.endpoint === 'saveBook')?.body.totalChapterNum).toBe(3);
});

test('bookshelf details also recover a missing chapter count', async ({ page }) => {
  const calls = await fixture(page, {}, true);
  const dialog = await details(page, true);
  await expect(dialog.getByText('共 3 章', { exact: true })).toBeVisible();
  expect(calls.some((call) => call.endpoint === 'getChapterList')).toBe(true);
});

test('a slow metadata reply preserves the book name and cover being edited', async ({ page }) => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await fixture(page, { getBookInfo: async (route) => { await pending; await route.fulfill({ json: { isSuccess: true, data: { ...book, name: '源站返回的旧名字', customCoverUrl: '' } } }); } });
  const dialog = await details(page);
  await dialog.getByLabel('书名', { exact: true }).fill('用户修改的名字');
  await dialog.getByLabel('封面 URL', { exact: true }).fill('https://images.test/my-cover.jpg');
  release();
  await expect(dialog.getByText('共 3 章', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('书名', { exact: true })).toHaveValue('用户修改的名字');
  await expect(dialog.getByLabel('封面 URL', { exact: true })).toHaveValue('https://images.test/my-cover.jpg');
});

test('a failed catalog stays explicit and can be retried without losing edits', async ({ page }) => {
  let attempts = 0;
  await fixture(page, { getChapterList: async (route) => { attempts++; await route.fulfill({ json: attempts === 1 ? { isSuccess: false, errorMsg: '目录请求暂时失败' } : { isSuccess: true, data: chapters } }); } });
  const dialog = await details(page);
  await expect(dialog.getByText('目录请求暂时失败', { exact: true })).toBeVisible();
  await expect(dialog.getByText('共 0 章', { exact: true })).toHaveCount(0);
  await dialog.getByLabel('书名', { exact: true }).fill('重试保留的名字');
  await dialog.getByRole('button', { name: '重试', exact: true }).click();
  await expect(dialog.getByText('共 3 章', { exact: true })).toBeVisible();
  await expect(dialog.getByLabel('书名', { exact: true })).toHaveValue('重试保留的名字');
  expect(attempts).toBe(2);
});

test('known shelf chapter counts do not refetch the whole directory', async ({ page }) => {
  const calls = await fixture(page, { getBookshelf: async (route) => route.fulfill({ json: { isSuccess: true, data: [{ ...book, totalChapterNum: 452 }] } }) }, true);
  const dialog = await details(page, true);
  await expect(dialog.getByText('共 452 章', { exact: true })).toBeVisible();
  expect(calls.some((call) => call.endpoint === 'getChapterList')).toBe(false);
});

test('search details reuse a shelf book with its existing count and full metadata', async ({ page }) => {
  const calls = await fixture(page, { getBookshelf: async (route) => route.fulfill({ json: { isSuccess: true, data: [{ ...book, totalChapterNum: 452, intro: '已缓存的完整简介' }] } }) });
  const dialog = await details(page);
  await expect(dialog.getByText('共 452 章', { exact: true })).toBeVisible();
  await expect(dialog.getByText('已缓存的完整简介', { exact: true })).toBeVisible();
  expect(calls.some((call) => ['getBookInfo', 'getChapterList'].includes(call.endpoint))).toBe(false);
});
