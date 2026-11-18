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
 *  3. One name may carry one distinctive word where the other carries a single one, but
 *     only when it is that name's **last** distinctive word: `Antwerp` matches `Royal
 *     Antwerp FC`, while `Real` does not match `Real Madrid`. The head of an English,
 *     Dutch, German or Scandinavian club name is its last word; Spanish, Portuguese and
 *     Italian names put the family first, so a single word there stays unlinked.
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

    /** A team that is a second side, an age group or a women's team is a different team. */
    private val RESERVE_MARKERS = setOf(
        "ii", "iii", "b", "u17", "u19", "u20", "u21", "u23", "women", "ladies", "reserves", "youth",
    )

    private fun words(name: String): List<String> =
        TeamNames.normalizeNameKey(name).split(' ').filter { it.isNotEmpty() }

    /** The words of a name that actually identify a club, in the order published. */
    fun core(name: String): List<String> = words(name)
        .filter { it !in LEGAL_FORMS }
        .distinct()

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
        // A single distinctive word can still name the club when it is the head of the
        // longer name: "Antwerp" is "Royal Antwerp FC", but "Real" is not "Real Madrid".
        if (publishedCore.size == 1 || wantedCore.size == 1) {
            val (single, longer) = if (publishedCore.size == 1) {
                publishedCore.single() to wantedCore
            } else {
                wantedCore.single() to publishedCore
            }
            return single == longer.last()
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
