import { describe, expect, test } from 'vitest';
import { textRange } from '../src/lib/readingPosition';
import { convertChinese, convertChineseHtml } from '../src/lib/chinese';
import { readShelfSettings, sortShelf } from '../src/lib/shelf';
import { themeProfiles } from '../src/lib/themePackages';
describe('FU reading behavior', () => {
  test('character ranges cross formatting and paragraph separators without losing the position', () => { const root = document.createElement('div'); root.innerHTML = '<p>甲<b>乙丙</b></p>\n<p>丁戊</p>'; expect(textRange(root, 2, 3).toString()).toBe('丙\n丁'); expect(textRange(root, -10, 2).toString()).toBe('甲乙'); expect(textRange(root, 100).toString()).toBe(''); });
  test('FU phrase dictionaries distinguish hair, progress and queen and preserve URLs', () => { expect(convertChinese('头发发展，皇后在后台干杯。', 'traditional')).toBe('頭髮發展，皇后在後臺乾杯。'); expect(convertChinese('頭髮發展，皇后在後臺乾杯。', 'simple')).toBe('头发发展，皇后在后台干杯。'); expect(convertChineseHtml('<p>头发</p><img src="/头发.jpg">', 'traditional')).toBe('<p>頭髮</p><img src="/头发.jpg">'); });
  test('FU theme imports retain separate day/night colors and static backgrounds', () => { const parsed = themeProfiles({ format: 'reading-ng-theme', version: 1, theme: { name: '墨色', colors: { mode: 'MANUAL', manualLight: { background: -1, primaryText: -16777216 }, manualDark: { background: -16777216, primaryText: -1 } } }, lightBackgroundAsset: 'assets/day.png' }, new Map([['assets/day.png', '/assets/day.png']])); expect(parsed.profiles[0].settings).toMatchObject({ customBackgroundColor: '#ffffff', customTextColor: '#000000', backgroundImage: '/assets/day.png' }); expect(parsed.profiles[1].settings).toMatchObject({ theme: 'night', customBackgroundColor: '#000000', customTextColor: '#ffffff' }); });
  test('shelf sorting does not mutate API data and uses stable names for equal progress', () => { const books = [{ bookUrl: 'a', name: '甲', durChapterTime: 1 }, { bookUrl: 'b', name: '乙', durChapterTime: 3 }]; expect(sortShelf(books, readShelfSettings()).map((item) => item.bookUrl)).toEqual(['b', 'a']); expect(books[0].bookUrl).toBe('a'); });
});
