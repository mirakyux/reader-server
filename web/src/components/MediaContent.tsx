import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import DOMPurify from 'dompurify';
import { FastForward, Rewind } from 'lucide-react';
import { ActionButton, Field } from './Controls';
import { request, resourceUrl } from '../lib/api';
import { cacheScope } from '../lib/cache';
import { parseDanmaku, parseSubtitles, visibleTimedText } from '../lib/media';
import '../media.css';
import { convertChinese } from '../lib/chinese';
import { appearanceUrl, loadReadingFont } from '../lib/appearance';
import { Cover } from './Library';
import type { Book, Bookmark, Chapter, ReadingSettings } from '../types';
import { highlightedHtml, selectionOffsets } from '../lib/bookmarks';
function frameDocument(frame: HTMLIFrameElement | null): Document | undefined { try { return frame?.contentDocument || undefined; } catch { return undefined; /* Cross-origin frames do not expose their Document. */ } }
export function AudioContent({ book, content, chapter, onEnd }: { book: Book; content: string; chapter?: Chapter; onEnd: () => void }) {
  const [time, setTime] = useState(0); const lyrics = (chapter?.lyric || '').split(/\r?\n/).map((line) => { const match = line.match(/^\[(\d+):(\d+(?:\.\d+)?)\](.*)$/); return { time: match ? Number(match[1]) * 60 + Number(match[2]) : -1, text: match ? match[3] : line }; }); const current = lyrics.findLastIndex((line) => line.time >= 0 && line.time <= time);
  const audio = useRef<HTMLAudioElement>(null); const [rate, setRate] = useState(1); const key = `reader.audio@${book.bookUrl}@${content}`;
  return <div className="audio-content"><div className="audio-cover"><Cover book={book} /></div><audio ref={audio} src={resourceUrl(content.trim())} controls preload="metadata" onLoadedMetadata={() => { if (audio.current) { audio.current.currentTime = Number(localStorage.getItem(key) || 0); audio.current.playbackRate = rate; } }} onTimeUpdate={() => { if (audio.current) { localStorage.setItem(key, String(audio.current.currentTime)); setTime(audio.current.currentTime); } }} onEnded={onEnd} /><div className="toolbar"><ActionButton variant="outline" startIcon={<Rewind />} onClick={() => { if (audio.current) audio.current.currentTime = Math.max(0, audio.current.currentTime - 15); }}>后退 15 秒</ActionButton><Field select label="播放速度" value={rate} onChange={(event) => { const value = Number(event.target.value); setRate(value); if (audio.current) audio.current.playbackRate = value; }}>{[0.5, 0.75, 1, 1.25, 1.5, 2].map((value) => <option value={value} key={value}>{value}×</option>)}</Field><ActionButton variant="outline" endIcon={<FastForward />} onClick={() => { if (audio.current) audio.current.currentTime = Math.min(audio.current.duration, audio.current.currentTime + 15); }}>前进 15 秒</ActionButton></div>{chapter?.lyric && <details open><summary>歌词 / 字幕</summary>{/^https?:\/\//i.test(chapter.lyric) ? <a href={chapter.lyric} target="_blank" rel="noreferrer">打开歌词文件</a> : lyrics.map((line, index) => <p className={index === current ? 'text-primary font-bold' : 'muted'} key={index}>{line.text}</p>)}</details>}{chapter?.danmaku && <details><summary>章节弹幕</summary><pre className="advanced-result">{chapter.danmaku}</pre></details>}</div>;
}
function useMediaText(bookUrl: string, index: number, field: 'lyric' | 'danmaku', value = ''): { text: string; error?: string } {
  const [loaded, setLoaded] = useState<{ key: string; text: string; error?: string }>();
  const key = `${bookUrl}@${index}@${field}@${value}`; const remote = /^(https?:\/\/|\/|@js:)/.test(value.trim());
  useEffect(() => {
    if (!remote) return;
    const controller = new AbortController();
    void request<string>('getChapterMediaText', { method: 'POST', data: { url: bookUrl, index, field }, signal: controller.signal })
      .then((text) => setLoaded({ key, text }))
      .catch((error) => { if (!controller.signal.aborted) setLoaded({ key, text: '', error: error.message }); });
    return () => controller.abort();
  }, [bookUrl, index, field, remote, key]);
  return remote ? loaded?.key === key ? loaded : { text: '' } : { text: value };
}
export function VideoContent({ book, content, chapter, onEnd }: { book: Book; content: string; chapter?: Chapter; onEnd: () => void }) {
  const video = useRef<HTMLVideoElement>(null); const stage = useRef<HTMLDivElement>(null); const [rate, setRate] = useState(1); const [time, setTime] = useState(0); const [commentsEnabled, setCommentsEnabled] = useState(true);
  const key = `reader.video@${cacheScope()}@${book.bookUrl}@${chapter?.index || 0}`;
  const subtitle = useMediaText(book.bookUrl, chapter?.index || 0, 'lyric', chapter?.lyric);
  const comments = useMediaText(book.bookUrl, chapter?.index || 0, 'danmaku', chapter?.danmaku);
  const cues = useMemo(() => parseSubtitles(subtitle.text), [subtitle.text]); const danmaku = useMemo(() => parseDanmaku(comments.text), [comments.text]);
  const active = visibleTimedText(cues, time); const visibleComments = commentsEnabled ? visibleTimedText(danmaku, time).slice(0, 10) : [];
  return <div className="form-stack"><div className="video-stage" ref={stage}>
    <video ref={video} className="role-player" src={resourceUrl(content.trim())} controls preload="metadata" onLoadedMetadata={() => { if (video.current) { video.current.currentTime = Number(localStorage.getItem(key) || 0); video.current.playbackRate = rate; setTime(video.current.currentTime); } }} onTimeUpdate={() => { if (video.current) { localStorage.setItem(key, String(video.current.currentTime)); setTime(video.current.currentTime); } }} onSeeked={() => { if (video.current) setTime(video.current.currentTime); }} onEnded={onEnd} />
    <div className="video-subtitles" aria-live="off">{active.map((cue, index) => <div key={index}>{cue.text}</div>)}</div>
    <div className="video-danmaku" aria-label="播放弹幕">{visibleComments.map((cue, index) => <span key={`${cue.start}-${index}`} style={{ color: cue.color, top: `${8 + index * 7}%`, left: cue.mode === 4 || cue.mode === 5 ? '50%' : `${100 - (time - cue.start) / (cue.end - cue.start) * 130}%`, transform: 'translateX(-50%)' }}>{cue.text}</span>)}</div>
    </div><div className="toolbar"><Field label="视频播放速度" select value={rate} onChange={(event) => { const value = Number(event.target.value); setRate(value); if (video.current) video.current.playbackRate = value; }}>{[0.5, 0.75, 1, 1.25, 1.5, 2].map((value) => <option key={value} value={value}>{value}×</option>)}</Field>{danmaku.length > 0 && <ActionButton variant="outline" onClick={() => setCommentsEnabled(!commentsEnabled)}>{commentsEnabled ? '关闭弹幕' : '开启弹幕'}</ActionButton>}<ActionButton variant="outline" onClick={() => { void stage.current?.requestFullscreen?.(); }}>视频全屏</ActionButton></div>
    {(subtitle.error || comments.error) && <p role="alert">{subtitle.error || comments.error}</p>}
    {chapter?.lyric && <details><summary>视频字幕</summary>{cues.length ? cues.map((cue, index) => <button className={`subtitle-cue${active.includes(cue) ? ' active' : ''}`} key={index} onClick={() => { if (video.current) { video.current.currentTime = cue.start; setTime(cue.start); } }}>{cue.text}</button>) : <pre className="advanced-result">{subtitle.text}</pre>}</details>}
    {chapter?.danmaku && !danmaku.length && comments.text && <details><summary>章节弹幕</summary><pre className="advanced-result">{comments.text}</pre></details>}
  </div>;
}
export function EpubContent({ content, settings, background, color, bookmarks, chapter, bookmarkId, onSelection, onDocument }: { content: string; settings: ReadingSettings; background: string; color: string; bookmarks: Bookmark[]; chapter: number; bookmarkId?: string; onSelection: (selection: ReturnType<typeof selectionOffsets>) => void; onDocument: (body: HTMLElement | undefined) => void }) {
  const frame = useRef<HTMLIFrameElement>(null); const original = useRef<{ document: Document; html: string } | undefined>(undefined); const fontGeneration = useRef(0); const cleanup = useRef(() => {}); const positioned = useRef<{ document: Document; bookmarkId: string } | undefined>(undefined);
  const attachDocument = useCallback(() => {
    cleanup.current();
    const document = frameDocument(frame.current); if (!document?.body || document.URL === 'about:blank') { onDocument(undefined); return; } const updateSelection = () => onSelection(selectionOffsets(document.body)); document.addEventListener('selectionchange', updateSelection); cleanup.current = () => document.removeEventListener('selectionchange', updateSelection); onDocument(document.body);
  }, [onDocument, onSelection]);
  const applySettings = useCallback(() => {
    const generation = ++fontGeneration.current;
      const document = frameDocument(frame.current); if (!document?.body || document.URL === 'about:blank') { onDocument(undefined); return; }
      // Restore the source markup before applying ranges so edits and Chinese conversion never accumulate.
      if (original.current?.document !== document) original.current = { document, html: DOMPurify.sanitize(document.body.innerHTML) };
      const template = window.document.createElement('template'); template.innerHTML = highlightedHtml(original.current.html, bookmarks, chapter); document.body.replaceChildren(...Array.from(template.content.childNodes, (node) => document.importNode(node, true)));
      document.documentElement.style.setProperty('background', background); Object.assign(document.body.style, { background, color, fontSize: `${settings.fontSize}px`, lineHeight: String(settings.lineHeight), fontFamily: settings.fontFamily, fontWeight: String(settings.fontWeight), padding: '12px 18px', margin: '0', position: 'relative', zIndex: '0' });
      if (settings.customFontUrl) void loadReadingFont(settings.customFontUrl, document).then((family) => { if (generation === fontGeneration.current && frameDocument(frame.current) === document) document.body.style.setProperty('font-family', `${family}, ${settings.fontFamily}`); }).catch(() => {});
      const imageBackground = document.createElementNS('http://www.w3.org/1999/xhtml', 'div') as HTMLDivElement; imageBackground.id = 'reader-background'; document.body.prepend(imageBackground); Object.assign(imageBackground.style, { position: 'fixed', inset: '0', pointerEvents: 'none', zIndex: '-1', display: settings.backgroundImage ? 'block' : 'none', backgroundImage: settings.backgroundImage ? `url(${JSON.stringify(appearanceUrl(settings.backgroundImage))})` : '', backgroundSize: settings.backgroundSize || 'cover', backgroundPosition: 'center', backgroundRepeat: 'no-repeat', opacity: String(settings.backgroundOpacity ?? 0.3) });
      document.querySelectorAll('p').forEach((paragraph) => paragraph.style.setProperty('margin-bottom', `${settings.paragraphSpace}em`)); document.querySelectorAll('img').forEach((image) => { image.style.setProperty('max-width', '100%'); image.style.setProperty('height', 'auto'); }); const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let node: Node | null; while ((node = walker.nextNode())) { const text = node as Text; if (!text.parentElement?.closest('script,style')) text.replaceData(0, text.length, convertChinese(text.data, settings.chineseScript)); }
      if (bookmarkId && (positioned.current?.document !== document || positioned.current.bookmarkId !== bookmarkId)) { const mark = document.querySelector(`mark[data-bookmark="${CSS.escape(bookmarkId)}"]`); if (mark) { mark.scrollIntoView({ block: 'center' }); positioned.current = { document, bookmarkId }; } }
      onDocument(document.body);
  }, [background, color, settings, bookmarks, chapter, bookmarkId, onDocument]);
  const release = useCallback(() => { cleanup.current(); fontGeneration.current++; }, []);
  useEffect(() => { applySettings(); }, [applySettings, content]);
  useEffect(() => { attachDocument(); return release; }, [attachDocument, content, release]);
  return <iframe ref={frame} className="epub-content" title="EPUB 章节正文" src={resourceUrl(content.trim())} sandbox="allow-same-origin" onLoad={() => { applySettings(); attachDocument(); }} />;
}
