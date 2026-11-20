package top.wkbin.zaomeng.data.api

private val cardMacro = Regex(
    "${Regex.escape("{{char}}")}|${Regex.escape("{{user}}")}",
    RegexOption.IGNORE_CASE,
)

/** Expands the two name placeholders used in imported card text and previews. */
fun expandSillyTavernNames(text: String, character: String, user: String): String =
    cardMacro.replace(text) { if (it.value.equals("{{char}}", ignoreCase = true)) character else user }
