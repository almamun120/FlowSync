package app.flowsync.data

import app.flowsync.model.AppConfig
import app.flowsync.model.SyncProfile
import kotlinx.serialization.json.Json

/** All configuration lives in plain JSON – both the live store and exported backups use this format. */
object BackupManager {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(profiles: List<SyncProfile>, includeSecrets: Boolean): String {
        val list = if (includeSecrets) profiles else profiles.map { it.withoutSecrets() }
        return json.encodeToString(AppConfig.serializer(), AppConfig(exportedAt = System.currentTimeMillis(), profiles = list))
    }

    fun parse(text: String): AppConfig {
        val cfg = json.decodeFromString(AppConfig.serializer(), text)
        require(cfg.app == "FlowSync") { "This is not a FlowSync configuration file" }
        return cfg
    }
}
