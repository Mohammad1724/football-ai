package com.footballai.app.data.remote

import com.footballai.app.domain.catalog.TeamNames

/**
 * When two publishers name the same club with different words.
 *
 * The live feed, the archive and the crest providers all publish club names in their own
 * house style: `KAA Gent` against `Gent`, `Cercle Brugge KSV` against `Cercle Brugge`,
 * `Royal Antwerp FC` against `Antwerp`, `1. FC Köln` against `FC Köln`. Exact string
 * equality throws all of those away, which is why a club could have fixtures, history and a
 * published badge and still be drawn as two initials.
 *
 * The rules are deliberately narrower than "similar":
 *
 *  1. A reserve, age or women's marker on one side and not the other is a hard reject:
 *     `Bayern Munich II` is not `Bayern Munich`, in either direction, ever.
 *  2. Equality after folding case, accents, punctuation and the legal-form words (`fc`,
 *     `ksv`, `cd`, `1`, `04`, …) is accepted — those words describe a company, not a club.
 *  3. A single distinctive word matches a longer name when it is that name's **last**
 *     word (`Antwerp` / `Royal Antwerp FC`), or its first word when the longer name is
 *     exactly two words (`Ajax` / `Ajax Amsterdam`, `LASK` / `LASK Linz`). Words that head
 *     more than one club — `Real`, `Bayer`, `Sporting`, `Union`, `City` — are not evidence
 *     and never match on their own, which is why `Real` does not take `Real Madrid`'s badge.
 *  4. Two longer names are accepted when one contains the other's words.
 *  5. The caller must still find exactly one candidate. Two clubs that both fit mean the
 *     question was ambiguous, and an ambiguous name keeps its initials instead of taking a
 *     badge that may belong to somebody else.
 *
 * Nothing here is a similarity score: every accepted pair satisfies a rule that can be read
 * off the two strings, and every rule has a test with a counter-example.
 */
internal object ClubNameMatcher {

    /**
     * Words that describe a club's legal form, its founding year or a publisher's shorthand
     * — never which club it is.
     */
    private val LEGAL_FORMS = setOf(
        // European legal forms.
        "fc", "afc", "ac", "acf", "cf", "cfc", "sc", "sfc", "rsc", "krc", "kvc", "rfc",
        "as", "ss", "ssc", "us", "usl", "sv", "vfl", "vfb", "tsg", "rc", "rcd", "ogc", "aj",
        "de", "futbol", "calcio", "cd", "ca", "ud", "sd", "ad", "sp", "cs", "cp",
        // Northern and Eastern European forms.
        "fk", "sk", "bk", "if", "ff", "ik", "nk", "kf", "hnk", "kaa", "ksv", "kv", "fcm",
        "cfr", "bsc", "dsc", "fsv", "vfr", "ssv", "tsv",
        // Founding years that some publishers keep and others drop.
        "1", "04", "05", "07", "09", "96", "98", "1899", "1900", "1904", "1907",
    )

    /**
     * Words that head more than one club, so on their own they identify nobody: `Real`
     * (Madrid, Sociedad, Betis), `Bayer` (Leverkusen, Uerdingen), `Sporting` (Lisbon,
     * Kansas City), `Union` (Berlin, Saint-Gilloise), `City`, `Club`, `Nacional`, `Inter`.
     */
    private val FAMILY_WORDS = setOf(
        "real", "bayer", "sporting", "union", "city", "club", "atletico", "athletic",
        "nacional", "national", "inter", "deportivo", "racing", "dinamo", "dynamo",
    )

    /** A team that is a second side, an age group or a women's team is a different team. */
    private val RESERVE_MARKERS = setOf(
        "ii", "iii", "b", "jong", "young", "u17", "u18", "u19", "u20", "u21", "u23",
        "women", "ladies", "reserves", "reserve", "youth", "academy",
    )

    private fun words(name: String): List<String> =
        TeamNames.normalizeNameKey(name).split(' ').filter { it.isNotEmpty() }

    /**
     * The words of a name that actually identify a club, in the order published.
     *
     * Punctuation inside an abbreviation is not a word boundary: the feed writes
     * `N.E.C. Nijmegen` and the source writes `NEC Nijmegen`, so a run of one-letter words
     * is one word.
     */
    fun core(name: String): List<String> {
        val kept = words(name).filter { it !in LEGAL_FORMS }
        val merged = mutableListOf<String>()
        val run = StringBuilder()
        for (word in kept) {
            if (word.length == 1) {
                run.append(word)
                continue
            }
            if (run.isNotEmpty()) {
                merged.add(run.toString())
                run.clear()
            }
            merged.add(word)
        }
        if (run.isNotEmpty()) merged.add(run.toString())
        return merged.distinct()
    }

    /**
     * True when one name carries a reserve/age/women marker the other does not — the one
     * difference that must never be forgiven, in either direction.
     */
    fun reserveMismatch(first: String, second: String): Boolean {
        val firstMarkers = words(first).filter { it in RESERVE_MARKERS }.toSet()
        val secondMarkers = words(second).filter { it in RESERVE_MARKERS }.toSet()
        return firstMarkers != secondMarkers
    }

    /** Whether two published names can only mean the same club. */
    fun matches(published: String, wanted: String): Boolean {
        if (published.isBlank() || wanted.isBlank()) return false
        if (reserveMismatch(published, wanted)) return false
        if (TeamNames.normalizeNameKey(published) == TeamNames.normalizeNameKey(wanted)) return true
        val publishedCore = core(published)
        val wantedCore = core(wanted)
        if (publishedCore.isEmpty() || wantedCore.isEmpty()) return false
        // "CD Nacional" / "Nacional", "KAA Gent" / "Gent": the same words once the form is gone.
        if (publishedCore.toSet() == wantedCore.toSet()) return true
        // A single distinctive word still names the club when it is the head of the longer
        // name ("Antwerp" is "Royal Antwerp FC", "Ajax" is "Ajax Amsterdam"), unless the
        // word heads several clubs ("Real" is not "Real Madrid").
        if (publishedCore.size == 1 || wantedCore.size == 1) {
            val (single, longer) = if (publishedCore.size == 1) {
                publishedCore.single() to wantedCore
            } else {
                wantedCore.single() to publishedCore
            }
            if (single in FAMILY_WORDS) return false
            // The tail of a name is its head noun in every league that matters here.
            if (single == longer.last()) return true
            // The first word is only evidence when the longer name is club + place, as in
            // "Ajax Amsterdam"; "Real Madrid Betis" style names stay unmatched.
            return longer.size == 2 && single == longer.first()
        }
        return publishedCore.toSet().containsAll(wantedCore) || wantedCore.containsAll(publishedCore)
    }

    /**
     * The single candidate that matches one of [wanted], or null.
     *
     * [publishedNames] returns every name the source published for that candidate, so a
     * provider that ships a short name and a display name is judged on both.
     */
    fun <T> pick(
        candidates: List<T>,
        publishedNames: (T) -> List<String>,
        wanted: List<String>,
    ): T? {
        if (candidates.isEmpty() || wanted.isEmpty()) return null
        val matched = candidates.filter { candidate ->
            val names = publishedNames(candidate).filter { it.isNotBlank() }
            wanted.any { query -> names.any { published -> matches(published, query) } }
        }
        return matched.singleOrNull()
    }
}
