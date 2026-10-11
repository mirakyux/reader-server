import { useCallback, useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
export function useReadingPages(viewport: RefObject<HTMLElement | null>, enabled: boolean, revision: unknown) {
  const [layout, setLayout] = useState({ page: 0, count: 1, width: 1 }); const current = useRef(layout);
  useEffect(() => { current.current = layout; }, [layout]);
  useEffect(() => {
    const element = viewport.current; if (!enabled || !element) return;
    const measure = () => { const content = element.querySelector<HTMLElement>('.reader-pages'); if (!content) return; const width = content.clientWidth + 32; const count = Math.max(1, Math.round((content.scrollWidth + 32) / width)); setLayout((old) => ({ page: Math.min(old.page, count - 1), count, width })); };
    const resize = new ResizeObserver(measure); resize.observe(element); element.querySelectorAll('img').forEach((image) => image.addEventListener('load', measure)); measure(); void document.fonts?.ready.then(measure);
    return () => { resize.disconnect(); element.querySelectorAll('img').forEach((image) => image.removeEventListener('load', measure)); };
  }, [viewport, enabled, revision]);
  const turn = useCallback((direction: number): boolean => { const old = current.current; const page = old.page + direction; if (page < 0 || page >= old.count) return false; setLayout({ ...old, page }); return true; }, []);
  const reset = useCallback(() => setLayout((old) => ({ ...old, page: 0 })), []);
  const locate = useCallback((rect: DOMRect, left: number) => { const old = current.current; const page = Math.max(0, Math.min(old.count - 1, Math.floor((rect.left - left + old.page * old.width) / old.width))); setLayout({ ...old, page }); }, []);
  return { ...layout, turn, reset, locate };
}
