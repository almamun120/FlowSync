package app.flowsync.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Protocol(val label: String, val defaultPort: Int) {
    LOCAL("Local", 0),
    SMB("SMB", 445),
    FTP("FTP", 21),
    SFTP("SFTP", 22),
    WEBDAV("WebDAV", 80),
    /** Any rclone remote (Google Drive, Dropbox, OneDrive, S3 …). host = remote name, path = folder. */
    RCLONE("rclone", 0)
}

@Serializable
enum class SyncMode(val label: String, val help: String) {
    COPY("Copy", "Copies new and changed files. Nothing is ever deleted."),
    MIRROR("Mirror", "Makes the destination identical to the source. Extra files on the destination are deleted."),
    MOVE("Move", "Copies to the destination, then deletes the file from the source."),
    BISYNC("Two-way (bisync)", "rclone only: keeps both sides identical – changes made on either side are applied to the other.")
}

@Serializable
enum class FilterMode(val label: String) {
    ALL("All files"),
    ONLY("Only these"),
    EXCLUDE("All except these")
}

/** One side (source or destination) of a profile. Works for local SAF folders and remote servers. */
@Serializable
data class EndpointConfig(
    val protocol: Protocol = Protocol.LOCAL,
    /** Local: SAF tree URI (persisted permission – no root, no "all files access"). */
    val localUri: String = "",
    val host: String = "",
    /** 0 = protocol default. */
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** SMB only. */
    val domain: String = "",
    /** SMB only: share name. */
    val share: String = "",
    /** Base folder on the server (inside the share for SMB). */
    val path: String = "",
    /** FTP: explicit FTPS, WebDAV: HTTPS. */
    val secure: Boolean = false,
    /** SFTP: optional PEM private key (password field is then used as passphrase too). */
    val privateKey: String = "",
    val timeoutSec: Int = 20
)

@Serializable
data class FileFilter(
    val mode: FilterMode = FilterMode.ALL,
    /** Custom extensions without dot, lower-case, e.g. ["jpg","mp4"]. */
    val extensions: List<String> = emptyList(),
    val skipHidden: Boolean = false,
    /** 0 = unlimited. */
    val maxSizeMb: Long = 0
)

@Serializable
data class Schedule(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 60,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false
)

/** Settings used when both sides are rclone remotes and rclone's own engine runs the job. */
@Serializable
data class RcloneOptions(
    /** Use rclone's native sync (server-side copy, checksums, parallel transfers). */
    val native: Boolean = true,
    /** e.g. "2M", "512k", or a timetable like "08:00,512k 23:00,off". Empty = unlimited. */
    val bwLimit: String = "",
    val transfers: Int = 4,
    val checksum: Boolean = false,
    val dryRun: Boolean = false,
    /** Any extra rclone flags, e.g. "--fast-list --max-age 7d --backup-dir remote:old". When set, rclone runs as a command. */
    val extraFlags: String = ""
)

@Serializable
data class SyncProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val source: EndpointConfig = EndpointConfig(),
    val destination: EndpointConfig = EndpointConfig(),
    val mode: SyncMode = SyncMode.COPY,
    val includeSubfolders: Boolean = true,
    val filter: FileFilter = FileFilter(),
    val schedule: Schedule = Schedule(),
    val rclone: RcloneOptions = RcloneOptions(),
    val lastRunAt: Long = 0,
    val lastResult: String = ""
) {
    val needsNetwork: Boolean
        get() = source.protocol != Protocol.LOCAL || destination.protocol != Protocol.LOCAL

    val bothRclone: Boolean
        get() = source.protocol == Protocol.RCLONE && destination.protocol == Protocol.RCLONE

    /** rclone runs the whole job itself (needed for bisync). */
    val useNativeRclone: Boolean
        get() = bothRclone && (rclone.native || mode == SyncMode.BISYNC)

    fun withoutSecrets(): SyncProfile = copy(
        source = source.copy(password = "", privateKey = ""),
        destination = destination.copy(password = "", privateKey = "")
    )
}

/** A server started from rclone: serve http / webdav / ftp / sftp / dlna / s3 / nfs, or rclone's Web GUI. */
@Serializable
data class ServeConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** http, webdav, ftp, sftp, dlna, s3, nfs, webgui */
    val kind: String = "http",
    /** "remote:path" or a local path (needs All files access). Not used by webgui. */
    val target: String = "",
    val port: Int = 8080,
    /** true = reachable from the Wi-Fi network (0.0.0.0), false = this phone only (127.0.0.1) */
    val lan: Boolean = true,
    val user: String = "",
    val pass: String = "",
    val readOnly: Boolean = false,
    val extraFlags: String = "",
    val autoStart: Boolean = false
)

/** A saved rclone command line (any command, any flags) that can run now or on a schedule. */
@Serializable
data class RcloneTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Without the word "rclone", e.g.  sync mydrive:Photos dropbox:Backup --dry-run */
    val command: String = "",
    /** 0 = manual only (minimum 15 when set) */
    val intervalMinutes: Int = 0,
    val wifiOnly: Boolean = false,
    val lastRunAt: Long = 0,
    val lastResult: String = ""
)

@Serializable
data class AppConfig(
    val app: String = "FlowSync",
    val version: Int = 1,
    val exportedAt: Long = 0,
    val profiles: List<SyncProfile> = emptyList(),
    val serves: List<ServeConfig> = emptyList(),
    val tasks: List<RcloneTask> = emptyList()
)

enum class DotStatus { CHECKING, ONLINE, OFFLINE, PAUSED }

data class Availability(val status: DotStatus, val detail: String = "")
