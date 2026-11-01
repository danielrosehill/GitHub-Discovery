package com.danielrosehill.githubdiscovery.api

import org.json.JSONObject
import java.time.LocalDate

enum class DateRange(val label: String, val days: Long?) {
    ANY("Any time", null), DAY("24 h", 1), WEEK("Week", 7), MONTH("Month", 30),
    QUARTER("3 months", 90), YEAR("Year", 365);

    fun since(today: LocalDate = LocalDate.now()): LocalDate? = days?.let { today.minusDays(it) }
}

/**
 * Sort orders. GitHub search accepts `sort:<field>-<dir>` inside the query string (verified 2026-11-01 for
 * stars, forks, updated). There is no working `sort:created`; [NEWEST] is emulated client-side by walking
 * backwards through `created:` date windows (see AppViewModel.loadNewest).
 */
enum class Sort(val label: String, val qualifier: String?) {
    STARS("Most stars", "sort:stars-desc"),
    NEWEST("Newest", null),
    UPDATED("Recently updated", "sort:updated-desc"),
    FORKS("Most forks", "sort:forks-desc"),
    BEST("Best match", null),
    HELP_WANTED("Help wanted", "sort:help-wanted-issues-desc"),
}

/** The desktop advanced-search operators for repositories, as form fields. */
data class Filters(
    val keywords: String = "",
    val inName: Boolean = false,
    val inDescription: Boolean = false,
    val inReadme: Boolean = false,
    val inTopics: Boolean = false,
    val topics: String = "",
    val language: String = "",
    val owner: String = "",
    val minStars: String = "",
    val maxStars: String = "",
    val minForks: String = "",
    val created: DateRange = DateRange.ANY,
    val pushed: DateRange = DateRange.ANY,
    val license: String = "",
    val goodFirstIssues: String = "",
    val hideArchived: Boolean = true,
    val includeForks: Boolean = false,
    val extra: String = "",
) {
    fun topicList(): List<String> =
        topics.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun isEmpty(): Boolean = build(includeCreated = true).replace("archived:false", "").isBlank()

    fun build(includeCreated: Boolean = true, today: LocalDate = LocalDate.now()): String {
        val p = mutableListOf<String>()
        if (keywords.isNotBlank()) p += keywords.trim()
        val ins = listOfNotNull(
            "name".takeIf { inName }, "description".takeIf { inDescription },
            "readme".takeIf { inReadme }, "topics".takeIf { inTopics },
        )
        if (ins.isNotEmpty() && keywords.isNotBlank()) p += "in:" + ins.joinToString(",")
        topicList().forEach { p += "topic:$it" }
        if (language.isNotBlank()) p += "language:" + quote(language.trim())
        if (owner.isNotBlank()) p += "user:" + owner.trim().removePrefix("@")
        val lo = minStars.trim().toIntOrNull()
        val hi = maxStars.trim().toIntOrNull()
        when {
            lo != null && hi != null -> p += "stars:$lo..$hi"
            lo != null -> p += "stars:>=$lo"
            hi != null -> p += "stars:<=$hi"
        }
        minForks.trim().toIntOrNull()?.let { p += "forks:>=$it" }
        if (includeCreated) created.since(today)?.let { p += "created:>=$it" }
        pushed.since(today)?.let { p += "pushed:>=$it" }
        if (license.isNotBlank()) p += "license:" + license.trim().lowercase()
        goodFirstIssues.trim().toIntOrNull()?.let { p += "good-first-issues:>=$it" }
        if (hideArchived) p += "archived:false"
        if (includeForks) p += "fork:true"
        if (extra.isNotBlank()) p += extra.trim()
        return p.joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("keywords", keywords).put("inName", inName).put("inDescription", inDescription)
        .put("inReadme", inReadme).put("inTopics", inTopics).put("topics", topics)
        .put("language", language).put("owner", owner).put("minStars", minStars)
        .put("maxStars", maxStars).put("minForks", minForks).put("created", created.name)
        .put("pushed", pushed.name).put("license", license).put("goodFirstIssues", goodFirstIssues)
        .put("hideArchived", hideArchived).put("includeForks", includeForks).put("extra", extra)

    companion object {
        private fun quote(s: String) = if (s.contains(' ')) "\"$s\"" else s

        fun fromJson(j: JSONObject) = Filters(
            keywords = j.optString("keywords"), inName = j.optBoolean("inName"),
            inDescription = j.optBoolean("inDescription"), inReadme = j.optBoolean("inReadme"),
            inTopics = j.optBoolean("inTopics"), topics = j.optString("topics"),
            language = j.optString("language"), owner = j.optString("owner"),
            minStars = j.optString("minStars"), maxStars = j.optString("maxStars"),
            minForks = j.optString("minForks"),
            created = runCatching { DateRange.valueOf(j.optString("created")) }.getOrDefault(DateRange.ANY),
            pushed = runCatching { DateRange.valueOf(j.optString("pushed")) }.getOrDefault(DateRange.ANY),
            license = j.optString("license"), goodFirstIssues = j.optString("goodFirstIssues"),
            hideArchived = j.optBoolean("hideArchived", true), includeForks = j.optBoolean("includeForks"),
            extra = j.optString("extra"),
        )
    }
}

/** One-tap "trending" surrogates: a created-since window plus a sort. Keywords/topic/language are kept. */
enum class Preset(val label: String, val created: DateRange, val sort: Sort) {
    HOT_WEEK("Hot this week", DateRange.WEEK, Sort.STARS),
    HOT_MONTH("Hot this month", DateRange.MONTH, Sort.STARS),
    RISING_YEAR("Rising this year", DateRange.YEAR, Sort.STARS),
    NEWEST("Newest", DateRange.ANY, Sort.NEWEST),
    ALL_TIME("Most starred ever", DateRange.ANY, Sort.STARS),
    ACTIVE("Active now", DateRange.ANY, Sort.UPDATED),
}

data class SavedSearch(val name: String, val filters: Filters, val sort: Sort) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("filters", filters.toJson()).put("sort", sort.name)

    companion object {
        fun fromJson(j: JSONObject) = SavedSearch(
            j.getString("name"), Filters.fromJson(j.getJSONObject("filters")),
            runCatching { Sort.valueOf(j.getString("sort")) }.getOrDefault(Sort.STARS),
        )
    }
}
