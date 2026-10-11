package app.flowsync.sync

import app.flowsync.fs.CancellableInputStream
import app.flowsync.fs.Endpoint
import app.flowsync.fs.PathUtil
import app.flowsync.fs.RemoteFile
import app.flowsync.fs.SyncCancelledException
import app.flowsync.model.SyncMode
import app.flowsync.model.SyncProfile
import app.flowsync.util.Format
import java.io.IOException

class SyncStats {
    @Volatile var copied = 0
    @Volatile var skipped = 0
    @Volatile var deleted = 0
    @Volatile var failed = 0
    @Volatile var bytes = 0L
}

/**
 * One-way sync source -> destination.
 * A file is copied when it is missing, its size differs, or the source is newer than the destination.
 * (Not "different mtime": many back-ends can't preserve mtime, so only "source newer" is reliable.)
 */
class SyncEngine(
    private val profile: SyncProfile,
    private val src: Endpoint,
    private val dst: Endpoint,
    private val isCancelled: () -> Boolean,
    private val log: (String) -> Unit,
    private val progress: (String, SyncStats) -> Unit
) {
    val stats = SyncStats()
    private val matcher = FileMatcher(profile.filter)
    private val mode = profile.mode

    fun run(): SyncStats {
        src.checkAvailable()
        dst.checkAvailable()
        dst.mkdirs("")
        syncDir("")
        return stats
    }

    private fun check() { if (isCancelled()) throw SyncCancelledException() }

    private fun syncDir(rel: String) {
        check()
        progress(if (rel.isEmpty()) "Scanning…" else "Scanning $rel", stats)
        val srcList = src.list(rel)
        val dstMap = dst.list(rel).associateBy { it.name }

        // Safety net: never wipe a destination because the source was momentarily unreadable/empty.
        if (rel.isEmpty() && mode == SyncMode.MIRROR && srcList.isEmpty() && dstMap.isNotEmpty()) {
            throw IOException("Source is empty – Mirror refused to delete the destination")
        }

        val seen = HashSet<String>()
        for (f in srcList) {
            check()
            seen += f.name
            if (profile.filter.skipHidden && f.name.startsWith(".")) continue
            val childRel = PathUtil.join(rel, f.name)

            if (f.isDir) {
                if (!profile.includeSubfolders) continue
                val d = dstMap[f.name]
                try {
                    if (d != null && !d.isDir) dst.delete(childRel, false)
                    syncDir(childRel)
                    if (mode == SyncMode.MOVE && src.list(childRel).isEmpty()) src.delete(childRel, true)
                } catch (e: SyncCancelledException) { throw e }
                catch (e: Exception) {
                    stats.failed++
                    log("FAILED folder $childRel: ${e.message ?: e.javaClass.simpleName}")
                }
                continue
            }

            if (!matcher.accepts(f)) continue
            val d = dstMap[f.name]
            try {
                val needsCopy = d == null || d.isDir || d.size != f.size || f.modified > d.modified + TOLERANCE_MS
                if (needsCopy) {
                    if (d != null && d.isDir) deleteTree(dst, childRel, true)
                    copy(childRel, f)
                } else {
                    stats.skipped++
                }
                if (mode == SyncMode.MOVE) {
                    src.delete(childRel, false)
                    stats.deleted++
                    log("MOVED   $childRel")
                }
            } catch (e: SyncCancelledException) { throw e }
            catch (e: Exception) {
                check()
                stats.failed++
                log("FAILED  $childRel: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        if (mode == SyncMode.MIRROR) {
            for ((name, d) in dstMap) {
                check()
                if (name in seen) continue
                if (!d.isDir && !matcher.accepts(d)) continue // not part of the filtered set -> leave alone
                val childRel = PathUtil.join(rel, name)
                try {
                    deleteTree(dst, childRel, d.isDir)
                    log("DELETED $childRel")
                } catch (e: SyncCancelledException) { throw e }
                catch (e: Exception) {
                    stats.failed++
                    log("FAILED delete $childRel: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun copy(rel: String, f: RemoteFile) {
        progress("Copying $rel", stats)
        src.read(rel).use { raw ->
            val ins = CancellableInputStream(raw, isCancelled) { n -> stats.bytes += n }
            dst.write(rel, ins, f.size, f.modified)
        }
        stats.copied++
        log("COPIED  $rel  (${Format.bytes(f.size)})")
    }

    private fun deleteTree(ep: Endpoint, rel: String, isDir: Boolean) {
        check()
        if (isDir) {
            for (c in ep.list(rel)) deleteTree(ep, PathUtil.join(rel, c.name), c.isDir)
            ep.delete(rel, true)
        } else {
            ep.delete(rel, false)
            stats.deleted++
        }
    }

    companion object {
        const val TOLERANCE_MS = 2000L
    }
}
