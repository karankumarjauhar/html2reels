package com.html2reel.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.html2reel.model.RecentProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val Context.studioDataStore by preferencesDataStore(name = "html2reel_prefs")

class RecentProjectsStore(private val context: Context) {

    private val recentsKey = stringPreferencesKey("recent_projects_json")

    val recentsFlow: Flow<List<RecentProject>> = context.studioDataStore.data.map { prefs ->
        parseProjects(prefs[recentsKey].orEmpty()).filter { File(it.filePath).exists() }
    }

    suspend fun saveProject(
        existingId: String?,
        title: String,
        rawHtml: String,
        durationSec: Float,
        resolutionLabel: String
    ): RecentProject = withContext(Dispatchers.IO) {
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        val id = existingId ?: UUID.randomUUID().toString()
        val file = File(projectsDir, "$id.html")
        file.writeText(rawHtml, Charsets.UTF_8)

        val item = RecentProject(
            id = id,
            title = title.ifBlank { "Untitled Reel" },
            updatedAtMillis = System.currentTimeMillis(),
            filePath = file.absolutePath,
            durationSec = durationSec,
            resolutionLabel = resolutionLabel
        )

        context.studioDataStore.edit { prefs ->
            val current = parseProjects(prefs[recentsKey].orEmpty())
                .filterNot { it.id == id }
                .toMutableList()
            current.add(0, item)
            val trimmed = current.take(15)
            prefs[recentsKey] = serializeProjects(trimmed)
        }
        item
    }

    suspend fun loadHtml(project: RecentProject): String = withContext(Dispatchers.IO) {
        File(project.filePath).readText(Charsets.UTF_8)
    }

    suspend fun removeProject(projectId: String) = withContext(Dispatchers.IO) {
        context.studioDataStore.edit { prefs ->
            val current = parseProjects(prefs[recentsKey].orEmpty())
            current.firstOrNull { it.id == projectId }?.let { File(it.filePath).delete() }
            prefs[recentsKey] = serializeProjects(current.filterNot { it.id == projectId })
        }
    }

    private fun parseProjects(jsonStr: String): List<RecentProject> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        RecentProject(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            updatedAtMillis = obj.getLong("updatedAtMillis"),
                            filePath = obj.getString("filePath"),
                            durationSec = obj.optDouble("durationSec", 10.0).toFloat(),
                            resolutionLabel = obj.optString("resolutionLabel", "1080×1920")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeProjects(items: List<RecentProject>): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("updatedAtMillis", item.updatedAtMillis)
                    put("filePath", item.filePath)
                    put("durationSec", item.durationSec.toDouble())
                    put("resolutionLabel", item.resolutionLabel)
                }
            )
        }
        return arr.toString()
    }
}
