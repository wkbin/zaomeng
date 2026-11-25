package top.wkbin.zaomeng.data.api

private val cardMacro = Regex(
    "${Regex.escape("{{char}}")}|${Regex.escape("{{user}}")}",
    RegexOption.IGNORE_CASE,
)

/** Expands the two name placeholders used in imported card text and previews. */
fun expandSillyTavernNames(text: String, character: String, user: String): String =
    cardMacro.replace(text) { if (it.value.equals("{{char}}", ignoreCase = true)) character else user }

private val outletMacro = Regex("""\{\{outlet::([^{}\r\n]+?)\}\}""", RegexOption.IGNORE_CASE)

val sillyTavernOutletCardFields = setOf(
    "description", "personality", "scenario", "mes_example", "system_prompt", "post_history_instructions", "depth_prompt",
)

fun referencedSillyTavernOutlets(card: Map<*, *>?): Set<String> = sillyTavernOutletCardFields
    .asSequence()
    .mapNotNull { card?.get(it) as? String }
    .flatMap { outletMacro.findAll(it).map { match -> match.groupValues[1].trim() } }
    .toSet()

fun String.isValidSillyTavernOutletName(): Boolean =
    isNotBlank() && length <= 120 && none { it in "{}\r\n" }

/** Substitutes one level of outlet macros. A name without any active content expands to empty. */
fun expandSillyTavernOutlets(text: String, outlets: Map<String, String>): String =
    outletMacro.replace(text) { outlets[it.groupValues[1].trim()].orEmpty() }

private val exampleStart = Regex("<START>", RegexOption.IGNORE_CASE)

fun sillyTavernExampleBlocks(text: String): List<String> =
    exampleStart.split(text).map(String::trim).filter(String::isNotEmpty)
