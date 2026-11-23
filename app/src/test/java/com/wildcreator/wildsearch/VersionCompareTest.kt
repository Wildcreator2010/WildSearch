package com.wildcreator.wildsearch

import com.wildcreator.wildsearch.util.isNewerVersion
import org.junit.Assert.*
import org.junit.Test

class VersionCompareTest {
    @Test fun stableReleaseUpdatesSameCoreBeta() {
        assertTrue(isNewerVersion("1.2.3", "1.2.3-beta.1"))
    }

    @Test fun semverPrecedenceAndLegacyVersions() {
        val ordered = listOf("alpha", "alpha.1", "alpha.beta", "beta", "beta.2", "beta.11", "rc.1")
            .map { "1.0.0-$it" } + "1.0.0"
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue("$b > $a", isNewerVersion(b, a))
            assertFalse(isNewerVersion(a, b))
        }
        assertFalse(isNewerVersion("V1.2+build.9", "v1.2.0+other"))
        assertTrue(isNewerVersion("1.2.0.1", "1.2"))
        assertTrue(isNewerVersion("1.0.0-beta.99999999999999999999", "1.0.0-beta.99"))
        for (bad in listOf("", "1..2", "1.2.3-", "1.2.3-01", "1.2.3+", "1.2.3+a+b", "-1")) {
            assertFalse(isNewerVersion(bad, "1.0.0"))
            assertFalse(isNewerVersion("2.0.0", bad))
        }
    }
}
