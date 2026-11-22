package dev.gitpockit.core.data

import dev.gitpockit.core.data.storage.TreeDocumentPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeDocumentPathsTest {
    @Test fun primaryVolume() {
        assertEquals("/storage/emulated/0/Documents/Notes", TreeDocumentPaths.toPath("primary:Documents/Notes"))
        assertEquals("/storage/emulated/0", TreeDocumentPaths.toPath("primary:"))
    }

    @Test fun sdCard() = assertEquals("/storage/1A2B-3C4D/Vault", TreeDocumentPaths.toPath("1A2B-3C4D:Vault/"))

    @Test fun homeMapsToDocuments() = assertEquals("/storage/emulated/0/Documents/x", TreeDocumentPaths.toPath("home:x"))

    @Test fun rawPath() = assertEquals("/storage/emulated/0/Download/r", TreeDocumentPaths.toPath("raw:/storage/emulated/0/Download/r/"))

    @Test fun unknownProviders() {
        assertNull(TreeDocumentPaths.toPath("msd:1234"))
        assertNull(TreeDocumentPaths.toPath("nocolon"))
    }
}
