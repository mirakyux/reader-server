import { useEffect, useState } from 'react';
import { matchingReplaceRules } from './reading';
import type { Book, ReplaceRule } from '../types';
import { notify } from '../components/Common';
export function useReplacedText(text: string, rules: ReplaceRule[], book: Book | undefined, target: 'content' | 'title' = 'content') {
  const [result, setResult] = useState({ input: '', text: '' });
  useEffect(() => {
    let active = true; let worker: Worker | undefined; let cancel: (() => void) | undefined;
    const run = async () => { let current = text;
      for (const rule of book ? matchingReplaceRules(rules, book, target) : []) {
        if (!active) return;
        if (!rule.isRegex) { if (rule.pattern) current = current.split(rule.pattern).join(rule.replacement); continue; }
        if (!worker) worker = new Worker(new URL('./replaceWorker.ts', import.meta.url), { type: 'module' });
        current = await new Promise<string>((resolve) => { const fallback = current; const timer = setTimeout(() => { worker?.terminate(); worker = undefined; if (active) notify(`替换规则「${rule.name}」超时，已跳过`, 'warning'); resolve(fallback); }, Math.max(1, Math.min(30000, rule.timeoutMillisecond || 3000))); cancel = () => { clearTimeout(timer); resolve(fallback); }; worker!.onmessage = (event) => { clearTimeout(timer); resolve(String(event.data.text)); }; worker!.onerror = () => { clearTimeout(timer); worker?.terminate(); worker = undefined; resolve(fallback); }; worker!.postMessage({ text: current, rule }); });
      }
      if (active) setResult({ input: text, text: current }); worker?.terminate();
    };
    void run(); return () => { active = false; worker?.terminate(); cancel?.(); };
  }, [text, rules, book, target]);
  return result.input === text ? result.text : text;
}
