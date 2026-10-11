import type { Bookmark } from '../types';
export function bookmarkRange(bookmark: Bookmark, chapter: number, length: number): [number, number] | undefined {
  if (bookmark.bookmarkType !== 1) return;
  const end = bookmark.endChapterIndex ?? bookmark.chapterIndex;
  if (chapter < bookmark.chapterIndex || chapter > end) return;
  const from = Math.max(0, chapter === bookmark.chapterIndex ? bookmark.chapterPos || 0 : 0);
  const to = Math.min(length, chapter === end ? bookmark.endChapterPos || 0 : length);
  return to > from ? [from, to] : undefined;
}
export function selectionOffsets(root: HTMLElement): { text: string; from: number; to: number } | undefined {
  const document = root.ownerDocument; const selection = document.getSelection(); if (!selection?.rangeCount || selection.isCollapsed) return;
  const range = selection.getRangeAt(0); if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return;
  const prefix = document.createRange(); prefix.selectNodeContents(root); prefix.setEnd(range.startContainer, range.startOffset);
  return { text: range.toString(), from: prefix.toString().length, to: prefix.toString().length + range.toString().length };
}
export function argbHex(color = -32885): string { return `#${(color >>> 0).toString(16).padStart(8, '0').slice(2)}`; }
export function hexArgb(color: string): number { return (parseInt(color.replace('#', 'ff'), 16) | 0); }
export function highlightedHtml(html: string, bookmarks: Bookmark[], chapter: number): string {
  const container = document.createElement('div'); container.innerHTML = html; const length = container.textContent?.length || 0;
  const ranges = bookmarks.map((bookmark) => ({ bookmark, range: bookmarkRange(bookmark, chapter, length) })).filter((item) => item.range);
  if (!ranges.length) return html;
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT); const nodes: Text[] = []; let current: Node | null;
  while ((current = walker.nextNode())) nodes.push(current as Text);
  let offset = 0;
  for (const node of nodes) { const text = node.data; const start = offset; offset += text.length; const matching = ranges.filter((item) => item.range![0] < offset && item.range![1] > start); if (!matching.length) continue;
    const points = [...new Set([0, text.length, ...matching.flatMap((item) => [Math.max(0, item.range![0] - start), Math.min(text.length, item.range![1] - start)])])].sort((a, b) => a - b); const fragment = document.createDocumentFragment();
    for (let index = 0; index < points.length - 1; index++) { const from = points[index]; const to = points[index + 1]; const match = matching.find((item) => item.range![0] <= start + from && item.range![1] >= start + to); if (!match) fragment.append(text.slice(from, to)); else { const mark = document.createElement('mark'); mark.textContent = text.slice(from, to); mark.dataset.bookmark = String(match.bookmark.time); mark.title = match.bookmark.content || ''; mark.style.color = 'inherit'; const color = argbHex(match.bookmark.highlightColor); if (!match.bookmark.highlightStyle) mark.style.backgroundColor = color; else { mark.style.backgroundColor = 'transparent'; mark.style.textDecoration = `underline ${match.bookmark.highlightStyle === 2 ? 'wavy' : 'solid'} ${color}`; } fragment.append(mark); } }
    node.replaceWith(fragment);
  }
  return container.innerHTML;
}
