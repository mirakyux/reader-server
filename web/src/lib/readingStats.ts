import { useEffect } from 'react';
import { apiUrl, sessionScope } from './api';
import { randomUUID } from './utils';

export interface ReadingSession { id: string; bookUrl: string; startTime: number; endTime: number; seconds: number; characters: number; chapterIndex: number }
export class ReadingClock {
  private elapsed = 0; private activeSince: number | null; private end: number; private characters = 0;
  constructor(private readonly start: number, readonly bookUrl: string, readonly chapterIndex: number, readonly id: string = randomUUID(), active = true) { this.activeSince = active ? start : null; this.end = start; }
  visibility(active: boolean, now: number) { if (this.activeSince !== null) this.elapsed += Math.max(0, now - this.activeSince); this.activeSince = active ? now : null; this.end = Math.max(this.end, now); }
  seenCharacters(count: number) { this.characters = Math.max(this.characters, Math.max(0, Math.floor(count))); }
  snapshot(now: number): ReadingSession { this.end = Math.max(this.end, now); return { id: this.id, bookUrl: this.bookUrl, chapterIndex: this.chapterIndex, startTime: this.start, endTime: this.end, seconds: Math.floor((this.elapsed + (this.activeSince === null ? 0 : Math.max(0, now - this.activeSince))) / 1000), characters: this.characters }; }
}
function queueKey() { return `reader.statistics@${sessionScope()}`; }
function pending(key: string): ReadingSession[] { try { return JSON.parse(localStorage.getItem(key) || '[]'); } catch { return []; } }
async function send(record: ReadingSession, keepalive = false) { const response = await fetch(apiUrl('reading/session'), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(record), keepalive }); if (!response.ok || !(await response.json()).isSuccess) throw new Error('阅读记录暂未同步'); }
/** Count visible reading only; idle pages pause after five minutes. Retry pending records on the next reader visit. */
export function useReadingStatistics(bookUrl: string | undefined, chapterIndex: number, ready: boolean, article: React.RefObject<HTMLElement | null>, readingRoot?: HTMLElement) {
  useEffect(() => {
    if (!bookUrl || !ready) return;
    const key = queueKey(); const clock = new ReadingClock(Date.now(), bookUrl, chapterIndex, randomUUID(), !document.hidden); let lastActivity = Date.now(); let stopped = false; let sending = false;
    const activity = () => { lastActivity = Date.now(); clock.visibility(!document.hidden, lastActivity); updateCharacters(); };
    const updateCharacters = () => { const element = readingRoot || article.current; if (!element) return; const text = element.innerText || element.textContent || ''; const bounds = element.getBoundingClientRect(); const view = element.ownerDocument.defaultView || window; const scrolling = readingRoot ? element.ownerDocument.scrollingElement : element; const visible = Math.max(0, Math.min(1, (view.innerHeight - bounds.top + (scrolling?.scrollTop || 0)) / Math.max(scrolling?.scrollHeight || element.scrollHeight, bounds.height, 1))); clock.seenCharacters(text.length * visible); };
    const visible = () => { clock.visibility(!document.hidden && Date.now() - lastActivity < 300000, Date.now()); if (!document.hidden) activity(); else void flush(true); };
    const flush = async (keepalive = false) => {
      const record = clock.snapshot(Date.now()); if (!record.seconds) return;
      const records = pending(key); const index = records.findIndex((item) => item.id === record.id); if (index >= 0) records[index] = record; else records.push(record); localStorage.setItem(key, JSON.stringify(records.slice(-1000)));
      if (sending || key !== queueKey()) return; sending = true;
      try { for (const item of records) { if (key !== queueKey()) break; await send(item, keepalive); const current = pending(key); localStorage.setItem(key, JSON.stringify(current.filter((queued) => queued.id !== item.id || queued.endTime > item.endTime))); } } catch { /* Preserve pending data for a future online visit. */ } finally { sending = false; }
    };
    for (const record of pending(key)) void send(record).then(() => { const current = pending(key); localStorage.setItem(key, JSON.stringify(current.filter((item) => item.id !== record.id || item.endTime > record.endTime))); }).catch(() => {});
    updateCharacters();
    const interval = setInterval(() => { if (stopped) return; const now = Date.now(); clock.visibility(!document.hidden && now - lastActivity < 300000, now - lastActivity >= 300000 ? lastActivity + 300000 : now); updateCharacters(); void flush(); }, 30000);
    const hide = () => { clock.visibility(false, Date.now()); void flush(true); };
    document.addEventListener('visibilitychange', visible); window.addEventListener('pagehide', hide); window.addEventListener('scroll', activity, { passive: true }); article.current?.addEventListener('scroll', activity, { passive: true }); window.addEventListener('pointerdown', activity); window.addEventListener('keydown', activity);
    const element = article.current;
    const frameDocument = readingRoot?.ownerDocument; const frameWindow = frameDocument?.defaultView; frameWindow?.addEventListener('scroll', activity, { passive: true }); frameDocument?.addEventListener('pointerdown', activity); frameDocument?.addEventListener('keydown', activity);
    return () => { stopped = true; clearInterval(interval); clock.visibility(false, Date.now()); void flush(true); document.removeEventListener('visibilitychange', visible); window.removeEventListener('pagehide', hide); window.removeEventListener('scroll', activity); element?.removeEventListener('scroll', activity); window.removeEventListener('pointerdown', activity); window.removeEventListener('keydown', activity); frameWindow?.removeEventListener('scroll', activity); frameDocument?.removeEventListener('pointerdown', activity); frameDocument?.removeEventListener('keydown', activity); };
  }, [bookUrl, chapterIndex, ready, article, readingRoot]);
}
