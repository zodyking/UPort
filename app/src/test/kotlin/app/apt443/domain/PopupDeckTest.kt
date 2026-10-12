package app.apt443.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupDeckTest {
    @Test
    fun sameCameraKeepsItsFadeClock() {
        val first = deck().admit(alert("a", "cam"), nowMs = 1_000, maxOpen = 2)
        val next = first.admit(alert("b", "cam"), nowMs = 8_000, maxOpen = 2)
        assertEquals(1, next.slots.size)
        assertEquals("b", next.slots.single().alert.id)
        assertEquals(1_000, next.slots.single().openedAtMs)
        assertFalse(next.slots.single().engaged)
    }

    @Test
    fun secondCameraStacksUntilTheCap() {
        val deck = deck()
            .admit(alert("a", "cam-a"), 0, 2)
            .admit(alert("b", "cam-b"), 1_000, 2)
        assertEquals(listOf("a", "b"), deck.slots.map { it.alert.id })
        assertEquals(0, deck.slots[0].openedAtMs)
        assertEquals(1_000, deck.slots[1].openedAtMs)
    }

    @Test
    fun fullDeckKnocksOffTheOldestUntouchedCard() {
        val deck = deck()
            .admit(alert("a", "cam-a"), 0, 2)
            .admit(alert("b", "cam-b"), 1_000, 2)
            .admit(alert("c", "cam-c"), 2_000, 2)
        assertEquals(listOf("b", "c"), deck.slots.map { it.alert.id })
    }

    @Test
    fun engagedCardStaysUntilAStrongerEventNeedsTheSlot() {
        val engaged = deck()
            .admit(alert("a", "cam-a"), 0, 2)
            .engage("a")
            .admit(alert("b", "cam-b"), 1_000, 2)
        val motion = engaged.admit(alert("c", "cam-c", AlertKind.Motion), 2_000, 2)
        assertEquals(listOf("a", "c"), motion.slots.map { it.alert.id })
        assertTrue(motion.slots.first().engaged)

        val doorbells = deck()
            .admit(alert("d1", "door-1", AlertKind.Doorbell), 0, 2)
            .admit(alert("d2", "door-2", AlertKind.Doorbell), 1_000, 2)
            .engage("d1")
            .engage("d2")
        val dropped = doorbells.admit(alert("m", "cam", AlertKind.Motion), 3_000, 2)
        assertEquals(listOf("d1", "d2"), dropped.slots.map { it.alert.id })

        val knocked = doorbells.admit(alert("ring", "door-3", AlertKind.Doorbell), 4_000, 2)
        assertEquals(listOf("d2", "ring"), knocked.slots.map { it.alert.id })
    }

    @Test
    fun expireRemovesOnlyUntouchedCards() {
        val deck = deck()
            .admit(alert("a", "cam-a"), 0, 2)
            .admit(alert("b", "cam-b"), 4_000, 2)
            .engage("b")
        val left = deck.expire(nowMs = 10_000, fadeMs = 10_000)
        assertEquals(listOf("b"), left.slots.map { it.alert.id })
    }

    @Test
    fun twoPopupsFitTheFireTvScreen() {
        val screenW = 960f
        val screenH = 540f
        listOf(1, 2, 3).forEach { panes ->
            PopupSize.entries.forEach { size ->
                val width = PopupLayout.cardWidthDp(screenW, screenH, panes, compact = false, size = size)
                val group = PopupLayout.groupWidthDp(width, panes)
                val height = PopupLayout.cardHeightDp(width, compact = false)
                val open = PopupLayout.cardHeightDp(width, compact = false, actionsOpen = true)
                assertTrue("width $width for $panes $size", group <= screenW - 96f)
                assertTrue("height $height for $panes $size", height <= screenH - 54f)
                assertTrue("open $open for $panes $size", open <= screenH - 54f)
                assertEquals(PopupLayout.ACTION_ROW_DP, open - height, 0.01f)
                assertEquals(width * 9f / 16f + PopupLayout.CARD_BAR_DP, height, 0.01f)
            }
        }
        val small = PopupLayout.cardWidthDp(screenW, screenH, 1, compact = false, size = PopupSize.Small)
        val large = PopupLayout.cardWidthDp(screenW, screenH, 1, compact = false, size = PopupSize.Large)
        assertTrue(small < 200f)
        assertTrue(small < large)
    }

    @Test
    fun minimalCardsShareOneHeightPerSize() {
        PopupSize.entries.forEach { size ->
            val height = PopupLayout.minimalHeightDp(size, 960f, 540f)
            val row = PopupLayout.minimalRowDp(listOf(16f / 9f, 4f / 3f, 9f / 16f), size, 960f, 540f)
            row.forEach { assertEquals("height for $size", height, it.heightDp, 0.01f) }
        }
        val small = PopupLayout.minimalHeightDp(PopupSize.Small, 960f, 540f)
        val medium = PopupLayout.minimalHeightDp(PopupSize.Medium, 960f, 540f)
        val large = PopupLayout.minimalHeightDp(PopupSize.Large, 960f, 540f)
        assertTrue(small < medium && medium < large)
        assertEquals(minOf(960f * 0.18f, 190f) * 9f / 16f, small, 0.01f)
    }

    @Test
    fun minimalWidthFollowsTheStreamWithinLimits() {
        val height = 100f
        assertEquals(100f * 16f / 9f, PopupLayout.minimalWidthDp(height, 16f / 9f), 0.01f)
        assertEquals(75f, PopupLayout.minimalWidthDp(height, 3f / 4f), 0.01f)
        assertEquals(60f, PopupLayout.minimalWidthDp(height, 0.3f), 0.01f)
        assertEquals(220f, PopupLayout.minimalWidthDp(height, 3.5f), 0.01f)
        assertEquals(100f * 16f / 9f, PopupLayout.minimalWidthDp(height, Float.NaN), 0.01f)
        assertEquals(100f * 16f / 9f, PopupLayout.minimalWidthDp(height, 0f), 0.01f)
    }

    @Test
    fun minimalRowStaysInsideTheSafeArea() {
        val screenW = 960f
        val safe = screenW - PopupLayout.OVERSCAN_X_DP
        PopupSize.entries.forEach { size ->
            listOf(0f, 180f).forEach { prompt ->
                val extra = if (prompt > 0f) prompt + PopupLayout.GAP_DP else 0f
                val row = PopupLayout.minimalRowDp(List(3) { 3.5f }, size, screenW, 540f, extra)
                val total = row.sumOf { it.widthDp.toDouble() }.toFloat() + PopupLayout.GAP_DP * 2 + extra
                assertTrue("row $total for $size prompt $prompt", total <= safe + 0.5f)
                val first = row.first().heightDp
                row.forEach { assertEquals(first, it.heightDp, 0.01f) }
            }
        }
        assertTrue(PopupLayout.minimalRowDp(emptyList(), PopupSize.Small).isEmpty())
    }

    @Test
    fun netflixAsksOnceUntilTheyLeave() {
        val asked = QuietSession().receive("com.netflix.ninja", "", "app.apt443", silenced = false)
        assertEquals("Netflix", asked.prompt?.label)
        val again = asked.receive("com.netflix.ninja", "com.netflix.ninja.Main", "app.apt443", false)
        assertEquals(asked.prompt, again.prompt)
        val declined = again.declined()
        assertNull(declined.prompt)
        val held = declined.receive("com.netflix.ninja", "", "app.apt443", false)
        assertNull(held.prompt)
        val home = held.receive("com.amazon.tv.leanbacklauncher", "", "app.apt443", false)
        val next = home.receive("com.netflix.ninja", "", "app.apt443", false)
        assertEquals("Netflix", next.prompt?.label)
    }

    @Test
    fun unansweredPromptStaysUntilTheyAnswer() {
        val asked = QuietSession().receive("com.netflix.ninja", "", "app.apt443", false, 1_000)
        val flashed = asked.receive("com.android.systemui", "", "app.apt443", false, 2_000)
        assertEquals(asked.prompt, flashed.prompt)
        val home = flashed.receive("com.amazon.tv.leanbacklauncher", "", "app.apt443", false, 3_000)
        assertEquals(asked.prompt, home.prompt)
        assertFalse(home.releaseSilence)
        val other = home.receive("com.google.android.youtube.tv", "", "app.apt443", false, 4_000)
        assertEquals("Netflix", other.prompt?.label)
        assertFalse(other.prompt!!.recheck)
    }

    @Test
    fun hdmiAsksAndOurWindowDoesNotClearIt() {
        val hdmi = QuietSession().receive("com.amazon.tv.input", "HdmiActivity", "app.apt443", false)
        assertEquals("HDMI", hdmi.prompt?.label)
        val still = hdmi.receive("app.apt443", "AlertWindow", "app.apt443", false)
        assertEquals(hdmi.prompt, still.prompt)
        val quiet = still.receive("com.amazon.tv.input", "", "app.apt443", silenced = true)
        assertNull(quiet.prompt)
    }

    @Test
    fun launcherAndUnknownAppsAreNotAWatchSession() {
        assertNull(watchContext("com.amazon.tv.leanbacklauncher"))
        assertNull(watchContext("com.amazon.tv.settings"))
        assertNull(watchContext("com.example.game"))
        assertEquals(WatchKind.LiveTv, watchContext("com.amazon.tv.livetv")?.kind)
        assertEquals("YouTube", watchContext("com.google.android.youtube.tv")?.label)
        val home = QuietSession().receive("com.amazon.tv.leanbacklauncher", "", "app.apt443", silenced = true)
        assertNull(home.prompt)
        assertFalse(home.releaseSilence)
    }

    @Test
    fun yesStaysQuietUntilThatAppLeaves() {
        val start = 5_000L
        val yes = QuietSession()
            .receive("com.netflix.ninja", "", "app.apt443", false, start)
            .accepted(start)
        assertNull(yes.prompt)
        assertEquals("com.netflix.ninja", yes.silencedPackage)
        assertEquals(start + QUIET_HOLD_MS, yes.recheckAtMs)
        assertFalse(yes.releaseSilence)

        val stay = yes.receive("com.netflix.ninja", "Main", "app.apt443", true, start + 60_000)
        assertNull(stay.prompt)
        assertFalse(stay.releaseSilence)

        val overlay = stay.receive("app.apt443", "AlertWindow", "app.apt443", true, start + 61_000)
        assertEquals(stay.silencedPackage, overlay.silencedPackage)
        assertFalse(overlay.releaseSilence)

        val home = overlay.receive("com.amazon.tv.leanbacklauncher", "", "app.apt443", true, start + 62_000)
        assertNull(home.prompt)
        assertNull(home.silencedPackage)
        assertTrue(home.releaseSilence)

        val back = home.copy(releaseSilence = false)
            .receive("com.netflix.ninja", "", "app.apt443", false, start + 70_000)
        assertEquals("Netflix", back.prompt?.label)
        assertFalse(back.prompt!!.recheck)
    }

    @Test
    fun recheckKeepsSilenceOnYesAndEndsItOnNo() {
        val start = 10_000L
        val quiet = QuietSession()
            .receive("com.google.android.youtube.tv", "", "app.apt443", false, start)
            .accepted(start)
        assertNull(quiet.due(start + QUIET_HOLD_MS - 1).prompt)

        val due = quiet.receive(
            "com.google.android.youtube.tv",
            "",
            "app.apt443",
            true,
            start + QUIET_HOLD_MS,
        )
        assertEquals("YouTube", due.prompt?.label)
        assertTrue(due.prompt!!.recheck)
        assertTrue(due.extendSilence)
        assertFalse(due.releaseSilence)
        val byClock = quiet.due(start + QUIET_HOLD_MS)
        assertEquals(due.prompt, byClock.prompt)
        assertTrue(byClock.extendSilence)

        val leftUp = due.receive("app.apt443", "", "app.apt443", true, start + QUIET_HOLD_MS + 5)
        assertEquals(due.prompt, leftUp.prompt)
        assertEquals("com.google.android.youtube.tv", leftUp.silencedPackage)

        val again = due.accepted(start + QUIET_HOLD_MS + 2_000)
        assertNull(again.prompt)
        assertEquals(start + QUIET_HOLD_MS + 2_000 + QUIET_HOLD_MS, again.recheckAtMs)

        val no = due.declined()
        assertTrue(no.releaseSilence)
        assertNull(no.silencedPackage)
        assertEquals("com.google.android.youtube.tv", no.declinedPackage)
        val held = no.copy(releaseSilence = false).receive(
            "com.google.android.youtube.tv",
            "",
            "app.apt443",
            false,
            start + QUIET_HOLD_MS + 3_000,
        )
        assertNull(held.prompt)
    }

    @Test
    fun anotherAppEndsSilenceAndAsksAgain() {
        val start = 1_000L
        val netflix = QuietSession()
            .receive("com.netflix.ninja", "", "app.apt443", false, start)
            .accepted(start)
        val prime = netflix.receive("com.amazon.avod.thirdpartyclient", "", "app.apt443", true, start + 5_000)
        assertEquals("Prime Video", prime.prompt?.label)
        assertFalse(prime.prompt!!.recheck)
        assertTrue(prime.releaseSilence)
        assertNull(prime.silencedPackage)
    }

    private fun deck() = PopupDeck()

    private fun alert(id: String, camera: String, kind: AlertKind = AlertKind.Motion) = AlertInset(
        id = id,
        title = id,
        subtitle = "",
        cameraId = camera,
        kind = kind,
    )
}
