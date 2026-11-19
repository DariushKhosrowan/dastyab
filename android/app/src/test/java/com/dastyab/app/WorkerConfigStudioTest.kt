package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hermetic JVM unit tests for [WorkerConfigStudio].
 *
 * Uses pure-Kotlin JSON and file storage so tests run without Robolectric or
 * Android runtime stubs.
 */
class WorkerConfigStudioTest {

    @Test
    fun parseVlessDecodesPathAndPreservesDefaults() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@my-worker.sub.workers.dev:443" +
            "?encryption=none&security=tls&type=ws&sni=my-worker.sub.workers.dev" +
            "&host=my-worker.sub.workers.dev&path=%2F&fp=chrome#WorkerEdge"
        val cfg = WorkerConfigStudio.parseVless(uri)
        assertNotNull(cfg)
        cfg!!
        assertEquals("11111111-2222-3333-4444-555555555555", cfg.uuid)
        assertEquals("my-worker.sub.workers.dev", cfg.address)
        assertEquals(443, cfg.port)
        assertEquals("/", cfg.canonicalPath())
        assertEquals("my-worker.sub.workers.dev", cfg.effectiveSni())
        assertEquals("my-worker.sub.workers.dev", cfg.effectiveHost())
        assertEquals("WorkerEdge", cfg.name)

        // Rendered link keeps plain '/' in path so v2rayN/v2rayNG never rejects %2F
        val rendered = cfg.toVlessUri()
        assertTrue("Expected plain path=/ in $rendered", rendered.contains("path=/&"))
        assertFalse("Must not contain encoded %2F in path", rendered.contains("path=%2F"))
    }

    @Test
    fun applyCleanIpSwapsAddressAndPortWhilePreservingWorkerSniAndHost() {
        val base = WorkerConfigStudio.parseVless(
            "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@edge.example.workers.dev:443" +
                "?encryption=none&security=tls&type=ws&sni=edge.example.workers.dev" +
                "&host=edge.example.workers.dev&path=/ws&fp=firefox#Orig"
        )!!

        val patched = base.applyCleanIp("104.17.1.22:2053", newName = "clean-cf")
        assertNotNull(patched)
        patched!!
        assertEquals("104.17.1.22", patched.address)
        assertEquals(2053, patched.port)
        assertEquals("edge.example.workers.dev", patched.sni)
        assertEquals("edge.example.workers.dev", patched.host)
        assertEquals("/ws", patched.canonicalPath())
        assertEquals("firefox", patched.fp)
        assertEquals("clean-cf", patched.name)
    }

    @Test
    fun applyCleanIpRejectsInvalidIpv4OrPorts() {
        val base = WorkerConfigStudio.fromExistingWorker("w.sub.workers.dev", uuidInput = "uuid-1")!!
        assertNull(base.applyCleanIp("not-an-ip"))
        assertNull(base.applyCleanIp("104.017.1.2")) // leading zero octet
        assertNull(base.applyCleanIp("256.1.1.1"))
        assertNull(base.applyCleanIp("104.17.1.2:0"))
        assertNull(base.applyCleanIp("104.17.1.2:70000"))
        assertNotNull(base.applyCleanIp("104.17.1.2"))
    }

    @Test
    fun fromExistingWorkerNormalizesSchemeAndAppliesOptionalCleanIp() {
        val cfg = WorkerConfigStudio.fromExistingWorker(
            workerHostOrUrl = "https://cool-worker.myacct.workers.dev/some/path?x=1",
            uuidInput = "",
            wsPath = "vless-ws",
            port = 8443,
            cleanIpEndpoint = "172.64.10.20",
            label = "MyCustomWorker"
        )
        assertNotNull(cfg)
        cfg!!
        assertTrue(cfg.uuid.isNotBlank())
        assertEquals("172.64.10.20", cfg.address)
        assertEquals(8443, cfg.port)
        assertEquals("cool-worker.myacct.workers.dev", cfg.sni)
        assertEquals("cool-worker.myacct.workers.dev", cfg.host)
        assertEquals("/vless-ws", cfg.canonicalPath())
        assertEquals("MyCustomWorker", cfg.name)
    }

    @Test
    fun exportFormatsProduceValidJsonAndClashYamlWithCleanIpAndWorkerSni() {
        val cfg = WorkerConfigStudio.fromExistingWorker(
            workerHostOrUrl = "relay.myacct.workers.dev",
            uuidInput = "12345678-1234-1234-1234-123456789abc",
            wsPath = "/",
            port = 443,
            cleanIpEndpoint = "104.18.2.30:2096",
            label = "RelayClean"
        )!!

        // v2rayN JSON
        val v2rayJson = cfg.renderV2rayNJson()
        @Suppress("UNCHECKED_CAST")
        val v2 = WorkerConfigStudio.parseJson(v2rayJson) as Map<String, Any?>
        assertEquals("RelayClean", v2["remarks"])
        @Suppress("UNCHECKED_CAST")
        val outbounds = v2["outbounds"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val stream = outbounds[0]["streamSettings"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val tls = stream["tlsSettings"] as Map<String, Any?>
        assertEquals("relay.myacct.workers.dev", tls["serverName"])
        @Suppress("UNCHECKED_CAST")
        val ws = stream["wsSettings"] as Map<String, Any?>
        assertEquals("/", ws["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = ws["headers"] as Map<String, Any?>
        assertEquals("relay.myacct.workers.dev", headers["Host"])

        // sing-box JSON
        val sbJson = cfg.renderSingBoxJson()
        @Suppress("UNCHECKED_CAST")
        val sb = WorkerConfigStudio.parseJson(sbJson) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sbOut = (sb["outbounds"] as List<Map<String, Any?>>)[0]
        assertEquals("104.18.2.30", sbOut["server"])
        assertEquals(2096L, (sbOut["server_port"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val sbTls = sbOut["tls"] as Map<String, Any?>
        assertEquals("relay.myacct.workers.dev", sbTls["server_name"])

        // Clash YAML
        val clash = cfg.renderClashYaml()
        assertTrue(clash.contains("server: 104.18.2.30"))
        assertTrue(clash.contains("port: 2096"))
        assertTrue(clash.contains("servername: relay.myacct.workers.dev"))
        assertTrue(clash.contains("Host: relay.myacct.workers.dev"))
        assertTrue(clash.contains("path: /"))
    }

    @Test
    fun extractCleanIpsFromTextDeduplicatesAndValidates() {
        val text = "104.17.1.1, 172.64.2.3:8443; bad.ip\n104.17.1.1 198.41.192.7:443 999.1.1.1"
        val ips = WorkerConfigStudio.extractCleanIpsFromText(text)
        assertEquals(listOf("104.17.1.1", "172.64.2.3:8443", "198.41.192.7:443"), ips)
    }

    @Test
    fun cliArgBuildersRespectDefaultVsCustomProfiles() {
        assertEquals(
            listOf("deploy", "--preset", "gaming", "--account", "acc1", "--worker", "w1"),
            WorkerConfigStudio.buildDeployArgs("default", "acc1", "w1", WorkerConfigStudio.WorkerPreset.GAMING)
        )
        assertEquals(
            listOf("--profile", "work", "find", "3", "--seed", "104.17.1.1"),
            WorkerConfigStudio.buildFindArgs("work", count = 3, seedIps = listOf("104.17.1.1:443", "invalid"))
        )
        assertNull(WorkerConfigStudio.buildTokenRecoverArgs("default", "lbl", "   "))
        assertEquals(
            listOf("--profile", "work", "token-recover", "cf", "secret123"),
            WorkerConfigStudio.buildTokenRecoverArgs("work", "cf", "secret123")
        )
    }

    @Test
    fun profileStoreRoundtripPersistsTokensCleanIpsAndHistory() {
        val tmpHome: File = Files.createTempDirectory("dastyab-studio-test").toFile()
        try {
            assertTrue(WorkerConfigStudio.saveTokenToProfile(tmpHome, "work", "main-tok", "cf_token_1234567890"))
            val merged = WorkerConfigStudio.appendCleanIps(
                tmpHome,
                "work",
                listOf("104.17.5.5", "172.64.9.9:2053", "invalid", "104.17.5.5")
            )
            assertEquals(listOf("104.17.5.5", "172.64.9.9:2053"), merged)

            val afterRemove = WorkerConfigStudio.removeCleanIp(tmpHome, "work", "104.17.5.5")
            assertEquals(listOf("172.64.9.9:2053"), afterRemove)

            val cfg = WorkerConfigStudio.fromExistingWorker(
                workerHostOrUrl = "test.acct.workers.dev",
                uuidInput = "aaaaaaaa-0000-0000-0000-bbbbbbbbbbbb",
                cleanIpEndpoint = "172.64.9.9:2053",
                label = "TestClean"
            )!!
            WorkerConfigStudio.appendHistoryRecord(tmpHome, "work", cfg, isCleanIp = true, note = "unit-test")

            val profiles = WorkerConfigStudio.listProfiles(tmpHome)
            assertTrue(profiles.any { it.id == "default" })
            assertTrue(profiles.any { it.id == "work" })

            val summary = WorkerConfigStudio.loadProfileSummary(tmpHome, "work")
            assertEquals("work", summary.profileId)
            assertEquals("main-tok", summary.activeTokenLabel)
            assertEquals(1, summary.tokens.size)
            assertTrue(summary.tokens[0].isActive)
            assertEquals(listOf("172.64.9.9:2053"), summary.cleanIps)
            assertEquals(1, summary.history.size)
            assertEquals("TestClean", summary.history[0].name)
            assertTrue(summary.history[0].isCleanIp)
            assertEquals(cfg.toVlessUri(), summary.lastDeployedBaseLink)
        } finally {
            tmpHome.deleteRecursively()
        }
    }
}
