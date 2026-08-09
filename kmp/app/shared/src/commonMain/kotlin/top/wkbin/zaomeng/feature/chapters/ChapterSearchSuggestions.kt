package top.wkbin.zaomeng.feature.chapters

import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto

internal fun chapterSearchSuggestions(
    chapters: List<ChapterDto>,
    sessions: List<DialogueSessionDto>,
    limit: Int = 8,
): List<String> = buildList {
    chapters.forEach { chapter -> addAll(chapter.participants) }
    sessions.forEach { session -> addAll(session.participants) }
}
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .take(limit)
