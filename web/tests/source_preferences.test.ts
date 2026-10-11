import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { request, setSession } from '../src/lib/api';

beforeEach(() => { localStorage.clear(); setSession({ apiBase: '/reader3', accessToken: '', secureKey: '', userNS: '' }); });
afterEach(() => vi.restoreAllMocks());

test('source calls save current preferences once and save changes before executing again', async () => {
  localStorage.setItem('reader.settings', JSON.stringify({ fontSize: 29, theme: 'night' }));
  const fetcher = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ isSuccess: true, data: {} })));
  await request('searchBook', { data: { key: 'fixture' } });
  await request('getSourceExploreUi', { data: { bookSourceUrl: 'https://fixture.test' } });
  const saves = () => fetcher.mock.calls.filter(([url]) => String(url).includes('/saveUserConfig'));
  expect(saves()).toHaveLength(1);
  expect(JSON.parse(String(saves()[0][1]?.body))).toMatchObject({ '@merge': true, reactReadingSettings: { fontSize: 29, theme: 'night' } });
  expect(String(fetcher.mock.calls[0][0])).toContain('/saveUserConfig');
  localStorage.setItem('reader.settings', JSON.stringify({ fontSize: 30, theme: 'paper' }));
  await request('searchBook', { data: { key: 'next' } });
  expect(saves()).toHaveLength(2);
});

test('failed preference synchronization is retried and prevents stale source execution', async () => {
  localStorage.setItem('reader.settings', JSON.stringify({ fontSize: 31 }));
  const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ isSuccess: false, errorMsg: 'save failed' })))
    .mockImplementation(async () => new Response(JSON.stringify({ isSuccess: true, data: {} })));
  await expect(request('searchBook', { data: {} })).rejects.toThrow('save failed');
  expect(fetcher).toHaveBeenCalledTimes(1);
  await request('searchBook', { data: {} });
  expect(fetcher.mock.calls.filter(([url]) => String(url).includes('/saveUserConfig'))).toHaveLength(2);
});

test('source refresh actions notify the discovery page', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ isSuccess: true, data: { refreshExplore: true } })));
  const refresh = vi.fn(); window.addEventListener('reader:source-explore-refresh', refresh);
  try { await request('runSourceAction', { data: {} }); expect(refresh).toHaveBeenCalledTimes(1); }
  finally { window.removeEventListener('reader:source-explore-refresh', refresh); }
});

test('Cookie account preference saves are independent and late saves cannot mark another account synced', async () => {
  localStorage.setItem('reader.settings', JSON.stringify({ fontSize: 31 }));
  const saves: (() => void)[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) => {
    if (String(url).includes('/saveUserConfig')) return new Promise<Response>((resolve) => saves.push(() => resolve(new Response(JSON.stringify({ isSuccess: true, data: {} })))));
    return new Response(JSON.stringify({ isSuccess: true, data: {} }));
  });
  setSession({ authenticatedUser: 'alice' });
  const previous = request('searchBook', { data: {} }).catch((error: Error) => error.name);
  await vi.waitFor(() => expect(saves).toHaveLength(1));
  setSession({ authenticatedUser: 'bob' });
  const current = request('searchBook', { data: {} });
  try {
    await vi.waitFor(() => expect(saves).toHaveLength(2), { timeout: 500 });
    saves[1](); await current;
    saves[0](); expect(await previous).toBe('AbortError');
    await request('searchBook', { data: {} });
    expect(saves).toHaveLength(2);
  } finally { saves.forEach((release) => release()); await Promise.allSettled([previous, current]); }
});
