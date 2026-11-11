package com.footballai.engine

import com.footballai.engine.math.Poisson
import kotlin.math.exp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoissonTest {

    @Test
    fun `pmf matches the closed form for zero goals`() {
        assertEquals(exp(-1.0), Poisson.pmf(0, 1.0), 1e-12)
        assertEquals(exp(-2.5), Poisson.pmf(0, 2.5), 1e-12)
    }

    @Test
    fun `pmf matches the closed form for k goals`() {
        val lambda = 1.7
        val expected = exp(-lambda) * Math.pow(lambda, 3.0) / 6.0
        assertEquals(expected, Poisson.pmf(3, lambda), 1e-12)
    }

    @Test
    fun `distribution sums to one`() {
        val sum = Poisson.distribution(2.3, 60).sum()
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun `cdf is monotone and bounded`() {
        var previous = 0.0
        for (k in 0..20) {
            val value = Poisson.cdf(k, 1.4)
            assertTrue(value >= previous - 1e-12, "cdf must not decrease")
            assertTrue(value in 0.0..1.0)
            previous = value
        }
        assertEquals(1.0, Poisson.cdf(40, 1.4), 1e-9)
    }

    @Test
    fun `zero lambda only allows zero goals`() {
        assertEquals(1.0, Poisson.pmf(0, 0.0), 1e-12)
        assertEquals(0.0, Poisson.pmf(3, 0.0), 1e-12)
    }

    @Test
    fun `negative lambda never produces probability mass`() {
        assertEquals(0.0, Poisson.pmf(1, -1.0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, Poisson.logPmf(1, -1.0), 0.0)
    }

    @Test
    fun `over probability is consistent with explicit summation`() {
        val home = 1.45
        val away = 1.15
        var under25 = 0.0
        for (h in 0..12) {
            for (a in 0..12) {
                if (h + a <= 2.5) under25 += Poisson.pmf(h, home) * Poisson.pmf(a, away)
            }
        }
        assertEquals(1.0 - under25, Poisson.probabilityOver(home, away, 2.5), 1e-9)
    }

    @Test
    fun `over probabilities increase with lower lines`() {
        val over05 = Poisson.probabilityOver(1.4, 1.1, 0.5)
        val over25 = Poisson.probabilityOver(1.4, 1.1, 2.5)
        val over45 = Poisson.probabilityOver(1.4, 1.1, 4.5)
        assertTrue(over05 > over25 && over25 > over45)
        assertTrue(abs(over05 - 1.0 + exp(-2.5)) < 1e-9)
    }
}
