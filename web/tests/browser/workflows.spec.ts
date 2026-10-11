import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
async function emptyFixture(page: Page) {
  const calls: { endpoint: string; body: unknown; raw: string }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/').pop()!; const raw = route.request().postData() || ''; let body: unknown = {}; try { body = route.request().postDataJSON(); } catch { /* multipart */ } calls.push({ endpoint, body, raw });
    const defaults: Record<string, unknown> = { getBookshelf: [], getBookSources: [], getBookGroups: [], getUserInfo: { secure: false }, getTxtTocRules: [{ name: '通用章节', rule: '^第.*章' }], getBookmarks: [], getReplaceRules: [], library: [], categories: [{ title: '今日', url: 'https://rss.test/today' }, { title: '历史', url: 'https://rss.test/history' }], getRssSources: [{ sourceUrl: 'https://rss.test', sourceName: '阅读日报', sortUrl: '今日::https://rss.test/today\n历史::https://rss.test/history' }], importBookPreview: [{ book: { bookUrl: '/assets/default/book/测试.txt', name: '测试本地书', origin: 'loc_book', author: '测试作者' }, chapters: [{ index: 0, title: '第一章 起点', url: '0' }] }], getRssArticles: { first: [{ title: '今天的文章', link: 'https://rss.test/article', origin: 'https://rss.test' }], second: '' }, getRssContent: '<p>文章正文可读。</p><script>window.evil=true</script>' };
    await route.fulfill({ json: { isSuccess: true, data: defaults[endpoint] ?? true } });
  });
  return calls;
}
test('TXT upload previews chapters and saves the server-normalized book', async ({ page }) => {
  const calls = await emptyFixture(page); await page.goto('/'); await page.getByRole('button', { name: '导入书籍', exact: true }).click(); await page.locator('input[type=file]').setInputFiles({ name: '测试.txt', mimeType: 'text/plain', buffer: Buffer.from('第一章 起点\n正文。') }); await expect(page.getByText('测试本地书', { exact: true })).toBeVisible(); await expect(page.getByText('第一章 起点', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '加入书架', exact: true }).click(); expect(calls.find((call) => call.endpoint === 'importBookPreview')?.raw).toContain('Content-Disposition: form-data'); expect(calls.some((call) => call.endpoint === 'saveBook' && (call.body as { origin: string }).origin === 'loc_book')).toBe(true);
});
test('RSS opens actual article content and strips scripts', async ({ page }) => {
  const calls = await emptyFixture(page); await page.goto('/#/rss'); await page.getByRole('button', { name: '阅读日报', exact: true }).click(); await page.getByRole('button', { name: '今天的文章', exact: true }).click(); await expect(page.getByText('文章正文可读。', { exact: true })).toBeVisible(); expect(await page.evaluate(() => 'evil' in window)).toBe(false); expect(calls.some((call) => call.endpoint === 'getRssContent' && (call.body as { link: string }).link === 'https://rss.test/article')).toBe(true);
  expect(calls.some((call) => call.endpoint === 'getRssArticles' && (call.body as { sortName: string; sortUrl: string }).sortName === '今日' && (call.body as { sortUrl: string }).sortUrl === 'https://rss.test/today')).toBe(true);
});
test('failed whole-book cache never triggers an export request and exposes failed chapter numbers', async ({ page }) => {
  await emptyFixture(page); let exported = false;
  await page.route('**/reader3/getBookshelf*', (route) => route.fulfill({ json: { isSuccess: true, data: [{ bookUrl: 'https://book.test', name: '待缓存书籍', type: 0, totalChapterNum: 3 }] } }));
  await page.route('**/reader3/cacheBookSSE*', (route) => route.fulfill({ contentType: 'text/event-stream', body: 'event: end\ndata: {"cachedCount":2,"successCount":2,"failedCount":1,"totalCount":3,"failedChapters":[1],"complete":false}\n\n' }));
  await page.route('**/reader3/exportBook*', (route) => { exported = true; return route.fulfill({ body: 'must not download' }); });
  await page.goto('/#/settings'); await page.getByRole('tab', { name: '缓存与下载', exact: true }).click(); await page.getByRole('button', { name: '缓存与导出', exact: true }).click(); await page.getByRole('button', { name: '缓存全部并导出', exact: true }).click(); await expect(page.getByText('失败章节：第 2 章', { exact: true })).toBeVisible(); await expect(page.getByRole('button', { name: '重试并导出', exact: true })).toBeVisible(); expect(exported).toBe(false);
});
test('EPUB import rebuilds the selected directory mode before saving', async ({ page }) => {
  const calls = await emptyFixture(page); const book = { bookUrl: '/assets/default/book/测试.epub', name: '测试 EPUB', origin: 'loc_book', tocUrl: 'spin+toc' }; let rebuilt: unknown;
  await page.route('**/reader3/importBookPreview*', (route) => route.fulfill({ json: { isSuccess: true, data: [{ book, chapters: [{ index: 0, title: '第一章', url: 'one.xhtml' }] }] } })); await page.route('**/reader3/getChapterListByRule*', (route) => { rebuilt = route.request().postDataJSON(); return route.fulfill({ json: { isSuccess: true, data: { book: { ...book, tocUrl: 'toc' }, chapters: [{ index: 0, title: '目录章节', url: 'one.xhtml' }] } } }); });
  await page.goto('/'); await page.getByRole('button', { name: '导入书籍', exact: true }).click(); await page.locator('input[type=file]').setInputFiles({ name: '测试.epub', mimeType: 'application/epub+zip', buffer: Buffer.from('epub fixture') }); await page.getByLabel('EPUB 目录方式', { exact: true }).selectOption('toc'); await page.getByRole('button', { name: '按规则重建目录', exact: true }).click(); await expect(page.getByText('目录章节', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '加入书架', exact: true }).click(); expect(rebuilt).toMatchObject({ tocUrl: 'toc' }); expect(calls.find((call) => call.endpoint === 'saveBook')?.body).toMatchObject({ tocUrl: 'toc' });
});
