import { useCallback, useEffect, useRef, useState } from 'react';
import { useLibrary } from '../context';
import { request } from '../lib/api';
import { errorText } from '../lib/reading';
import type { Book } from '../types';
import { ErrorAlert, notify } from './Common';
import { ActionButton, Modal, ModalActions, ModalBody, ModalTitle, Spinner } from './Controls';

interface ChangeSourceProps {
  book: Book;
  index?: number;
  onTemporary?: (content: string) => void | Promise<void>;
  onClose: () => void;
  onChanged?: (book: Book) => void | Promise<void>;
  onSwitching?: (switching: boolean) => void;
}

type Operation = 'load' | 'search' | 'switch' | 'temporary';
interface SearchResult { list: Book[]; lastIndex: number }
const sourceKey = (book: Book) => JSON.stringify([book.origin, book.bookUrl]);

export function ChangeSource({ book, index, onTemporary, onClose, onChanged, onSwitching }: ChangeSourceProps) {
  const { books, sources, reload } = useLibrary();
  const inShelf = books.some((item) => item.bookUrl === book.bookUrl);
  const [results, setResults] = useState<Book[]>([]);
  const [lastIndex, setLastIndex] = useState(-1);
  const [exhausted, setExhausted] = useState(false);
  const [pending, setPending] = useState<Operation>();
  const [error, setError] = useState('');
  const [retryOperation, setRetryOperation] = useState<Operation>('load');
  const [selected, setSelected] = useState<Book>();
  const [temporary, setTemporary] = useState<Book>();
  const generation = useRef(0);
  const locked = useRef(false);
  const controller = useRef<AbortController | undefined>(undefined);
  const busy = pending !== undefined;

  const merge = useCallback((previous: Book[], candidates: Book[]) => [...new Map([...previous, ...candidates]
    .filter((item) => item && typeof item.bookUrl === 'string' && item.bookUrl && typeof item.origin === 'string' && item.origin && item.origin !== book.origin &&
      item.name?.trim() === book.name.trim() && (item.author || '').trim() === (book.author || '').trim())
    .map((item) => [sourceKey(item), item])).values()], [book.origin, book.name, book.author]);

  const execute = useCallback(async (operation: Operation, action: (active: () => boolean) => Promise<void>) => {
    if (locked.current) return;
    locked.current = true;
    const current = generation.current;
    const active = () => generation.current === current;
    setPending(operation); setRetryOperation(operation); setError('');
    try { await action(active); }
    catch (failure) { if (active()) setError(errorText(failure)); }
    finally { if (active()) { locked.current = false; setPending(undefined); } }
  }, []);

  const search = useCallback(async (cursor: number, active: () => boolean) => {
    if (!sources.length) throw new Error('请先在书源页导入书源，再尝试换源');
    setPending('search'); setRetryOperation('search');
    let value: SearchResult;
    try {
      value = await request<SearchResult>('searchBookSource', { method: 'POST', data: { url: book.bookUrl, lastIndex: cursor, searchSize: 5 }, signal: controller.current?.signal });
    } catch (failure) {
      if (active() && errorText(failure) === '没有更多了') { setExhausted(true); return; }
      throw failure;
    }
    if (!active()) return;
    if (!value || !Array.isArray(value.list) || !Number.isInteger(value.lastIndex) || value.lastIndex <= cursor) throw new Error('书源搜索未返回有效进度，请重试');
    setResults((previous) => merge(previous, value.list));
    setLastIndex(value.lastIndex);
    setExhausted(value.lastIndex >= sources.length - 1);
  }, [book.bookUrl, sources.length, merge]);

  const load = useCallback(() => execute('load', async (active) => {
    setResults([]); setLastIndex(-1); setExhausted(false);
    const available = await request<Book[]>('getAvailableBookSource', { method: 'POST', data: { url: book.bookUrl }, signal: controller.current?.signal });
    if (!active()) return;
    if (!Array.isArray(available)) throw new Error('可用书源返回格式错误，请重试');
    const cached = merge([], available);
    setResults(cached);
    if (!cached.length) await search(-1, active);
  }), [book.bookUrl, execute, merge, search]);

  useEffect(() => {
    const current = generation.current + 1;
    generation.current = current; locked.current = false;
    const abort = new AbortController(); controller.current = abort;
    void load();
    return () => { generation.current = current + 1; locked.current = false; abort.abort(); };
  }, [load]);

  const switchBook = () => {
    if (!selected || !inShelf) return Promise.resolve();
    return execute('switch', async (active) => {
      onSwitching?.(true);
      try {
        const updated = await request<Book>('setBookSource', { method: 'POST', data: { bookUrl: book.bookUrl, newUrl: selected.bookUrl, bookSourceUrl: selected.origin } });
        if (!active()) return;
        if (!updated || typeof updated.bookUrl !== 'string' || !updated.bookUrl || typeof updated.name !== 'string' || !updated.name || typeof updated.origin !== 'string' || !updated.origin || !Number.isInteger(updated.durChapterIndex) || Number(updated.durChapterIndex) < 0) {
          throw new Error('换源返回的书籍信息不完整，请重试');
        }
        await reload();
        if (!active()) return;
        await onChanged?.(updated);
        notify('已切换书源，阅读位置已匹配');
        onClose();
      } finally { onSwitching?.(false); }
    });
  };

  const switchChapter = (candidate: Book) => {
    if (!onTemporary || index === undefined) return Promise.resolve();
    setTemporary(candidate);
    return execute('temporary', async (active) => {
      const value = await request<{ content: string }>('getAlternateChapter', { method: 'POST', data: { url: book.bookUrl, index, alternateBookUrl: candidate.bookUrl, bookSourceUrl: candidate.origin } });
      if (!active()) return;
      if (!value || typeof value.content !== 'string' || !value.content.trim()) throw new Error('临时书源未返回正文，请重试');
      await onTemporary(value.content);
      notify('本章已使用临时书源');
      onClose();
    });
  };

  const retry = () => {
    if (retryOperation === 'switch') void switchBook();
    else if (retryOperation === 'temporary' && temporary) void switchChapter(temporary);
    else if (retryOperation === 'search') void execute('search', (active) => search(lastIndex, active));
    else void load();
  };
  const close = () => { if (pending !== 'switch' && pending !== 'temporary') onClose(); };

  return <Modal open onClose={close} maxWidth="md">
    <ModalTitle>切换书源</ModalTitle>
    <ModalBody>
      <p>当前书源：{book.originName || book.origin || '未知书源'}</p>
      <p className="muted">查找同名同作者的其他书源；整书换源会匹配当前章节，并保留书架分组与阅读设置。</p>
      {!inShelf && <p className="muted">整书换源前，请先将本书加入书架。</p>}
      {pending && <div role="status" className="flex items-center gap-2 py-3"><Spinner size={18} />{({ load: '正在加载可用书源…', search: '正在搜索其他书源…', switch: '正在切换书源并匹配章节…', temporary: '正在读取临时书源正文…' })[pending]}</div>}
      <ErrorAlert error={error} retry={busy ? undefined : retry} />
      <ul className="grid gap-3 py-3">
        {results.map((item) => <li key={sourceKey(item)} className="rounded-lg border border-border p-3">
          <div className="flex flex-col items-start justify-between gap-3 sm:flex-row">
            <div className="min-w-0 w-full sm:w-auto sm:flex-1"><h3 className="break-words font-medium">{item.originName || item.origin}</h3><p className="break-words text-sm">{item.name} · {item.author || '作者未知'}</p><p className="muted break-words text-sm">最新章节：{item.latestChapterTitle || '暂无信息'}</p></div>
            <div className="flex flex-wrap gap-2 sm:shrink-0">
              <ActionButton variant="outline" size="sm" disabled={busy || !inShelf} onClick={() => { setError(''); setSelected(item); }}>整书换源</ActionButton>
              {onTemporary && index !== undefined && <ActionButton variant="ghost" size="sm" disabled={busy} onClick={() => void switchChapter(item)}>只换本章</ActionButton>}
            </div>
          </div>
        </li>)}
      </ul>
      {!busy && !error && !results.length && <p className="muted">未找到同名同作者的其他书源</p>}
      {selected && <div className="rounded-lg border border-border p-3"><p>切换到「{selected.originName || selected.origin}」？</p><p className="muted text-sm">服务端将根据章节标题匹配阅读位置，正文位置会从匹配章节开头重新开始。</p></div>}
      {lastIndex >= 0 && <p className="muted text-sm">已检查 {Math.min(lastIndex + 1, sources.length)} / {sources.length} 个书源 · 找到 {results.length} 个可选结果</p>}
    </ModalBody>
    <ModalActions>
      {selected ? <><ActionButton variant="ghost" disabled={busy} onClick={() => { setSelected(undefined); setError(''); }}>取消</ActionButton><ActionButton disabled={busy || !inShelf} onClick={() => void switchBook()}>确认</ActionButton></> : <>
        <ActionButton variant="ghost" disabled={pending === 'switch' || pending === 'temporary'} onClick={close}>关闭</ActionButton>
        {exhausted && !results.length && <ActionButton variant="outline" disabled={busy} onClick={() => void load()}>重新搜索</ActionButton>}
        <ActionButton variant="outline" disabled={busy || exhausted} onClick={() => void execute('search', (active) => search(lastIndex, active))}>{exhausted ? '已搜索全部书源' : '查找更多书源'}</ActionButton>
      </>}
    </ModalActions>
  </Modal>;
}
