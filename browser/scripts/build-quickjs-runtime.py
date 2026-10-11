"""Build the FU-compatible QuickJS runtime with native Promise rejection tracking.

Uses an unmodified quickjs-emscripten v0.32.0 checkout and Emscripten 4.0.12.
Only the C binding is extended; the Bellard engine is not modified.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--emcc", type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    source = args.source.resolve()
    work = project.parent / "build/quickjs-build/runtime"
    output = project / "quickjs-runtime"
    work.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if revision != "df4efb9ef2cb25c417ecb57986da462d11b244ed":
        raise RuntimeError("Expected quickjs-emscripten v0.32.0 source revision")
    shutil.copytree(source / "vendor/quickjs", work / "vendor/quickjs", dirs_exist_ok=True)
    shutil.copytree(source / "templates", work / "templates", dirs_exist_ok=True)
    code = (source / "c/interface.c").read_text(encoding="utf-8")
    original = "JSRuntime *QTS_NewRuntime() {\n  return JS_NewRuntime();\n}"
    tracker = '''// Each module is owned by one worker and creates one fresh runtime.
static int reader_unhandled_rejections;
static void reader_track_rejection(JSContext *ctx, JSValueConst promise,
                                  JSValueConst reason, JS_BOOL handled, void *opaque) {
  if (handled) reader_unhandled_rejections--;
  else reader_unhandled_rejections++;
}

int ReaderQuickJsHasUnhandledRejections(void) {
  return reader_unhandled_rejections > 0;
}

JSRuntime *QTS_NewRuntime() {
  JSRuntime *rt = JS_NewRuntime();
  reader_unhandled_rejections = 0;
  if (rt) JS_SetHostPromiseRejectionTracker(rt, reader_track_rejection, NULL);
  return rt;
}'''
    if code.count(original) != 1:
        raise RuntimeError("Upstream binding changed; review the native tracker patch")
    patched = code.replace(original, tracker)
    (work / "c").mkdir(exist_ok=True)
    interface = work / "c/interface.c"
    interface.write_text(patched, encoding="utf-8", newline="\n")
    declarations = re.findall(r"^([\w()* ]+[\s*]+)(QTS_\w+)\((.*?)\) ?\{", code, re.M)
    symbols = ["_" + name for kind, name, _ in declarations
               if "DebugOnly" not in kind and "AsyncifyOnly" not in kind]
    symbols += ["_malloc", "_free", "_ReaderQuickJsHasUnhandledRejections"]
    symbol_file = work / "symbols.json"
    symbol_file.write_text(json.dumps(symbols), encoding="utf-8")
    runtime_methods = work / "runtime-methods.json"
    runtime_methods.write_text((source / "exportedRuntimeMethods.json").read_text(), encoding="utf-8")
    native = work / "vendor/quickjs"
    version = (native / "VERSION").read_text().strip()
    if version != "2025-09-13":
        raise RuntimeError("Expected the FU Bellard QuickJS engine version")
    sources = [str(interface)] + [str(native / (name + ".c")) for name in
                                 ["quickjs", "dtoa", "libregexp", "libunicode", "cutils", "quickjs-libc"]]
    command = [str(args.emcc.resolve()), *sources, "-Oz", "-flto", "-D_GNU_SOURCE",
               '-DCONFIG_VERSION="2025-09-13"', "-DCONFIG_STACK_CHECK", "--no-entry",
               "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sEXPORT_NAME=QuickJSRaw",
               "-sENVIRONMENT=node", "-sALLOW_MEMORY_GROWTH=1", "-sALLOW_TABLE_GROWTH=1",
               "-sSTACK_SIZE=5MB", "-sFILESYSTEM=0", "-sINVOKE_RUN=0", "-sSUPPORT_ERRNO=0",
               "-sNODEJS_CATCH_EXIT=0", "-sEXPORTED_FUNCTIONS=@" + str(symbol_file),
               "-sEXPORTED_RUNTIME_METHODS=@" + str(runtime_methods),
               "--pre-js", str(work / "templates/pre-extension.js"),
               "--pre-js", str(work / "templates/pre-wasmMemory.js"),
               "-o", str(output / "quickjs.mjs")]
    subprocess.run(command, check=True)
    shutil.copyfile(source / "LICENSE", output / "LICENSE-quickjs-emscripten")
    shutil.copyfile(native / "LICENSE", output / "LICENSE-quickjs")
    evidence = {"bindingVersion": "0.32.0", "bindingRevision": revision,
                "engineRevision": "f1139494d18a2053630c5ed3384a42bb70db3c53",
                "emscripten": "4.0.12", "nativePromiseRejectionTracker": True,
                "files": {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                          for name in ["quickjs.mjs", "quickjs.wasm"]}}
    (output / "build.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
