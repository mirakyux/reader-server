import { describe, expect, it, vi } from 'vitest';
import { completeCache, exportBook } from '../src/lib/download';
describe('whole-book export contract', () => {
  it('requires an explicit complete result with all chapters and zero failures', () => { expect(completeCache({ cachedCount: 10, successCount: 10, failedCount: 0, totalCount: 10, complete: true })).toBe(true); expect(completeCache({ cachedCount: 9, successCount: 9, failedCount: 1, totalCount: 10, complete: false })).toBe(false); expect(completeCache({ cachedCount: 10, successCount: 10, failedCount: 0, totalCount: 10 })).toBe(false); expect(completeCache({ cachedCount: 0, successCount: 0, failedCount: 0, totalCount: 0, complete: true })).toBe(false); });
  it('rejects a JSON server error instead of downloading it as a book', async () => { vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: false, errorMsg: '章节缓存不完整' }), { headers: { 'Content-Type': 'application/json' } })); await expect(exportBook({ bookUrl: 'book', name: '书' }, true)).rejects.toThrow('章节缓存不完整'); });
  it('does not download an empty response', async () => { vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { headers: { 'Content-Type': 'application/epub+zip' } })); await expect(exportBook({ bookUrl: 'book', name: '书' }, true)).rejects.toThrow('文件为空'); });
  it('keeps legacy calls while enabling FU export options by default', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { headers: { 'Content-Type': 'application/epub+zip' } }));
    await expect(exportBook({ bookUrl: 'book', name: '书' }, true, true)).rejects.toThrow('文件为空');
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({ url: 'book', isEpub: 1, useReplace: true, filterInteractiveImages: true, useTemplate: true });
  });
  it('sends explicitly disabled image filtering and custom templates', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { headers: { 'Content-Type': 'application/epub+zip' } }));
    await expect(exportBook({ bookUrl: 'book', name: '书' }, true, false, { filterInteractiveImages: false, useTemplate: false })).rejects.toThrow('文件为空');
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toMatchObject({ isEpub: 1, filterInteractiveImages: false, useTemplate: false });
  });
});
