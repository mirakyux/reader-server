import type { ApiEnvelope } from '../types';
import { readSettings } from './reading';

export class ApiError extends Error {
  constructor(message: string, readonly code?: string) { super(message); this.name = 'ApiError'; }
}
export interface Session { apiBase: string; accessToken: string; secureKey: string; userNS: string; authenticatedUser: string }
const defaults: Session = { apiBase: import.meta.env.VITE_API_BASE_URL || '/reader3', accessToken: '', secureKey: '', userNS: '', authenticatedUser: '' };
export function readSession(): Session {
  try { return { ...defaults, ...JSON.parse(localStorage.getItem('reader.session') || '{}') }; } catch { return { ...defaults }; }
}
let session = readSession();
let syncedPreferences = '';
const pendingPreferences = new Map<string, Promise<void>>();
export function getSession(): Session { return { ...session }; }
export function setSession(value: Partial<Session>) {
  const changedServer = value.apiBase !== undefined && value.apiBase !== session.apiBase;
  const changedToken = value.accessToken !== undefined && value.accessToken !== session.accessToken;
  session = { ...session, ...value, authenticatedUser: changedServer ? '' : value.authenticatedUser ?? (changedToken ? '' : session.authenticatedUser) };
  syncedPreferences = ''; localStorage.setItem('reader.session', JSON.stringify(session));
}
export function sessionScope(value = session): string { return `${value.apiBase}@${value.userNS || value.authenticatedUser || value.accessToken.split(':')[0] || 'default'}`; }
function scopedApiUrl(value: Session, path: string, params: Record<string, unknown> = {}): string {
  const base = value.apiBase.trim().replace(/\/$/, '');
  const normalized = base.startsWith('/') || /^https?:\/\//.test(base) ? base : `http://${base}`;
  const url = new URL(`${normalized}/${path.replace(/^\//, '')}`, window.location.origin);
  const auth = { ...(value.accessToken ? { accessToken: value.accessToken } : {}), ...(value.secureKey ? { secureKey: value.secureKey } : {}), ...(value.userNS ? { userNS: value.userNS } : {}) };
  Object.entries({ ...auth, ...params }).forEach(([key, value]) => { if (value !== undefined && value !== null) url.searchParams.set(key, String(value)); });
  return url.toString();
}
export function apiUrl(path: string, params: Record<string, unknown> = {}): string { return scopedApiUrl(session, path, params); }
export function assetUrl(path: string): string {
  if (!path) return '';
  if (/^https?:\/\//.test(path)) return apiUrl('cover', { path });
  const base = new URL(apiUrl('')).origin;
  return new URL(path, base).toString();
}
export function resourceUrl(path: string): string { const base = new URL(apiUrl('')).origin; const resolved = path.replaceAll('__API_ROOT__', base); return new URL(resolved, base).toString(); }
async function drainInteractions(bookSourceUrl: string, sourceType: 'book' | 'rss' = 'book', active = () => true) { try { const response = await fetch(apiUrl('drainSourceInteractions'), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(bookSourceUrl ? { bookSourceUrl, sourceType } : { sourceType }) }); const value = await response.json(); if (active() && value.isSuccess && value.data?.actions?.length) window.dispatchEvent(new CustomEvent('reader:source-actions', { detail: value.data.actions.map((action: Record<string, unknown>) => ({ ...action, bookSourceUrl: action.bookSourceUrl || bookSourceUrl, sourceType: action.sourceType || sourceType })) })); } catch { /* Optional interaction delivery must not obscure the original API result. */ } }
async function syncSourcePreferences(): Promise<void> {
  if (!localStorage.getItem('reader.settings') && !localStorage.getItem('reader.appearance') && !localStorage.getItem('config')) return;
  let appearance: unknown;
  try { appearance = JSON.parse(localStorage.getItem('reader.appearance') || 'null'); } catch { appearance = null; }
  const preferences = { '@merge': true, reactReadingSettings: readSettings(), ...(appearance ? { reactAppearance: appearance } : {}) };
  const scope = sessionScope();
  const fingerprint = JSON.stringify([scope, apiUrl('saveUserConfig'), preferences]);
  if (syncedPreferences === fingerprint) return;
  const pending = pendingPreferences.get(fingerprint);
  if (pending) return pending;
  const saving = request('saveUserConfig', { method: 'POST', data: preferences }).then(() => { if (scope === sessionScope()) syncedPreferences = fingerprint; }).finally(() => { pendingPreferences.delete(fingerprint); });
  pendingPreferences.set(fingerprint, saving);
  return saving;
}
export async function request<T>(path: string, options: { method?: 'GET' | 'POST'; data?: unknown; params?: Record<string, unknown>; signal?: AbortSignal } = {}): Promise<T> {
  const identity = getSession();
  const multipart = options.data instanceof FormData;
  const sourceOperation = ['getBookContent', 'getBookContentDetails', 'getChapterList', 'getBookInfo', 'searchBook', 'searchBookMulti', 'exploreBook', 'checkBookSource', 'runSourceAction', 'applySourceExploreAction', 'getSourceExploreUi', 'getSourceReviews', 'loginBookSource', 'getRssArticles', 'getRssContent', 'rss/categories', 'rss/debug', 'rss/article/cache'].includes(path);
  if (sourceOperation) await syncSourcePreferences();
  if (sourceOperation && sessionScope(identity) !== sessionScope()) throw new DOMException('阅读空间已切换', 'AbortError');
  const body = options.data && typeof options.data === 'object' ? options.data as Record<string, unknown> : options.params || {};
  const sourceType: 'book' | 'rss' = path.startsWith('rss/') || path === 'getRssArticles' || path === 'getRssContent' || body.sourceType === 'rss' ? 'rss' : 'book';
  const article = body.article && typeof body.article === 'object' ? body.article as Record<string, unknown> : undefined;
  const bookSourceUrl = String(sourceType === 'rss' ? body.sourceUrl || body.origin || article?.origin || body.bookSourceUrl || '' : body.bookSourceUrl || ''); let draining = false;
  const drain = async () => { if (draining || sessionScope(identity) !== sessionScope()) return; draining = true; try { await drainInteractions(bookSourceUrl, sourceType, () => sessionScope(identity) === sessionScope()); } finally { draining = false; } };
  const polling = sourceOperation ? setInterval(() => { void drain(); }, 1000) : undefined;
  try {
  const response = await fetch(scopedApiUrl(identity, path, options.params), {
    method: options.method || (options.data !== undefined ? 'POST' : 'GET'), credentials: 'include', signal: options.signal,
    headers: multipart ? undefined : { 'Content-Type': 'application/json' },
    body: options.data === undefined ? undefined : options.data instanceof FormData ? options.data : JSON.stringify(options.data),
  });
  if (!response.ok) throw new ApiError(`请求失败（HTTP ${response.status}）`);
  const result = await response.json() as ApiEnvelope<T>;
  if (!result.isSuccess) {
    const code = typeof result.data === 'string' ? result.data : undefined;
    if (code === 'NEED_LOGIN') window.dispatchEvent(new Event('reader:login'));
    throw new ApiError(result.errorMsg || '服务器未能完成请求', code);
  }
  if (sourceOperation && sessionScope(identity) === sessionScope() && result.data && typeof result.data === 'object' && 'refreshExplore' in result.data && result.data.refreshExplore === true) window.dispatchEvent(new Event('reader:source-explore-refresh'));
  return result.data;
  } finally { if (polling) clearInterval(polling); if (sourceOperation) void drain(); }
}
export const api = { get: <T>(path: string, params?: Record<string, unknown>) => request<T>(path, { params }), post: <T>(path: string, data: unknown = {}) => request<T>(path, { method: 'POST', data }) };
export function openStream(path: string, params: Record<string, unknown>, onData: (data: Record<string, unknown>) => void, onEnd: () => void, onError: (error: Error) => void): EventSource {
  const stream = new EventSource(apiUrl(path, params), { withCredentials: true });
  let active = true; let canceled = false; let delivery: Promise<void> | undefined;
  const sourceOperation = ['cacheBookSSE', 'bookSourceDebugSSE', 'searchBookMultiSSE', 'searchBookSourceSSE'].includes(path);
  const drain = () => { if (!delivery) delivery = drainInteractions(String(params.bookSourceUrl || ''), 'book', () => !canceled).finally(() => { delivery = undefined; }); return delivery; };
  const polling = sourceOperation ? setInterval(() => { if (active) void drain(); }, 1000) : undefined;
  const close = stream.close.bind(stream);
  const stop = () => { if (!active) return; active = false; if (polling !== undefined) clearInterval(polling); close(); };
  const finish = () => { if (sourceOperation) void drain(); stop(); };
  stream.close = () => { canceled = true; stop(); };
  const parse = (event: MessageEvent) => { if (!active) return false; try { const value = JSON.parse(event.data); onData(value); return true; } catch { stream.close(); onError(new Error('服务器返回的实时数据格式错误')); return false; } };
  stream.onmessage = parse;
  stream.addEventListener('end', (event) => { if (!parse(event as MessageEvent)) return; finish(); onEnd(); });
  stream.addEventListener('error', (event) => {
    if (!active) return;
    let message = '实时连接已断开';
    if (event instanceof MessageEvent) { try { message = String((JSON.parse(event.data) as ApiEnvelope<unknown>).errorMsg || '操作失败'); } catch { message = '服务器返回的实时数据格式错误'; } }
    finish(); onError(new Error(message));
  });
  return stream;
}
