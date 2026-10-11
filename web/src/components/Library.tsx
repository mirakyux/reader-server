import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { ContextMenu, DropdownMenu } from 'radix-ui';
import { ActionButton, BadgeChip, Spinner, Modal, ModalActions, ModalBody, ModalTitle, ToggleLabel, IconAction, Toggle, TabItem, TabBar, Field } from './Controls';
import { Plus as Add, ArrowRight as ArrowForward, BookOpen as AutoStoriesOutlined, Trash2 as DeleteOutline, Folder as FolderOutlined, RefreshCw as Refresh, Search as SearchIcon, Upload as UploadFile, Download, MoreHorizontal, Shuffle } from 'lucide-react';
import type { Book, BookGroup, BookSource, ImportPreview } from '../types';
import { useLibrary } from '../context';
import { api, assetUrl, request } from '../lib/api';
import { ShelfManage } from './ShelfManage';
import { BookDownload, BookDownloadButton } from './BookDownload';
import { ChangeSource } from './ChangeSource';
import { ExploreControls, FileDownloads } from './SourceFeatures';
import { sourceEvent } from '../lib/sourceActions';
import type { SourceExploreUi } from './SourceFeatures';
import { openBook, navigate } from '../lib/navigation';
import { errorText, exploreCategories, progressPercent } from '../lib/reading';
import { readShelfSettings, sortShelf } from '../lib/shelf';
import { Confirm, DataTable, EmptyState, ErrorAlert, FileButton, PageTitle, Panel, Toolbar, useAction } from './Common';

export function Cover({ book }: { book: Book }) { const { sources } = useLibrary(); const [decoded, setDecoded] = useState(''); const source = sources.find((item) => item.bookSourceUrl === book.origin); useEffect(() => { if (!source?.coverDecodeJs || !book.coverUrl || book.customCoverUrl) return; let active = true; void api.post<{ base64: string; contentType: string }>('decodeSourceImage', { bookSourceUrl: source.bookSourceUrl, book, url: book.coverUrl, isCover: true }).then((value) => { if (active) setDecoded(`data:${value.contentType};base64,${value.base64}`); }).catch(() => {}); return () => { active = false; }; }, [source?.bookSourceUrl, source?.coverDecodeJs, book]); const [failed, setFailed] = useState(false); const cover = decoded || book.customCoverUrl || book.coverUrl; return cover && !failed ? <img className="book-cover" src={cover.startsWith('data:') ? cover : assetUrl(cover)} alt={`${book.name}封面`} loading="lazy" onError={() => setFailed(true)} /> : <div className="book-cover generated-cover"><AutoStoriesOutlined /><strong>{book.name}</strong><small>{book.author || '佚名'}</small></div>; }
function BookMenuItems({ menu, book, onDownload, onDetails, onChangeSource }: { menu: 'context' | 'dropdown'; book: Book; onDownload: (format: 'txt' | 'epub') => void; onDetails: () => void; onChangeSource: () => void }) {
  const { Item, Label, Separator } = menu === 'context' ? ContextMenu : DropdownMenu;
  const unavailable = book.origin === 'loc_book' ? '本地原文件可在书仓下载' : book.type !== undefined && book.type !== 0 ? '此类型不支持文本导出' : '';
  return <>
    <Item className="app-menu-item" disabled={Boolean(unavailable)} onSelect={() => onDownload('txt')}><Download size={17} />下载 TXT</Item>
    <Item className="app-menu-item" disabled={Boolean(unavailable)} onSelect={() => onDownload('epub')}><Download size={17} />下载 EPUB</Item>
    {unavailable && <Label className="app-menu-label">{unavailable}</Label>}
    <Separator className="app-menu-separator" />
    {book.origin && book.origin !== 'loc_book' && <Item className="app-menu-item" onSelect={onChangeSource}><Shuffle size={17} />切换书源</Item>}
    <Item className="app-menu-item" onSelect={onDetails}><AutoStoriesOutlined size={17} />书籍详情</Item>
  </>;
}

function BookCard({ book, onDetails, isShelf }: { book: Book; onDetails: (book: Book) => void; isShelf: boolean }) {
  const [downloadFormat, setDownloadFormat] = useState<'txt' | 'epub'>();
  const [changingSource, setChangingSource] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const openingDialog = useRef(false);
  const prepareDialog = () => { openingDialog.current = true; menuButton.current?.focus(); };
  const menuItems = (menu: 'context' | 'dropdown') => <BookMenuItems menu={menu} book={book} onDownload={(format) => { prepareDialog(); setDownloadFormat(format); }} onDetails={() => { prepareDialog(); onDetails(book); }} onChangeSource={() => { prepareDialog(); setChangingSource(true); }} />;
  const closeMenuFocus = (event: Event) => { if (openingDialog.current) { event.preventDefault(); openingDialog.current = false; } };
  const title = <button className="book-title-button" onClick={() => onDetails(book)}><h3>{book.name}</h3></button>;
  const card = <article className="book-card" onKeyDown={isShelf ? (event) => {
    if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
      event.preventDefault();
      menuButton.current?.focus();
      setMenuOpen(true);
    }
  } : undefined}>
    <button className="book-cover-button" onClick={() => isShelf ? openBook(book) : onDetails(book)}><Cover book={book} /><div className="cover-overlay"><AutoStoriesOutlined /><span>{isShelf ? '继续阅读' : '查看详情'}</span></div></button>
    {isShelf ? <div className="book-title-row">{title}<DropdownMenu.Root modal={false} open={menuOpen} onOpenChange={setMenuOpen}>
      <DropdownMenu.Trigger asChild><IconAction ref={menuButton} className="book-menu-trigger" aria-label={`书籍操作 · ${book.name}`} title="书籍操作"><MoreHorizontal /></IconAction></DropdownMenu.Trigger>
      <DropdownMenu.Portal><DropdownMenu.Content className="app-menu book-action-menu" align="end" sideOffset={6} collisionPadding={12} onCloseAutoFocus={closeMenuFocus}>{menuItems('dropdown')}</DropdownMenu.Content></DropdownMenu.Portal>
    </DropdownMenu.Root></div> : title}
    <p className="book-author">{book.author || '作者未知'}</p>
    <div className="book-card-foot"><span>{isShelf ? book.durChapterTitle || '尚未开始' : book.originName || book.kind || '网络书源'}</span>{isShelf && <span>{progressPercent(book.durChapterIndex || 0, book.totalChapterNum || 0)}%</span>}</div>
    {isShelf && <div className="book-progress"><i style={{ width: `${progressPercent(book.durChapterIndex || 0, book.totalChapterNum || 0)}%` }} /></div>}
  </article>;
  return <>
    {isShelf ? <ContextMenu.Root modal={false}><ContextMenu.Trigger asChild>{card}</ContextMenu.Trigger><ContextMenu.Portal><ContextMenu.Content className="app-menu book-action-menu" collisionPadding={12} onCloseAutoFocus={closeMenuFocus}>{menuItems('context')}</ContextMenu.Content></ContextMenu.Portal></ContextMenu.Root> : card}
    {downloadFormat && <BookDownload book={book} initialFormat={downloadFormat} returnFocusRef={menuButton} onClose={() => setDownloadFormat(undefined)} />}
    {changingSource && <ChangeSource book={book} onClose={() => setChangingSource(false)} />}
  </>;
}

export function BookGrid({ books, onDetails, isShelf = false }: { books: Book[]; onDetails: (book: Book) => void; isShelf?: boolean }) {
  return <div className="book-grid">{books.map((book) => <BookCard key={book.bookUrl} book={book} onDetails={onDetails} isShelf={isShelf} />)}</div>;
}
export function Shelf() {
  const { books, groups, loading, error, reload } = useLibrary(); const [query, setQuery] = useState(''); const [group, setGroup] = useState(0); const [details, setDetails] = useState<Book>(); const [importOpen, setImportOpen] = useState(false); const [groupsOpen, setGroupsOpen] = useState(false); const [manageOpen, setManageOpen] = useState(false); const [layoutOpen, setLayoutOpen] = useState(false); const [shelfSettings, setShelfSettings] = useState(readShelfSettings); useEffect(() => localStorage.setItem('shelfConfig', JSON.stringify(shelfSettings)), [shelfSettings]);
  const filtered = books.filter((book) => `${book.name} ${book.author || ''}`.toLowerCase().includes(query.toLowerCase()) && (!group || (group === -2 ? book.origin === 'loc_book' : group === -3 ? book.type === 1 : group === -4 ? !book.group : (Number(book.group || 0) & group) !== 0)));
  const recent = [...books].sort((a, b) => Number(b.durChapterTime || 0) - Number(a.durChapterTime || 0))[0];
  return <div className="shelf-page"><PageTitle title="我的书架" extra={<>
    <ActionButton variant="outline" startIcon={<UploadFile />} onClick={() => setImportOpen(true)}>导入书籍</ActionButton>
    <DropdownMenu.Root><DropdownMenu.Trigger asChild><ActionButton variant="ghost" aria-label="管理书架">管理</ActionButton></DropdownMenu.Trigger>
      <DropdownMenu.Portal><DropdownMenu.Content className="app-menu" align="end" sideOffset={8} collisionPadding={12}>
        <DropdownMenu.Item className="app-menu-item" disabled={loading} onSelect={() => void (async () => { for (const book of books) await sourceEvent(book, 'startShelfRefresh'); try { await reload(true); } finally { for (const book of books) await sourceEvent(book, 'endShelfRefresh'); } })()}><Refresh size={17} />检查更新</DropdownMenu.Item>
        <DropdownMenu.Item className="app-menu-item" onSelect={() => setManageOpen(true)}>批量管理</DropdownMenu.Item><DropdownMenu.Item className="app-menu-item" onSelect={() => setLayoutOpen(true)}>书架布局与排序</DropdownMenu.Item>
        <DropdownMenu.Item className="app-menu-item" onSelect={() => setGroupsOpen(true)}><FolderOutlined size={17} />分组管理</DropdownMenu.Item>
      </DropdownMenu.Content></DropdownMenu.Portal>
    </DropdownMenu.Root>
  </>} /><ErrorAlert error={error} retry={() => void reload()} />
    {recent ? <div className="continue-card"><div className="continue-copy"><span className="eyebrow">继续上次的阅读</span><h2>{recent.name}</h2><p>{recent.author || '佚名'} · {recent.durChapterTitle || '尚未开始'}</p><ActionButton variant="default" endIcon={<ArrowForward />} onClick={() => openBook(recent)}>继续阅读</ActionButton></div><div className="continue-cover"><Cover book={recent} /></div></div> : <div className="welcome-card"><h2>从一本好书开始</h2><p>搜索喜欢的故事，或导入本地书籍。</p><ActionButton variant="default" endIcon={<ArrowForward />} onClick={() => navigate('/search')}>寻找一本好书</ActionButton></div>}
    <div className="shelf-heading"><TabBar value={group} onChange={(_, value) => setGroup(value)} variant="scrollable" scrollButtons="auto"><TabItem value={0} label={`全部书籍 ${books.length}`} />{groups.filter((item) => item.groupId !== -1 && item.show !== false).map((item) => <TabItem value={item.groupId} key={item.groupId} label={item.groupName} />)}</TabBar><Field size="sm" label="筛选书名或作者" placeholder="筛选书名或作者" value={query} onChange={(event) => setQuery(event.target.value)} /></div>
    {loading && !books.length ? <div className="loading-space"><Spinner size={28} /></div> : filtered.length ? <div className={`shelf-layout-${shelfSettings.layout}`} style={{ '--shelf-columns': shelfSettings.columns, '--shelf-gap': `${shelfSettings.gap}px`, '--shelf-radius': `${shelfSettings.radius}px` } as CSSProperties}><BookGrid books={sortShelf(filtered, shelfSettings)} isShelf onDetails={setDetails} /></div> : <EmptyState title={books.length ? '没有找到匹配书籍' : '你的书架，等着第一本好书'} description="搜索网络书源，或导入本地 TXT、EPUB、UMD、CBZ 书籍。" action={<ActionButton startIcon={<SearchIcon />} onClick={() => navigate('/search')}>去搜索</ActionButton>} />}
    {layoutOpen && <Modal open onClose={() => setLayoutOpen(false)}><ModalTitle>书架布局与排序</ModalTitle><ModalBody><div className="form-stack"><Field select label="布局" value={shelfSettings.layout} onChange={(event) => setShelfSettings({ ...shelfSettings, layout: event.target.value as 'grid' | 'list' })}><option value="grid">封面网格</option><option value="list">书籍列表</option></Field><Field select label="排列方式" value={shelfSettings.sort} onChange={(event) => setShelfSettings({ ...shelfSettings, sort: event.target.value as typeof shelfSettings.sort })}>{[['read','最近阅读'],['updated','最近更新'],['added','加入时间'],['name','书名'],['author','作者'],['manual','自定义顺序']].map(([value,label]) => <option key={value} value={value}>{label}</option>)}</Field><ToggleLabel label="倒序排列" control={<Toggle checked={shelfSettings.descending} onChange={(_, descending) => setShelfSettings({ ...shelfSettings, descending })} />} /><Field label="网格列数（手机最多3列）" type="number" value={shelfSettings.columns} onChange={(event) => setShelfSettings({ ...shelfSettings, columns: Math.min(8, Math.max(2, Number(event.target.value))) })} /><Field label="书籍间距" type="number" value={shelfSettings.gap} onChange={(event) => setShelfSettings({ ...shelfSettings, gap: Math.min(48, Math.max(8, Number(event.target.value))) })} /><Field label="封面圆角" type="number" value={shelfSettings.radius} onChange={(event) => setShelfSettings({ ...shelfSettings, radius: Math.min(24, Math.max(0, Number(event.target.value))) })} /></div></ModalBody><ModalActions><ActionButton onClick={() => setLayoutOpen(false)}>完成</ActionButton></ModalActions></Modal>}{details && <BookDetails book={details} onClose={() => setDetails(undefined)} />}{importOpen && <BookImport onClose={() => setImportOpen(false)} />}{groupsOpen && <GroupManager onClose={() => setGroupsOpen(false)} />}{manageOpen && <ShelfManage onClose={() => setManageOpen(false)} />}
  </div>;
}
export function BookDetails({ book, onClose }: { book: Book; onClose: () => void }) {
  const { books, groups, reload } = useLibrary();
  const shelfBook = books.find((item) => item.bookUrl === book.bookUrl);
  const inShelf = Boolean(shelfBook);
  const [draft, setDraft] = useState<Book>(() => ({ ...book, ...shelfBook }));
  const [deleting, setDeleting] = useState(false); const [tocPreview, setTocPreview] = useState<ImportPreview>(); const { run, busy } = useAction();
  const edited = useRef(new Set<keyof Book>());
  const [detailLoading, setDetailLoading] = useState(false);
  const [catalogLoaded, setCatalogLoaded] = useState(Number(book.totalChapterNum) > 0);
  const [detailError, setDetailError] = useState('');
  const [detailRevision, setDetailRevision] = useState(0);
  const [changingSource, setChangingSource] = useState(false);
  const updateDraft = (changes: Partial<Book>) => { Object.keys(changes).forEach((key) => edited.current.add(key)); setDraft((previous) => ({ ...previous, ...changes })); };
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const load = async () => {
      setDetailLoading(true); setDetailError('');
      try {
        const details = shelfBook || (book.origin !== 'loc_book' ? await request<Book>('getBookInfo', { method: 'POST', data: { url: book.bookUrl, bookSourceUrl: book.origin }, signal: controller.signal }) : book);
        if (!active) return;
        const total = Number(details.totalChapterNum) > 0 ? Number(details.totalChapterNum) : Number(book.totalChapterNum) || 0;
        setDraft((previous) => {
          const preserved = Object.fromEntries([...edited.current].map((key) => [key, previous[key]]));
          return { ...previous, ...details, totalChapterNum: total, ...preserved };
        });
        if (details.type === 3) return;
        if (total > 0) { setCatalogLoaded(true); return; }
        const chapters = await request<ImportPreview['chapters']>('getChapterList', { method: 'POST', data: { url: details.bookUrl, bookSourceUrl: details.origin || book.origin, fromBookInfo: true }, signal: controller.signal });
        if (!active) return;
        if (!Array.isArray(chapters)) throw new Error('书源未返回有效目录');
        setDraft((previous) => ({ ...previous, totalChapterNum: chapters.length }));
        setCatalogLoaded(true);
      } catch (failure) { if (active) setDetailError(errorText(failure)); }
      finally { if (active) setDetailLoading(false); }
    };
    void load();
    return () => { active = false; controller.abort(); };
  }, [book, shelfBook, detailRevision]);
  const save = async () => { const saved = await api.post<Book>('saveBook', draft); if (!inShelf) void sourceEvent(saved, 'addBookShelf'); setDraft(saved); await reload(); };
  return <Modal open onClose={onClose} fullWidth maxWidth="sm"><ModalTitle>书籍详情</ModalTitle><ModalBody><div className="book-detail-head"><Cover book={draft} /><div><h2>{draft.name}</h2><p>{draft.author || '佚名'}</p><BadgeChip size="sm" label={draft.originName || (draft.origin === 'loc_book' ? '本地书籍' : '网络书源')} />{draft.type !== 3 && <p className="muted" role="status">{catalogLoaded || Number(draft.totalChapterNum) > 0 ? `共 ${draft.totalChapterNum || 0} 章` : detailError ? '目录读取失败' : '正在读取目录…'}</p>}</div></div><ErrorAlert error={detailError} retry={() => setDetailRevision((value) => value + 1)} />{draft.type === 3 && <FileDownloads book={draft} />}<p className="book-intro">{String(draft.intro || '暂无简介').replace(/<[^>]*>/g, '')}</p><div className="form-stack"><Field label="书名" value={draft.name} onChange={(event) => updateDraft({ name: event.target.value })} /><Field label="封面 URL" value={draft.customCoverUrl || ''} onChange={(event) => updateDraft({ customCoverUrl: event.target.value })} /><div>{groups.filter((item) => item.groupId > 0).map((item) => <BadgeChip key={item.groupId} label={item.groupName} color={(Number(draft.group || 0) & item.groupId) ? 'primary' : 'default'} onClick={() => updateDraft({ group: Number(draft.group || 0) ^ item.groupId })} className="group-chip" />)}</div><ToggleLabel label="正文净化" control={<Toggle checked={draft.useReplaceRule !== false} onChange={(_, checked) => updateDraft({ useReplaceRule: checked })} />} /><ToggleLabel control={<Toggle checked={draft.canUpdate !== false} onChange={(_, checked) => updateDraft({ canUpdate: checked })} />} label="追更：检查新章节" /></div><p className="muted">最新：{draft.latestChapterTitle || '暂无信息'}</p></ModalBody><ModalActions className="flex-wrap">{inShelf && <IconAction color="error" aria-label="移出书架" onClick={() => setDeleting(true)}><DeleteOutline /></IconAction>}{inShelf && draft.origin === 'loc_book' && /\.(?:txt|epub)(?:$|[?# ])/i.test(`${draft.bookUrl} ${draft.originName || ''}`) && <ActionButton disabled={busy} onClick={() => void run(async () => { const chapters = await api.post<ImportPreview['chapters']>('getChapterList', { url: draft.bookUrl }); setTocPreview({ book: draft, chapters }); })}>重建本地目录</ActionButton>}{draft.origin && draft.origin !== 'loc_book' && <ActionButton variant="outline" startIcon={<Shuffle />} disabled={busy || detailLoading} onClick={() => setChangingSource(true)}>切换书源</ActionButton>}<ActionButton onClick={onClose}>关闭</ActionButton>{inShelf && <BookDownloadButton book={draft} />}<ActionButton disabled={busy || detailLoading} onClick={() => void run(save, inShelf ? '已保存' : '已加入书架')}>{inShelf ? '保存修改' : '加入书架'}</ActionButton><ActionButton variant="default" onClick={() => { openBook(draft, undefined, undefined, true); onClose(); }}>{draft.type === 3 ? '查看下载' : '开始阅读'}</ActionButton></ModalActions>{changingSource && <ChangeSource book={draft} onClose={() => setChangingSource(false)} onChanged={onClose} />}{tocPreview && <BookImport initial={[tocPreview]} onClose={() => setTocPreview(undefined)} />}{deleting && <Confirm danger title={`将「${book.name}」移出书架？`} onClose={() => setDeleting(false)} onConfirm={async () => { await api.post('deleteBook', draft); void sourceEvent(draft, 'delBookShelf'); await reload(); onClose(); }} />}</Modal>;
}
export function BookImport({ onClose, initial = [] }: { onClose: () => void; initial?: ImportPreview[] }) {
  const { groups, reload } = useLibrary(); const { run, busy } = useAction(); const [preview, setPreview] = useState<ImportPreview[]>(initial); const [group, setGroup] = useState(initial[0]?.book.group || 0); const [tocRule, setTocRule] = useState(initial[0]?.book.tocUrl || ''); const [rules, setRules] = useState<{ name: string; rule: string }[]>([]);
  useEffect(() => { void api.get<{ name: string; rule: string }[]>('getTxtTocRules').then(setRules).catch(() => {}); }, []);
  const upload = async (files: File[]) => { const data = new FormData(); files.forEach((file, index) => data.append(`file${index}`, file)); const imported = await api.post<ImportPreview[]>('importBookPreview', data); setPreview(imported); setTocRule(imported[0]?.book.tocUrl || ''); };
  return <Modal open fullWidth maxWidth="md" onClose={onClose}><ModalTitle>导入本地书籍</ModalTitle><ModalBody><p className="muted">支持 TXT、EPUB、UMD、CBZ，先预览目录，再加入书架。</p><Toolbar><FileButton accept=".txt,.epub,.umd,.cbz" multiple onFiles={(files) => run(() => upload(files)).then(() => undefined)}>选择书籍文件</FileButton><Field select size="sm" label="导入分组" value={group} onChange={(event) => setGroup(Number(event.target.value))} sx={{ minWidth: 170 }}><option value={0}>未分组</option>{groups.filter((item) => item.groupId > 0).map((item) => <option key={item.groupId} value={item.groupId}>{item.groupName}</option>)}</Field></Toolbar><DataTable rows={preview} rowKey={(item) => item.book.bookUrl} loading={busy} columns={[{ title: '书籍', render: (item) => item.book.name }, { title: '作者', render: (item) => item.book.author || '佚名' }, { title: '章节', render: (item) => item.chapters.length }, { title: '目录预览', render: (item) => item.chapters.slice(0, 3).map((chapter) => chapter.title).join(' / ') }]} empty={<EmptyState title="选择文件以预览书籍" />} />{preview.length === 1 && /\.(?:txt|epub)(?:$|[?# ])/i.test(`${preview[0].book.bookUrl} ${preview[0].book.originName || ''}`) && <div className="form-stack">{/\.epub(?:$|[?# ])/i.test(`${preview[0].book.bookUrl} ${preview[0].book.originName || ''}`) ? <Field select fullWidth label="EPUB 目录方式" value={tocRule || 'spin+toc'} onChange={(event) => setTocRule(event.target.value)}><option value="spin+toc">阅读顺序与目录结合</option><option value="spin">按阅读顺序</option><option value="toc">按书籍目录</option></Field> : <Field fullWidth label="自定义 TXT 目录正则" value={tocRule} onChange={(event) => setTocRule(event.target.value)} helperText="例如 ^第.{1,10}章.*$；留空使用默认目录规则" />}<Toolbar>{! /\.epub(?:$|[?# ])/i.test(`${preview[0].book.bookUrl} ${preview[0].book.originName || ''}`) && rules.slice(0, 6).map((rule) => <BadgeChip key={rule.name} label={rule.name} onClick={() => setTocRule(rule.rule)} size="sm" />)}<ActionButton onClick={() => void run(async () => { const result = await api.post<ImportPreview>('getChapterListByRule', { ...preview[0].book, tocUrl: tocRule }); setPreview([result]); }, '目录已重建')}>按规则重建目录</ActionButton></Toolbar></div>}</ModalBody><ModalActions><ActionButton onClick={onClose}>取消</ActionButton><ActionButton variant="default" disabled={busy || !preview.length} onClick={() => void run(async () => { for (const item of preview) await api.post('saveBook', { ...item.book, group }); await reload(); onClose(); }, `已导入 ${preview.length} 本书`)}>加入书架</ActionButton></ModalActions></Modal>;
}
function GroupManager({ onClose }: { onClose: () => void }) {
  const { groups, reload } = useLibrary(); const [name, setName] = useState(''); const [editing, setEditing] = useState<BookGroup>(); const [deleting, setDeleting] = useState<BookGroup>(); const { run } = useAction();
  const ordered = [...groups].sort((left, right) => (left.order || 0) - (right.order || 0));
  const move = async (item: BookGroup, direction: number) => { const index = ordered.findIndex((group) => group.groupId === item.groupId); const target = index + direction; if (target < 0 || target >= ordered.length) return; const next = [...ordered]; [next[index], next[target]] = [next[target], next[index]]; await api.post('saveBookGroupOrder', { order: next.map((group, position) => ({ groupId: group.groupId, order: position })) }); await reload(); };
  const save = async () => { if (!name.trim()) throw new Error('请输入分组名称'); await api.post('saveBookGroup', { ...editing, groupName: name.trim(), show: true }); setName(''); setEditing(undefined); await reload(); };
  return <Modal open onClose={onClose} fullWidth maxWidth="sm"><ModalTitle>分组管理</ModalTitle><ModalBody><Toolbar><Field size="sm" label={editing ? '修改分组名称' : '新分组名称'} value={name} onChange={(event) => setName(event.target.value)} /><ActionButton startIcon={<Add />} onClick={() => void run(save, '分组已保存')}>{editing ? '保存' : '创建'}</ActionButton></Toolbar><DataTable<BookGroup> rows={ordered} rowKey={(item) => item.groupId} columns={[{ title: '分组', render: (item) => item.groupName }, { title: '显示', render: (item) => <Toggle checked={item.show !== false} onChange={(_, show) => void run(async () => { await api.post('saveBookGroup', { ...item, show }); await reload(); })} /> }, { title: '操作', render: (item) => <><ActionButton size="sm" disabled={ordered[0]?.groupId === item.groupId} onClick={() => void run(() => move(item, -1))}>上移</ActionButton><ActionButton size="sm" disabled={ordered.at(-1)?.groupId === item.groupId} onClick={() => void run(() => move(item, 1))}>下移</ActionButton>{item.groupId > 0 && <><ActionButton onClick={() => { setEditing(item); setName(item.groupName); }}>改名</ActionButton><IconAction color="error" onClick={() => setDeleting(item)}><DeleteOutline /></IconAction></>}</> }]} /></ModalBody><ModalActions><ActionButton onClick={onClose}>完成</ActionButton></ModalActions>{deleting && <Confirm danger title={`删除分组「${deleting.groupName}」？`} onClose={() => setDeleting(undefined)} onConfirm={async () => { await api.post('deleteBookGroup', deleting); await reload(); }} />}</Modal>;
}
export function Search() {
  const { sources } = useLibrary(); const { run, busy } = useAction(); const [keyword, setKeyword] = useState(''); const [source, setSource] = useState(''); const [group, setGroup] = useState(''); const [concurrent, setConcurrent] = useState(12); const [books, setBooks] = useState<Book[]>([]); const [details, setDetails] = useState<Book>(); const [lastIndex, setLastIndex] = useState(-1); const [page, setPage] = useState(1); const [searched, setSearched] = useState(false);
  const [submitted, setSubmitted] = useState<{ key: string; bookSourceUrl: string; bookSourceGroup: string; concurrentCount: number }>();
  const searchRequest = useRef(0);
  useEffect(() => () => { searchRequest.current += 1; }, []);
  const groups = [...new Set(sources.map((item) => item.bookSourceGroup).filter(Boolean))];
  const changed = submitted && (keyword.trim() !== submitted.key || source !== submitted.bookSourceUrl || group !== submitted.bookSourceGroup || concurrent !== submitted.concurrentCount);
  const search = async (more = false) => {
    const query = more ? submitted : { key: keyword.trim(), bookSourceUrl: source, bookSourceGroup: group, concurrentCount: concurrent };
    if (!query?.key) throw new Error('请输入书名或作者');
    const requestId = ++searchRequest.current;
    setSearched(true);
    if (!more) { setSubmitted(query); setBooks([]); setLastIndex(-1); setPage(1); }
    const params = { ...query, lastIndex: more ? lastIndex : -1, page: more ? page + 1 : 1 };
    try {
      const result = await api.post<Book[] | { list: Book[]; lastIndex: number }>(query.bookSourceUrl ? 'searchBook' : 'searchBookMulti', params);
      if (requestId !== searchRequest.current) return;
      const list = Array.isArray(result) ? result : result.list;
      setBooks((previous) => [...new Map([...(more ? previous : []), ...list].map((item) => [item.bookUrl, item])).values()]);
      setLastIndex(Array.isArray(result) ? -1 : result.lastIndex); setPage(params.page);
    } catch (error) { if (requestId === searchRequest.current) throw error; }
  };
  return <><PageTitle eyebrow="FIND YOUR NEXT STORY" title="搜索好书" description="跨书源搜索书名与作者，遇见下一本想读的书。" /><Panel><form onSubmit={(event) => { event.preventDefault(); void run(() => search()); }}><div className="search-main"><Field fullWidth size="medium" value={keyword} onChange={(event) => setKeyword(event.target.value)} label="书名 / 作者" autoFocus /><ActionButton type="submit" variant="default" startIcon={<SearchIcon />} disabled={busy}>搜索</ActionButton></div><Toolbar><Field select size="sm" label="搜索书源" value={source} onChange={(event) => setSource(event.target.value)} sx={{ minWidth: 230 }}><option value="">全部启用书源</option>{sources.filter((item) => item.enabled !== false).map((item) => <option value={item.bookSourceUrl} key={item.bookSourceUrl}>{item.bookSourceName}</option>)}</Field><Field select size="sm" label="书源分组" value={group} onChange={(event) => setGroup(event.target.value)} sx={{ minWidth: 180 }}><option value="">全部分组</option>{groups.map((item) => <option value={item} key={item}>{item}</option>)}</Field><Field select size="sm" label="并发数" value={concurrent} onChange={(event) => setConcurrent(Number(event.target.value))} sx={{ width: 120 }}>{[4, 8, 12, 24].map((value) => <option value={value} key={value}>{value}</option>)}</Field></Toolbar></form>{changed && <p className="muted" role="status">搜索条件已变更，点击“搜索”开始新搜索。</p>}</Panel><div className="results-title"><h2>{searched ? `找到 ${books.length} 本书${submitted ? ` · “${submitted.key}”` : ''}` : '从一个关键词开始'}</h2>{busy && <Spinner size={22} />}</div>{books.length ? <><BookGrid books={books} onDetails={setDetails} /><div className="load-more"><ActionButton variant="outline" disabled={busy} onClick={() => void run(() => search(true))}>搜索更多</ActionButton></div></> : <EmptyState title={searched ? '暂时没有找到结果' : '故事正在某处等你'} description={sources.length ? '试试喜欢的书名或作者，也可以指定一个书源。' : '先导入书源，才能搜索网络图书。'} action={!sources.length && <ActionButton onClick={() => navigate('/sources')}>添加书源</ActionButton>} />}{details && <BookDetails book={details} onClose={() => setDetails(undefined)} />}</>;
}
export function Explore() {
  const [sourceUi, setSourceUi] = useState<SourceExploreUi>(); const { sources } = useLibrary(); const [source, setSource] = useState<BookSource>(); const [categories, setCategories] = useState<{ title: string; url: string }[]>([]); const [custom, setCustom] = useState(''); const [active, setActive] = useState(''); const [books, setBooks] = useState<Book[]>([]); const [details, setDetails] = useState<Book>(); const [page, setPage] = useState(1); const { run, busy } = useAction();
  const selectedSource = useRef<BookSource | undefined>(undefined); const uiRequest = useRef(0); const bookRequest = useRef(0);
  useEffect(() => () => { uiRequest.current += 1; bookRequest.current += 1; selectedSource.current = undefined; }, []);
  const loadSourceUi = useCallback(async (selected: BookSource) => {
    const requestId = ++uiRequest.current;
    try {
      const value = await api.post<SourceExploreUi>('getSourceExploreUi', { bookSourceUrl: selected.bookSourceUrl });
      if (requestId === uiRequest.current && selectedSource.current === selected && Array.isArray(value.categories)) setSourceUi(value);
    } catch (error) { if (requestId === uiRequest.current && selectedSource.current === selected) throw error; }
  }, []);
  const selectSource = (selected: BookSource) => {
    const selection = { ...selected };
    selectedSource.current = selection; bookRequest.current += 1;
    setSource(selection); setCategories(exploreCategories(selected.exploreUrl)); setBooks([]); setActive(''); setPage(1); setCustom(''); setSourceUi(undefined);
    void run(() => loadSourceUi(selection));
  };
  const explore = async (url: string, more = false) => {
    if (!source) throw new Error('请选择书源'); if (!url) throw new Error('请选择分类或输入发现地址');
    const bookSourceUrl = source.bookSourceUrl;
    if (selectedSource.current !== source) return;
    const requestId = ++bookRequest.current; const next = more ? page + 1 : 1;
    if (!more) { setActive(url); setBooks([]); setPage(1); }
    try {
      const list = await api.post<Book[]>('exploreBook', { ruleFindUrl: url, bookSourceUrl, page: next });
      if (requestId !== bookRequest.current || selectedSource.current !== source) return;
      setBooks((previous) => more ? [...previous, ...list] : list); setPage(next);
    } catch (error) { if (requestId === bookRequest.current && selectedSource.current === source) throw error; }
  };
  useEffect(() => { if (!source) return; const refresh = () => { void loadSourceUi(source).catch(() => {}); }; window.addEventListener('reader:source-explore-refresh', refresh); return () => window.removeEventListener('reader:source-explore-refresh', refresh); }, [source, loadSourceUi]);
  return <><PageTitle eyebrow="EXPLORE THE LIBRARY" title="发现书海" description="循着分类与榜单，发现意料之外的好故事。" /><div className="explore-layout"><Panel><h3>可发现的书源</h3>{sources.filter((item) => item.enabled !== false && item.enabledExplore !== false && (item.exploreUrl || item.mainJs)).map((item) => <ActionButton key={item.bookSourceUrl} fullWidth variant={source?.bookSourceUrl === item.bookSourceUrl ? 'default' : 'ghost'} className="explore-source" onClick={() => selectSource(item)}>{item.bookSourceName}</ActionButton>)}{!sources.length && <EmptyState title="尚无书源" action={<ActionButton onClick={() => navigate('/sources')}>添加书源</ActionButton>} />}</Panel><div>{source ? <><Panel><h2>{source.bookSourceName}</h2>{sourceUi && <ExploreControls key={source.bookSourceUrl} source={source} ui={sourceUi} onChange={(value) => { if (selectedSource.current === source) setSourceUi(value); }} onExplore={(url) => run(() => explore(url)).then(() => undefined)} />}<Toolbar>{(sourceUi ? [] : categories).map((item) => <BadgeChip key={item.url} label={item.title} onClick={() => void run(() => explore(item.url))} color={active === item.url ? 'primary' : 'default'} />)}</Toolbar><Toolbar><Field size="sm" fullWidth label="发现地址 / 分类参数" value={custom} onChange={(event) => setCustom(event.target.value)} /><ActionButton disabled={busy} onClick={() => void run(() => explore(custom))}>打开</ActionButton></Toolbar></Panel>{busy && <Spinner size={24} />}{books.length ? <><BookGrid books={books} onDetails={setDetails} /><div className="load-more"><ActionButton onClick={() => void run(() => explore(active, true))} disabled={busy}>下一页</ActionButton></div></> : <EmptyState title="选择分类开始探索" />}</> : <EmptyState title="从左侧选择一个书源" />}</div></div>{details && <BookDetails book={details} onClose={() => setDetails(undefined)} />}</>;
}
