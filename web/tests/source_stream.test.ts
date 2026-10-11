import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { openStream, setSession } from '../src/lib/api';

class SourceStream extends EventTarget {
  onmessage: ((event: MessageEvent) => unknown) | null = null;
  closed = false;
  close() { this.closed = true; }
  emit(type: string, data: unknown) { const event = new MessageEvent(type, { data: typeof data === 'string' ? data : JSON.stringify(data) }); if (type === 'message') this.onmessage?.(event); else this.dispatchEvent(event); }
}
describe('source interactions during long-running cache streams', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('EventSource', SourceStream); setSession({ apiBase: '/reader3', accessToken: '', userNS: '', secureKey: '' }); });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
  it('delivers a browser verification while cache awaits source interaction, and stops after cancellation', async () => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: true, data: { actions: [{ type: 'browser', interactionId: 'verification', bookSourceUrl: 'https://source.test' }] } })));
    const received = vi.fn(); window.addEventListener('reader:source-actions', received);
    const stream = openStream('cacheBookSSE', { url: 'https://book.test' }, vi.fn(), vi.fn(), vi.fn()) as unknown as SourceStream;
    await vi.advanceTimersByTimeAsync(1000);
    expect(received).toHaveBeenCalledOnce(); expect((received.mock.calls[0][0] as CustomEvent).detail[0]).toMatchObject({ interactionId: 'verification', bookSourceUrl: 'https://source.test' });
    stream.close(); await vi.advanceTimersByTimeAsync(5000); expect(stream.closed).toBe(true); expect(fetcher).toHaveBeenCalledOnce(); window.removeEventListener('reader:source-actions', received);
  });
  it.each(['end', 'error', 'malformed'])('stops source polling on %s without reconnecting or exporting invalid progress', async (ending) => {
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: true, data: { actions: [] } })));
    const ended = vi.fn(); const failed = vi.fn(); const stream = openStream('bookSourceDebugSSE', { bookSourceUrl: 'https://source.test' }, vi.fn(), ended, failed) as unknown as SourceStream;
    await vi.advanceTimersByTimeAsync(1000); stream.emit(ending === 'malformed' ? 'end' : ending, ending === 'malformed' ? 'broken-json' : { complete: true }); await vi.advanceTimersByTimeAsync(5000);
    expect(stream.closed).toBe(true); expect(fetcher).toHaveBeenCalledTimes(ending === 'malformed' ? 1 : 2); expect(ended).toHaveBeenCalledTimes(ending === 'end' ? 1 : 0); expect(failed).toHaveBeenCalledTimes(ending === 'end' ? 0 : 1);
  });
  it('does not deliver a verification response that arrives after cancellation', async () => {
    let respond!: (value: Response) => void; vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise((resolve) => { respond = resolve; }));
    const received = vi.fn(); window.addEventListener('reader:source-actions', received);
    const stream = openStream('cacheBookSSE', {}, vi.fn(), vi.fn(), vi.fn()); await vi.advanceTimersByTimeAsync(1000); stream.close(); respond(new Response(JSON.stringify({ isSuccess: true, data: { actions: [{ type: 'browser' }] } }))); await vi.advanceTimersByTimeAsync(1); expect(received).not.toHaveBeenCalled(); window.removeEventListener('reader:source-actions', received);
  });
  it('delivers nonblocking source interactions even when a stream ends before the first poll', async () => { const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ isSuccess: true, data: { actions: [{ type: 'browser', interactionId: 'quick-source' }] } }))); const received = vi.fn(); window.addEventListener('reader:source-actions', received); const stream = openStream('cacheBookSSE', {}, vi.fn(), vi.fn(), vi.fn()) as unknown as SourceStream; stream.emit('end', { complete: true }); await vi.advanceTimersByTimeAsync(1); expect(received).toHaveBeenCalledOnce(); await vi.advanceTimersByTimeAsync(5000); expect(fetcher).toHaveBeenCalledOnce(); window.removeEventListener('reader:source-actions', received); });
  it('does not poll source interactions for unrelated streams', async () => { const fetcher = vi.spyOn(globalThis, 'fetch'); const stream = openStream('ai/chatSSE', {}, vi.fn(), vi.fn(), vi.fn()); await vi.advanceTimersByTimeAsync(3000); expect(fetcher).not.toHaveBeenCalled(); stream.close(); });
});
