package io.legado.app.model.jsSource

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.SourceStorage
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.lang.ref.WeakReference

/** Library globals resolve request bindings dynamically without retaining them in the cached scope. */
object SourceSharedJsScope {
    private class LibraryGlobal : ScriptableObject() {
        override fun getClassName(): String = "global"
        override fun get(name: String, start: Scriptable): Any? {
            val binding = SourceScriptRuntime.libraryBinding(name)
            return if (binding !== Scriptable.NOT_FOUND) binding else super.get(name, start)
        }
        override fun has(name: String, start: Scriptable): Boolean =
            SourceScriptRuntime.libraryBinding(name) !== Scriptable.NOT_FOUND || super.has(name, start)
    }
    private data class Key(val namespace: String, val library: String, val thread: Long? = null)
    private val scopes = LinkedHashMap<Key, WeakReference<ScriptableObject>>(16, 0.75f, true)
    private val cryptoScopes = LinkedHashMap<Key, WeakReference<ScriptableObject>>(16, 0.75f, true)
    private var revision = 0L

    fun getOrCreate(
        source: BaseSource,
        cx: Context,
        initialize: (ScriptableObject) -> Unit,
    ): ScriptableObject? {
        val library = source.jsLib?.takeIf { it.isNotBlank() }
        val key = if (library == null) Key(source.getStorageNamespace(), "CryptoJS", Thread.currentThread().threadId())
            else Key(source.getStorageNamespace(), SourceStorage.namespace("jsLib", library))
        val cache = if (library == null) cryptoScopes else scopes
        val initializedAtRevision = synchronized(scopes) {
            cache[key]?.get()?.let { return it }
            revision
        }
        // Library initialization may download code; do not block unrelated sources while it runs.
        val scope = LibraryGlobal()
        cx.initStandardObjects(scope)
        initialize(scope)
        scope.preventExtensions()
        synchronized(scopes) {
            if (revision != initializedAtRevision) return scope
            // Concurrent first loads reuse the first published scope. Do not lock
            // execution: ajaxAll may reenter the same library on an IO thread.
            cache[key]?.get()?.let { return it }
            cache[key] = WeakReference(scope)
            val iterator = cache.entries.iterator()
            while (cache.size > 16 && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return scope
    }

    fun invalidateLibrary(source: BaseSource) {
        val namespace = source.getStorageNamespace()
        synchronized(scopes) {
            revision++
            scopes.keys.removeIf { it.namespace == namespace }
            cryptoScopes.keys.removeIf { it.namespace == namespace }
        }
    }
}
