import { describe, expect, test } from 'vitest';
import { convertChinese, convertChineseHtml } from '../src/lib/chinese';
describe('legacy Chinese conversion', () => {
  test('converts text while preserving punctuation, emoji and other scripts', () => { expect(convertChinese('阅读图书 ABC 😀。', 'traditional')).toBe('閱讀圖書 ABC 😀。'); expect(convertChinese('閱讀圖書 ABC 😀。', 'simple')).toBe('阅读图书 ABC 😀。'); expect(convertChinese('阅读圖書', 'original')).toBe('阅读圖書'); });
  test('converts HTML text without changing image or download paths', () => { expect(convertChineseHtml('<p>阅读图书</p><img src="/assets/图书.png" alt="图书">', 'traditional')).toBe('<p>閱讀圖書</p><img src="/assets/图书.png" alt="图书">'); });
});
