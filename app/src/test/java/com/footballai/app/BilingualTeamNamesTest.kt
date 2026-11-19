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
    fun `club names shown in November screenshots have Persian and published names`() {
        val names = mapOf(
            "Amedspor" to "آمداسپور", "Kasimpasa" to "قاسم‌پاشا", "Kocaelispor" to "کوجائلی‌اسپور",
            "Istanbul Basaksehir" to "استانبول باشاک‌شهیر", "Çorum" to "چوروم", "Göztepe" to "گوزتپه",
            "Çaykur Rizespor" to "چای‌کور ریزه‌اسپور", "Erzurumspor" to "ارزروم‌اسپور",
            "Alanyaspor" to "آلانیااسپور", "Gençlerbirliği" to "گنچلربیرلیغی", "Konyaspor" to "قونیه‌اسپور",
            "Gaziantep" to "غازی‌عینتاب", "Samsunspor" to "سامسون‌اسپور", "Eyüpspor" to "ایوپ‌اسپور",
            "sc Heerenveen" to "هیرنفین", "Fortuna Sittard" to "فورتونا سیتارد", "Go Ahead Eagles" to "گو اهد ایگلز",
            "FC Groningen" to "خرونینگن", "SC Cambuur" to "کامبور", "N.E.C. Nijmegen" to "نایمخن",
            "Sparta Rotterdam" to "اسپارتا روتردام", "PEC Zwolle" to "زووله", "FC Utrecht" to "اوترخت",
            "Excelsior Rotterdam" to "اکسلسیور روتردام", "Telstar" to "تلستار", "ADO Den Haag" to "آدو دن هاخ", "Willem II" to "ویلم دوم",
            "Partick Thistle" to "پارتیک تیستل", "Stenhousemuir" to "استنهاوسمویر", "Raith Rovers" to "ریث روورز",
            "Dunfermline Athletic" to "دانفرملین اتلتیک", "Livingston" to "لیوینگستون",
            "Inverness Caledonian Thistle" to "اینورنس کالدونین تیستل", "Ayr United" to "ایر یونایتد",
            "Arbroath" to "آربروث", "Greenock Morton" to "گرینوک مورتون", "Queen's Park" to "کوئینز پارک",
            "RC Deportivo" to "دپورتیوو لاکرونیا", "Deportivo Alavés" to "آلاوس", "Levante UD" to "لوانته", "Málaga CF" to "مالاگا",
            "Viktoria Köln" to "ویکتوریا کلن", "TSV Havelse" to "هاولسه",
            "Colwyn Bay" to "کولوین بی", "Airbus" to "ایرباس", "Holywell" to "هولی‌ول", "Briton Ferry" to "بریتِن فری",
            "Connahs Q." to "کوناز کی", "Caernarfon" to "کارنارفون", "Ammanford" to "امانفورد", "Cardiff Metropolitan" to "کاردیف متروپولیتن",
        )
        names.forEach { (source, persian) ->
            assertEquals(persian to source, NameLocalization.namePair(source, "fa"), source)
            assertEquals(source, NameLocalization.namePair(source, "en").first, source)
        }
        assertNull(NameLocalization.persianTeamName("Viktoria Köln II"))
        assertNull(NameLocalization.persianTeamName("Colwyn Bay Wanderers"))
    }

    @Test
    fun `unknown and reserve clubs do not acquire an invented name`() {
        assertEquals("Imaginary United" to null, NameLocalization.namePair("Imaginary United", "fa"))
        assertNull(NameLocalization.persianTeamName("Bayern Munich II"))
        assertEquals("کلن" to null, NameLocalization.namePair("کولن", "fa", "Cologne"))
    }
}
