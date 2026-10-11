import type { Book } from '../types';
import { getSession, sessionScope } from './api';
export function activeBookKey(): string { return `reader.activeBook@${sessionScope()}`; }
export function rememberActiveBook(book: Book): void { localStorage.setItem(activeBookKey(), JSON.stringify(book)); }
export function navigate(path: string, params: Record<string, string | number> = {}) { const search = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)])); window.location.hash = path + (search.size ? `?${search}` : ''); }
export function openBook(book: Book, index?: number, bookmark?: number, fromBookInfo = false) { rememberActiveBook(book); navigate('/reader', { url: book.bookUrl, ...(index !== undefined ? { index } : {}), ...(bookmark !== undefined ? { bookmark } : {}), ...(fromBookInfo ? { fromBookInfo: 1 } : {}) }); }
export function activeBook(url: string): Book | undefined { try { const session = getSession(); const legacy = !session.userNS && !session.authenticatedUser && !session.accessToken && session.apiBase === '/reader3' ? localStorage.getItem('reader.activeBook') : null; const book = JSON.parse(localStorage.getItem(activeBookKey()) || legacy || 'null'); if (book?.bookUrl !== url) return; if (legacy && !localStorage.getItem(activeBookKey())) rememberActiveBook(book); return book; } catch { return undefined; } }
