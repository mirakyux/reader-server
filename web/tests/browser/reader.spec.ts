import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
test.use({ serviceWorkers: 'block' });
const book = { bookUrl: 'https://books.test/three-body', name: '三体', author: '刘慈欣', origin: 'https://source.test', originName: '测试书源', totalChapterNum: 3, durChapterIndex: 0, durChapterTitle: '第一章 科学边界', intro: '文明与宇宙的故事。', group: 1 };
const source = { bookSourceUrl: 'https://source.test', bookSourceName: '测试书源', enabled: true, bookSourceGroup: '科幻', exploreUrl: '科幻小说::/science' };
const chapters = ['第一章 科学边界', '第二章 台球', '第三章 射手和农场主'].map((title, index) => ({ title, index, url: `/chapter/${index}` }));
async function fixture(page: Page, overrides: Record<string, unknown> = {}) {
  await page.addInitScript(() => Object.defineProperty(document, 'hidden', { get: () => true }));
  const calls: { endpoint: string; body: Record<string, unknown> }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/').pop()!; let body: Record<string, unknown> = {}; try { body = route.request().postDataJSON() || {}; } catch { /* multipart */ } calls.push({ endpoint, body });
    const defaults: Record<string, unknown> = { getBookshelf: [book], getBookSources: [source], getBookGroups: [{ groupId: 1, groupName: '科幻', show: true }], getUserInfo: { secure: false }, getReplaceRules: [], getBookmarks: [], getRssSources: [], getTxtTocRules: [], getBookInfo: book, getChapterList: chapters, getBookContent: `${chapters[Number(body.index) || 0].title}\n宇宙很大，生活更大。正文内容正在这里继续。`, saveBookProgress: true, parseBookSource: [{ ...source, bookSourceUrl: 'https://new.test', bookSourceName: '新脚本书源', mainJs: 'function search() {}' }], getBookSourceCookies: { cookie: '' }, getBookSourceLoginUi: { version: 2, rows: [{ name: '用户名', key: 'username', type: 'text' }, { name: '密码', key: 'password', type: 'password' }], state: {} }, loginBookSource: { success: true, close: true, state: {} }, searchBookMulti: { list: [book], lastIndex: 0 }, getRssArticles: { first: [], second: '' }, getLocalStoreFileList: [], getWebdavFileList: [] };
    if (endpoint === 'cacheBookSSE') { await route.fulfill({ contentType: 'text/event-stream', body: `data: {"cachedCount":1,"successCount":1,"failedCount":0,"totalCount":3}\n\nevent: end\ndata: {"cachedCount":3,"successCount":3,"failedCount":0,"totalCount":3,"complete":true}\n\n` }); return; }
    if (endpoint === 'exportBook') { await route.fulfill({ contentType: 'text/plain', headers: { 'Content-Disposition': 'attachment; filename=test.txt' }, body: '三体\n完整章节正文' }); return; }
    await route.fulfill({ json: { isSuccess: true, data: endpoint in overrides ? overrides[endpoint] : defaults[endpoint] ?? true } });
  });
  return calls;
}
test('shelf, zero-based chapter progress, catalog and reading settings work', async ({ page }) => {
  const calls = await fixture(page); const errors: string[] = []; page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/'); await expect(page.getByRole('heading', { name: '我的书架', exact: true })).toBeVisible(); await page.getByRole('button', { name: '继续阅读', exact: true }).click();
  await expect(page.getByRole('heading', { name: '第一章 科学边界' })).toBeVisible(); await expect(page.getByText('宇宙很大，生活更大。正文内容正在这里继续。')).toBeVisible();
  expect(calls.some((call) => call.endpoint === 'saveBookProgress' && call.body.index === 0)).toBe(true);
  await page.getByRole('button', { name: '目录', exact: true }).click(); await page.getByRole('button', { name: '2 第二章 台球' }).click(); await expect(page.getByRole('heading', { name: '第二章 台球' })).toBeVisible();
  await page.getByRole('button', { name: '阅读设置', exact: true }).click(); await page.getByRole('button', { name: '夜读', exact: true }).click(); await expect(page.locator('.reader-view')).toHaveClass(/theme-night/); expect(errors).toEqual([]);
});
test('sources support script preview, grouped filtering and real dynamic login', async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/sources'); await expect(page.getByRole('heading', { name: '书源工作台', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '导入书源', exact: true }).click(); await page.getByLabel('书源 JSON / JavaScript').fill('function search(keyword) { return []; }'); await page.getByRole('button', { name: '预览书源', exact: true }).click(); await expect(page.getByText('新脚本书源', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '导入 1 个书源', exact: true }).click();
  expect(calls.some((call) => call.endpoint === 'parseBookSource' && String(call.body.content).includes('function search'))).toBe(true); expect(calls.some((call) => call.endpoint === 'saveBookSources')).toBe(true);
  await page.getByRole('button', { name: '更多操作 · 测试书源', exact: true }).click(); await page.getByRole('menuitem', { name: '书源登录', exact: true }).click(); await page.getByLabel('用户名', { exact: true }).fill('reader'); await page.getByLabel('密码', { exact: true }).fill('secret123'); await page.getByRole('button', { name: '提交登录', exact: true }).click();
  expect(calls.some((call) => call.endpoint === 'loginBookSource' && (call.body.form as { username: string }).username === 'reader')).toBe(true);
});
test('directory source rules receive the actual details entry flag', async ({ page }) => {
  const calls = await fixture(page);
  await page.goto('/');
  await page.getByRole('button', { name: '三体', exact: true }).click();
  await page.getByRole('button', { name: '开始阅读', exact: true }).click();
  await expect(page.getByRole('heading', { name: '第一章 科学边界' })).toBeVisible();
  expect(calls.filter((call) => call.endpoint === 'getChapterList').at(-1)?.body.fromBookInfo).toBe(true);
  await page.goto('/');
  await page.getByRole('button', { name: '继续阅读', exact: true }).click();
  await expect(page.getByRole('heading', { name: '第一章 科学边界' })).toBeVisible();
  expect(calls.filter((call) => call.endpoint === 'getChapterList').at(-1)?.body.fromBookInfo).toBe(false);
});
test('complete cache ends before a download request, without exporting partial content', async ({ page }) => {
  const calls = await fixture(page); await page.goto('/#/settings'); await page.getByRole('tab', { name: '缓存与下载', exact: true }).click(); await page.getByRole('button', { name: '缓存与导出', exact: true }).click(); const download = page.waitForEvent('download'); await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click(); expect((await download).suggestedFilename()).toBe('test.txt');
  const endpoints = calls.map((call) => call.endpoint); expect(endpoints.indexOf('exportBook')).toBeGreaterThan(endpoints.indexOf('cacheBookSSE'));
});
test('mobile navigation and all secondary pages remain usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await fixture(page); await page.goto('/'); await expect(page.getByRole('navigation', { name: '主导航', exact: true })).toBeVisible();
  for (const [path, heading] of [['/search', '搜索好书'], ['/explore', '发现书海'], ['/bookmarks', '书签与摘录'], ['/rules', '正文替换规则'], ['/rss', '订阅'], ['/local', '本地书仓'], ['/webdav', 'WebDAV 与备份']]) { await page.goto(`/#${path}`); await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); }
});
test('dynamic login preserves input, initializes new fields and carries action state', async ({ page }) => {
  await fixture(page, { getBookSourceLoginUi: { version: 2, rows: [{ name: '用户名', key: 'username', type: 'text' }, { name: '获取验证码', type: 'button', action: 'sendCode' }], state: {} } });
  const submitted: Record<string, unknown>[] = [];
  await page.route('**/reader3/loginBookSource*', async (route) => { const body = route.request().postDataJSON() as Record<string, unknown>; submitted.push(body); await route.fulfill({ json: { isSuccess: true, data: body.action === 'sendCode' ? { success: true, close: false, state: { nonce: 'verified' }, rows: [{ name: '用户名', key: 'username', type: 'text' }, { name: '验证码', key: 'code', type: 'text', value: '2468' }, { name: '记住会话', key: 'remember', type: 'toggle', value: 'true' }] } : { success: true, close: true, state: {} } } }); });
  await page.goto('/#/sources'); await page.getByRole('button', { name: '更多操作 · 测试书源', exact: true }).click(); await page.getByRole('menuitem', { name: '书源登录', exact: true }).click(); await page.getByLabel('用户名', { exact: true }).fill('reader'); await page.getByRole('button', { name: '获取验证码', exact: true }).click(); await expect(page.getByLabel('验证码', { exact: true })).toHaveValue('2468'); await expect(page.getByLabel('用户名', { exact: true })).toHaveValue('reader'); await page.getByRole('button', { name: '提交登录', exact: true }).click();
  expect(submitted[1]).toMatchObject({ form: { username: 'reader', code: '2468', remember: true }, state: { nonce: 'verified' } });
});
test('EPUB text stays sandboxed and reacts to reading settings without reloading chapters', async ({ page }) => {
  await fixture(page, { getBookContent: '/epub/test/one.xhtml' }); await page.route('**/epub/test/one.xhtml', (route) => route.fulfill({ contentType: 'text/html', body: '<p>阅读图书。</p><script>parent.epubScript=true</script>' })); await page.goto('/'); await page.getByRole('button', { name: '继续阅读', exact: true }).click();
  const frame = page.frameLocator('iframe[title="EPUB 章节正文"]'); await expect(frame.getByText('阅读图书。')).toBeVisible(); expect(await page.evaluate(() => 'epubScript' in window)).toBe(false); await page.getByRole('button', { name: '阅读设置', exact: true }).click(); await page.getByLabel('简繁转换', { exact: true }).selectOption('traditional'); await expect(frame.getByText('閱讀圖書。')).toBeVisible(); await page.getByLabel('字重', { exact: true }).selectOption('700'); await expect(frame.locator('body')).toHaveCSS('font-weight', '700'); await page.getByLabel('简繁转换', { exact: true }).selectOption('original'); await expect(frame.getByText('阅读图书。')).toBeVisible();
});
test('bulk group removal leaves books outside the group untouched', async ({ page }) => {
  const calls = await fixture(page, { getBookshelf: [book, { ...book, bookUrl: 'https://books.test/ungrouped', name: '未分组书籍', group: 0 }] }); await page.goto('/'); await page.getByRole('button', { name: '管理书架', exact: true }).click(); await page.getByRole('menuitem', { name: '批量管理', exact: true }).click(); await page.getByRole('checkbox', { name: '选择全部', exact: true }).check(); await page.getByLabel('目标分组', { exact: true }).selectOption('1'); await page.getByRole('button', { name: '移出分组', exact: true }).click();
  await expect.poll(() => calls.filter((call) => call.endpoint === 'removeBookGroupMulti').length).toBe(1); expect(calls.find((call) => call.endpoint === 'removeBookGroupMulti')?.body).toMatchObject({ groupId: 1, bookList: [{ bookUrl: book.bookUrl }] }); expect((calls.find((call) => call.endpoint === 'removeBookGroupMulti')?.body.bookList as unknown[]).length).toBe(1);
});
test('reader retries failed content even when the refreshed directory length stays unchanged', async ({ page }) => {
  await fixture(page); let attempts = 0; await page.route('**/reader3/getBookContent*', (route) => route.fulfill({ json: ++attempts === 1 ? { isSuccess: false, errorMsg: '临时正文错误' } : { isSuccess: true, data: '重试后完整正文。' } })); await page.goto('/'); await page.getByRole('button', { name: '继续阅读', exact: true }).click(); await expect(page.getByText('临时正文错误', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '重试', exact: true }).click(); await expect(page.getByText('重试后完整正文。', { exact: true })).toBeVisible();
});
