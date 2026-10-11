package com.ljyh.mei.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.constants.UserAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

sealed interface VersionUpdateResult {
    data class UpdateAvailable(
        val latestTag: String,
        val releaseUrl: String,
        val isBeta: Boolean = false,
        val currentBuildLabel: String? = null,
        val comparisonKnown: Boolean = true,
    ) : VersionUpdateResult

    data object UpToDate : VersionUpdateResult

    data object Failed : VersionUpdateResult
}

/** Checks GitHub tags or downloadable main-branch CI builds off the Compose thread. */
object VersionUpdateChecker {
    private const val Repository = "NEORUAA/MeiloX"
    private const val RepositoryUrl = "https://github.com/$Repository"
    private const val ApiUrl = "https://api.github.com/repos/$Repository"
    private const val TagsEndpoint = "$ApiUrl/tags?per_page=100"
    private const val WorkflowPath = ".github/workflows/main.yml"
    private const val RunsPerPage = 30
    private const val MaxRunPages = 2
    private const val MaxArtifactRequests = 5
    private const val ConnectTimeoutMillis = 8_000
    private const val ReadTimeoutMillis = 8_000

    suspend fun check(
        currentVersion: String,
        betaUpdatesEnabled: Boolean = false,
    ): VersionUpdateResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(30_000) {
            checkWithFetcher(
                currentVersion,
                betaUpdatesEnabled,
                InstalledBuild(
                    number = BuildConfig.BUILD_NUMBER,
                    commitSha = BuildConfig.BUILD_COMMIT_SHA,
                    sourceDirty = BuildConfig.BUILD_SOURCE_DIRTY,
                    ciRunNumber = BuildConfig.CI_RUN_NUMBER,
                    ciRunAttempt = BuildConfig.CI_RUN_ATTEMPT,
                ),
                ::requestJson,
            )
        } ?: VersionUpdateResult.Failed
    }

    internal suspend fun checkWithFetcher(
        currentVersion: String,
        betaUpdatesEnabled: Boolean,
        installedBuild: InstalledBuild,
        fetchJson: suspend (String) -> JsonElement,
    ): VersionUpdateResult = try {
        if (betaUpdatesEnabled) {
            checkBeta(installedBuild, fetchJson)
        } else {
            checkStable(currentVersion, fetchJson)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        VersionUpdateResult.Failed
    }

    private suspend fun checkStable(
        currentVersion: String,
        fetchJson: suspend (String) -> JsonElement,
    ): VersionUpdateResult {
        val current = parseVersion(currentVersion) ?: return VersionUpdateResult.Failed
        val tags = fetchJson(TagsEndpoint).asJsonArray.mapNotNull { element ->
            val name = element.objectOrNull()?.string("name")?.trim() ?: return@mapNotNull null
            parseVersion(name)?.let { version -> VersionTag(name, version) }
        }
        val latest = tags.maxWithOrNull { left, right ->
            compareVersions(left.version, right.version)
        } ?: return VersionUpdateResult.Failed

        return if (compareVersions(latest.version, current) > 0) {
            VersionUpdateResult.UpdateAvailable(
                latestTag = latest.name,
                releaseUrl = "$RepositoryUrl/releases/tag/${URLEncoder.encode(latest.name, "UTF-8")}",
            )
        } else {
            VersionUpdateResult.UpToDate
        }
    }

    private suspend fun checkBeta(
        installedBuild: InstalledBuild,
        fetchJson: suspend (String) -> JsonElement,
    ): VersionUpdateResult {
        val currentCiBuild = installedBuild.run {
            if (!sourceDirty && ciRunNumber > 0 && ciRunAttempt > 0) {
                CiBuild(ciRunNumber, ciRunAttempt)
            } else {
                null
            }
        }
        var artifactRequests = 0
        for (page in 1..MaxRunPages) {
            val response = fetchJson(
                "$ApiUrl/actions/workflows/main.yml/runs" +
                    "?branch=main&status=success&per_page=$RunsPerPage&page=$page",
            ).asJsonObject
            val rawRuns = response.array("workflow_runs") ?: return VersionUpdateResult.Failed
            val runs = rawRuns.mapNotNull { it.objectOrNull()?.toTrustedRun() }
                .sortedByDescending { it.build }
            for (run in runs) {
                if (currentCiBuild != null && run.build <= currentCiBuild) {
                    return VersionUpdateResult.UpToDate
                }
                if (artifactRequests >= MaxArtifactRequests) return VersionUpdateResult.Failed
                artifactRequests++
                val artifacts = fetchJson(
                    "$ApiUrl/actions/runs/${run.id}/artifacts?per_page=100",
                ).asJsonObject.array("artifacts") ?: return VersionUpdateResult.Failed
                val latestBuild = artifacts.mapNotNull {
                    it.objectOrNull()?.downloadableBuildFor(run)
                }.maxOrNull()
                if (latestBuild != null) {
                    val comparison = if (currentCiBuild != null) {
                        if (latestBuild <= currentCiBuild) BuildComparison.CurrentOrNewer
                        else BuildComparison.UpdateAvailable
                    } else {
                        compareSource(installedBuild, run.headSha, fetchJson)
                    }
                    if (comparison == BuildComparison.CurrentOrNewer) {
                        return VersionUpdateResult.UpToDate
                    }
                    return VersionUpdateResult.UpdateAvailable(
                        latestTag = latestBuild.label,
                        releaseUrl = "$RepositoryUrl/actions/runs/${run.id}#artifacts",
                        isBeta = true,
                        currentBuildLabel = currentCiBuild?.label ?: installedBuild.label,
                        comparisonKnown = comparison == BuildComparison.UpdateAvailable,
                    )
                }
            }
            if (rawRuns.size() < RunsPerPage) break
        }
        return VersionUpdateResult.Failed
    }

    private suspend fun compareSource(
        installedBuild: InstalledBuild,
        candidateSha: String,
        fetchJson: suspend (String) -> JsonElement,
    ): BuildComparison {
        if (installedBuild.sourceDirty || !SHA_PATTERN.matches(installedBuild.commitSha)) {
            return BuildComparison.Unknown
        }
        if (installedBuild.commitSha.equals(candidateSha, ignoreCase = true)) {
            return BuildComparison.CurrentOrNewer
        }
        val comparison = try {
            fetchJson(
                "$ApiUrl/compare/${installedBuild.commitSha}...$candidateSha?per_page=1",
            ).asJsonObject
        } catch (failure: GitHubHttpException) {
            if (failure.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return BuildComparison.Unknown
            }
            throw failure
        }
        return when (comparison.string("status")) {
            "ahead" -> BuildComparison.UpdateAvailable
            "behind", "identical" -> BuildComparison.CurrentOrNewer
            "diverged" -> BuildComparison.Unknown
            else -> error("GitHub returned an unknown commit comparison status")
        }
    }

    private suspend fun requestJson(endpoint: String): JsonElement {
        currentCoroutineContext().ensureActive()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ConnectTimeoutMillis
            readTimeout = ReadTimeoutMillis
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UserAgent)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw GitHubHttpException(statusCode)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader)
            }
            currentCoroutineContext().ensureActive()
            response
        } finally {
            connection.disconnect()
        }
    }

    internal data class InstalledBuild(
        val number: Long,
        val commitSha: String,
        val sourceDirty: Boolean,
        val ciRunNumber: Long = 0,
        val ciRunAttempt: Int = 0,
    ) {
        val label: String get() = buildString {
            append("build #$number")
            if (SHA_PATTERN.matches(commitSha)) append(" · ${commitSha.take(7)}")
            if (sourceDirty) append(" *")
        }
    }

    internal class GitHubHttpException(val statusCode: Int) :
        IOException("GitHub update request failed: HTTP $statusCode")

    private enum class BuildComparison { UpdateAvailable, CurrentOrNewer, Unknown }

    private data class CiBuild(val number: Long, val attempt: Int) : Comparable<CiBuild> {
        val label: String get() = "beta #$number.$attempt"

        override fun compareTo(other: CiBuild): Int =
            compareValuesBy(this, other, CiBuild::number, CiBuild::attempt)
    }

    private data class WorkflowRun(
        val id: Long,
        val repositoryId: Long,
        val headSha: String,
        val build: CiBuild,
    )

    private fun JsonObject.toTrustedRun(): WorkflowRun? {
        if (string("path") != WorkflowPath || string("head_branch") != "main" ||
            string("status") != "completed" || string("conclusion") != "success" ||
            string("event") !in setOf("push", "workflow_dispatch", "schedule")
        ) return null
        val repository = get("repository")?.objectOrNull() ?: return null
        val headRepository = get("head_repository")?.objectOrNull() ?: return null
        if (repository.string("full_name") != Repository ||
            headRepository.string("full_name") != Repository
        ) return null
        val repositoryId = repository.long("id")?.takeIf { it > 0 } ?: return null
        if (headRepository.long("id") != repositoryId) return null
        val id = long("id")?.takeIf { it > 0 } ?: return null
        val number = long("run_number")?.takeIf { it > 0 } ?: return null
        val attempt = long("run_attempt")?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
        val sha = string("head_sha")?.takeIf { SHA_PATTERN.matches(it) } ?: return null
        return WorkflowRun(id, repositoryId, sha, CiBuild(number, attempt))
    }

    private fun JsonObject.downloadableBuildFor(run: WorkflowRun): CiBuild? {
        if (get("expired")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
                it.isBoolean && !it.asBoolean
            } != true
        ) return null
        if ((long("size_in_bytes") ?: 0) <= 0) return null
        val expiration = string("expires_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        if (!expiration.isAfter(Instant.now())) return null
        val name = string("name") ?: return null
        val match = Regex("^MeiloX-(.+)-${run.build.number}(?:\\.([1-9][0-9]*))?$")
            .matchEntire(name) ?: return null
        if (parseVersion(match.groupValues[1]) == null) return null
        // A partial workflow rerun can retain an APK from an earlier build attempt.
        val attempt = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
        if (attempt !in 1..run.build.attempt) return null
        val workflowRun = get("workflow_run")?.objectOrNull() ?: return null
        if (workflowRun.long("id") != run.id ||
            workflowRun.long("repository_id") != run.repositoryId ||
            workflowRun.long("head_repository_id") != run.repositoryId ||
            workflowRun.string("head_branch") != "main" ||
            workflowRun.string("head_sha") != run.headSha
        ) return null
        return CiBuild(run.build.number, attempt)
    }

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { isJsonObject }?.asJsonObject

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private data class VersionTag(
        val name: String,
        val version: ComparableVersion,
    )

    private data class ComparableVersion(
        val parts: List<Int>,
        val preRelease: Boolean,
    )

    private fun parseVersion(raw: String): ComparableVersion? {
        val normalized = raw.trim().removePrefix("refs/tags/")
        val match = VERSION_PATTERN.matchEntire(normalized) ?: return null
        val parts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        return ComparableVersion(
            parts = parts,
            preRelease = match.groupValues[2].isNotEmpty(),
        )
    }

    private fun compareVersions(left: ComparableVersion, right: ComparableVersion): Int {
        val size = maxOf(left.parts.size, right.parts.size)
        for (index in 0 until size) {
            val difference = left.parts.getOrElse(index) { 0 }
                .compareTo(right.parts.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return when {
            left.preRelease == right.preRelease -> 0
            left.preRelease -> -1
            else -> 1
        }
    }

    private val VERSION_PATTERN = Regex(
        "^[vV]?(\\d+(?:\\.\\d+)*)(-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
    )
    private val SHA_PATTERN = Regex("^[0-9a-fA-F]{40}$")
}
