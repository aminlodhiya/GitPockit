package dev.gitpockit.core.git.jgit

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.gitpockit.core.git.GitCredentials
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File

/**
 * SSH session factory that authenticates with an in-memory private key and verifies
 * host keys against an app-private known_hosts file (trust on first use).
 *
 * It never reads ~/.ssh, which does not exist on Android.
 */
internal class KeySshSessionFactory(
    private val key: GitCredentials.SshKey,
    private val knownHostsFile: File,
) : JschConfigSessionFactory() {

    override fun configure(hc: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
    }

    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        jsch.addIdentity(
            "gitpockit-key",
            key.privateKey,
            null,
            key.passphrase?.toByteArray(),
        )
        knownHostsFile.parentFile?.mkdirs()
        if (!knownHostsFile.exists()) knownHostsFile.createNewFile()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        jsch.hostKeyRepository = TofuHostKeyRepository(jsch.hostKeyRepository)
        return jsch
    }
}
