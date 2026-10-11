import type { ReplaceRule } from '../types';
self.onmessage = (event: MessageEvent<{ text: string; rule: ReplaceRule }>) => { const { text, rule } = event.data; try { self.postMessage({ text: text.replace(new RegExp(rule.pattern, 'g'), rule.replacement) }); } catch { self.postMessage({ text }); } };
