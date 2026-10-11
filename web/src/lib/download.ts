import { apiUrl, ApiError } from './api';
import type { ApiEnvelope, Book } from '../types';
export interface CacheProgress { cachedCount: number; successCount: number; failedCount: number; totalCount?: number; failedChapters?: number[]; complete?: boolean }
export interface ExportOptions { filterInteractiveImages?: boolean; useTemplate?: boolean }
export function completeCache(progress: CacheProgress): boolean { return progress.complete === true && progress.failedCount === 0 && progress.totalCount !== undefined && progress.cachedCount >= progress.totalCount && progress.totalCount > 0; }
export async function exportBook(book: Book, epub: boolean, useReplace = false, options: ExportOptions = {}): Promise<void> {
  const response = await fetch(apiUrl('exportBook'), { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: book.bookUrl, isEpub: epub ? 1 : 0, useReplace, filterInteractiveImages: options.filterInteractiveImages ?? true, useTemplate: options.useTemplate ?? true }) });
  if (!response.ok) throw new ApiError(`下载失败（HTTP ${response.status}）`);
  if ((response.headers.get('Content-Type') || '').includes('application/json')) { const error = await response.json() as ApiEnvelope<unknown>; throw new ApiError(error.errorMsg || '服务器拒绝导出未完整缓存的书籍'); }
  const blob = await response.blob(); if (!blob.size) throw new ApiError('导出的文件为空');
  const disposition = response.headers.get('Content-Disposition') || '';
  const filename = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] || /filename="?([^";]+)/i.exec(disposition)?.[1];
  const link = document.createElement('a'); const href = URL.createObjectURL(blob); link.href = href;
  try { link.download = filename ? decodeURIComponent(filename.replace(/\+/g, ' ')) : `${book.name}.${epub ? 'epub' : 'txt'}`; } catch { link.download = `${book.name}.${epub ? 'epub' : 'txt'}`; }
  link.click(); setTimeout(() => URL.revokeObjectURL(href), 1000);
}
