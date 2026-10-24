package com.nuvio.app.features.livetv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedLiveTvSourceStoreTest {
    private val root: Path = Files.createTempDirectory("nuvio-livetv-store")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun xtreamSource(password: String = "s3cret&value") = LiveTvSource(
        id = "panel",
        name = "Panel",
        kind = LiveTvSourceKind.XTREAM,
        location = "https://panel.example.com/base",
        username = "user name",
        password = password,
        epgUrl = "https://panel.example.com/guide.xml",
    )

    @Test
    fun roundTripsEncryptedConfigurationWithoutPlaintextOnDisk() {
        val store = EncryptedLiveTvSourceStore(1, root)
        store.save(listOf(xtreamSource()))

        val restored = EncryptedLiveTvSourceStore(1, root).load()

        assertEquals(listOf(xtreamSource()), restored)
        val ciphertext = Files.readAllBytes(root.resolve("profile-1/sources.bin"))
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("s3cret&value"))
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("panel.example.com"))
    }

    @Test
    fun keyIsRandomPerProfileAndRejectsAnotherProfilesCiphertext() {
        EncryptedLiveTvSourceStore(1, root).save(listOf(xtreamSource()))
        val other = EncryptedLiveTvSourceStore(2, root)
        assertTrue(other.load().isEmpty())
        other.save(listOf(xtreamSource()))

        val keyOne = Files.readAllBytes(root.resolve("profile-1/sources.key"))
        val keyTwo = Files.readAllBytes(root.resolve("profile-2/sources.key"))
        assertFalse(keyOne.contentEquals(keyTwo))
        assertEquals(32, keyOne.size)

        Files.copy(
            root.resolve("profile-1/sources.bin"),
            root.resolve("profile-2/sources.bin"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        assertFailsWith<LiveTvStorageException> { EncryptedLiveTvSourceStore(2, root).load() }
    }

    @Test
    fun corruptionIsReportedAndNeverOverwritten() {
        val store = EncryptedLiveTvSourceStore(1, root)
        store.save(listOf(xtreamSource()))
        val ciphertextFile = root.resolve("profile-1/sources.bin")
        val corrupted = Files.readAllBytes(ciphertextFile)
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 0x01).toByte()
        Files.write(ciphertextFile, corrupted)

        assertFailsWith<LiveTvStorageException> { EncryptedLiveTvSourceStore(1, root).load() }
        assertContentEquals(corrupted, Files.readAllBytes(ciphertextFile))
    }

    @Test
    fun missingKeyOverCiphertextIsNeverRegenerated() {
        EncryptedLiveTvSourceStore(1, root).save(listOf(xtreamSource()))
        val keyFile = root.resolve("profile-1/sources.key")
        assertTrue(Files.deleteIfExists(keyFile))

        assertFailsWith<LiveTvStorageException> { EncryptedLiveTvSourceStore(1, root).load() }
        assertFalse(Files.exists(keyFile), "A lost key must never be replaced over existing ciphertext")
        assertFailsWith<LiveTvStorageException> {
            EncryptedLiveTvSourceStore(1, root).save(listOf(xtreamSource("different")))
        }
        assertFalse(Files.exists(keyFile))
    }

    @Test
    fun oversizedConfigurationIsRejectedRatherThanTruncated() {
        val store = EncryptedLiveTvSourceStore(1, root)
        val tooMany = (0..LiveTvMaxSources).map { index -> xtreamSource().copy(id = "panel-$index") }
        assertFailsWith<LiveTvStorageException> { store.save(tooMany) }
        assertFailsWith<LiveTvStorageException> {
            store.save(listOf(xtreamSource().copy(password = "p".repeat(513))))
        }
        assertFalse(Files.exists(root.resolve("profile-1/sources.bin")))
    }

    @Test
    fun invalidRecordIsRejectedBeforeCiphertextChanges() {
        val store = EncryptedLiveTvSourceStore(1, root)
        store.save(listOf(xtreamSource()))
        val ciphertextFile = root.resolve("profile-1/sources.bin")
        val before = Files.readAllBytes(ciphertextFile)

        val invalid = listOf(
            xtreamSource().copy(location = "   "),
            xtreamSource().copy(name = ""),
            xtreamSource().copy(id = "x".repeat(129)),
            xtreamSource().copy(password = "p".repeat(513)),
            xtreamSource(),
            xtreamSource(),
        )
        assertFailsWith<LiveTvStorageException> { store.save(invalid) }
        assertFailsWith<LiveTvStorageException> { store.save(listOf(xtreamSource().copy(location = " "))) }

        assertContentEquals(before, Files.readAllBytes(ciphertextFile), "Rejected saves must not rewrite the payload")
        assertEquals("s3cret&value", EncryptedLiveTvSourceStore(1, root).load().single().password)
    }

    @Test
    fun profileDirectoriesAndFilesAreOwnerOnly() {
        val store = EncryptedLiveTvSourceStore(7, root)
        store.save(listOf(xtreamSource()))

        val view = Files.getFileAttributeView(
            root.resolve("profile-7"),
            PosixFileAttributeView::class.java,
        ) ?: return
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            view.readAttributes().permissions(),
        )
        listOf("sources.bin", "sources.key").forEach { name ->
            val file = root.resolve("profile-7/$name")
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
            )
        }
    }

    @Test
    fun symlinkedCiphertextIsRefused() {
        val store = EncryptedLiveTvSourceStore(1, root)
        store.save(listOf(xtreamSource()))
        val real = root.resolve("outside.bin")
        Files.move(root.resolve("profile-1/sources.bin"), real)
        val link = root.resolve("profile-1/sources.bin")
        runCatching { Files.createSymbolicLink(link, real) }.getOrElse { return }

        assertFailsWith<LiveTvStorageException> { EncryptedLiveTvSourceStore(1, root).load() }
        assertFailsWith<LiveTvStorageException> { store.save(listOf(xtreamSource())) }
    }

    @Test
    fun symlinkedRootIsRefused() {
        val realRoot = Files.createTempDirectory("nuvio-livetv-real")
        val linkedRoot = root.resolve("linked")
        runCatching { Files.createSymbolicLink(linkedRoot, realRoot) }.getOrElse { return }
        try {
            val store = EncryptedLiveTvSourceStore(1, linkedRoot)
            assertFailsWith<LiveTvStorageException> { store.save(listOf(xtreamSource())) }
        } finally {
            realRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun emptyStoreLoadsEmptyAndClearRemovesSecrets() {
        val store = EncryptedLiveTvSourceStore(3, root)
        assertTrue(store.load().isEmpty())
        store.save(listOf(xtreamSource()))
        assertTrue(Files.exists(root.resolve("profile-3/sources.key")))

        store.clear()

        assertFalse(Files.exists(root.resolve("profile-3/sources.key")))
        assertFalse(Files.exists(root.resolve("profile-3/sources.bin")))
        assertTrue(store.load().isEmpty())
        assertFailsWith<LiveTvStorageException> { store.save(listOf(xtreamSource())) }
    }

    @Test
    fun atomicReplacementLeavesNoTemporaryFiles() {
        val store = EncryptedLiveTvSourceStore(1, root)
        store.save(listOf(xtreamSource()))
        store.save(listOf(xtreamSource("rotated-password")))

        val files = Files.list(root.resolve("profile-1")).use { entries ->
            entries.map { it.fileName.toString() }.sorted().collect(java.util.stream.Collectors.toList())
        }
        assertEquals(listOf("sources.bin", "sources.key"), files)
        assertEquals("rotated-password", EncryptedLiveTvSourceStore(1, root).load().single().password)
    }
}
