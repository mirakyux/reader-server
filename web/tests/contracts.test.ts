import { describe, expect, it, vi, beforeEach } from 'vitest';
import { api, apiUrl, ApiError, setSession } from '../src/lib/api';
import { chapterIndex, progressPercent, sourceImport, exploreCategories, applyReplaceRules } from '../src/lib/reading';
describe('legacy reader API compatibility', () => {
  beforeEach(() => setSession({ apiBase: '/reader3', accessToken: '', secureKey: '', userNS: '' }));
  it('preserves authentication without leaking it into JSON payloads', async () => {
    setSession({ accessToken: 'user:secret', secureKey: 'manager key', userNS: 'alice' });
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: true, data: true })));
    await api.post('saveBookProgress', { url: 'https://book.test/a?x=1', index: 0 });
    const [url, options] = fetcher.mock.calls[0];
    expect(new URL(String(url)).searchParams.get('accessToken')).toBe('user:secret');
    expect(new URL(String(url)).searchParams.get('userNS')).toBe('alice');
    expect(options?.body).toBe(JSON.stringify({ url: 'https://book.test/a?x=1', index: 0 }));
    expect(options?.credentials).toBe('include');
  });
  it('uses a configured standalone API and handles trailing slashes', () => { setSession({ apiBase: 'https://api.test/reader3/' }); expect(apiUrl('getBookshelf')).toBe('https://api.test/reader3/getBookshelf'); });
  it('propagates server errors and requests login for NEED_LOGIN', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: false, data: 'NEED_LOGIN', errorMsg: '请登录后使用' })));
    const listener = vi.fn(); window.addEventListener('reader:login', listener);
    await expect(api.get('getBookshelf')).rejects.toEqual(new ApiError('请登录后使用', 'NEED_LOGIN'));
    expect(listener).toHaveBeenCalledOnce(); window.removeEventListener('reader:login', listener);
  });
  it('lets the browser choose multipart boundaries for book import', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: true, data: [] })));
    const form = new FormData(); form.append('file0', new File(['第一章\n正文'], 'book.txt'));
    await api.post('importBookPreview', form); expect(fetcher.mock.calls[0][1]?.body).toBe(form); expect(fetcher.mock.calls[0][1]?.headers).toBeUndefined();
  });
});
describe('reading and source import regression', () => {
  it('restores chapter zero and clamps progress after catalog updates', () => { expect(chapterIndex({ bookUrl: 'a', name: '书', durChapterIndex: 0 }, 50)).toBe(0); expect(chapterIndex({ bookUrl: 'a', name: '书', durChapterIndex: 40 }, 5)).toBe(4); expect(progressPercent(0, 100)).toBe(1); expect(progressPercent(0, 0)).toBe(0); });
  it('imports BOM, single objects, JS rules and legacy headerMap without evaluating scripts', () => { const imported = sourceImport('\uFEFF' + JSON.stringify({ bookSourceUrl: 'https://source.test', bookSourceName: 'JS书源', headerMap: { Cookie: 'a=1' }, ruleSearch: { name: '@js:java.getString("a")' } })); expect(imported[0].header).toBe('{"Cookie":"a=1"}'); expect(imported[0].ruleSearch?.name).toContain('@js:'); expect(imported[0].headerMap).toBeUndefined(); });
  it('refuses malformed imports before mutations', () => { expect(() => sourceImport('[{"bookSourceName":"无地址"}]')).toThrow('缺少书源名称或地址'); expect(() => sourceImport('[]')).toThrow('为空'); });
  it('keeps :: inside discovery URLs and never evaluates JavaScript in the browser', () => { expect(exploreCategories('排行::https://test/a::b')[0].url).toBe('https://test/a::b'); expect(exploreCategories('@js:window.evil()')).toEqual([]); });
  it('applies enabled scoped replacements and tolerates invalid regex', () => { const book = { bookUrl: 'a', name: '甲' }; expect(applyReplaceRules('广告 正文', [{ id: 1, name: '清理', pattern: '广告 ', replacement: '', scope: '甲', isEnabled: true, isRegex: false }, { id: 2, name: '坏规则', pattern: '[', replacement: '', isEnabled: true, isRegex: true }], book)).toBe('正文'); });
});
