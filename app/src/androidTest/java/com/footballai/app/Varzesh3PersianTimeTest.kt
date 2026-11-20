package com.footballai.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.footballai.app.data.remote.Varzesh3Provider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Varzesh3PersianTimeTest {
    @Test fun convertsIranianCivilTimeRegardlessOfDeviceTimeZone() {
        assertEquals(Instant.parse("2026-11-23T11:00:00Z").toEpochMilli(),
            Varzesh3Provider.persianKickoff("1405/09/02", "14:30"))
        assertEquals(Instant.parse("2025-03-20T20:30:00Z").toEpochMilli(),
            Varzesh3Provider.persianKickoff("1404/01/01", "00:00"))
        assertNull(Varzesh3Provider.persianKickoff("1404/12/30", "12:00"))
        assertNull(Varzesh3Provider.persianKickoff("1405/09/02", "نامشخص"))
    }
}
