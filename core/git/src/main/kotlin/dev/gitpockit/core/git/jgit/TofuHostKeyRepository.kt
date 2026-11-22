package dev.gitpockit.core.git.jgit

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * Trust-on-first-use host key policy.
 *
 * - Unknown host: its key is saved to known_hosts and the connection is accepted.
 * - Known host with the same key: accepted.
 * - Known host with a *different* key: rejected (possible man-in-the-middle).
 *
 * [onNewHost] lets the UI record or display fingerprints of newly trusted hosts.
 */
internal class TofuHostKeyRepository(
    private val delegate: HostKeyRepository,
    private val onNewHost: (host: String, fingerprint: String) -> Unit = { _, _ -> },
) : HostKeyRepository by delegate {

    override fun check(host: String, key: ByteArray): Int =
        when (val result = delegate.check(host, key)) {
            HostKeyRepository.NOT_INCLUDED -> {
                val hostKey = HostKey(host, key)
                delegate.add(hostKey, null as UserInfo?)
                onNewHost(host, hostKey.getFingerPrint(null) ?: hostKey.type)
                HostKeyRepository.OK
            }
            else -> result
        }
}
