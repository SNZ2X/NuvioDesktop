package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.EnumSet
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Fixed, credential-free storage failure. Callers surface this text, never a path or URL. */
internal class LiveTvStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Store capability used by the account wipe path to delete secrets and reject later writes. */
internal interface LiveTvClearableSourceStore {
    fun clear()
}

/** Upper bound on persisted sources; the UI adds one at a time and never needs more. */
internal const val LiveTvMaxSources = 64

/**
 * Source configuration encrypted as one AES-GCM payload per profile.
 *
 * Layout: `<root>/profile-<id>/sources.key` holds a random 256-bit key and
 * `sources.bin` holds `MAGIC | VERSION | IV | ciphertext+tag`. The profile ID is
 * authenticated as additional data, so ciphertext copied between profiles fails to
 * decrypt. Every write goes to a fresh temporary file in the same directory and is
 * moved into place atomically; reads and writes refuse symlinked paths.
 *
 * Threat model: the key sits next to the ciphertext with owner-only permissions
 * (POSIX 0600/0700, or an owner-only ACL on Windows). This protects against
 * accidental disclosure, backups of the ciphertext alone, and other OS users. It is
 * not an OS credential vault: a process running as the same user can read both
 * files. The key is never regenerated while ciphertext exists, so a lost key is
 * reported as corruption instead of silently replacing the saved sources.
 */
internal class EncryptedLiveTvSourceStore(
    private val profileId: Int,
    val root: Path = DesktopStorage.rootDir.resolve("livetv"),
) : LiveTvSourceStore, LiveTvClearableSourceStore {
    internal val directory: Path = root.resolve("profile-$profileId")
    private val ciphertextFile: Path = directory.resolve(SourcesFile)
    private val keyFile: Path = directory.resolve(KeyFile)

    private val lock = Any()
    private val random = SecureRandom()

    @Volatile
    private var closed = false

    override fun load(): List<LiveTvSource> = synchronized(lock) {
        if (closed) return emptyList()
        verifyAncestors()
        val payload = readBounded(ciphertextFile, MaxCiphertextBytes) ?: return emptyList()
        val key = readKey() ?: throw LiveTvStorageException(CorruptMessage)
        val plaintext = decrypt(payload, key) ?: throw LiveTvStorageException(CorruptMessage)
        decode(plaintext)
    }

    override fun save(sources: List<LiveTvSource>) {
        val sanitized = sources.map(::sanitizeForSave).distinctBy { it.id }
        synchronized(lock) {
            ensureOpen()
            if (sanitized.size > LiveTvMaxSources) throw LiveTvStorageException(LimitMessage)
            // Validate before touching the ciphertext: an entry that could not be read back
            // must be rejected here instead of being written and silently dropped on reload.
            if (sanitized.size != sources.size || sanitized.any { !isStorable(it) }) {
                throw LiveTvStorageException(InvalidMessage)
            }
            ensureDirectory()
            val key = loadOrCreateKeyForSave()
            val payload = encrypt(encode(sanitized), key)
            if (payload.size > MaxCiphertextBytes) throw LiveTvStorageException(LimitMessage)
            writeAtomically(ciphertextFile, payload)
        }
    }

    /**
     * Permanently removes this profile's key and ciphertext and rejects later writes.
     * Used by [LiveTvRepository.clearAll] before account storage is wiped.
     */
    override fun clear() {
        synchronized(lock) {
            closed = true
            deleteQuietly(ciphertextFile)
            deleteQuietly(keyFile)
            deleteTemporaryFiles()
            runCatching { Files.deleteIfExists(directory) }
        }
    }

    private fun ensureOpen() {
        if (closed) throw LiveTvStorageException(ClearedMessage)
    }

    private fun encode(sources: List<LiveTvSource>): ByteArray =
        json.encodeToString(SourcesEnvelope.serializer(), SourcesEnvelope(sources = sources))
            .toByteArray(StandardCharsets.UTF_8)

    private fun decode(bytes: ByteArray): List<LiveTvSource> {
        val envelope = try {
            json.decodeFromString(SourcesEnvelope.serializer(), bytes.toString(StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            throw LiveTvStorageException(CorruptMessage)
        }
        if (envelope.version != Version) throw LiveTvStorageException(CorruptMessage)
        // Never truncate: a record that no longer fits the limits is reported as unreadable
        // rather than silently dropped from the user's saved configuration.
        if (envelope.sources.size > LiveTvMaxSources || envelope.sources.any { !isStorable(it) }) {
            throw LiveTvStorageException(CorruptMessage)
        }
        if (envelope.sources.map { it.id }.distinct().size != envelope.sources.size) {
            throw LiveTvStorageException(CorruptMessage)
        }
        return envelope.sources
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(IvBytes).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TagBits, iv))
            cipher.updateAAD(additionalData())
            val sealed = cipher.doFinal(plaintext)
            ByteBuffer.allocate(Magic.size + 1 + IvBytes + sealed.size)
                .put(Magic)
                .put(Version.toByte())
                .put(iv)
                .put(sealed)
                .array()
        } catch (error: GeneralSecurityException) {
            throw LiveTvStorageException(SaveMessage, error)
        }
    }

    private fun decrypt(payload: ByteArray, key: SecretKeySpec): ByteArray? {
        val header = Magic.size + 1
        if (payload.size < header + IvBytes + TagBits / 8) return null
        if (!payload.copyOfRange(0, Magic.size).contentEquals(Magic)) return null
        if (payload[Magic.size].toInt() != Version) return null
        val iv = payload.copyOfRange(header, header + IvBytes)
        return try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagBits, iv))
            cipher.updateAAD(additionalData())
            cipher.doFinal(payload, header + IvBytes, payload.size - header - IvBytes)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun additionalData(): ByteArray =
        "nuvio-livetv:$Version:$profileId".toByteArray(StandardCharsets.UTF_8)

    private fun readKey(): SecretKeySpec? {
        val bytes = readBounded(keyFile, KeyBytes) ?: return null
        if (bytes.size != KeyBytes) throw LiveTvStorageException(CorruptMessage)
        return SecretKeySpec(bytes, KeyAlgorithm)
    }

    private fun loadOrCreateKeyForSave(): SecretKeySpec {
        readKey()?.let { return it }
        // A key next to ciphertext must never be regenerated: that would silently make the
        // saved sources unreadable and discard credentials without telling the user.
        if (readBounded(ciphertextFile, MaxCiphertextBytes) != null) {
            throw LiveTvStorageException(KeyMissingMessage)
        }
        val generated = ByteArray(KeyBytes).also(random::nextBytes)
        writeAtomically(keyFile, generated)
        return SecretKeySpec(generated, KeyAlgorithm)
    }

    private fun ensureDirectory() {
        try {
            if (Files.isSymbolicLink(root)) throw LiveTvStorageException(SymlinkMessage)
            Files.createDirectories(root)
            restrictToOwner(root)
            if (Files.isSymbolicLink(directory)) throw LiveTvStorageException(SymlinkMessage)
            Files.createDirectories(directory)
            restrictToOwner(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw LiveTvStorageException(SaveMessage)
            }
        } catch (error: LiveTvStorageException) {
            throw error
        } catch (error: IOException) {
            throw LiveTvStorageException(SaveMessage, error)
        }
    }

    /**
     * Save runs [ensureDirectory], so its checks only help after the directories exist. A
     * read of an already-populated store had no such check, so a symlinked [root] or profile
     * directory is rejected here before any file beneath it is opened.
     */
    private fun verifyAncestors() {
        if (Files.isSymbolicLink(root)) throw LiveTvStorageException(SymlinkMessage)
        if (Files.isSymbolicLink(directory)) throw LiveTvStorageException(SymlinkMessage)
    }

    private fun readBounded(path: Path, maxBytes: Int): ByteArray? {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.isSymbolicLink(path)) throw LiveTvStorageException(SymlinkMessage)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw LiveTvStorageException(CorruptMessage)
            Files.newByteChannel(
                path,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                if (channel.size() > maxBytes.toLong()) throw LiveTvStorageException(CorruptMessage)
                val output = ByteArrayOutputStream(minOf(maxBytes, DefaultBufferBytes).coerceAtLeast(1))
                val buffer = ByteBuffer.allocate(DefaultBufferBytes)
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    output.write(buffer.array(), 0, read)
                    if (output.size() > maxBytes) throw LiveTvStorageException(CorruptMessage)
                }
                return output.toByteArray()
            }
        } catch (error: LiveTvStorageException) {
            throw error
        } catch (error: IOException) {
            throw LiveTvStorageException(CorruptMessage, error)
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        try {
            if (Files.isSymbolicLink(target)) throw LiveTvStorageException(SymlinkMessage)
            val temporary = Files.createTempFile(directory, TemporaryPrefix, TemporarySuffix)
            try {
                restrictToOwner(temporary)
                writeFully(temporary, bytes)
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                syncDirectory()
            } finally {
                deleteQuietly(temporary)
            }
        } catch (error: LiveTvStorageException) {
            throw error
        } catch (error: IOException) {
            throw LiveTvStorageException(SaveMessage, error)
        }
    }

    private fun writeFully(path: Path, bytes: ByteArray) {
        Files.newByteChannel(
            path,
            setOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
        }
    }

    private fun syncDirectory() {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun deleteTemporaryFiles() {
        runCatching {
            Files.list(directory).use { entries ->
                entries
                    .filter { it.fileName.toString().startsWith(TemporaryPrefix) }
                    .forEach(::deleteQuietly)
            }
        }
    }

    private fun deleteQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    /**
     * Fails closed: if the filesystem supports neither POSIX permissions nor file ACLs, or
     * the owner-only restriction cannot be applied, the caller must not write secrets there.
     * [writeAtomically] calls this before any content reaches the temporary file's final
     * name, so a key is never left behind with broad permissions.
     */
    private fun restrictToOwner(path: Path) {
        val isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (posix != null) {
            val permissions = if (isDirectory) DirectoryPermissions else FilePermissions
            try {
                Files.setPosixFilePermissions(path, permissions)
            } catch (error: IOException) {
                throw LiveTvStorageException(PermissionMessage, error)
            } catch (error: UnsupportedOperationException) {
                throw LiveTvStorageException(PermissionMessage, error)
            }
            return
        }
        restrictWindowsAcl(path)
    }

    private fun restrictWindowsAcl(path: Path) {
        val view = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw LiveTvStorageException(PermissionMessage)
        val owner = try {
            view.owner
        } catch (error: IOException) {
            throw LiveTvStorageException(PermissionMessage, error)
        }
        if (owner == null) throw LiveTvStorageException(PermissionMessage)
        val entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
        // Replacing the list drops inherited entries, leaving only the owner's allow ACE.
        try {
            view.acl = listOf(entry)
        } catch (error: IOException) {
            throw LiveTvStorageException(PermissionMessage, error)
        } catch (error: UnsupportedOperationException) {
            throw LiveTvStorageException(PermissionMessage, error)
        }
    }

    private fun sanitizeForSave(source: LiveTvSource): LiveTvSource = source.copy(
        name = source.name.trim(),
        location = source.location.trim(),
        username = if (source.kind == LiveTvSourceKind.XTREAM) source.username.trim() else "",
        password = if (source.kind == LiveTvSourceKind.XTREAM) source.password else "",
        epgUrl = source.epgUrl.trim(),
    )

    private fun isStorable(source: LiveTvSource): Boolean =
        source.id.isNotBlank() &&
            source.id.length <= MaxIdLength &&
            source.name.isNotBlank() &&
            source.name.length <= MaxNameLength &&
            source.location.isNotBlank() &&
            source.location.length <= MaxLocationLength &&
            source.username.length <= MaxCredentialLength &&
            source.password.length <= MaxCredentialLength &&
            source.epgUrl.length <= MaxLocationLength

    private companion object {
        const val Transformation = "AES/GCM/NoPadding"
        const val KeyAlgorithm = "AES"
        const val Version = 1
        const val KeyBytes = 32
        const val IvBytes = 12
        const val TagBits = 128
        const val MaxCiphertextBytes = 1024 * 1024
        const val DefaultBufferBytes = 8192
        const val MaxIdLength = 128
        const val MaxNameLength = 256
        const val MaxLocationLength = 2048
        const val MaxCredentialLength = 512
        const val SourcesFile = "sources.bin"
        const val KeyFile = "sources.key"
        const val TemporaryPrefix = ".sources-"
        const val TemporarySuffix = ".part"
        val Magic = "NVLTVSRC".toByteArray(StandardCharsets.US_ASCII)
        val FilePermissions: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val DirectoryPermissions: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

        const val CorruptMessage = "Saved Live TV sources could not be read."
        const val SaveMessage = "Live TV source could not be saved."
        const val LimitMessage = "Too many Live TV sources."
        const val InvalidMessage = "A Live TV source is not valid."
        const val PermissionMessage = "Live TV storage could not be protected with owner-only permissions."
        const val ClearedMessage = "Live TV storage was cleared."
        const val KeyMissingMessage = "Saved Live TV sources could not be read."
        const val SymlinkMessage = "Live TV storage refused a symbolic link."
    }
}

@Serializable
private class SourcesEnvelope(
    val version: Int = 1,
    val sources: List<LiveTvSource> = emptyList(),
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
