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
    fun `additional leagues in the screenshots are bilingual without guessing unknowns`() {
        val names = mapOf(
            "FC Tokyo" to "اف‌سی توکیو", "Kashiwa Reysol" to "کاشیوا ریسول",
            "Sanfrecce Hiroshima" to "سانفریچه هیروشیما", "Machida Zelvia" to "ماچیدا زلویا",
            "Vissel Kobe" to "ویسل کوبه", "Kashima Antlers" to "کاشیما آنتلرز",
            "Yokohama F. Marinos" to "یوکوهاما مارینوس", "Kawasaki Frontale" to "کاوازاکی فرونتاله",
            "Urawa Red Diamonds" to "اوراوا رد دایموندز", "Fagiano Okayama" to "فاژیانو اوکایاما",
            "Cerezo Osaka" to "سرزو اوساکا", "Kyoto Sanga" to "کیوتو سانگا",
            "Wellington Phoenix" to "ولینگتون فونیکس", "Sydney FC" to "سیدنی", "Brisbane Roar FC" to "بریزبن روار",
            "Melbourne City FC" to "ملبورن سیتی", "Perth Glory FC" to "پرت گلوری",
            "Western Sydney Wanderers FC" to "وسترن سیدنی واندررز", "Macarthur FC" to "مک‌آرتور",
            "Auckland FC" to "اوکلند", "Melbourne Victory" to "ملبورن ویکتوری", "Newcastle Jets" to "نیوکاسل جتس",
            "Central Coast Mariners FC" to "سنترال کوست مارینرز", "Adelaide United" to "آدلاید یونایتد",
            "Viking" to "وایکینگ", "Lillestrom" to "لیلستروم", "Tromso" to "ترومسو", "Valerenga" to "والرنگا",
            "Sarpsborg 08" to "سارپسبرگ ۰۸", "KFUM Oslo" to "کی‌اف‌یو‌ام اسلو", "Hamarkameratene" to "هام‌کام",
            "Fredrikstad" to "فردریکستاد", "Sandefjord" to "ساندفیورد",
            "Cercle Brugge KSV" to "سرکل بروژ", "KVC Westerlo" to "وسترلو",
            "Korona Kielce" to "کرونا کیلتسه", "Piast Gliwice" to "پیاست گلیویتسه",
            "Slask Wroclaw" to "شلونسک وروتسواف", "Pogon Szczecin" to "پوگون شچچین",
            "Puszcza" to "پوشچا", "Chrobry Glogow" to "خروبری گووگوف", "LKS Lodz" to "ال‌کااس ووچ",
            "Legnica" to "لگنیتسا", "Swit Szczecin" to "شویت شچچین", "Chojniczanka" to "خوینیتشانکا",
            "Zawisza" to "زاویشا", "Leczna" to "وِنچنا", "Podhale Nowy Targ" to "پودهاله نووی تارگ", "Tychy" to "تیخی",
            "Ol. Grudziadz" to "المپیا گرودزیوندز", "Penybont" to "پنی‌بونت", "Haverfordwest" to "هاورفوردوست",
            "Cardiff MU" to "کاردیف متروپولیتن", "Bala Town" to "بالا تاون",
            "Newport City" to "نیوپورت سیتی", "Caerau Ely" to "کایرائو الی", "Llanelli" to "لانلی",
            "Caerphilly" to "کایرفیلی", "Carmarthen Town" to "کارمارتن تاون", "Pontypridd" to "پونتی‌پریث",
            "Trethomas Bluebirds" to "ترتوماس بلوبردز", "Pure Swansea" to "پیور سوانزی",
        )
        names.forEach { (source, persian) ->
            assertEquals(persian to source, NameLocalization.namePair(source, "fa"), source)
            assertEquals(source, NameLocalization.namePair(source, "en").first, source)
        }
        assertNull(NameLocalization.persianTeamName("Sydney FC II"))
        assertNull(NameLocalization.persianTeamName("Newport City Women"))
        assertNull(NameLocalization.persianTeamName("S. Wola"))
    }

    @Test
    fun `unknown and reserve clubs do not acquire an invented name`() {
        assertEquals("Imaginary United" to null, NameLocalization.namePair("Imaginary United", "fa"))
        assertNull(NameLocalization.persianTeamName("Bayern Munich II"))
        assertEquals("کلن" to null, NameLocalization.namePair("کولن", "fa", "Cologne"))
    }
}
