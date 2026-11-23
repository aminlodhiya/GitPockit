package dev.gitpockit.ui.files

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONObject

/**
 * Loads the bundled TextMate grammars and GitPockit editor themes once per process
 * (assets/textmate, see NOTICE.md there for sources and licenses).
 */
object TextMateSetup {
    private const val DARK = "gitpockit-dark"
    private const val LIGHT = "gitpockit-light"
    private val mutex = Mutex()
    @Volatile private var loaded = false
    private var extensions: Map<String, String> = emptyMap() // extension -> language name
    private var scopes: Map<String, String> = emptyMap() // language name -> scope

    suspend fun ensure(context: Context) = mutex.withLock {
        if (loaded) return@withLock
        withContext(Dispatchers.IO) {
            val assets = context.applicationContext.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            for ((name, dark) in listOf(DARK to true, LIGHT to false)) {
                val path = "textmate/$name.json"
                val source = IThemeSource.fromInputStream(FileProviderRegistry.getInstance().tryGetInputStream(path), path, null)
                ThemeRegistry.getInstance().loadTheme(ThemeModel(source, name).apply { isDark = dark })
            }
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            val langs = JSONObject(assets.open("textmate/languages.json").bufferedReader().readText()).getJSONArray("languages")
            scopes = (0 until langs.length()).associate { i -> langs.getJSONObject(i).let { it.getString("name") to it.getString("scopeName") } }
            val ext = JSONObject(assets.open("textmate/extensions.json").bufferedReader().readText())
            extensions = ext.keys().asSequence().associateWith { ext.getString(it) }
        }
        loaded = true
    }

    fun useTheme(dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) DARK else LIGHT)
    }

    /** TextMate scope for a file name, or null for plain text. */
    fun scopeFor(fileName: String): String? {
        val lower = fileName.lowercase()
        val lang = when {
            lower == "dockerfile" || lower == "makefile" -> "shell"
            lower.startsWith(".") && '.' !in lower.drop(1) -> extensions[lower.drop(1)] // .bashrc, .gitconfig
            lower.endsWith(".gradle.kts") -> "kotlin"
            else -> extensions[lower.substringAfterLast('.', "")]
        } ?: return null
        return scopes[lang]
    }

    /** Ignore files use the INI grammar for comments; good enough for highlighting. */
    fun isIgnoreFile(name: String) = name == ".gitignore" || name == "exclude"
}
