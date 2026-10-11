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
    GDRIVE("Google Drive", 0)
}

@Serializable
enum class SyncMode(val label: String, val help: String) {
    COPY("Copy", "Copies new and changed files. Nothing is ever deleted."),
    MIRROR("Mirror", "Makes the destination identical to the source. Extra files on the destination are deleted."),
    MOVE("Move", "Copies to the destination, then deletes the file from the source.")
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
    /** Google Drive: full Drive access (see existing files) instead of app-created files only. */
    val driveFull: Boolean = false,
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
    val lastRunAt: Long = 0,
    val lastResult: String = ""
) {
    val needsNetwork: Boolean
        get() = source.protocol != Protocol.LOCAL || destination.protocol != Protocol.LOCAL

    fun withoutSecrets(): SyncProfile = copy(
        source = source.copy(password = "", privateKey = ""),
        destination = destination.copy(password = "", privateKey = "")
    )
}

@Serializable
data class AppConfig(
    val app: String = "FlowSync",
    val version: Int = 1,
    val exportedAt: Long = 0,
    val profiles: List<SyncProfile> = emptyList()
)

enum class DotStatus { CHECKING, ONLINE, OFFLINE, PAUSED }

data class Availability(val status: DotStatus, val detail: String = "")
