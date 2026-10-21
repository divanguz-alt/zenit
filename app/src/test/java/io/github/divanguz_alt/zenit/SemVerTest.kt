package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.update.GhAsset
import io.github.divanguz_alt.zenit.update.GhRelease
import io.github.divanguz_alt.zenit.update.SemVer
import io.github.divanguz_alt.zenit.update.UpdateCheck
import io.github.divanguz_alt.zenit.update.UpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(s: String) = SemVer.parse(s)!!

    @Test fun parses() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 0), v("1.2"))
        assertEquals(listOf("rc", "1"), v("2.0.0-rc.1+build.5").preRelease)
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("1.x.0"))
    }

    @Test fun ordering() {
        val sorted = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
            .map(::v)
        assertEquals(sorted, sorted.shuffled(java.util.Random(4)).sorted())
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("v1.0.0") == v("1.0.0"))
    }

    private fun release(tag: String, pre: Boolean = false) = GhRelease(
        tag = tag, body = "## Fixed\n- **Crash** on start\n- See [notes](https://x)", prerelease = pre,
        assets = listOf(GhAsset("zenit-$tag.apk", "https://example/apk", 10), GhAsset("zenit-$tag.apk.sha256", "https://example/sha", 1)),
    )

    @Test fun updateEvaluation() {
        val current = v("1.0.0")
        val newer = UpdateRepository.evaluate(release("v1.0.1"), current)
        assertTrue(newer is UpdateCheck.Available)
        newer as UpdateCheck.Available
        assertEquals("v1.0.1", newer.info.tag)
        assertEquals("zenit-v1.0.1.apk.sha256", newer.info.checksum?.name)
        assertEquals("Fixed\n• Crash on start\n• See notes", newer.info.notes)
        assertEquals(UpdateCheck.UpToDate, UpdateRepository.evaluate(release("v1.0.0"), current))
        assertEquals(UpdateCheck.UpToDate, UpdateRepository.evaluate(release("v0.9.0"), current))
        assertEquals(UpdateCheck.UpToDate, UpdateRepository.evaluate(release("v2.0.0", pre = true), current))
    }
}
