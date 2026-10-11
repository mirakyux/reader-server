import { afterEach, expect, it, vi } from 'vitest';
import { copyText } from '../src/lib/clipboard';

afterEach(() => { vi.unstubAllGlobals(); document.body.replaceChildren(); window.getSelection()?.removeAllRanges(); });

it('uses the secure Clipboard API when available', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined); const execCommand = vi.fn();
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand });
  await copyText('https://reader.test/book');
  expect(writeText).toHaveBeenCalledWith('https://reader.test/book');
  expect(execCommand).not.toHaveBeenCalled();
});

it.each(['missing', 'denied'] as const)('falls back on HTTP or rejected permission (%s) and restores reading selection and focus', async (condition) => {
  const button = document.createElement('button'); const paragraph = document.createElement('p');
  paragraph.textContent = '保留原来的选文'; document.body.append(button, paragraph); button.focus();
  const selection = window.getSelection()!;
  selection.setBaseAndExtent(paragraph.firstChild!, 5, paragraph.firstChild!, 1);
  vi.stubGlobal('navigator', condition === 'missing' ? {} : { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('Denied')) } });
  const execCommand = vi.fn(() => {
    const field = document.activeElement as HTMLTextAreaElement;
    expect(field.value).toBe('https://reader.test/book');
    expect(field.selectionStart).toBe(0); expect(field.selectionEnd).toBe(field.value.length);
    selection.removeAllRanges();
    return true;
  });
  Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand });
  await copyText('https://reader.test/book');
  expect(execCommand).toHaveBeenCalledWith('copy');
  expect(document.activeElement).toBe(button);
  expect(selection.toString()).toBe('留原来的');
  expect(selection.anchorOffset).toBe(5); expect(selection.focusOffset).toBe(1);
  expect(document.querySelector('textarea')).toBeNull();
});

it('restores an editable input selection after native copying fails', async () => {
  const field = document.createElement('input'); field.value = '正在编辑的文字'; document.body.append(field); field.focus(); field.setSelectionRange(1, 4, 'backward');
  vi.stubGlobal('navigator', {});
  Object.defineProperty(document, 'execCommand', { configurable: true, value: vi.fn(() => false) });
  await expect(copyText('链接')).rejects.toThrow('复制失败');
  expect(document.activeElement).toBe(field);
  expect(field.value).toBe('正在编辑的文字');
  expect([field.selectionStart, field.selectionEnd, field.selectionDirection]).toEqual([1, 4, 'backward']);
  expect(document.querySelector('textarea')).toBeNull();
});

it('restores focus and removes the temporary field when native copying throws', async () => {
  const button = document.createElement('button'); document.body.append(button); button.focus();
  vi.stubGlobal('navigator', {});
  Object.defineProperty(document, 'execCommand', { configurable: true, value: vi.fn(() => { throw new Error('Native copy rejected'); }) });
  await expect(copyText('链接')).rejects.toThrow('Native copy rejected');
  expect(document.activeElement).toBe(button); expect(document.querySelector('textarea')).toBeNull();
});
