import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { request, setSession } from '../src/lib/api';
import { consumeSourceActions } from '../src/lib/sourceActions';

describe('RSS verification requests', () => {
  beforeEach(() => { vi.useFakeTimers(); setSession({ apiBase: '/reader3', accessToken: '', secureKey: '', userNS: '' }); });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });
  it.each([
    ['getRssArticles', { sourceUrl: 'https://shared.test' }],
    ['getRssContent', { article: { origin: 'https://shared.test' } }],
    ['rss/debug', { sourceUrl: 'https://shared.test' }],
    ['rss/article/cache', { article: { origin: 'https://shared.test' } }],
  ])('delivers sourceType while %s is awaiting browser verification', async (path, data) => {
    let complete!: (response: Response) => void;
    const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation((url) => String(url).includes('/drainSourceInteractions')
      ? Promise.resolve(new Response(JSON.stringify({ isSuccess: true, data: { actions: [{ id: 'rss-verify', type: 'browser', url: 'https://shared.test/login' }] } })))
      : new Promise((resolve) => { complete = resolve; }));
    const received = vi.fn(); window.addEventListener('reader:source-actions', received);
    try {
      const pending = request(path, { method: 'POST', data });
      await vi.advanceTimersByTimeAsync(1000);
      const polling = fetcher.mock.calls.find(([url]) => String(url).includes('/drainSourceInteractions'))!;
      expect(JSON.parse(String(polling[1]?.body))).toEqual({ bookSourceUrl: 'https://shared.test', sourceType: 'rss' });
      expect((received.mock.calls[0][0] as CustomEvent).detail[0]).toMatchObject({ bookSourceUrl: 'https://shared.test', sourceType: 'rss', id: 'rss-verify' });
      complete(new Response(JSON.stringify({ isSuccess: true, data: [] })));
      await pending; await vi.advanceTimersByTimeAsync(1);
      const count = fetcher.mock.calls.length; await vi.advanceTimersByTimeAsync(5000); expect(fetcher).toHaveBeenCalledTimes(count);
    } finally { window.removeEventListener('reader:source-actions', received); }
  });
  it('keeps explicit RSS identity when forwarding interactions at the same book URL', () => {
    const received = vi.fn(); window.addEventListener('reader:source-actions', received);
    try {
      consumeSourceActions({ actions: [{ id: 'rss', type: 'browser', url: 'https://shared.test', sourceType: 'rss' }] }, 'https://shared.test');
      expect((received.mock.calls[0][0] as CustomEvent).detail[0].sourceType).toBe('rss');
    } finally { window.removeEventListener('reader:source-actions', received); }
  });
});
