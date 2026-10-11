import { beforeEach, expect, test } from 'vitest';
import { activeBook, rememberActiveBook } from '../src/lib/navigation';
import { setSession } from '../src/lib/api';
beforeEach(() => { setSession({ apiBase: '/reader3', userNS: '', accessToken: '', secureKey: '' }); });
test('active books are isolated by account and server; only anonymous default reads the old key', () => { const book = { bookUrl: 'book', name: '书' }; localStorage.setItem('reader.activeBook', JSON.stringify(book)); setSession({ userNS: 'alice' }); expect(activeBook('book')).toBeUndefined(); rememberActiveBook(book); setSession({ userNS: 'bob' }); expect(activeBook('book')).toBeUndefined(); setSession({ userNS: 'alice', apiBase: 'https://other.test/reader3' }); expect(activeBook('book')).toBeUndefined(); setSession({ userNS: '', apiBase: '/reader3' }); expect(activeBook('book')).toEqual(book); });
