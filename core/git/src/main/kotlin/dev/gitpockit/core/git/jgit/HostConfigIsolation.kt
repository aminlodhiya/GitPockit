package dev.gitpockit.core.git.jgit

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * Replaces the machine's system-wide Git config (/etc/gitconfig) with a fixed in-memory one.
 *
 * - Android has no system Git config, but test/CI machines do, and theirs typically configure
 *   the external `git-lfs` program as the LFS filter. JGit would run it during a clone, before
 *   GitPockit can configure the new repository, and it fails. Ignoring the host config makes
 *   behaviour identical everywhere.
 * - The replacement sets `filter.lfs.useJGitBuiltin = true` at the base of every repository's
 *   config, so GitPockit's own LFS filters are used from the very first checkout of a clone.
 */
internal object HostConfigIsolation {
    @Volatile private var installed = false

    fun ensure() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            SystemReader.setInstance(object : SystemReader.Delegate(SystemReader.getInstance()) {
                override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
                    object : FileBasedConfig(parent, null, fs) {
                        init {
                            setBoolean("filter", "lfs", "useJGitBuiltin", true)
                        }

                        override fun load() = Unit // nothing on disk to read

                        override fun isOutdated(): Boolean = false
                    }
            })
            installed = true
        }
    }
}
