import { useEffect, useState } from 'react';
import DOMPurify from 'dompurify';
import { Download, Pencil, Plus, RefreshCw, Star, Trash2 } from 'lucide-react';
import { ActionButton, Field, IconAction, Modal, ModalBody, ModalTitle, Toggle } from './Controls';
import { Confirm, DataTable, FileButton, JsonDialog, PageTitle, Panel, Toolbar, useAction } from './Common';
import { api } from '../lib/api';
import { warmOfflineShell } from '../lib/cache';
import { downloadJson, exploreCategories } from '../lib/reading';
import { cachedRssArticle, cachedRssFeed, loadRssLibrary, mergeRssArticles, rememberRssArticle, rememberRssFeed, rssIdentity, rssPageRequest, rssSnapshot } from '../lib/rss';
import type { SavedRssArticle, RssListKind } from '../lib/rss';
import type { RssArticle, RssSource } from '../types';
import '../rss-sync.css';
import { SourceBrowser } from './SourceFeatures';

export function Rss() {
  const [sources, setSources] = useState<RssSource[]>([]); const [source, setSource] = useState<RssSource>();
  const [articles, setArticles] = useState<RssArticle[]>([]); const [favorites, setFavorites] = useState<SavedRssArticle[]>([]);
  const [editing, setEditing] = useState<RssSource>(); const [deleting, setDeleting] = useState<RssSource>();
  const [article, setArticle] = useState<SavedRssArticle>(); const [mode, setMode] = useState<'feed' | RssListKind>('feed');
  const [page, setPage] = useState(1); const [sortUrl, setSortUrl] = useState(''); const [sortName, setSortName] = useState('');
  const [nextUrl, setNextUrl] = useState<string | null>(null); const [categories, setCategories] = useState<{ title: string; url: string }[]>([]);
  const [search, setSearch] = useState(''); const [group, setGroup] = useState(''); const [sourceGroup, setSourceGroup] = useState('');
  const [ordering, setOrdering] = useState('source'); const [debug, setDebug] = useState<Record<string, unknown>>(); const [clearing, setClearing] = useState(false);
  const [browser, setBrowser] = useState<string>();
  const { run, busy } = useAction();
  const load = async () => { setSources(await rssSnapshot('sources', () => api.get<RssSource[]>('getRssSources')) || []); setFavorites(await loadRssLibrary('favorites')); };
  useEffect(() => { void run(load); }, [run]);
  const loadSaved = async (kind: RssListKind) => { setMode(kind); setArticles(await loadRssLibrary(kind)); setSource(undefined); setGroup(''); };
  const loadArticles = async (selected: RssSource, url = '', name = '', more = false, key = search) => {
    if (selected.singleUrl) {
      setSource(selected); setMode('feed'); setArticles([]); setNextUrl(null); setCategories([]);
      await open({ origin: selected.sourceUrl, link: selected.sourceUrl, title: selected.sourceName });
      return;
    }
    setSource(selected); setMode('feed');
    if (!more) { setArticles([]); setCategories([]); setNextUrl(null); }
    const options = await rssSnapshot(`categories:${selected.sourceUrl}`, () => api.get<{ title: string; url: string }[]>('rss/categories', { sourceUrl: selected.sourceUrl }));
    const first = !selected.singleUrl ? options[0] || exploreCategories(selected.sortUrl)[0] : undefined;
    const selectedUrl = url || first?.url || selected.sourceUrl; const selectedName = name || first?.title || '';
    const requestedPage = more ? page + 1 : 1;
    const request = rssPageRequest(nextUrl, selectedUrl, requestedPage);
    let result: { first: RssArticle[]; second: string | null };
    try { result = await api.post('getRssArticles', { sourceUrl: selected.sourceUrl, sortName: selectedName, ...request, key }); }
    catch (error) { const saved = await cachedRssFeed(selected.sourceUrl, selectedUrl); if (!saved) throw error; result = { first: saved.rows, second: saved.nextUrl }; }
    const rows = more ? mergeRssArticles(articles, result.first) : result.first;
    setSource(selected); setMode('feed'); setCategories(options); setSortUrl(selectedUrl); setSortName(selectedName); setPage(requestedPage); setNextUrl(result.second || null); setArticles(rows);
    await rememberRssFeed(selected.sourceUrl, selectedUrl, rows, result.second);
  };
  const open = async (row: RssArticle) => {
    const saved = row as SavedRssArticle; let content = saved.content || '';
    if (!content && mode !== 'offline') {
      try { content = await api.post<string>('getRssContent', { sourceUrl: row.origin || source?.sourceUrl, link: row.link, origin: row.origin || source?.sourceUrl, article: row }); }
      catch (error) { content = (await cachedRssArticle(row))?.content || ''; if (!content) throw error; }
    }
    const selected = { ...row, origin: row.origin || source?.sourceUrl || '', content: content || row.description || '', group: saved.group || '默认分组' };
    setArticle(selected); await rememberRssArticle(selected);
    if (navigator.onLine) await api.post('rss/article', { kind: 'history', article: selected });
  };
  const starred = (row: RssArticle) => favorites.some((favorite) => rssIdentity(row) === rssIdentity(favorite));
  const favorite = async (row: RssArticle, remove = starred(row)) => {
    if (remove) await api.post('rss/article/delete', { ...row, kind: 'favorites' });
    else await api.post('rss/article', { kind: 'favorites', article: { ...row, origin: row.origin || source?.sourceUrl, group: (row as SavedRssArticle).group || '默认分组' } });
    const rows = await loadRssLibrary('favorites'); setFavorites(rows); if (mode === 'favorites') setArticles(rows);
  };
  const download = async () => {
    if (!article) return;
    const cached = await api.post<SavedRssArticle>('rss/article/cache', { article });
    await rememberRssArticle(cached); await loadRssLibrary('offline'); await warmOfflineShell(); setArticle(cached);
  };
  const ordered = articles.filter((row) => !group || (row as SavedRssArticle).group === group).slice().sort((a, b) => ordering === 'title' ? a.title.localeCompare(b.title, 'zh-CN') : ordering === 'date' ? (Date.parse(b.pubDate || '') || 0) - (Date.parse(a.pubDate || '') || 0) : 0);
  const groups = [...new Set(articles.map((row) => (row as SavedRssArticle).group || '默认分组'))];
  const sourceGroups = [...new Set(sources.map((row) => String(row.sourceGroup || '')).filter(Boolean))];
  return <><PageTitle title="订阅" description="阅读订阅文章，收藏、分组，并保存到设备离线阅读。" extra={<>
    <FileButton onFiles={(files) => run(async () => { const value = JSON.parse(await files[0].text()); await api.post('saveRssSources', Array.isArray(value) ? value : [value]); await load(); }, '订阅源已导入').then(() => undefined)} />
    <ActionButton variant="outline" startIcon={<Download />} onClick={() => downloadJson('reader-rss-sources.json', sources)}>导出订阅源</ActionButton>
    <ActionButton startIcon={<Plus />} onClick={() => setEditing({ sourceName: '', sourceUrl: '', ruleArticles: '', ruleTitle: '', ruleLink: '', ruleContent: '' })}>添加订阅</ActionButton>
  </>} /><Toolbar>{(['favorites', 'history', 'offline'] as const).map((kind) => <ActionButton key={kind} variant={mode === kind ? 'default' : 'outline'} onClick={() => void run(() => loadSaved(kind))}>{({ favorites: '收藏', history: '历史', offline: '离线' })[kind]}</ActionButton>)}<ActionButton variant="ghost" disabled={busy} onClick={() => void run(load)}>刷新订阅源</ActionButton></Toolbar>
    <div className="rss-layout"><Panel><h3>我的订阅</h3><Field select label="订阅分组" value={sourceGroup} onChange={(event) => setSourceGroup(event.target.value)}><option value="">全部</option>{sourceGroups.map((item) => <option key={item}>{item}</option>)}</Field>
      {sources.filter((row) => !sourceGroup || row.sourceGroup === sourceGroup).slice().sort((a, b) => Number(a.customOrder || 0) - Number(b.customOrder || 0) || a.sourceName.localeCompare(b.sourceName, 'zh-CN')).map((item) => <div className={`rss-source ${source?.sourceUrl === item.sourceUrl ? 'active' : ''}`} key={item.sourceUrl}>
        <button disabled={item.enabled === false || busy} onClick={() => void run(() => loadArticles(item, '', '', false, ''))}>{item.sourceName}</button>
        <Toggle inputProps={{ 'aria-label': `启用 ${item.sourceName}` }} checked={item.enabled !== false} onChange={(_, enabled) => void run(async () => { await api.post('saveRssSource', { ...item, enabled }); await load(); })} />
        <IconAction aria-label={`编辑 ${item.sourceName}`} onClick={() => setEditing(item)}><Pencil /></IconAction><IconAction aria-label={`删除 ${item.sourceName}`} onClick={() => setDeleting(item)}><Trash2 /></IconAction>
      </div>)}
    </Panel><Panel>{source ? <><div className="panel-heading"><h2>{source.sourceName}</h2><IconAction aria-label="刷新文章" disabled={busy} onClick={() => void run(() => loadArticles(source, sortUrl, sortName))}><RefreshCw /></IconAction></div>
      <Toolbar>{categories.map((category) => <ActionButton size="sm" variant={sortUrl === category.url ? 'default' : 'outline'} key={category.url} onClick={() => void run(() => loadArticles(source, category.url, category.title))}>{category.title || '默认'}</ActionButton>)}</Toolbar>
      <Toolbar><Field label="订阅搜索" value={search} onChange={(event) => setSearch(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void run(() => loadArticles(source, sortUrl, sortName)); }} /><ActionButton disabled={busy} onClick={() => void run(() => loadArticles(source, sortUrl, sortName))}>搜索</ActionButton>
        <ActionButton variant="ghost" disabled={busy} onClick={() => void run(async () => setDebug(await api.post('rss/debug', { sourceUrl: source.sourceUrl, sortUrl, sortName, key: search })))}>调试此订阅</ActionButton><ActionButton variant="ghost" onClick={() => setBrowser(source.sourceUrl)}>浏览器登录</ActionButton></Toolbar></> : <h2>{mode === 'feed' ? '选择订阅源' : ({ favorites: '文章收藏', history: '阅读历史', offline: '设备离线文章' })[mode]}</h2>}
      <Toolbar><Field select label="文章排序" value={ordering} onChange={(event) => setOrdering(event.target.value)}><option value="source">源顺序 / 最近保存</option><option value="date">发布时间</option><option value="title">标题</option></Field>
        {mode === 'favorites' && <><Field select label="收藏分组" value={group} onChange={(event) => setGroup(event.target.value)}><option value="">全部</option>{groups.map((item) => <option key={item}>{item}</option>)}</Field><ActionButton variant="outline" onClick={() => downloadJson('rssStar.json', favorites)}>导出收藏</ActionButton><FileButton onFiles={(files) => run(async () => { const rows = JSON.parse(await files[0].text()) as SavedRssArticle[]; if (!Array.isArray(rows)) throw new Error('收藏文件应为数组'); for (const row of rows) await api.post('rss/article', { kind: 'favorites', article: row }); await load(); await loadSaved('favorites'); }, '收藏已导入').then(() => undefined)}>导入收藏</FileButton></>}
        {(mode === 'history' || mode === 'offline') && <ActionButton variant="ghost" color="error" onClick={() => setClearing(true)}>清空{mode === 'history' ? '历史' : '离线列表'}</ActionButton>}
      </Toolbar><DataTable rows={ordered} rowKey={rssIdentity} loading={busy} columns={[
        { title: '文章', render: (row) => <button className="article-link" onClick={() => void run(() => open(row))}><strong>{row.title}</strong><p className="muted">{row.description?.replace(/<[^>]+>/g, '').slice(0, 100)}</p></button> },
        { title: '时间', render: (row) => row.pubDate || '—' }, { title: '操作', render: (row) => <Toolbar><IconAction aria-label={starred(row) ? `取消收藏 ${row.title}` : `收藏 ${row.title}`} onClick={() => void run(() => favorite(row))}><Star fill={starred(row) ? 'currentColor' : 'none'} /></IconAction>{mode !== 'feed' && <IconAction aria-label={`从${mode === 'history' ? '历史' : mode === 'offline' ? '离线' : '收藏'}移除 ${row.title}`} onClick={() => void run(async () => { await api.post('rss/article/delete', { ...row, kind: mode }); await loadSaved(mode); })}><Trash2 /></IconAction>}</Toolbar> }
      ]} />{source && <ActionButton variant="outline" disabled={busy || !nextUrl} onClick={() => void run(() => loadArticles(source, sortUrl, sortName, true))}>{nextUrl ? '加载更多' : '已到最后一页'}</ActionButton>}
    </Panel></div>
    {editing && <JsonDialog title="订阅源编辑与排序" value={editing} onClose={() => setEditing(undefined)} onSave={async (value) => { const item = value as RssSource; if (!item.sourceUrl || !item.sourceName) throw new Error('请填写订阅源名称和地址'); await api.post('saveRssSource', item); await load(); }} />}
    {deleting && <Confirm danger title={`删除「${deleting.sourceName}」？`} onClose={() => setDeleting(undefined)} onConfirm={async () => { await api.post('deleteRssSource', deleting); await load(); }} />}
    {clearing && <Confirm danger title={`清空${mode === 'history' ? '历史' : '离线列表'}？`} onClose={() => setClearing(false)} onConfirm={async () => { await api.post('rss/article/delete', { kind: mode, all: true }); if (mode !== 'feed') await loadSaved(mode); }} />}
    {article && <Modal open onClose={() => setArticle(undefined)} maxWidth="md"><ModalTitle>{article.title}</ModalTitle><ModalBody><Toolbar><ActionButton variant="outline" onClick={() => void run(() => favorite(article))}>{starred(article) ? '取消收藏' : '收藏文章'}</ActionButton><ActionButton disabled={busy} startIcon={<Download />} onClick={() => void run(download, '正文与图片已缓存到设备')}>保存离线</ActionButton></Toolbar>
      <Field label="收藏分组" value={article.group || '默认分组'} onChange={(event) => setArticle({ ...article, group: event.target.value })} /><ActionButton variant="ghost" disabled={busy} onClick={() => void run(() => favorite(article, false), '收藏分组已保存')}>保存分组</ActionButton>
      <article className="rss-article" dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(article.content || '') }} />{/^https?:\/\//i.test(article.link) && <a href={article.link} target="_blank" rel="noreferrer" className="text-primary underline">打开原文</a>}
    </ModalBody></Modal>}
    {debug && <Modal open onClose={() => setDebug(undefined)} maxWidth="md"><ModalTitle>订阅调试结果</ModalTitle><ModalBody><p>耗时 {String(debug.elapsedMs)} 毫秒；下一页 {String(debug.nextUrl || '无')}</p><pre className="rss-debug">{JSON.stringify(debug, null, 2)}</pre></ModalBody></Modal>}
    {browser && <SourceBrowser bookSourceUrl={browser} sourceType="rss" onClose={() => setBrowser(undefined)} />}
  </>;
}
