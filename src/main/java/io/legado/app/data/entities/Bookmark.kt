package io.legado.app.data.entities




//@Parcelize
//@Entity(tableName = "bookmarks", indices = [(Index(value = ["bookUrl"], unique = true))])
data class Bookmark(
//    @PrimaryKey
    val time: Long = System.currentTimeMillis(),
    val bookName: String = "",
    val bookAuthor: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterName: String = "",
    var bookText: String = "",
    var content: String = "",
    var bookmarkType: Int = 0,
    var endChapterIndex: Int = 0,
    var endChapterPos: Int = 0,
    var highlightStyle: Int = 0,
    var highlightColor: Int = -32885

) {
    fun validate() {
        require(chapterIndex >= 0 && chapterPos >= 0) { "书签位置不能为负数" }
        require(bookmarkType in 0..1 && highlightStyle in 0..2) { "书签类型或高亮样式无效" }
        if (bookmarkType == 1) {
            require(endChapterIndex >= chapterIndex && endChapterPos >= 0 &&
                (endChapterIndex > chapterIndex || endChapterPos > chapterPos)) { "高亮结束位置必须在开始位置之后" }
        }
    }
    fun coversChapter(index: Int): Boolean = bookmarkType == 1 && index in chapterIndex..endChapterIndex
    fun positions(index: Int): IntRange? = if (!coversChapter(index)) null else
        (if (index == chapterIndex) chapterPos else 0) until (if (index == endChapterIndex) endChapterPos else Int.MAX_VALUE)
}
