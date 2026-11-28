package com.footballai.app.v2

/**
 * The seven day-one leagues of the rewrite (spec step 2), in the user's locked
 * default display order: Iran, Champions League, La Liga, Premier League,
 * Serie A, Bundesliga, Ligue 1.
 *
 * [slug] is the football-ai-data v2 directory name and the stable key used in
 * persisted settings; [nameFa] is the short Persian name used as the home
 * group header, [settingsLabelFa] the longer label used in Settings rows.
 */
data class V2League(
    val slug: String,
    val fotmobId: Int,
    val nameFa: String,
    val settingsLabelFa: String,
)

object V2Leagues {

    val IRAN = V2League("iran", 523, "لیگ ایران", "لیگ ایران")
    val CHAMPIONS_LEAGUE = V2League("champions-league", 42, "لیگ قهرمانان", "لیگ قهرمانان اروپا")
    val LA_LIGA = V2League("la-liga", 87, "لالیگا", "لالیگا — اسپانیا")
    val PREMIER_LEAGUE = V2League("premier-league", 47, "لیگ برتر انگلیس", "لیگ برتر — انگلیس")
    val SERIE_A = V2League("serie-a", 55, "سری آ", "سری آ — ایتالیا")
    val BUNDESLIGA = V2League("bundesliga", 54, "بوندس‌لیگا", "بوندس‌لیگا — آلمان")
    val LIGUE_1 = V2League("ligue-1", 53, "لیگ ۱", "لیگ ۱ — فرانسه")

    /** Locked default order (spec step 4, settings mockup). */
    val DEFAULT_ORDER: List<V2League> =
        listOf(IRAN, CHAMPIONS_LEAGUE, LA_LIGA, PREMIER_LEAGUE, SERIE_A, BUNDESLIGA, LIGUE_1)

    val BY_SLUG: Map<String, V2League> = DEFAULT_ORDER.associateBy { it.slug }

    fun bySlug(slug: String): V2League? = BY_SLUG[slug]

    /**
     * Resolves a persisted order (slugs) into leagues: known slugs keep their
     * saved relative order, any league missing from the saved list is appended
     * in default order, unknown slugs are dropped.
     */
    fun resolveOrder(savedSlugs: List<String>): List<V2League> {
        val saved = savedSlugs.mapNotNull { BY_SLUG[it] }.distinct()
        val missing = DEFAULT_ORDER.filter { it !in saved }
        return saved + missing
    }
}
