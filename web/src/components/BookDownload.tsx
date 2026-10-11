import { useEffect, useRef, useState } from 'react';
import { Download, RefreshCw, Upload } from 'lucide-react';
import type { Book } from '../types';
import { api, openStream } from '../lib/api';
import { completeCache, exportBook } from '../lib/download';
import type { CacheProgress } from '../lib/download';
import { ActionButton, Alert, Field, Modal, ModalActions, ModalBody, ModalTitle, Toggle, ToggleLabel } from './Controls';
import { Progress } from './ui/progress';
import { notify, useAction } from './Common';
export function BookDownload({ book, onClose, initialFormat = 'txt', returnFocusRef }: { book: Book; onClose: () => void; initialFormat?: 'txt' | 'epub'; returnFocusRef?: React.RefObject<HTMLElement | null> }) {
  const [useReplace, setUseReplace] = useState(false);
  const [format, setFormat] = useState<string>(initialFormat);
  const [concurrent, setConcurrent] = useState(4);
  const [filterInteractiveImages, setFilterInteractiveImages] = useState(true);
  const [useTemplate, setUseTemplate] = useState(true);
  const [templateFiles, setTemplateFiles] = useState<string[] | undefined>();
  const [templateError, setTemplateError] = useState(false);
  const [templateRetry, setTemplateRetry] = useState(0);
  const [progress, setProgress] = useState<CacheProgress>({ cachedCount: 0, successCount: 0, failedCount: 0 });
  const [running, setRunning] = useState(false);
  const [failure, setFailure] = useState('');
  const [completed, setCompleted] = useState(false);
  const latest = useRef(progress);
  const stream = useRef<EventSource | null>(null);
  const templateInput = useRef<HTMLInputElement>(null);
  const { run, busy } = useAction();
  const locked = running || busy;
  const hasTemplate = Boolean(templateFiles?.length);
  useEffect(() => () => stream.current?.close(), []);
  useEffect(() => {
    if (format !== 'epub') return;
    let active = true;
    setTemplateError(false);
    void run(async () => {
      try {
        const template = await api.get<{ files: string[] }>('getEpubTemplate');
        if (active) setTemplateFiles(template.files);
      } catch (error) {
        if (active) setTemplateError(true);
        throw error;
      }
    });
    return () => { active = false; };
  }, [format, run, templateRetry]);
  const uploadTemplate = (file: File) => void run(async () => {
    if (!/\.zip$/i.test(file.name)) throw new Error('请选择模板 ZIP 文件');
    const form = new FormData();
    form.append('file', file);
    const template = await api.post<{ files: string[] }>('saveEpubTemplate', form);
    setTemplateFiles(template.files);
    setTemplateError(false);
    setUseTemplate(true);
  }, '自定义模板已保存');
  const restoreTemplate = () => void run(async () => {
    const template = await api.post<{ files: string[] }>('deleteEpubTemplate', {});
    setTemplateFiles(template.files);
    setTemplateError(false);
    setUseTemplate(true);
  }, '已恢复内置模板');
  const start = () => {
    stream.current?.close();
    setFailure('');
    setRunning(true);
    setCompleted(false);
    stream.current = openStream('cacheBookSSE', { url: book.bookUrl, refresh: 0, concurrentCount: concurrent }, (data) => {
      const next = data as unknown as CacheProgress;
      latest.current = next;
      setProgress(next);
    }, () => {
      setRunning(false);
      if (!completeCache(latest.current)) {
        setFailure(`尚有 ${latest.current.failedCount || Math.max(0, Number(latest.current.totalCount || 0) - latest.current.cachedCount)} 章未完成。可重试，已缓存章节会保留。`);
        return;
      }
      void run(async () => {
        await exportBook(book, format === 'epub', useReplace, { filterInteractiveImages, useTemplate });
        setCompleted(true);
      }, '完整书籍已导出');
    }, (error) => { setRunning(false); setFailure(error.message); });
  };
  return <Modal open returnFocusRef={returnFocusRef} onClose={() => { stream.current?.close(); onClose(); }}>
    <ModalTitle>缓存与导出 · {book.name}</ModalTitle>
    <ModalBody><div className="form-stack">
      <Alert>先缓存全部章节，确认完整后再导出。失败可重试；取消操作会保留已有缓存。</Alert>
      <Field select label="导出格式" value={format} onChange={(event) => setFormat(event.target.value)} disabled={locked}><option value="txt">TXT · 纯文本</option><option value="epub">EPUB · 电子书</option></Field>
      <Field select label="缓存并发数" value={concurrent} onChange={(event) => setConcurrent(Number(event.target.value))} disabled={locked}>{[2, 4, 8, 12, 24].map((value) => <option value={value} key={value}>{value}</option>)}</Field>
      <ToggleLabel label="应用替换规则" control={<Toggle disabled={locked} checked={useReplace} onChange={(_, checked) => setUseReplace(checked)} />} />
      {format === 'epub' && <details>
        <summary>导出样式</summary>
        <div className="form-stack mt-4">
          <p className="muted small">上传包含 Text/chapter.html 的模板 ZIP，自定义电子书排版。</p>
          <p aria-live="polite">{templateError ? '模板状态读取失败' : templateFiles === undefined ? '正在读取模板状态…' : hasTemplate ? `已保存自定义模板（${templateFiles.length} 个文件）` : '当前使用内置模板'}</p>
          {templateError && <ActionButton variant="outline" size="sm" disabled={locked} onClick={() => setTemplateRetry((value) => value + 1)}>重新读取模板</ActionButton>}
          <div className="toolbar">
            <ActionButton variant="outline" disabled={locked} startIcon={<Upload />} onClick={() => templateInput.current?.click()}>上传模板 ZIP</ActionButton>
            <input ref={templateInput} hidden type="file" accept=".zip,application/zip" aria-label="上传模板 ZIP" disabled={locked} onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) uploadTemplate(file);
            }} />
            <ActionButton variant="ghost" disabled={locked || !hasTemplate} onClick={restoreTemplate}>恢复内置模板</ActionButton>
          </div>
          <ToggleLabel label="使用自定义模板" control={<Toggle inputProps={{ 'aria-label': '使用自定义模板' }} disabled={locked || !hasTemplate} checked={hasTemplate && useTemplate} onChange={(_, checked) => setUseTemplate(checked)} />} />
          <ToggleLabel label="过滤交互图片" control={<Toggle inputProps={{ 'aria-label': '过滤交互图片' }} disabled={locked} checked={filterInteractiveImages} onChange={(_, checked) => setFilterInteractiveImages(checked)} />} />
          <p className="muted small">导出时隐藏评论、段评等交互入口图片。</p>
        </div>
      </details>}
      <Progress value={progress.totalCount ? progress.cachedCount / progress.totalCount * 100 : 0} />
      <p>已缓存 {progress.cachedCount} / {progress.totalCount || '—'} 章 · 本次成功 {progress.successCount} · 失败 {progress.failedCount}</p>
      {progress.failedChapters && progress.failedChapters.length > 0 && <p className="text-destructive">失败章节：{progress.failedChapters.map((index) => `第 ${index + 1} 章`).join('、')}</p>}
      {failure && <Alert severity="error">{failure}</Alert>}
      {completed && <Alert severity="success">完整书籍已下载。</Alert>}
    </div></ModalBody>
    <ModalActions>
      <ActionButton variant="ghost" disabled={!running || busy} onClick={() => { stream.current?.close(); setRunning(false); notify('缓存已取消，已完成的章节将保留', 'info'); }}>取消缓存</ActionButton>
      <ActionButton disabled={locked} startIcon={failure ? <RefreshCw /> : <Download />} onClick={start}>{failure ? '重试并导出' : '缓存全部并导出'}</ActionButton>
    </ModalActions>
  </Modal>;
}
export function BookDownloadButton({ book }: { book: Book }) { const [open, setOpen] = useState(false); if ((book.type !== undefined && book.type !== 0) || book.origin === 'loc_book') return <span className="muted small">{book.origin === 'loc_book' ? '本地原文件可在书仓下载' : '此类型不支持文本导出'}</span>; return <><ActionButton variant="outline" size="sm" startIcon={<Download />} onClick={() => setOpen(true)}>缓存与导出</ActionButton>{open && <BookDownload book={book} onClose={() => setOpen(false)} />}</>; }
