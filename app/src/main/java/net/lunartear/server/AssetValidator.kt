package net.lunartear.server

import java.io.File

/**
 * Checks the user-supplied asset tree before the server is allowed to start.
 *
 * This is a port of upstream's own manifest at cmd/wizard/main.go:139-176, which
 * lives in package main and cannot be imported. It matters more here than on
 * desktop: master-data parsing reaches 24 log.Fatal sites in internal/masterdata,
 * and log.Fatal is os.Exit — so catching a bad tree up front turns a silent
 * process death into a readable message.
 */
object AssetValidator {

    private const val MASTER_DATA = "assets/release/20240404193219.bin.e"

    /** Always required. */
    private val required = listOf(
        Entry("assets", isDir = true),
        Entry(MASTER_DATA, isDir = false),
    )

    /**
     * At least one of these must be complete. Users may have only the un-split
     * shared tree, only Android, only iOS, or any combination.
     */
    private val trees = listOf(
        "assets/revisions/0" to "shared",
        "assets/revisions/0/android" to "android",
        "assets/revisions/0/ios" to "ios",
    ).map { (base, label) ->
        Tree(
            label = label,
            entries = listOf(
                Entry("$base/list.bin", isDir = false),
                Entry("$base/assetbundle", isDir = true),
                Entry("$base/resources", isDir = true),
            ),
        )
    }

    private data class Entry(val path: String, val isDir: Boolean)
    private data class Tree(val label: String, val entries: List<Entry>)

    data class Result(
        val ok: Boolean,
        /** Human-readable problems, most important first. */
        val problems: List<String>,
        /** Names of the asset trees that are fully present. */
        val presentTrees: List<String>,
    )

    fun validate(root: File): Result {
        val problems = mutableListOf<String>()

        if (!root.exists()) {
            return Result(
                ok = false,
                problems = listOf("${AppPaths.displayRoot} does not exist. Create it and copy the asset tree in."),
                presentTrees = emptyList(),
            )
        }
        if (!root.isDirectory) {
            return Result(false, listOf("${AppPaths.displayRoot} is a file, not a directory."), emptyList())
        }
        if (!root.canRead()) {
            return Result(
                ok = false,
                problems = listOf("Cannot read ${AppPaths.displayRoot}. Grant \"All files access\" first."),
                presentTrees = emptyList(),
            )
        }
        // internal/runtime/holder.go:85 bumps the master-data file's mtime after
        // an admin reload. A read-only tree still plays, but clients stop
        // noticing content updates, so surface it.
        if (!root.canWrite()) {
            problems += "${AppPaths.displayRoot} is not writable; master-data reloads will not " +
                "be picked up by the client."
        }

        for (e in required) {
            checkEntry(root, e)?.let { problems += it }
        }

        val present = trees.filter { t -> t.entries.all { checkEntry(root, it) == null } }
        if (present.isEmpty()) {
            problems += "No complete asset revision found. Need one of: " +
                trees.joinToString(" | ") { t -> t.entries.first().path.substringBeforeLast('/') + "/{list.bin, assetbundle/, resources/}" }
        }

        // A missing master-data file is fatal; a non-writable root is not.
        val fatal = problems.any { !it.contains("not writable") }
        return Result(ok = !fatal, problems = problems, presentTrees = present.map { it.label })
    }

    /** Returns null when the entry is fine, otherwise a description. */
    private fun checkEntry(root: File, e: Entry): String? {
        val f = File(root, e.path)
        return when {
            !f.exists() -> "Missing ${if (e.isDir) "directory" else "file"}: ${e.path}"
            e.isDir && !f.isDirectory -> "${e.path} should be a directory but is a file"
            !e.isDir && f.isDirectory -> "${e.path} should be a file but is a directory"
            else -> null
        }
    }
}
