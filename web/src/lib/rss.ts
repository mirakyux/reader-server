import { api } from './api';
import { cacheChapter, cachedChapter, cacheScope } from './cache';
import type { RssArticle } from '../types';

export type RssListKind = 'favorites' | 'history' | 'offline';
export interface SavedRssArticle extends RssArticle { origin: string; content?: string; group?: string; starTime?: number; readTime?: number; cachedAt?: number; durPos?: number }
export const rssIdentity = (article: RssArticle) => `${article.origin || ''}\u0000${article.link}`;
const key = (name: string) => `${cacheScope()}@rss:${name}`;
async function cached<T>(name: string): Promise<T | undefined> { const saved = await cachedChapter(key(name)); return saved ? JSON.parse(saved) as T : undefined; }
async function remember(name: string, value: unknown) { await cacheChapter(key(name), JSON.stringify(value)); }
export function mergeRssArticles(previous: RssArticle[], next: RssArticle[]): RssArticle[] { return [...new Map([...previous, ...next].map((article) => [rssIdentity(article), article])).values()]; }
export function rssPageRequest(nextUrl: string | null, baseUrl: string, page: number) { return { sortUrl: page > 1 ? nextUrl || baseUrl : baseUrl, page }; }
export async function loadRssLibrary(kind: RssListKind): Promise<SavedRssArticle[]> {
  try { const rows = await api.get<SavedRssArticle[]>('rss/library', { kind }); await remember(kind, rows); return rows; }
  catch (error) { const rows = await cached<SavedRssArticle[]>(kind); if (rows) return rows; throw error; }
}
export async function rememberRssArticle(article: SavedRssArticle) { await remember(`article:${rssIdentity(article)}`, article); }
export function cachedRssArticle(article: RssArticle): Promise<SavedRssArticle | undefined> { return cached(`article:${rssIdentity(article)}`); }
export async function rememberRssFeed(sourceUrl: string, url: string, rows: RssArticle[], nextUrl: string | null) { await remember(`feed:${sourceUrl}:${url}`, { rows, nextUrl }); }
export function cachedRssFeed(sourceUrl: string, url: string): Promise<{ rows: RssArticle[]; nextUrl: string | null } | undefined> { return cached(`feed:${sourceUrl}:${url}`); }
export async function rssSnapshot<T>(name: string, load: () => Promise<T>): Promise<T> {
  try { const value = await load(); await remember(name, value); return value; }
  catch (error) { const value = await cached<T>(name); if (value !== undefined) return value; throw error; }
}
