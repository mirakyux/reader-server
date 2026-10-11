/** Clipboard API requires HTTPS; the native copy command also works on LAN HTTP. */
export async function copyText(text: string): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return;
    }
  } catch { /* A denied Clipboard API permission can still allow a user-initiated copy. */ }

  const active = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const inputSelection = active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement
    ? { start: active.selectionStart, end: active.selectionEnd, direction: active.selectionDirection } : undefined;
  const selection = window.getSelection();
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : [];
  const direction = selection && selection.anchorNode && selection.focusNode
    ? { anchor: selection.anchorNode, anchorOffset: selection.anchorOffset, focus: selection.focusNode, focusOffset: selection.focusOffset } : undefined;
  const field = document.createElement('textarea');
  field.value = text;
  field.readOnly = true;
  field.tabIndex = -1;
  field.setAttribute('aria-hidden', 'true');
  field.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;font-size:16px';
  document.body.appendChild(field);
  try {
    field.focus({ preventScroll: true });
    field.select();
    if (typeof document.execCommand !== 'function' || !document.execCommand('copy')) throw new Error('复制失败，请手动复制链接');
  } finally {
    field.remove();
    if (active?.isConnected) active.focus({ preventScroll: true });
    if (inputSelection && (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement) && inputSelection.start !== null && inputSelection.end !== null) {
      active.setSelectionRange(inputSelection.start, inputSelection.end, inputSelection.direction || undefined);
    }
    if (selection) {
      selection.removeAllRanges();
      if (ranges.length === 1 && direction && typeof selection.setBaseAndExtent === 'function') {
        selection.setBaseAndExtent(direction.anchor, direction.anchorOffset, direction.focus, direction.focusOffset);
      } else ranges.forEach((range) => selection.addRange(range));
    }
  }
}
