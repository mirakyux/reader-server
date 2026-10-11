import { createContext, useContext } from 'react';
import type { Book, BookSource, BookGroup, UserInfo } from './types';
export interface LibraryContextValue { books: Book[]; sources: BookSource[]; groups: BookGroup[]; user?: UserInfo; loading: boolean; error: string; reload: (refresh?: boolean) => Promise<void> }
export const LibraryContext = createContext<LibraryContextValue>({ books: [], sources: [], groups: [], loading: false, error: '', reload: async () => {} });
export function useLibrary() { return useContext(LibraryContext); }
