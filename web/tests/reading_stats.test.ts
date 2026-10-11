import { expect, it, vi } from 'vitest';
import { ReadingClock } from '../src/lib/readingStats';
it('pauses hidden reading, keeps a stable idempotent record and only grows seen characters', () => { const clock = new ReadingClock(1000, 'book', 0, 'session'); clock.seenCharacters(120); clock.visibility(false, 6000); clock.seenCharacters(80); expect(clock.snapshot(16000)).toMatchObject({ seconds: 5, characters: 120, id: 'session' }); clock.visibility(true, 17000); expect(clock.snapshot(21000)).toMatchObject({ startTime: 1000, endTime: 21000, seconds: 9, characters: 120, chapterIndex: 0 }); });
it('does not count a chapter opened while the page is hidden', () => { const clock = new ReadingClock(1000, 'book', 2, 'session', false); expect(clock.snapshot(10000).seconds).toBe(0); clock.visibility(true, 10000); expect(clock.snapshot(11500).seconds).toBe(1); });
it('creates reading sessions over LAN HTTP where randomUUID is unavailable', () => {
  const getRandomValues = crypto.getRandomValues.bind(crypto);
  vi.stubGlobal('crypto', { getRandomValues });
  try {
    const first = new ReadingClock(1000, 'book', 0).snapshot(3000);
    const second = new ReadingClock(1000, 'book', 0).snapshot(3000);
    expect(first.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(second.id).not.toBe(first.id);
    expect(first.seconds).toBe(2);
  } finally { vi.unstubAllGlobals(); }
});
