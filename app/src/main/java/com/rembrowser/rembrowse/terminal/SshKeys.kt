package com.rembrowser.rembrowse.terminal

import com.trilead.ssh2.crypto.OpenSSHKeyEncoder
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.PublicKeyUtils
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519Provider
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import java.io.IOException
import java.security.KeyPair
import java.security.SecureRandom

/** A key pair ready to keep: its private half unencrypted (the app encrypts it on the device). */
data class SshKeyMaterial(
    /** `-----BEGIN OPENSSH PRIVATE KEY-----…`, without a passphrase. */
    val privateKeyPem: String,
    /** The `authorized_keys` line: `ssh-ed25519 AAAA… comment`. */
    val publicKey: String,
    val fingerprint: String
)

/** Making and reading SSH keys. */
object SshKeys {

    init {
        Ed25519Provider.insertIfNeeded()
    }

    /** A new Ed25519 key pair, the kind `ssh-keygen` makes by default today. */
    fun generateEd25519(comment: String): SshKeyMaterial {
        val generator = Ed25519KeyPairGenerator().apply { initialize(256, SecureRandom()) }
        val pair = generator.generateKeyPair()
        val pem = OpenSSHKeyEncoder.exportOpenSSHEd25519(
            pair.private as Ed25519PrivateKey,
            pair.public as Ed25519PublicKey,
            comment
        )
        return material(pair, pem, comment)
    }

    /**
     * A private key pasted in: OpenSSH (`BEGIN OPENSSH PRIVATE KEY`) or PEM (RSA, EC). A key with
     * a passphrase needs it once, here: what is kept is the key without it, encrypted on the
     * device instead.
     *
     * @throws IllegalArgumentException with a message for the user when the key can't be read.
     */
    fun import(text: String, passphrase: String?, comment: String): SshKeyMaterial {
        val trimmed = text.trim()
        require(trimmed.startsWith("-----BEGIN ")) {
            "That isn't a private key. Paste the whole file, from \"-----BEGIN\" to \"-----END … KEY-----\"."
        }
        require(!trimmed.contains("PUBLIC KEY")) { "That's a public key. Paste the private key instead." }
        val structure = try {
            PEMDecoder.parsePEM(trimmed.toCharArray())
        } catch (e: IOException) {
            throw IllegalArgumentException("The key couldn't be read: ${e.message}", e)
        }
        val encrypted = runCatching { PEMDecoder.isPEMEncrypted(structure) }.getOrDefault(false)
        if (encrypted && passphrase.isNullOrEmpty()) {
            throw IllegalArgumentException("This key has a passphrase. Enter it to import the key.")
        }
        val pair = try {
            PEMDecoder.decode(structure, passphrase?.takeIf { it.isNotEmpty() })
        } catch (e: IOException) {
            throw IllegalArgumentException(
                if (encrypted) "Wrong passphrase, or the key couldn't be read." else "The key couldn't be read: ${e.message}",
                e
            )
        }
        val pem = if (encrypted) OpenSSHKeyEncoder.exportOpenSSH(pair.private, pair.public, comment) else trimmed
        return material(pair, pem, comment)
    }

    private fun material(pair: KeyPair, pem: String, comment: String): SshKeyMaterial {
        val publicKey = PublicKeyUtils.toAuthorizedKeysFormat(pair.public, comment).trim()
        val blob = PublicKeyUtils.extractPublicKeyBlob(pair.public)
        return SshKeyMaterial(pem, publicKey, TrileadSshConnector.fingerprint(blob))
    }
}
