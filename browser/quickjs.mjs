import { Worker } from 'node:worker_threads';

export const QUICKJS_MAX_INPUT_CHARS = 384000;

/** A bounded worker for each evaluation keeps the HTTP service responsive. */
export function createQuickJsSandbox({ timeout = 15000, maxWorkers = 4 } = {}) {
  const workers = new Set();
  let closed = false;
  return {
    get active() { return workers.size; },
    async evaluate(script, { signal } = {}) {
      const failure = (error) => ({ success: false, error });
      if (typeof script !== 'string') return failure('INPUT_READ_FAILED');
      if (script.length > QUICKJS_MAX_INPUT_CHARS || Buffer.byteLength(script) > QUICKJS_MAX_INPUT_CHARS * 4) return failure('INPUT_TOO_LARGE');
      if (closed || signal?.aborted) return failure('PROCESS_DIED');
      if (workers.size >= maxWorkers) return failure('BUSY');
      const worker = new Worker(new URL('./quickjs-worker.mjs', import.meta.url), {
        workerData: { script, deadline: Date.now() + timeout },
        resourceLimits: { maxOldGenerationSizeMb: 128, maxYoungGenerationSizeMb: 16, stackSizeMb: 2 },
      });
      workers.add(worker);
      try {
        return await new Promise((resolve) => {
          let done = false;
          const finish = (result) => {
            if (done) return;
            done = true; clearTimeout(timer); signal?.removeEventListener('abort', cancel); resolve(result);
          };
          const cancel = () => finish(failure('PROCESS_DIED'));
          const timer = setTimeout(() => finish(failure('EVALUATION_TIMEOUT')), timeout);
          signal?.addEventListener('abort', cancel, { once: true });
          worker.once('message', finish);
          worker.once('error', () => finish(failure('EVALUATION_FAILED')));
          worker.once('exit', () => finish(failure('PROCESS_DIED')));
          if (signal?.aborted) cancel();
        });
      } finally { await worker.terminate(); workers.delete(worker); }
    },
    async close() { closed = true; await Promise.all([...workers].map((worker) => worker.terminate())); },
  };
}
