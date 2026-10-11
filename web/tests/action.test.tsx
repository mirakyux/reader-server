import { act, renderHook } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { useAction } from '../src/components/Common';

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

it('keeps busy until every overlapping action finishes and retains a stable run function', async () => {
  const { result } = renderHook(useAction);
  const run = result.current.run;
  let finishFirst!: () => void;
  let finishSecond!: () => void;
  let first!: Promise<void | undefined>;
  let second!: Promise<void | undefined>;
  act(() => {
    first = run(() => new Promise<void>((resolve) => { finishFirst = resolve; }));
    second = run(() => new Promise<void>((resolve) => { finishSecond = resolve; }));
  });
  expect(result.current.busy).toBe(true);
  await act(async () => { finishFirst(); await first; });
  expect(result.current.busy).toBe(true);
  expect(result.current.run).toBe(run);
  await act(async () => { finishSecond(); await second; });
  expect(result.current.busy).toBe(false);
});

it('releases only its own busy operation after a failure', async () => {
  const { result } = renderHook(useAction);
  let finish!: () => void;
  let pending!: Promise<void | undefined>;
  await act(async () => {
    pending = result.current.run(() => new Promise<void>((resolve) => { finish = resolve; }));
    await result.current.run(() => Promise.reject(new Error('fixture failure')));
  });
  expect(result.current.busy).toBe(true);
  await act(async () => { finish(); await pending; });
  expect(result.current.busy).toBe(false);
});
