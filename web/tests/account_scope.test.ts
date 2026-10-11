import { beforeEach, expect, test, vi } from 'vitest';
import { api, getSession, setSession } from '../src/lib/api';
import { cacheScope, syncReadingProgress } from '../src/lib/cache';
import { activeBook, rememberActiveBook } from '../src/lib/navigation';
import { renderHook } from '@testing-library/react';
import { createRef } from 'react';
import { useReadingStatistics } from '../src/lib/readingStats';

beforeEach(() => { setSession({ apiBase: '/reader3', accessToken: '', secureKey: '', userNS: '', authenticatedUser: '' }); });

test('Cookie identity isolates active books and does not persist an unremembered token', () => {
  localStorage.setItem('reader.activeBook', JSON.stringify({ bookUrl: 'legacy', name: '匿名书' }));
  setSession({ authenticatedUser: 'alice' });
  expect(cacheScope()).toBe('/reader3@alice');
  expect(activeBook('legacy')).toBeUndefined();
  rememberActiveBook({ bookUrl: 'book', name: 'Alice的书' });
  setSession({ authenticatedUser: 'bob' });
  expect(activeBook('book')).toBeUndefined();
  expect(cacheScope()).toBe('/reader3@bob');
  expect(getSession().accessToken).toBe('');
  expect(JSON.parse(localStorage.getItem('reader.session')!).accessToken).toBe('');
});

test('changing servers clears the last Cookie identity and keeps account progress isolated', () => {
  setSession({ authenticatedUser: 'alice' });
  setSession({ apiBase: 'https://other.test/reader3' });
  expect(cacheScope()).toBe('https://other.test/reader3@default');
});

test('a progress flush stops at account boundaries and retains the previous account queue', async () => {
  setSession({ authenticatedUser: 'alice' });
  const first = { url: 'first', index: 0, chapterPos: 1, time: 1 };
  const second = { url: 'second', index: 1, chapterPos: 2, time: 2 };
  localStorage.setItem('reader.pending-progress@/reader3@alice', JSON.stringify([first]));
  let release!: () => void;
  const post = vi.spyOn(api, 'post').mockImplementation(() => new Promise<void>((resolve) => { release = resolve; }));
  const flushing = syncReadingProgress(second);
  await vi.waitFor(() => expect(post).toHaveBeenCalledOnce());
  setSession({ authenticatedUser: 'bob' });
  release();
  await flushing;
  expect(post).toHaveBeenCalledOnce();
  expect(JSON.parse(localStorage.getItem('reader.pending-progress@/reader3@alice')!)).toEqual([second]);
  expect(localStorage.getItem('reader.pending-progress@/reader3@bob')).toBeNull();
});

test('offline statistics stay with their Cookie account when the account changes', async () => {
  vi.useFakeTimers();
  const fetch = vi.spyOn(window, 'fetch').mockRejectedValue(new Error('offline'));
  try {
    setSession({ authenticatedUser: 'alice' });
    const hook = renderHook(() => useReadingStatistics('book', 0, true, createRef<HTMLElement>()));
    await vi.advanceTimersByTimeAsync(30000);
    expect(localStorage.getItem('reader.statistics@/reader3@alice')).not.toBeNull();
    expect(fetch).toHaveBeenCalledOnce();
    setSession({ authenticatedUser: 'bob' });
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetch).toHaveBeenCalledOnce();
    expect(localStorage.getItem('reader.statistics@/reader3@bob')).toBeNull();
    hook.unmount();
  } finally { vi.useRealTimers(); }
});
