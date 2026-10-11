package io.legado.app.data.entities.rule

data class ContentRule(
    var content: String? = null,
    var subContent: String? = null,
    var title: String? = null,
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null, //替换规则
    var imageStyle: String? = null,  //默认大小居中,FULL最大宽度
    var imageDecode: String? = null,
    var payAction: String? = null,
    var callBackJs: String? = null,
) : io.legado.app.help.source.SourceFields()
