package app.apt443.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDiagnosisTest {
    @Test
    fun alertReaderMatchesTheFlattenedComponentAndTheShortForm() {
        assertTrue(alertReaderListed("app.apt443/app.apt443.alert.Apt443AccessibilityService"))
        assertTrue(
            alertReaderListed(
                "com.example/.Other:app.apt443/app.apt443.alert.Apt443AccessibilityService",
            ),
        )
        assertTrue(alertReaderListed("app.apt443/.alert.Apt443AccessibilityService"))
        assertFalse(alertReaderListed("app.apt443/app.apt443.alert.OtherService"))
        assertFalse(alertReaderListed("app.other/app.apt443.alert.Apt443AccessibilityService"))
        assertFalse(alertReaderListed(null))
        assertFalse(alertReaderListed("  "))
    }

    @Test
    fun componentStringMatchesTheServiceAlreadyUsed() {
        assertEquals(
            "app.apt443/app.apt443.alert.Apt443AccessibilityService",
            alertReaderComponent("app.apt443"),
        )
    }

    @Test
    fun fireTvFromManufacturerModelFingerprintOrAmazonFeature() {
        assertEquals(TvFamily.FireTv, detectTvFamily("Amazon", "AFTMM", "Amazon/AFTMM/AFTMM:7.1.2", "Amazon"))
        assertEquals(TvFamily.FireTv, detectTvFamily("unknown", "AFTKA", "", ""))
        assertEquals(TvFamily.FireTv, detectTvFamily("unknown", "KFTUWI", "", ""))
        assertEquals(
            TvFamily.FireTv,
            detectTvFamily("TCL", "Smart TV", "Amazon/sheldon/sheldon:9/fireos", ""),
        )
        assertEquals(
            TvFamily.FireTv,
            detectTvFamily(
                manufacturer = "Google",
                model = "Chromecast",
                fingerprint = "",
                amazonFireTvFeature = true,
            ),
        )
    }

    @Test
    fun googleTvFromFeatureManufacturerOrModel() {
        assertEquals(
            TvFamily.GoogleTv,
            detectTvFamily("Google", "Chromecast", "Google/Chromecast/Chromecast:12", "Google"),
        )
        assertEquals(
            TvFamily.GoogleTv,
            detectTvFamily("Sony", "BRAVIA", "Sony/BRAVIA:12", "", googleTvFeature = true),
        )
        assertEquals(
            TvFamily.GoogleTv,
            detectTvFamily("Hisense", "Smart TV", "Hisense/foo:12 amati", ""),
        )
    }

    @Test
    fun otherAndroidTvIsNotDescribedAsFireTv() {
        assertEquals(
            TvFamily.AndroidTv,
            detectTvFamily("NVIDIA", "SHIELD Android TV", "NVIDIA/mdarcy/mdarcy:11", "NVIDIA"),
        )
        assertEquals(
            TvFamily.AndroidTv,
            detectTvFamily("Sony", "BRAVIA 4K GT", "Sony/BRAVIA_ATV2/BRAVIA_ATV2:10", "Sony"),
        )
    }

    @Test
    fun choosesActiveEthernetOverIdleWifiAndSkipsUnusableAddresses() {
        val chosen = chooseTvAddress(
            listOf(
                IpCandidate("192.168.1.20", IpKind.Wifi, active = false),
                IpCandidate("127.0.0.1", IpKind.Wifi, active = true),
                IpCandidate("169.254.8.1", IpKind.Ethernet, active = true),
                IpCandidate("10.0.0.8", IpKind.Ethernet, active = true),
            ),
        )
        assertEquals("10.0.0.8", chosen?.address)
        assertEquals(IpKind.Ethernet, chosen?.kind)
    }

    @Test
    fun idleWifiBeatsIdleEthernetAndPrivateBeatsPublicOnTheSameKind() {
        assertEquals(
            "192.168.1.20",
            chooseTvAddress(
                listOf(
                    IpCandidate("10.0.0.8", IpKind.Ethernet, active = false),
                    IpCandidate("192.168.1.20", IpKind.Wifi, active = false),
                ),
            )?.address,
        )
        assertEquals(
            "192.168.0.5",
            chooseTvAddress(
                listOf(
                    IpCandidate("8.8.8.8", IpKind.Wifi, active = false),
                    IpCandidate("192.168.0.5", IpKind.Wifi, active = false),
                ),
            )?.address,
        )
    }

    @Test
    fun activeAddressWinsEvenWhenAnotherAdapterIsPrivate() {
        assertEquals(
            "203.0.113.10",
            chooseTvAddress(
                listOf(
                    IpCandidate("192.168.0.5", IpKind.Wifi, active = false),
                    IpCandidate("203.0.113.10", IpKind.Ethernet, active = true),
                ),
            )?.address,
        )
    }

    @Test
    fun noUsableAddress() {
        assertNull(chooseTvAddress(emptyList()))
        assertNull(chooseTvAddress(listOf(IpCandidate("127.0.0.1", IpKind.Wifi, active = true))))
    }

    @Test
    fun commandsUseThisTvsAddressPortAndTheSettingsOps() {
        val commands = diagnosisCommands("app.apt443", "10.1.2.3")
        assertEquals("adb connect 10.1.2.3:5555", commands[0])
        assertEquals("adb shell appops set app.apt443 SYSTEM_ALERT_WINDOW allow", commands[1])
        assertEquals(
            "adb shell settings put secure enabled_accessibility_services " +
                "app.apt443/app.apt443.alert.Apt443AccessibilityService",
            commands[2],
        )
        assertEquals("adb shell settings put secure accessibility_enabled 1", commands[3])
        assertFalse(commands.any { it.contains("192.168.3.162") })
    }

    @Test
    fun missingAddressUsesPlaceholder() {
        val report = diagnose(blocked(ip = null))
        assertEquals("adb connect <tv-ip>:5555", report.commands[0])
        assertTrue(report.lines.any { it.text == "This TV has no network address yet." })
        assertTrue(report.whatToDo.any { it.contains("<tv-ip>") })
    }

    @Test
    fun readerOffIsPlainLanguage() {
        val report = diagnose(
            blocked(
                service = TriState.No,
                accessibility = TriState.Yes,
            ),
        )
        assertTrue(
            report.lines.any {
                it.text == "The alert reader is off, so popups only show inside this app." &&
                    it.tone == DiagnosisTone.NeedsAction
            },
        )
        assertFalse(report.canDrawOverApps)
    }

    @Test
    fun overlayGrantedSaysAlertsCanDraw() {
        val report = diagnose(blocked(draw = TriState.Yes, service = TriState.No, accessibility = TriState.No))
        assertTrue(report.lines.any { it.text == "Alerts can draw over other apps." && it.tone == DiagnosisTone.Ok })
        assertTrue(report.canDrawOverApps)
        assertEquals(listOf("Nothing to change. Alerts can draw over other apps."), report.whatToDo)
    }

    @Test
    fun fireTvPointsAtAdbAndDoesNotOfferTheSystemScreen() {
        val report = diagnose(blocked(family = TvFamily.FireTv, adb = TriState.No))
        val steps = report.whatToDo.joinToString(" ")
        assertTrue(steps.contains("ADB is the path that works"))
        assertTrue(report.lines.any { it.text == "ADB debugging is off, so this TV cannot receive the command." })
        assertFalse(steps.contains("may work"))
        assertFalse(steps.contains("usually cannot") && steps.contains("Google TV"))
        assertNull(report.systemAction)
        assertEquals("Fire TV", report.familyLabel)
    }

    @Test
    fun googleTvKeepsTheSystemScreenAndAdbFallback() {
        val report = diagnose(blocked(family = TvFamily.GoogleTv, adb = TriState.No))
        val steps = report.whatToDo.joinToString(" ")
        assertTrue(steps.contains("system permission screen may work"))
        assertTrue(steps.contains("ADB"))
        assertFalse(steps.contains("usually cannot"))
        assertFalse(steps.contains("only way"))
        assertEquals(DiagnosisAction.OpenOverlaySettings, report.systemAction)
        assertEquals("Google TV", report.familyLabel)
    }

    @Test
    fun androidTvIsNotToldTheFireTvLimitation() {
        val report = diagnose(blocked(family = TvFamily.AndroidTv))
        val steps = report.whatToDo.joinToString(" ")
        assertTrue(steps.contains("On this Android TV"))
        assertTrue(steps.contains("may work"))
        assertFalse(steps.contains("usually cannot"))
        assertEquals(DiagnosisAction.OpenOverlaySettings, report.systemAction)
    }

    @Test
    fun unreadableChecksStillShowCommands() {
        val report = diagnose(
            blocked(
                draw = TriState.Unreadable,
                ops = TriState.Unreadable,
                service = TriState.Unreadable,
                accessibility = TriState.Unreadable,
                adb = TriState.Unreadable,
                ip = null,
            ),
        )
        assertEquals(4, report.commands.size)
        assertTrue(report.lines.any { it.text.contains("cannot read whether it may draw over other apps") })
        assertTrue(report.lines.any { it.text.contains("cannot read whether the alert reader is on") })
        assertTrue(report.lines.any { it.text.contains("cannot read whether ADB debugging is on") })
        assertTrue(report.whatToDo.any { it.contains("commands below still grant it") })
    }

    @Test
    fun interfaceKindIgnoresLoopbackAndVpn() {
        assertEquals(IpKind.Wifi, interfaceKind("wlan0"))
        assertEquals(IpKind.Ethernet, interfaceKind("eth0"))
        assertNull(interfaceKind("lo"))
        assertNull(interfaceKind("tun0"))
    }

    private fun blocked(
        family: TvFamily = TvFamily.FireTv,
        draw: TriState = TriState.No,
        ops: TriState = TriState.No,
        service: TriState = TriState.No,
        accessibility: TriState = TriState.No,
        adb: TriState = TriState.Yes,
        ip: String? = "192.168.1.20",
        ipSource: String? = "Wi-Fi",
    ) = DiagnosisFacts(
        family = family,
        drawOverlays = draw,
        appOpsAllow = ops,
        serviceEnabled = service,
        accessibilityOn = accessibility,
        adbOn = adb,
        ip = ip,
        ipSource = ipSource,
        packageName = "app.apt443",
    )
}
