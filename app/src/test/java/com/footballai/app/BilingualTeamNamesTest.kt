package com.footballai.app

import com.footballai.app.domain.catalog.NameLocalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BilingualTeamNamesTest {
    @Test
    fun `screenshot clubs use Persian primary and original source name secondary`() {
        val names = mapOf(
            "1. FC Kaiserslautern" to "کایزرسلاوترن", "1. FC Magdeburg" to "ماگدبورگ",
            "DSC Arminia Bielefeld" to "آرمینیا بیله‌فلد", "FC St. Pauli" to "سنت پائولی",
            "SpVgg Greuther Fürth" to "گرویتر فورث", "SV Elversberg" to "الورسبرگ",
            "Bayer 04 Leverkusen" to "بایر لورکوزن", "SV Werder Bremen" to "وردر بریمن",
            "Hamburg SV" to "هامبورگ", "1. FSV Mainz 05" to "ماینتس",
            "SC Paderborn 07" to "پادربورن", "FC Cologne" to "کلن",
            "Coritiba" to "کوریتیبا", "Red Bull Bragantino" to "رد بول براگانچینو",
            "Athletico-PR" to "اتلتیکو پارانائنزه", "Atlético-MG" to "اتلتیکو مینیرو",
            "Chapecoense" to "شاپه‌کوئنزه", "Mirassol" to "میراسول",
        )
        for ((original, persian) in names) {
            assertEquals(persian to original, NameLocalization.namePair(original, "fa"), original)
            assertTrue(NameLocalization.matches(persian, original), original)
            assertEquals(original, NameLocalization.namePair(original, "en").first)
        }
    }
    @Test
    fun `unknown and reserve clubs do not acquire an invented name`() {
        assertEquals("Imaginary United" to null, NameLocalization.namePair("Imaginary United", "fa"))
        assertNull(NameLocalization.persianTeamName("Bayern Munich II"))
        assertEquals("کلن" to null, NameLocalization.namePair("کولن", "fa", "Cologne"))
    }
}
