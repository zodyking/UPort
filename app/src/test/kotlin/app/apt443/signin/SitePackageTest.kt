package app.apt443.signin

import app.apt443.domain.ReachPreference
import app.apt443.domain.SiteVpnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePackageTest {
    @Test
    fun roundTripJson() {
        val original = SitePackage(
            siteName = "Apt 443",
            consoleHost = "192.168.0.1",
            apiKey = "secret-key",
            osUsername = "admin",
            osPassword = "local-pass",
            networkApiKey = "net",
            trustConsoleCertificate = true,
        )
        val parsed = SitePackage.parse(original.toJsonBytes()).getOrThrow()
        assertEquals(original.siteName, parsed.siteName)
        assertEquals(original.consoleHost, parsed.consoleHost)
        assertEquals(original.apiKey, parsed.apiKey)
        assertEquals(original.osUsername, parsed.osUsername)
        assertEquals(original.osPassword, parsed.osPassword)
        assertEquals(original.networkApiKey, parsed.networkApiKey)
        assertTrue(parsed.trustConsoleCertificate)
        assertEquals(SiteVpnKind.None, parsed.vpn)
        assertEquals(SitePackage.FORMAT, "apt443-site")
        assertTrue(String(original.toJsonBytes()).contains("\"format\": \"apt443-site\""))
    }

    @Test
    fun rejectsMissingKey() {
        val json = """{"format":"apt443-site","consoleHost":"1.2.3.4"}"""
        assertTrue(SitePackage.parse(json.toByteArray()).isFailure)
    }

    @Test
    fun rejectsMissingLocalAdmin() {
        val json = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"admin"}
        """.trimIndent()
        assertTrue(SitePackage.parse(json.toByteArray()).isFailure)
    }

    @Test
    fun setupFieldsRoundTrip() {
        val pack = SitePackage.fromSetupFields(
            mapOf(
                "siteName" to "Apt 443",
                "consoleHost" to "192.168.0.1",
                "apiKey" to "key",
                "osUsername" to "admin",
                "osPassword" to "secret",
                "trustConsoleCertificate" to "on",
            ),
        ).getOrThrow()
        assertEquals("Apt 443", pack.siteName)
        assertEquals("key", pack.apiKey)
        assertTrue(pack.trustConsoleCertificate)
        assertEquals(SiteVpnKind.None, pack.vpn)
        assertTrue(pack.vpnSpecified)
        assertNull(pack.tvReach)
        assertEquals("None", pack.vpnLabel())
    }

    @Test
    fun rejectsNonHttpDownloadLink() {
        assertTrue(fetchRemoteSiteFile("file:///tmp/site.apt443").isFailure)
        assertTrue(blockedDownloadHost("127.0.0.1"))
        assertTrue(blockedDownloadHost("169.254.169.254"))
    }

    @Test
    fun namesTheFileUnvr() {
        assertTrue(SitePackage.fileNameFor("Apt 443").endsWith(".unvr"))
    }

    @Test
    fun acceptsUportSiteV1() {
        val json = """
            {"format":"uport-site","version":1,"siteName":"Home","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b"}
        """.trimIndent()
        val parsed = SitePackage.parse(json.toByteArray()).getOrThrow()
        assertEquals("Home", parsed.siteName)
        assertEquals("1.2.3.4", parsed.consoleHost)
        assertEquals("apt443-site", SitePackage.FORMAT)
    }

    @Test
    fun readsAFileSavedWithAByteOrderMark() {
        val json = "\uFEFF" + """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b"}
        """.trimIndent()
        assertEquals("k", SitePackage.parse(json.toByteArray()).getOrThrow().apiKey)
    }

    @Test
    fun uploadReadsTheSiteFileFromAPhoneForm() {
        val payload = """{"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b"}"""
        val body = buildString {
            append("--bound\r\n")
            append("Content-Disposition: form-data; name=\"action\"\r\n\r\n")
            append("upload\r\n")
            append("--bound\r\n")
            append("Content-Disposition: form-data; name=\"sitefile\"; filename=\"home.unvr\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
            append(payload)
            append("\r\n--bound--\r\n")
        }.toByteArray()
        val file = extractMultipartFile(body, "multipart/form-data; boundary=bound; charset=utf-8", "sitefile")
        assertEquals(payload, file?.toString(Charsets.UTF_8))
        val pack = SitePackage.parse(file!!).getOrThrow()
        assertEquals("k", pack.apiKey)
    }

    @Test
    fun acceptsLegacyProtectApiKeyField() {
        val json = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","protectApiKey":"legacy","osUsername":"a","osPassword":"b"}
        """.trimIndent()
        val pack = SitePackage.parse(json.toByteArray()).getOrThrow()
        assertEquals("legacy", pack.apiKey)
        assertFalse(pack.vpnSpecified)
        assertEquals(SiteVpnKind.None, pack.vpn)
    }

    @Test
    fun carriesTailscaleInTheFileAndKeepsItOutOfTheLabel() {
        val key = "tskey-auth-example"
        val original = base().copy(
            vpn = SiteVpnKind.Tailscale,
            tailscaleAuthKey = key,
            tailscaleLoginServer = "https://headscale.example.com",
            tvReach = ReachPreference.RemoteOnly,
        )
        val parsed = SitePackage.parse(original.toJsonBytes()).getOrThrow()
        assertEquals(SiteVpnKind.Tailscale, parsed.vpn)
        assertEquals(key, parsed.tailscaleAuthKey)
        assertEquals("https://headscale.example.com", parsed.tailscaleLoginServer)
        assertEquals(ReachPreference.RemoteOnly, parsed.tvReach)
        assertEquals("Tailscale", parsed.vpnLabel())
        assertFalse(parsed.vpnLabel().contains(key))
        assertTrue(String(original.toJsonBytes()).contains(key))
    }

    @Test
    fun rejectsWireGuardAndTeleport() {
        val wireguard = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b","vpn":"wireguard","wireguardConfig":"[Interface]\nPrivateKey = test\n[Peer]\n"}
        """.trimIndent()
        val teleport = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b","vpn":"teleport","teleportConfig":"[Interface]\nPrivateKey = test\n[Peer]\n"}
        """.trimIndent()
        assertTrue(SitePackage.parse(wireguard.toByteArray()).exceptionOrNull()?.message.orEmpty().contains("Tailscale only"))
        assertTrue(SitePackage.parse(teleport.toByteArray()).exceptionOrNull()?.message.orEmpty().contains("Tailscale only"))
    }

    @Test
    fun infersTailscaleWhenTheVpnFieldIsOmitted() {
        val json = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b","tailscaleAuthKey":"tskey-auth-example"}
        """.trimIndent()
        val pack = SitePackage.parse(json.toByteArray()).getOrThrow()
        assertEquals(SiteVpnKind.Tailscale, pack.vpn)
        assertTrue(pack.vpnSpecified)
        assertEquals("Tailscale", pack.vpnLabel())
    }

    @Test
    fun rejectsTailscaleWithoutAKey() {
        val json = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b","vpn":"tailscale"}
        """.trimIndent()
        assertTrue(SitePackage.parse(json.toByteArray()).isFailure)
    }

    @Test
    fun rejectsAWireGuardConfigThatIsNotATunnel() {
        val json = """
            {"format":"apt443-site","consoleHost":"1.2.3.4","apiKey":"k","osUsername":"a","osPassword":"b","vpn":"wireguard","wireguardConfig":"not a tunnel"}
        """.trimIndent()
        assertTrue(SitePackage.parse(json.toByteArray()).isFailure)
    }

    @Test
    fun explicitNoneDoesNotKeepAKey() {
        val pack = SitePackage.fromSetupFields(
            mapOf(
                "consoleHost" to "192.168.0.1",
                "apiKey" to "key",
                "osUsername" to "admin",
                "osPassword" to "secret",
                "vpn" to "none",
                "tailscaleAuthKey" to "tskey-auth-example",
                "tvReach" to "same-network",
            ),
        ).getOrThrow()
        assertEquals(SiteVpnKind.None, pack.vpn)
        assertEquals("", pack.tailscaleAuthKey)
        assertNull(pack.tvReach)
        assertFalse(pack.vpnLabel().contains("tskey"))
    }

    @Test
    fun setupFieldsStoreRemoteTailscale() {
        val pack = SitePackage.fromSetupFields(
            mapOf(
                "consoleHost" to "192.168.0.1",
                "apiKey" to "key",
                "osUsername" to "admin",
                "osPassword" to "secret",
                "vpn" to "tailscale",
                "tailscaleAuthKey" to "tskey-auth-example",
                "tvReach" to "remote",
            ),
        ).getOrThrow()
        assertEquals(SiteVpnKind.Tailscale, pack.vpn)
        assertEquals("tskey-auth-example", pack.tailscaleAuthKey)
        assertNull(pack.tvReach)
        assertEquals("Tailscale", pack.vpnLabel())
    }

    @Test
    fun rejectsAnUnknownReachValue() {
        assertTrue(SitePackage.parseTvReach("somewhere").isFailure)
        assertNull(SitePackage.parseTvReach("").getOrThrow())
        assertEquals(ReachPreference.RemoteOnly, SitePackage.parseTvReach("remote").getOrThrow())
    }

    @Test
    fun statusLinesDoNotIncludeSecrets() {
        val secret = "tskey-auth-example"
        val lines = listOf(
            VpnReachCopy.tailscale(hasKey = true, tunnelUp = false, needsPermission = false),
            VpnReachCopy.tailscale(hasKey = true, tunnelUp = false, needsPermission = true),
            VpnReachCopy.tailscale(hasKey = true, tunnelUp = true, needsPermission = false),
            VpnReachCopy.none(),
        )
        lines.forEach { line ->
            assertFalse(line.stateLabel.contains(secret))
            assertFalse(line.summary.contains(secret))
            assertFalse(line.summary.contains("PrivateKey"))
        }
        assertEquals("Not connected", VpnReachCopy.tailscale(true, false, false).stateLabel)
        assertEquals("Connected", VpnReachCopy.tailscale(true, true, false).stateLabel)
        assertFalse(VpnReachCopy.tailscale(true, false, false).summary.contains("Tailscale app"))
    }

    private fun base() = SitePackage(
        siteName = "Apt 443",
        consoleHost = "192.168.0.1",
        apiKey = "secret-key",
        osUsername = "admin",
        osPassword = "local-pass",
    )
}
