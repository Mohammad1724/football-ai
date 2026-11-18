package com.footballai.app.data.local

/**
 * The per-club "already asked" marker, in one place.
 *
 * A club that was asked and answered nothing cools down for a week, so the marker is the
 * reason a club can stay on initials. The number in it is a rule version: raising it asks
 * every club once more, which is what has to happen whenever the searching changed enough
 * that an old refusal means nothing. `TeamDao.crestlessInFixtures` repeats the literal in
 * its SQL — keep the two in step.
 */
object CrestAttempts {
    private const val PREFIX = "crest-attempt-v8:"

    fun key(teamId: String): String = PREFIX + teamId

    /** The same prefix, for the queries that join on it. */
    fun prefix(): String = PREFIX

    /** For reports: how the app spells the marker today. */
    fun version(): String = PREFIX.trimEnd(':')
}
