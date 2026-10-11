/** Character offsets use the rendered text nodes, including paragraph separators. */
export function textRange(root: HTMLElement, position: number, length = 1): Range {
  const document = root.ownerDocument; const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = []; let node: Node | null; while ((node = walker.nextNode())) if (!node.parentElement?.closest('script,style,[aria-hidden=true]')) nodes.push(node as Text);
  const total = nodes.reduce((sum, item) => sum + item.length, 0); const from = Math.max(0, Math.min(total, Math.floor(position))); const to = Math.min(total, from + length); const range = document.createRange();
  const locate = (offset: number): [Node, number] => { for (const item of nodes) { if (offset <= item.length) return [item, offset]; offset -= item.length; } return [root, root.childNodes.length]; };
  range.setStart(...locate(from)); range.setEnd(...locate(to)); return range;
}
export function visibleCharacter(root: HTMLElement, viewport?: HTMLElement | null): number {
  const window = root.ownerDocument.defaultView; if (!window || !root.isConnected) return 0;
  const bounds = viewport?.getBoundingClientRect(); const top = Math.max(bounds?.top || 75, 75); const left = bounds?.left || 0; const right = bounds?.right || window.innerWidth;
  const length = root.textContent?.length || 0;
  // Binary search on text geometry also works for CSS columns because columns are ordered left to right.
  let low = 0; let high = length;
  while (low < high) { const middle = Math.floor((low + high) / 2); const rect = textRange(root, middle).getBoundingClientRect(); if (rect.right <= left || (rect.left < right && rect.bottom < top)) low = middle + 1; else high = middle; }
  return low;
}
