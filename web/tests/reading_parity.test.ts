import { describe, expect, test } from 'vitest';
import { matchingReplaceRules } from '../src/lib/reading';
import { visibleCharacter } from '../src/lib/readingPosition';
describe('single-book purification switch', () => {
  const rules = [{ id: 1, name: 'test', pattern: '广告', replacement: '', isEnabled: true, isRegex: false, scopeTitle: true }];
  test('a disabled book leaves both chapter titles and content unchanged', () => {
    const book = { bookUrl: 'book', name: '书', useReplaceRule: false };
    expect(matchingReplaceRules(rules, book)).toEqual([]);
    expect(matchingReplaceRules(rules, book, 'title')).toEqual([]);
  });
  test('legacy books without a switch retain enabled rules', () => {
    expect(matchingReplaceRules(rules, { bookUrl: 'book', name: '书' })).toHaveLength(1);
  });
});
test('detached chapter documents do not produce or overwrite a reading offset', () => {
  const detached = document.createElement('div'); detached.textContent = '原有进度不可清零';
  expect(visibleCharacter(detached)).toBe(0);
  const unloadedFrame = document.implementation.createHTMLDocument('unloaded frame');
  unloadedFrame.body.textContent = '正文'; expect(visibleCharacter(unloadedFrame.body)).toBe(0);
});
