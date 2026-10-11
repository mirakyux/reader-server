import { parentPort, workerData } from 'node:worker_threads';
import variant from '@jitl/quickjs-wasmfile-release-sync';
import { newQuickJSWASMModuleFromVariant } from 'quickjs-emscripten-core';
import createNativeModule from './quickjs-runtime/quickjs.mjs';

// The Bellard engine revision matches FU quickjs-kt 1.0.4 (2025-09-13+f1139494).
const fail = (error) => ({ success: false, error });
const promiseText = (type) => `Promise { <state>: "${type}" }`;

async function evaluate() {
  let native;
  const engine = await newQuickJSWASMModuleFromVariant({
    ...variant,
    importModuleLoader: async () => async (options) => {
      native = await createNativeModule(options);
      return native;
    },
  });
  const runtime = engine.newRuntime();
  let context;
  let evaluated;
  let completed;
  let value;
  let stringify;
  const expired = () => Date.now() >= workerData.deadline;
  try {
    runtime.setMemoryLimit(64 * 1024 * 1024);
    runtime.setMaxStackSize(512 * 1024);
    runtime.setInterruptHandler(expired);
    // No module loader and no host bindings: scripts cannot reach Node or the OS.
    context = runtime.newContext();
    const json = context.getProp(context.global, 'JSON');
    try { stringify = context.getProp(json, 'stringify'); }
    finally { json.dispose(); }
    // FU evaluates global code with JS_EVAL_FLAG_ASYNC, preserving completion values
    // and allowing top-level await without wrapping or coercing the user's script.
    const result = context.evalCode(workerData.script, 'sandbox.js', 128);
    if (result.error) { result.error.dispose(); return fail(expired() ? 'EVALUATION_TIMEOUT' : 'EVALUATION_FAILED'); }
    evaluated = result.value;
    while (runtime.hasPendingJob()) {
      if (expired()) return fail('EVALUATION_TIMEOUT');
      const jobs = runtime.executePendingJobs(1);
      if (jobs.error) { jobs.error.dispose(); return fail(expired() ? 'EVALUATION_TIMEOUT' : 'EVALUATION_FAILED'); }
    }
    // Use the engine's own rejection tracker, including inaccessible Promises
    // created by async functions. A late .catch() removes the rejection.
    if (native._ReaderQuickJsHasUnhandledRejections()) return fail(expired() ? 'EVALUATION_TIMEOUT' : 'EVALUATION_FAILED');
    const state = context.getPromiseState(evaluated);
    if (state.type === 'pending') return { success: true, value: promiseText('pending') };
    if (state.type === 'rejected') { state.error.dispose(); return fail(expired() ? 'EVALUATION_TIMEOUT' : 'EVALUATION_FAILED'); }
    completed = state.value;
    value = context.getProp(completed, 'value');
    let text;
    if (context.typeof(value) === 'string') {
      const length = context.getProp(value, 'length');
      try { if (context.getNumber(length) > 65536) return fail('OUTPUT_TOO_LARGE'); }
      finally { length.dispose(); }
      // getString uses a NUL-terminated UTF-8 pointer. Native JSON serialization
      // preserves embedded NUL and UTF-16 surrogates across the WASM boundary.
      const encoded = context.callFunction(stringify, context.undefined, value);
      if (encoded.error) { encoded.error.dispose(); return fail('EVALUATION_FAILED'); }
      try { text = JSON.parse(context.getString(encoded.value)); }
      finally { encoded.value.dispose(); }
    } else {
      // FU exposes the state text when the completion itself is a Promise. It
      // does not automatically await it, nor stringify other result types.
      const completion = context.getPromiseState(value);
      if (completion.notAPromise) { completion.value.dispose(); return fail('EVALUATION_FAILED'); }
      if (completion.type === 'fulfilled') completion.value.dispose();
      if (completion.type === 'rejected') { completion.error.dispose(); return fail('EVALUATION_FAILED'); }
      text = promiseText(completion.type);
    }
    return text.length > 65536 ? fail('OUTPUT_TOO_LARGE') : { success: true, value: text };
  } catch {
    return fail(expired() ? 'EVALUATION_TIMEOUT' : 'EVALUATION_FAILED');
  } finally {
    runtime.setInterruptHandler(() => false);
    stringify?.dispose(); value?.dispose(); completed?.dispose(); evaluated?.dispose(); context?.dispose(); runtime.dispose();
  }
}

try { parentPort.postMessage(await evaluate()); }
catch { parentPort.postMessage(fail('EVALUATION_FAILED')); }
