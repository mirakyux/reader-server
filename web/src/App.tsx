import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Bookmark, ChevronDown, FileText, FolderOpen, Radio, Settings2, SlidersHorizontal, Code2 } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { Toaster } from 'sonner';
import { Spinner } from './components/Controls';
import { ErrorAlert } from './components/Common';
import { LibraryContext } from './context';
import { api, getSession, sessionScope, setSession } from './lib/api';
import { SourceInteractionsHost } from './components/SourceFeatures';
import { errorText } from './lib/reading';
import { navigate } from './lib/navigation';
import { activateOfflineScope, cacheLibrary, cachedBooks, cachedLibrary } from './lib/cache';
import type { Book, BookGroup, BookSource, UserInfo } from './types';
const Shelf = lazy(() => import('./components/Library').then((module) => ({ default: module.Shelf })));
const SearchPage = lazy(() => import('./components/Library').then((module) => ({ default: module.Search })));
const Explore = lazy(() => import('./components/Library').then((module) => ({ default: module.Explore })));
const Sources = lazy(() => import('./components/Sources').then((module) => ({ default: module.Sources })));
const Reader = lazy(() => import('./components/Reader').then((module) => ({ default: module.Reader })));
const Bookmarks = lazy(() => import('./components/Journal').then((module) => ({ default: module.Bookmarks })));
const Dictionary = lazy(() => import('./components/ReadingTools').then((module) => ({ default: module.Dictionary })));
const ReplaceRules = lazy(() => import('./components/Journal').then((module) => ({ default: module.ReplaceRules })));
const Rss = lazy(() => import('./components/Journal').then((module) => ({ default: module.Rss })));
const FileManager = lazy(() => import('./components/Settings').then((module) => ({ default: module.FileManager })));
const Settings = lazy(() => import('./components/Settings').then((module) => ({ default: module.Settings })));
const Login = lazy(() => import('./components/Settings').then((module) => ({ default: module.Login })));
const toolGroups = [
  { label: '阅读工具', items: [{ path: '/bookmarks', label: '书签与摘录', icon: Bookmark }, { path: '/dict', label: '词典', icon: FileText }, { path: '/rss', label: '订阅', icon: Radio }] },
  { label: '管理与设置', items: [{ path: '/sources', label: '书源工作台', icon: Code2 }, { path: '/rules', label: '替换规则', icon: SlidersHorizontal }, { path: '/local', label: '本地书仓', icon: FolderOpen }, { path: '/webdav', label: 'WebDAV 与备份', icon: FileText }, { path: '/settings', label: '设置', icon: Settings2 }] },
];
function hashLocation() { const [path, search] = (window.location.hash.slice(1) || '/').split('?'); return { path, params: new URLSearchParams(search) }; }
export default function App() {
  const [route, setRoute] = useState(hashLocation); const [books, setBooks] = useState<Book[]>([]); const [sources, setSources] = useState<BookSource[]>([]); const [groups, setGroups] = useState<BookGroup[]>([]); const [user, setUser] = useState<UserInfo>(); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [loginOpen, setLoginOpen] = useState(false); const [menuOpen, setMenuOpen] = useState(false); const revision = useRef(0);
  const [hydrated, setHydrated] = useState(false); const [scope, setScope] = useState(sessionScope); const displayedScope = useRef(sessionScope());
  const reload = useCallback(async (refresh = false) => {
    const version = ++revision.current; setLoading(true); setError('');
    const initialSession = JSON.stringify(getSession());
    if (displayedScope.current !== sessionScope()) { setHydrated(false); setBooks([]); setSources([]); setGroups([]); setUser(undefined); }
    const info = await Promise.allSettled([api.get<UserInfo>('getUserInfo')]).then(([result]) => result);
    if (version !== revision.current || initialSession !== JSON.stringify(getSession())) return;
    if (info.status === 'fulfilled') {
      setSession({ authenticatedUser: info.value.userInfo?.username || '', ...(!info.value.userInfo && info.value.secure ? { accessToken: '' } : {}) });
      setUser(info.value);
    }
    const activeScope = sessionScope();
    if (displayedScope.current !== activeScope) { setBooks([]); setSources([]); setGroups([]); setHydrated(false); }
    void activateOfflineScope();
    const requests = await Promise.allSettled([api.get<Book[]>('getBookshelf', { refresh: refresh ? 1 : 0 }), api.get<BookSource[]>('getBookSources'), api.get<BookGroup[]>('getBookGroups')]);
    if (version !== revision.current || activeScope !== sessionScope()) return;
    const [shelf, bookSources, bookGroups] = requests;
    const fallback = shelf.status === 'rejected' ? await cachedLibrary().catch(() => undefined) : undefined;
    const nextBooks = shelf.status === 'fulfilled' ? shelf.value || [] : fallback?.books || await cachedBooks().catch(() => []);
    const nextSources = bookSources.status === 'fulfilled' ? bookSources.value || [] : (fallback?.sources || []) as BookSource[];
    const nextGroups = bookGroups.status === 'fulfilled' ? bookGroups.value || [] : (fallback?.groups || []) as BookGroup[];
    if (version !== revision.current || activeScope !== sessionScope()) return;
    displayedScope.current = activeScope; setScope(activeScope); setHydrated(true);
    setBooks(nextBooks); setSources(nextSources); setGroups(nextGroups);
    if (shelf.status === 'fulfilled') void cacheLibrary({ books: nextBooks, sources: nextSources, groups: nextGroups }).catch(() => {});
    const failure = [...requests, info].find((result) => result.status === 'rejected'); const nextError = failure?.status === 'rejected' ? errorText(failure.reason) : ''; setError(nextError); setLoading(false);
  }, []);
  useEffect(() => { void reload(); const onHash = () => { setRoute(hashLocation()); window.scrollTo({ top: 0 }); setMenuOpen(false); }; const onLogin = () => setLoginOpen(true); window.addEventListener('hashchange', onHash); window.addEventListener('reader:login', onLogin); return () => { window.removeEventListener('hashchange', onHash); window.removeEventListener('reader:login', onLogin); }; }, [reload]);
  const context = useMemo(() => ({ books, sources, groups, user, loading, error, reload }), [books, sources, groups, user, loading, error, reload]);
  let page: React.ReactNode;
  switch (route.path) { case '/reader': page = <Reader key={route.params.get('url')} params={route.params} />; break; case '/search': page = <SearchPage />; break; case '/explore': page = <Explore />; break; case '/sources': page = <Sources />; break; case '/dict': page = <Dictionary />; break; case '/bookmarks': page = <Bookmarks />; break; case '/rules': page = <ReplaceRules />; break; case '/rss': page = <Rss />; break; case '/local': page = <FileManager />; break; case '/webdav': page = <FileManager webdav />; break; case '/settings': page = <Settings />; break; default: page = <Shelf />; }
  const findingBooks = route.path === '/search' || route.path === '/explore';
  const inTools = toolGroups.some((group) => group.items.some((item) => item.path === route.path));
  return <LibraryContext value={context}>
    <Toaster position="top-right" style={{ zIndex: 55 }} offset={{ top: 80, right: 24 }} mobileOffset={{ top: 72, left: 16, right: 16 }} richColors closeButton />
    <SourceInteractionsHost />
    {route.path === '/reader' ? <Suspense key={scope} fallback={<div className="loading-space"><Spinner /></div>}>{hydrated ? page : <div className="loading-space"><Spinner /></div>}</Suspense> : <div className="app-shell">
      <header className="app-header"><div className="app-header-inner">
        <a href="#/" className="brand" aria-label="Reader 首页"><span className="brand-symbol" aria-hidden>阅</span><strong>Reader</strong></a>
        <nav className="primary-nav" aria-label="主导航">
          <a href="#/" className={`primary-link ${route.path === '/' ? 'active' : ''}`} aria-current={route.path === '/' ? 'page' : undefined}>书架</a>
          <a href="#/search" className={`primary-link ${findingBooks ? 'active' : ''}`} aria-current={findingBooks ? 'page' : undefined}>找书</a>
          <DropdownMenu.Root modal={false} open={menuOpen} onOpenChange={setMenuOpen}>
            <DropdownMenu.Trigger asChild><button type="button" className={`primary-link ${inTools ? 'active' : ''}`}>更多<ChevronDown size={14} /></button></DropdownMenu.Trigger>
            <DropdownMenu.Portal><DropdownMenu.Content className="app-menu" align="end" sideOffset={8} collisionPadding={12}>
              {toolGroups.map((group, index) => <DropdownMenu.Group key={group.label}>
                {index > 0 && <DropdownMenu.Separator className="app-menu-separator" />}
                <DropdownMenu.Label className="app-menu-label">{group.label}</DropdownMenu.Label>
                {group.items.map((item) => <DropdownMenu.Item key={item.path} asChild><a href={`#${item.path}`} className="app-menu-item" aria-current={route.path === item.path ? 'page' : undefined}><item.icon size={17} />{item.label}</a></DropdownMenu.Item>)}
              </DropdownMenu.Group>)}
            </DropdownMenu.Content></DropdownMenu.Portal>
          </DropdownMenu.Root>
        </nav>
        <button className="profile-avatar" onClick={() => user?.userInfo ? navigate('/settings') : setLoginOpen(true)} aria-label="账户设置">{(user?.userInfo?.username || '读').slice(0, 1).toUpperCase()}</button>
      </div></header>
      <main className="page-content">
        {findingBooks && <nav className="find-navigation" aria-label="找书方式"><a href="#/search" aria-current={route.path === '/search' ? 'page' : undefined}>搜索</a><a href="#/explore" aria-current={route.path === '/explore' ? 'page' : undefined}>发现</a></nav>}
        {error && route.path !== '/' && <ErrorAlert error={error} retry={() => void reload()} />}
        <Suspense key={scope} fallback={<div className="loading-space"><Spinner /></div>}>{hydrated ? page : <div className="loading-space"><Spinner /></div>}</Suspense>
      </main>
      <footer className="app-footer"><span className={`connection-dot ${error ? 'disconnected' : ''}`} /><span role="status">{loading ? '正在连接…' : error ? '连接需要检查' : '阅读空间已连接'}</span></footer>
    </div>}
    {loginOpen && <Suspense fallback={null}><Login onClose={() => setLoginOpen(false)} /></Suspense>}
  </LibraryContext>;
}
