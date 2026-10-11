package io.legado.app.data.entities.rule

data class ExploreKind(
    var title: String = "",
    var url: String? = null,
    var type: String = "url",
    var action: String? = null,
    var chars: List<String>? = null,
    var default: String? = null,
    var viewName: String? = null,
    var style: Map<String, Any?>? = null,
) : io.legado.app.help.source.SourceFields()
