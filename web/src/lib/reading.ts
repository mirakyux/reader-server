import type { Book, BookSource, ReadingSettings, ReplaceRule } from '../types';
export const defaultReadingSettings: ReadingSettings = { theme: 'paper', fontSize: 21, lineHeight: 1.9, width: 760, fontFamily: 'serif', speechRate: 1, voiceName: '', pageMode: 'scroll', pageAnimation: 'slide', chineseScript: 'original', fontWeight: 400, paragraphSpace: 1.15, letterSpacing: 0, textIndent: 2, textAlign: 'left', pagePadding: 8, prefetchChapters: 2, keepAwake: false, comicDirection: 'vertical', comicScale: 1, comicFilter: 'original' };
export function chapterIndex(book: Book, count: number): number { return Math.max(0, Math.min(Math.max(0, count - 1), Number(book.durChapterIndex) || 0)); }
export function progressPercent(index: number, count: number): number { return count > 0 ? Math.round(((index + 1) / count) * 100) : 0; }
export function sourceImport(text: string): BookSource[] {
  const parsed: unknown = JSON.parse(text.replace(/^\uFEFF/, ''));
  const sources = Array.isArray(parsed) ? parsed : [parsed];
  if (!sources.length) throw new Error('书源文件为空');
  return sources.map((value, index) => {
    if (!value || typeof value !== 'object') throw new Error(`第 ${index + 1} 项不是有效书源`);
    const source = { ...value } as BookSource;
    if (!source.bookSourceUrl || !source.bookSourceName) throw new Error(`第 ${index + 1} 项缺少书源名称或地址`);
    if (source.headerMap && !source.header) source.header = typeof source.headerMap === 'string' ? source.headerMap : JSON.stringify(source.headerMap);
    delete source.headerMap;
    return source;
  });
}
export function exploreCategories(text = ''): { title: string; url: string }[] {
  try { const parsed: unknown = JSON.parse(text); if (Array.isArray(parsed)) return parsed.filter((item): item is { title: string; url: string } => !!item?.title && !!item?.url); } catch { /* Traditional title::url format. JavaScript executes on the server only. */ }
  return text.split(/\r?\n/).filter((line) => line.includes('::')).map((line) => { const separator = line.indexOf('::'); return { title: line.slice(0, separator), url: line.slice(separator + 2) }; });
}
export function matchingReplaceRules(rules: ReplaceRule[], book: Book, target: 'title' | 'content' = 'content'): ReplaceRule[] {
  if (book.useReplaceRule === false) return [];
  const matches = (scope: string) => scope.split(/[,，]/).some((name) => [book.name, book.origin, book.originName].includes(name.trim()));
  return rules.filter((rule) => rule.isEnabled && (target === 'title' ? rule.scopeTitle === true : rule.scopeContent !== false) && (!rule.scope || matches(rule.scope)) && (!rule.excludeScope || !matches(rule.excludeScope))).sort((left, right) => (left.order || 0) - (right.order || 0));
}
export function applyReplaceRules(text: string, rules: ReplaceRule[], book: Book, target: 'title' | 'content' = 'content'): string {
  return matchingReplaceRules(rules, book, target).reduce((content, rule) => {
    try { return rule.isRegex ? content.replace(new RegExp(rule.pattern, 'g'), rule.replacement) : content.split(rule.pattern).join(rule.replacement); } catch { return content; }
  }, text);
}
export function normalizeReadingSettings(value: unknown): ReadingSettings {
  const raw = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  const fonts = ['sans-serif', 'sans-serif', 'KaiTi, STKaiti, serif', 'serif', 'FangSong, serif']; const legacyFont = Number(raw.font) || 0;
  const fontMap = raw.customFontsMap && typeof raw.customFontsMap === 'object' ? raw.customFontsMap as Record<string, string> : {};
  const legacy: Partial<ReadingSettings> = { ...(raw.readWidth !== undefined ? { width: Number(raw.readWidth) } : {}), ...(raw.font !== undefined ? { fontFamily: fonts[legacyFont] || 'serif', customFontUrl: fontMap[['custom-system', 'custom-ht', 'custom-kt', 'custom-st', 'custom-fs'][legacyFont]] || '' } : {}), ...(raw.contentBGImg ? { backgroundImage: String(raw.contentBGImg) } : {}), ...(raw.chineseFont ? { chineseScript: raw.chineseFont === '繁体' ? 'traditional' : raw.chineseFont === '简体' ? 'simple' : 'original' } : {}), ...(raw.readMethod ? { pageMode: raw.readMethod === '左右滑动' ? 'page' : 'scroll' } : {}), ...(raw.theme === 'custom' ? { customTextColor: String(raw.fontColor || ''), customBackgroundColor: String(raw.contentColor || raw.bodyColor || '') } : {}) };
  const result = { ...defaultReadingSettings, ...legacy, ...raw } as ReadingSettings;
  if (!['paper', 'white', 'night', 'green'].includes(result.theme)) result.theme = raw.themeType === 'night' || raw.theme === 6 ? 'night' : raw.theme === 7 ? 'white' : raw.theme === 2 ? 'green' : 'paper';
  const number = (value: unknown, fallback: number, min: number, max: number) => Number.isFinite(Number(value)) ? Math.max(min, Math.min(max, Number(value))) : fallback;
  result.fontSize = number(result.fontSize, 21, 8, 100); result.lineHeight = number(result.lineHeight, 1.9, 1, 4); result.width = number(result.width, 760, 280, 2000); result.fontWeight = number(result.fontWeight, 400, 100, 900); result.paragraphSpace = number(result.paragraphSpace, 1.15, 0, 5);
  result.letterSpacing = number(result.letterSpacing, 0, -2, 8); result.textIndent = number(result.textIndent, 2, 0, 4); result.pagePadding = number(result.pagePadding, 8, 0, 64); result.prefetchChapters = Math.round(number(result.prefetchChapters, 2, 0, 10)); result.comicScale = number(result.comicScale, 1, 0.5, 3);
  if (!['slide', 'cover', 'none'].includes(result.pageAnimation || '')) result.pageAnimation = 'slide';
  if (!['left', 'justify'].includes(result.textAlign || '')) result.textAlign = 'left';
  if (!['vertical', 'horizontal'].includes(result.comicDirection || '')) result.comicDirection = 'vertical';
  if (!['original', 'grayscale', 'night'].includes(result.comicFilter || '')) result.comicFilter = 'original';
  if (!['scroll', 'page'].includes(result.pageMode)) result.pageMode = legacy.pageMode || 'scroll';
  if (!['original', 'simple', 'traditional'].includes(result.chineseScript)) result.chineseScript = 'original';
  return result;
}
export function readSettings(): ReadingSettings { try { const modern = localStorage.getItem('reader.settings'); return normalizeReadingSettings(JSON.parse(modern || localStorage.getItem('config') || '{}')); } catch { return { ...defaultReadingSettings }; } }
export function downloadJson(name: string, data: unknown) { const link = document.createElement('a'); const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
export function errorText(error: unknown): string { return error instanceof Error ? error.message : String(error); }
