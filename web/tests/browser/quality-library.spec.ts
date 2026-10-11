import { expect, test, type Page, type Route } from '@playwright/test';

const sources = ['A', 'B'].map((id) => ({
  bookSourceUrl: `https://source-${id}.test`, bookSourceName: `书源${id}`,
  bookSourceGroup: `分组${id}`, enabled: true, enabledExplore: true,
  exploreUrl: `分类${id}一::/${id}/one\n分类${id}二::/${id}/two`,
}));
type Body = Record<string, unknown>;
type Handler = (route: Route, body: Body) => Promise<void>;
async function fixture(page: Page, handlers: Record<string, Handler>) {
  const calls: { endpoint: string; body: Body }[] = [];
  await page.route('**/reader3/**', async (route) => {
    const endpoint = new URL(route.request().url()).pathname.split('/reader3/')[1];
    const body = (route.request().postDataJSON() || {}) as Body;
    calls.push({ endpoint, body });
    if (handlers[endpoint]) return handlers[endpoint](route, body);
    const defaults: Record<string, unknown> = {
      getBookshelf: [], getBookSources: sources, getBookGroups: [], getUserInfo: { secure: false },
      getSourceExploreUi: {}, drainSourceInteractions: { actions: [] },
    };
    await route.fulfill({ json: { isSuccess: true, data: defaults[endpoint] ?? [] } });
  });
  return calls;
}
const book = (name: string) => ({ bookUrl: `https://result.test/${name}`, name, origin: sources[0].bookSourceUrl });
const ui = (id: string) => ({ categories: [{ index: 0, title: `服务端${id}分类`, type: 'url', url: `/${id}` }], filters: [], values: {} });
function pendingResponse() {
  let release!: (data: unknown) => void;
  const response = new Promise<unknown>((resolve) => { release = resolve; });
  let started = false;
  return {
    handler: async (route: Route) => { started = true; await route.fulfill({ json: { isSuccess: true, data: await response } }); },
    release: (data: unknown) => release(data), started: () => started,
  };
}

test('search more retains submitted conditions and a new search resets its cursor', async ({ page }) => {
  const result: Handler = async (route, body) => route.fulfill({ json: { isSuccess: true, data: { list: [book(`结果${body.key}${body.page}`)], lastIndex: 7 } } });
  const calls = await fixture(page, { searchBookMulti: result, searchBook: async (route, body) => route.fulfill({ json: { isSuccess: true, data: [book(`结果${body.key}${body.page}`)] } }) });
  await page.goto('/#/search');
  await page.getByLabel('书名 / 作者', { exact: true }).fill('甲');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.getByRole('heading', { name: '结果甲1', exact: true })).toBeVisible();
  await page.getByLabel('书名 / 作者', { exact: true }).fill('乙');
  await page.getByLabel('搜索书源', { exact: true }).selectOption(sources[1].bookSourceUrl);
  await page.getByLabel('书源分组', { exact: true }).selectOption('分组B');
  await page.getByLabel('并发数', { exact: true }).selectOption('24');
  await page.getByRole('button', { name: '搜索更多', exact: true }).click();
  await expect(page.getByRole('heading', { name: '结果甲2', exact: true })).toBeVisible();
  expect(calls.filter((call) => call.endpoint.startsWith('searchBook')).at(-1)).toEqual({ endpoint: 'searchBookMulti', body: { key: '甲', bookSourceUrl: '', bookSourceGroup: '', concurrentCount: 12, page: 2, lastIndex: 7 } });
  await expect(page.getByText('搜索条件已变更，点击“搜索”开始新搜索。', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.getByRole('heading', { name: '结果乙1', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '结果甲1', exact: true })).toHaveCount(0);
  expect(calls.filter((call) => call.endpoint.startsWith('searchBook')).at(-1)).toEqual({ endpoint: 'searchBook', body: { key: '乙', bookSourceUrl: sources[1].bookSourceUrl, bookSourceGroup: '分组B', concurrentCount: 24, page: 1, lastIndex: -1 } });
});

test('a delayed discovery UI response cannot replace the current source categories', async ({ page }) => {
  const slow = pendingResponse();
  await fixture(page, { getSourceExploreUi: async (route, body) => body.bookSourceUrl === sources[0].bookSourceUrl ? slow.handler(route) : route.fulfill({ json: { isSuccess: true, data: ui('B') } }) });
  await page.goto('/#/explore');
  await page.getByRole('button', { name: '书源A', exact: true }).click();
  await expect.poll(slow.started).toBe(true);
  await page.getByRole('button', { name: '书源B', exact: true }).click();
  await expect(page.getByRole('button', { name: '服务端B分类', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '打开', exact: true })).toBeDisabled();
  const oldResponse = page.waitForResponse((response) => response.url().includes('getSourceExploreUi') && response.request().postDataJSON().bookSourceUrl === sources[0].bookSourceUrl);
  slow.release(ui('A'));
  // Waiting for the old response proves the absence assertion is not made before it arrives.
  await oldResponse;
  await expect(page.getByRole('button', { name: '打开', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: '服务端B分类', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '服务端A分类', exact: true })).toHaveCount(0);
});

test('changing discovery category ignores an older book response', async ({ page }) => {
  const slow = pendingResponse();
  const calls = await fixture(page, { exploreBook: async (route, body) => body.ruleFindUrl === '/A/one' ? slow.handler(route) : route.fulfill({ json: { isSuccess: true, data: [book('新分类结果')] } }) });
  await page.goto('/#/explore');
  await page.getByRole('button', { name: '书源A', exact: true }).click();
  await page.getByRole('button', { name: '分类A一', exact: true }).click();
  await expect.poll(slow.started).toBe(true);
  await page.getByRole('button', { name: '分类A二', exact: true }).click();
  await expect(page.getByRole('heading', { name: '新分类结果', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toBeDisabled();
  const oldResponse = page.waitForResponse((response) => response.url().includes('exploreBook') && response.request().postDataJSON().ruleFindUrl === '/A/one');
  slow.release([book('旧分类结果')]);
  await oldResponse;
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toBeEnabled();
  await expect(page.getByRole('heading', { name: '旧分类结果', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '新分类结果', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect.poll(() => calls.filter((call) => call.endpoint === 'exploreBook').at(-1)?.body).toMatchObject({ ruleFindUrl: '/A/two', page: 2 });
});

test('changing discovery source clears and ignores an older book response', async ({ page }) => {
  const slow = pendingResponse();
  await fixture(page, { exploreBook: async (route) => slow.handler(route) });
  await page.goto('/#/explore');
  await page.getByRole('button', { name: '书源A', exact: true }).click();
  await page.getByRole('button', { name: '分类A一', exact: true }).click();
  await expect.poll(slow.started).toBe(true);
  await page.getByRole('button', { name: '书源B', exact: true }).click();
  const oldResponse = page.waitForResponse((response) => response.url().includes('exploreBook'));
  slow.release([book('旧书源结果')]);
  await oldResponse;
  await expect(page.getByRole('button', { name: '打开', exact: true })).toBeEnabled();
  await expect(page.getByRole('heading', { name: '书源B', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '旧书源结果', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toHaveCount(0);
});

test('an old discovery control response stays obsolete after returning to the same source', async ({ page }) => {
  const slow = pendingResponse();
  await fixture(page, {
    getSourceExploreUi: async (route, body) => route.fulfill({ json: { isSuccess: true, data: { categories: [{ index: 0, title: `筛选${body.bookSourceUrl === sources[0].bookSourceUrl ? 'A' : 'B'}`, type: 'button' }], filters: [], values: {} } } }),
    applySourceExploreAction: async (route) => slow.handler(route),
  });
  await page.goto('/#/explore');
  await page.getByRole('button', { name: '书源A', exact: true }).click();
  await page.getByRole('button', { name: '筛选A', exact: true }).click();
  await expect.poll(slow.started).toBe(true);
  await page.getByRole('button', { name: '书源B', exact: true }).click();
  await page.getByRole('button', { name: '书源A', exact: true }).click();
  await expect(page.getByRole('button', { name: '筛选A', exact: true })).toBeVisible();
  const oldResponse = page.waitForResponse((response) => response.url().includes('applySourceExploreAction'));
  slow.release(ui('过期'));
  await oldResponse;
  await expect(page.getByRole('button', { name: '筛选A', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '服务端过期分类', exact: true })).toHaveCount(0);
});
