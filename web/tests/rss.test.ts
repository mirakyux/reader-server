import { describe, expect, it } from 'vitest';
import { mergeRssArticles, rssPageRequest } from '../src/lib/rss';

describe('RSS source pagination', () => {
  it('uses the actual next URL while retaining page templates', () => {
    expect(rssPageRequest('https://source/cursor/next', 'https://source/start', 2)).toEqual({ sortUrl: 'https://source/cursor/next', page: 2 });
    expect(rssPageRequest('https://source/{{page}}', 'https://source/{{page}}', 3).sortUrl).toBe('https://source/{{page}}');
  });
  it('merges repeated pages without collapsing the same link from separate sources', () => {
    const first = { origin: 'a', link: 'article', title: 'old' };
    expect(mergeRssArticles([first], [{ ...first, title: 'new' }, { ...first, origin: 'b' }])).toHaveLength(2);
    expect(mergeRssArticles([first], [{ ...first, title: 'new' }])[0].title).toBe('new');
  });
});
