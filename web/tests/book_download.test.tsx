import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { toast } from 'sonner';
import { BookDownload } from '../src/components/BookDownload';
import { api, openStream } from '../src/lib/api';
import { exportBook } from '../src/lib/download';

vi.mock('../src/lib/api', async (original) => ({ ...await original<typeof import('../src/lib/api')>(), openStream: vi.fn() }));
vi.mock('../src/lib/download', async (original) => ({ ...await original<typeof import('../src/lib/download')>(), exportBook: vi.fn() }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() } }));

const book = { bookUrl: 'https://book.test/one', name: '导出测试书' };
async function epubOptions() {
  render(<BookDownload book={book} onClose={vi.fn()} />);
  expect(screen.queryByText('导出样式', { exact: true })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('导出格式'), { target: { value: 'epub' } });
  await waitFor(() => expect(api.get).toHaveBeenCalledWith('getEpubTemplate'));
  await waitFor(() => expect(screen.getByRole('button', { name: '缓存全部并导出' })).toBeEnabled());
  expect(screen.getByText('导出样式', { exact: true }).closest('details')).not.toHaveAttribute('open');
  fireEvent.click(screen.getByText('导出样式', { exact: true }));
}

describe('EPUB export style controls', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(api, 'get').mockResolvedValue({ files: [] });
    vi.spyOn(api, 'post').mockResolvedValue({ files: ['Text/chapter.html'] });
    vi.mocked(exportBook).mockResolvedValue(undefined);
    vi.mocked(openStream).mockReturnValue({ close: vi.fn() } as unknown as EventSource);
  });

  it('keeps style controls collapsed and exports selected options after complete caching', async () => {
    await epubOptions();
    expect(screen.getByText('当前使用内置模板')).toBeVisible();
    expect(screen.getByRole('switch', { name: '使用自定义模板' })).toBeDisabled();
    const file = new File(['template zip'], 'reader-template.zip', { type: 'application/zip' });
    fireEvent.change(screen.getByLabelText('上传模板 ZIP'), { target: { files: [file] } });
    await screen.findByText('已保存自定义模板（1 个文件）');
    expect(api.post).toHaveBeenCalledWith('saveEpubTemplate', expect.any(FormData));
    expect((vi.mocked(api.post).mock.calls[0][1] as FormData).get('file')).toBe(file);
    fireEvent.click(screen.getByRole('switch', { name: '使用自定义模板' }));
    fireEvent.click(screen.getByRole('switch', { name: '过滤交互图片' }));
    fireEvent.click(screen.getByRole('button', { name: '缓存全部并导出' }));
    expect(screen.getByLabelText('上传模板 ZIP')).toBeDisabled();
    expect(screen.getByRole('button', { name: '恢复内置模板' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: '过滤交互图片' })).toBeDisabled();
    const callbacks = vi.mocked(openStream).mock.calls[0];
    await act(async () => {
      callbacks[2]({ cachedCount: 2, successCount: 2, failedCount: 0, totalCount: 2, complete: true });
      callbacks[3]();
    });
    expect(exportBook).toHaveBeenCalledWith(book, true, false, { filterInteractiveImages: false, useTemplate: false });
    expect(screen.getByText('完整书籍已下载。')).toBeVisible();
  });

  it('restores the built-in template only after the server confirms deletion', async () => {
    vi.mocked(api.get).mockResolvedValue({ files: ['Text/chapter.html', 'Styles/main.css'] });
    vi.mocked(api.post).mockResolvedValue({ files: [] });
    await epubOptions();
    expect(screen.getByText('已保存自定义模板（2 个文件）')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '恢复内置模板' }));
    await screen.findByText('当前使用内置模板');
    expect(api.post).toHaveBeenCalledWith('deleteEpubTemplate', {});
    expect(screen.getByRole('switch', { name: '使用自定义模板' })).toBeDisabled();
    expect(toast.success).toHaveBeenCalledWith('已恢复内置模板');
  });

  it('disables conflicting operations during template upload and reports upload failures', async () => {
    let reject!: (reason: Error) => void;
    vi.mocked(api.post).mockReturnValue(new Promise((_, failure) => { reject = failure; }));
    await epubOptions();
    fireEvent.change(screen.getByLabelText('上传模板 ZIP'), { target: { files: [new File(['bad zip'], 'bad.zip')] } });
    expect(screen.getByRole('button', { name: '缓存全部并导出' })).toBeDisabled();
    expect(screen.getByLabelText('导出格式')).toBeDisabled();
    expect(screen.getByRole('button', { name: '取消缓存' })).toBeDisabled();
    await act(async () => reject(new Error('模板无效')));
    expect(toast.error).toHaveBeenCalledWith('模板无效');
    expect(screen.getByText('当前使用内置模板')).toBeVisible();
    expect(screen.getByRole('button', { name: '缓存全部并导出' })).toBeEnabled();
  });

  it('preserves the saved template when restoration fails', async () => {
    vi.mocked(api.get).mockResolvedValue({ files: ['Text/chapter.html'] });
    vi.mocked(api.post).mockRejectedValue(new Error('无法恢复模板'));
    await epubOptions();
    fireEvent.click(screen.getByRole('button', { name: '恢复内置模板' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('无法恢复模板'));
    expect(screen.getByText('已保存自定义模板（1 个文件）')).toBeVisible();
  });

  it('retries template status without blocking ordinary export after a read failure', async () => {
    vi.mocked(api.get).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValue({ files: ['Text/chapter.html'] });
    await epubOptions();
    expect(screen.getByText('模板状态读取失败')).toBeVisible();
    expect(toast.error).toHaveBeenCalledWith('读取失败');
    fireEvent.click(screen.getByRole('button', { name: '重新读取模板' }));
    await screen.findByText('已保存自定义模板（1 个文件）');
    expect(screen.getByRole('switch', { name: '使用自定义模板' })).toBeEnabled();
  });

  it('rejects non-ZIP templates before uploading', async () => {
    await epubOptions();
    fireEvent.change(screen.getByLabelText('上传模板 ZIP'), { target: { files: [new File(['text'], 'template.txt')] } });
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('请选择模板 ZIP 文件'));
    expect(api.post).not.toHaveBeenCalled();
  });
});
