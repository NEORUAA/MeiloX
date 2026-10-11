package com.ljyh.mei.utils

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VersionUpdateCheckerTest {
    @Test
    fun betaUsesBuildNumberEvenWhenVersionNameHasNotChanged() = runBlocking {
        val requests = mutableListOf<String>()
        val result = checkBeta(
            number = 34,
            runs = runs(run(35)),
            artifacts = mapOf(35L to artifacts(artifact(35))),
            requests = requests,
        ) as VersionUpdateResult.UpdateAvailable

        assertEquals("beta #35.1", result.latestTag)
        assertEquals("beta #34.1", result.currentBuildLabel)
        assertEquals("https://github.com/NEORUAA/MeiloX/actions/runs/1035#artifacts", result.releaseUrl)
        assertTrue(result.isBeta)
        assertEquals(2, requests.size)
        assertTrue(requests.first().contains("/workflows/main.yml/runs?branch=main&status=success"))
        assertFalse(requests.any { "/tags" in it })
    }

    @Test
    fun equalAndNewerInstalledBuildsNeedNoArtifactRequest() = runBlocking {
        for ((number, attempt) in listOf(35L to 1, 35L to 2, 36L to 1)) {
            val requests = mutableListOf<String>()
            assertEquals(
                VersionUpdateResult.UpToDate,
                checkBeta(number, attempt, runs(run(35)), requests = requests),
            )
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun newerRunWinsOverAnyAttemptOfAnOlderRun() = runBlocking {
        val result = checkBeta(
            number = 34,
            attempt = 99,
            runs = runs(run(35)),
            artifacts = mapOf(35L to artifacts(artifact(35))),
        ) as VersionUpdateResult.UpdateAvailable

        assertEquals("beta #35.1", result.latestTag)
    }

    @Test
    fun rerunSelectsTheNewestDownloadableArtifactAttempt() = runBlocking {
        val result = checkBeta(
            number = 35,
            runs = runs(run(35, attempt = 2)),
            artifacts = mapOf(35L to artifacts(artifact(35), artifact(35, attempt = 2))),
        ) as VersionUpdateResult.UpdateAvailable

        assertEquals("beta #35.2", result.latestTag)
        assertEquals(
            VersionUpdateResult.UpToDate,
            checkBeta(
                number = 35,
                runs = runs(run(35, attempt = 2)),
                artifacts = mapOf(35L to artifacts(artifact(35, legacyName = true))),
            ),
        )
    }

    @Test
    fun partialRerunStillOffersThePreviousApkToOlderInstalledBuilds() = runBlocking {
        for (legacyName in listOf(false, true)) {
            val result = checkBeta(
                number = 34,
                runs = runs(run(35, attempt = 2)),
                artifacts = mapOf(35L to artifacts(artifact(35, legacyName = legacyName))),
            ) as VersionUpdateResult.UpdateAvailable

            assertEquals("beta #35.1", result.latestTag)
            assertEquals("beta #34.1", result.currentBuildLabel)
        }
    }

    @Test
    fun partialRerunDoesNotInventAnUpdateForTheAlreadyInstalledApk() = runBlocking {
        for (legacyName in listOf(false, true)) {
            assertEquals(
                VersionUpdateResult.UpToDate,
                checkBeta(
                    number = 35,
                    runs = runs(run(35, attempt = 2)),
                    artifacts = mapOf(35L to artifacts(artifact(35, legacyName = legacyName))),
                ),
            )
        }
    }

    @Test
    fun firstAttemptsCanUseExistingArtifactNames() = runBlocking {
        assertTrue(
            checkBeta(
                number = 34,
                runs = runs(run(35)),
                artifacts = mapOf(35L to artifacts(artifact(35, legacyName = true))),
            ) is VersionUpdateResult.UpdateAvailable,
        )
    }

    @Test
    fun failedWrongBranchWrongWorkflowAndForkRunsAreIgnored() = runBlocking {
        val requests = mutableListOf<String>()
        val result = checkBeta(
            number = 30,
            runs = runs(
                run(40, conclusion = "failure"),
                run(39, status = "in_progress"),
                run(38, branch = "feature"),
                run(37, path = ".github/workflows/other.yml"),
                run(36, headRepository = "someone/MeiloX"),
                run(35, event = "pull_request"),
                run(34),
            ),
            artifacts = mapOf(34L to artifacts(artifact(34))),
            requests = requests,
        ) as VersionUpdateResult.UpdateAvailable

        assertEquals("beta #34.1", result.latestTag)
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredDeletedAndUnrelatedArtifactsFallBackToTheLatestDownloadableBuild() = runBlocking {
        val result = checkBeta(
            number = 30,
            runs = runs(run(33), run(35), run(34), run(32)),
            artifacts = mapOf(
                35L to artifacts(artifact(35, expired = true)),
                34L to artifacts(),
                33L to artifacts(artifact(33, name = "other-file-33.1")),
                32L to artifacts(artifact(32)),
            ),
        ) as VersionUpdateResult.UpdateAvailable

        assertEquals("beta #32.1", result.latestTag)
    }

    @Test
    fun artifactMustBelongToTheSelectedRunAndRemainAvailable() = runBlocking {
        val invalidArtifacts = listOf(
            artifact(35, expiresAt = "2000-01-01T00:00:00Z"),
            artifact(35, runId = 1000),
            artifact(35, sha = "another-commit"),
            artifact(35, branch = "another-branch"),
            artifact(35, repositoryId = 7),
            artifact(35, size = 0),
            artifact(35, attempt = 2),
            artifact(35, name = "MeiloX-not-a-version-35.1"),
        )
        for (artifact in invalidArtifacts) {
            assertEquals(
                VersionUpdateResult.Failed,
                checkBeta(34, runs = runs(run(35)), artifacts = mapOf(35L to artifacts(artifact))),
            )
        }
    }

    @Test
    fun artifactFallbackHasAFixedRequestBudget() = runBlocking {
        val requests = mutableListOf<String>()
        assertEquals(
            VersionUpdateResult.Failed,
            checkBeta(
                number = 1,
                runs = runs(*(2L..10L).map { run(it) }.toTypedArray()),
                artifacts = (2L..10L).associateWith { artifacts() },
                requests = requests,
            ),
        )
        assertEquals(6, requests.size)
    }

    @Test
    fun fullRunPagesCanFallBackToTheNextPage() = runBlocking {
        val requests = mutableListOf<String>()
        val result = VersionUpdateChecker.checkWithFetcher("1.54.6", true, ciBuild(34)) { url ->
            requests += url
            when {
                "&page=1" in url -> runs(*Array(30) { run(100L + it, branch = "feature") })
                "&page=2" in url -> runs(run(35))
                "/runs/1035/artifacts" in url -> artifacts(artifact(35))
                else -> error("Unexpected endpoint: $url")
            }
        }
        assertTrue(result is VersionUpdateResult.UpdateAvailable)
        assertEquals(3, requests.size)
    }

    @Test
    fun stableChecksKeepTagOrderingAndIgnoreMissingCiMetadata() = runBlocking {
        val requests = mutableListOf<String>()
        val tags = JsonParser.parseString(
            """[{"name":"unrelated"},{"name":"v1.54.7-beta.1"},{"name":"v1.54.7"},{"name":"1.54.6"}]""",
        )
        val result = VersionUpdateChecker.checkWithFetcher("1.54.6", false, localBuild()) { url ->
            requests += url
            tags
        } as VersionUpdateResult.UpdateAvailable

        assertEquals("v1.54.7", result.latestTag)
        assertEquals("https://github.com/NEORUAA/MeiloX/releases/tag/v1.54.7", result.releaseUrl)
        assertFalse(result.isBeta)
        assertEquals(null, result.currentBuildLabel)
        assertEquals(listOf("https://api.github.com/repos/NEORUAA/MeiloX/tags?per_page=100"), requests)
        assertEquals(
            VersionUpdateResult.UpToDate,
            VersionUpdateChecker.checkWithFetcher("1.54.7", false, localBuild()) { tags },
        )
    }

    @Test
    fun malformedResponsesAndHttpFailuresBecomeFailed() = runBlocking {
        assertEquals(
            VersionUpdateResult.Failed,
            VersionUpdateChecker.checkWithFetcher("1.54.6", true, ciBuild(34)) {
                JsonParser.parseString("null")
            },
        )
        assertEquals(
            VersionUpdateResult.Failed,
            VersionUpdateChecker.checkWithFetcher("1.54.6", false, localBuild()) {
                throw java.io.IOException("HTTP 403")
            },
        )
    }

    @Test
    fun cancellationPropagatesToTheCaller() = runBlocking {
        val cancellation = CancellationException("Update check cancelled")
        try {
            VersionUpdateChecker.checkWithFetcher("1.54.6", true, ciBuild(34)) { throw cancellation }
            fail("Cancellation must not become an update failure")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun localBuildWithoutCiMetadataUsesCommitAncestryInsteadOfItsBuildNumber() = runBlocking {
        val requests = mutableListOf<String>()
        val installed = localBuild()
        val result = checkLocalBuild(installed, requests) { comparison("ahead") }
            as VersionUpdateResult.UpdateAvailable

        assertEquals(0L, installed.ciRunNumber)
        assertEquals(0, installed.ciRunAttempt)
        assertEquals("beta #35.1", result.latestTag)
        assertEquals("build #1700000000 · ${installed.commitSha.take(7)}", result.currentBuildLabel)
        assertTrue(result.comparisonKnown)
        assertTrue(result.isBeta)
        assertEquals(3, requests.size)
        assertEquals(
            "https://api.github.com/repos/NEORUAA/MeiloX/compare/" +
                "${installed.commitSha}...${commitSha(35)}?per_page=1",
            requests.last(),
        )
        assertFalse(requests.any { "/tags" in it })
    }

    @Test
    fun sameCommitInALocalBuildIsCurrentWithoutAComparisonRequest() = runBlocking {
        val requests = mutableListOf<String>()
        assertEquals(
            VersionUpdateResult.UpToDate,
            checkLocalBuild(localBuild(commitSha = commitSha(35)), requests),
        )
        assertEquals(2, requests.size)
    }

    @Test
    fun localBuildDoesNotOfferAnAncestorOrIdenticalCommitAsAnUpdate() = runBlocking {
        for (status in listOf("behind", "identical")) {
            assertEquals(
                VersionUpdateResult.UpToDate,
                checkLocalBuild { comparison(status) },
            )
        }
    }

    @Test
    fun divergedLocalBuildStillOffersTheLatestBetaWithoutClaimingItIsNewer() = runBlocking {
        val result = checkLocalBuild { comparison("diverged") }
            as VersionUpdateResult.UpdateAvailable

        assertFalse(result.comparisonKnown)
        assertEquals("beta #35.1", result.latestTag)
        assertEquals("https://github.com/NEORUAA/MeiloX/actions/runs/1035#artifacts", result.releaseUrl)
    }

    @Test
    fun unknownCommit404StillOffersTheLatestBeta() = runBlocking {
        val result = checkLocalBuild(localBuild(commitSha = "f".repeat(40))) {
            throw VersionUpdateChecker.GitHubHttpException(404)
        } as VersionUpdateResult.UpdateAvailable

        assertFalse(result.comparisonKnown)
        assertEquals("beta #35.1", result.latestTag)
    }

    @Test
    fun dirtyBuildDoesNotTrustItsCiNumberOrCommitForUpdateOrdering() = runBlocking {
        val requests = mutableListOf<String>()
        val installed = localBuild(commitSha = commitSha(35), sourceDirty = true)
            .copy(ciRunNumber = 999, ciRunAttempt = 1)
        val result = checkLocalBuild(installed, requests) as VersionUpdateResult.UpdateAvailable

        assertFalse(result.comparisonKnown)
        assertTrue(result.currentBuildLabel!!.endsWith(" *"))
        assertEquals(2, requests.size)
    }

    @Test
    fun missingOrUnsafeCommitShaOffersBetaWithoutBuildingAComparisonUrl() = runBlocking {
        for (sha in listOf("", "../refs/main?extra=1", "abc123")) {
            val requests = mutableListOf<String>()
            val result = checkLocalBuild(localBuild(commitSha = sha), requests)
                as VersionUpdateResult.UpdateAvailable

            assertFalse(result.comparisonKnown)
            assertEquals("build #1700000000", result.currentBuildLabel)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun comparisonRateLimitsAndNetworkFailuresRemainFailures() = runBlocking {
        for (failure in listOf(
            VersionUpdateChecker.GitHubHttpException(403),
            VersionUpdateChecker.GitHubHttpException(429),
            java.io.IOException("Network unavailable"),
        )) {
            assertEquals(
                VersionUpdateResult.Failed,
                checkLocalBuild { throw failure },
            )
        }
        assertEquals(
            VersionUpdateResult.Failed,
            VersionUpdateChecker.checkWithFetcher("1.54.6", true, localBuild()) {
                throw VersionUpdateChecker.GitHubHttpException(404)
            },
        )
    }

    @Test
    fun comparisonCancellationPropagatesToTheCaller() = runBlocking {
        val cancellation = CancellationException("Commit comparison cancelled")
        try {
            checkLocalBuild { throw cancellation }
            fail("Comparison cancellation must not become an update failure")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private suspend fun checkLocalBuild(
        installedBuild: VersionUpdateChecker.InstalledBuild = localBuild(),
        requests: MutableList<String> = mutableListOf(),
        compareResponse: suspend () -> JsonElement = { error("Unexpected commit comparison") },
    ): VersionUpdateResult = VersionUpdateChecker.checkWithFetcher(
        currentVersion = "1.54.6",
        betaUpdatesEnabled = true,
        installedBuild = installedBuild,
    ) { url ->
        requests += url
        when {
            "/workflows/main.yml/runs" in url -> runs(run(35))
            "/runs/1035/artifacts" in url -> artifacts(artifact(35))
            "/compare/" in url -> compareResponse()
            else -> error("Unexpected endpoint: $url")
        }
    }

    private fun localBuild(
        commitSha: String = commitSha(34),
        sourceDirty: Boolean = false,
    ) = VersionUpdateChecker.InstalledBuild(
        number = 1_700_000_000L,
        commitSha = commitSha,
        sourceDirty = sourceDirty,
    )

    private fun ciBuild(number: Long, attempt: Int = 1) = localBuild().copy(
        ciRunNumber = number,
        ciRunAttempt = attempt,
    )

    private fun comparison(status: String): JsonElement =
        JsonParser.parseString("""{"status":"$status"}""")

    private fun commitSha(number: Long): String = number.toString(16).padStart(40, '0')

    private suspend fun checkBeta(
        number: Long,
        attempt: Int = 1,
        runs: JsonElement,
        artifacts: Map<Long, JsonElement> = emptyMap(),
        requests: MutableList<String> = mutableListOf(),
    ): VersionUpdateResult = VersionUpdateChecker.checkWithFetcher(
        currentVersion = "1.54.6",
        betaUpdatesEnabled = true,
        installedBuild = ciBuild(number, attempt),
    ) { url ->
        requests += url
        if ("/workflows/main.yml/runs" in url) {
            runs
        } else {
            val runId = Regex("/runs/(\\d+)/artifacts").find(url)?.groupValues?.get(1)?.toLong()
                ?: error("Unexpected endpoint: $url")
            artifacts[runId - 1000] ?: error("Unexpected artifact request: $url")
        }
    }

    private fun runs(vararg values: String): JsonElement =
        JsonParser.parseString("""{"workflow_runs":[${values.joinToString(",")}]}""")

    private fun artifacts(vararg values: String): JsonElement =
        JsonParser.parseString("""{"artifacts":[${values.joinToString(",")}]}""")

    private fun run(
        number: Long,
        attempt: Int = 1,
        branch: String = "main",
        status: String = "completed",
        conclusion: String = "success",
        path: String = ".github/workflows/main.yml",
        headRepository: String = "NEORUAA/MeiloX",
        event: String = "push",
    ): String = """{
        "id":${1000 + number}, "run_number":$number, "run_attempt":$attempt,
        "head_branch":"$branch", "head_sha":"${commitSha(number)}", "path":"$path",
        "status":"$status", "conclusion":"$conclusion", "event":"$event",
        "repository":{"id":123,"full_name":"NEORUAA/MeiloX"},
        "head_repository":{"id":123,"full_name":"$headRepository"}
    }"""

    private fun artifact(
        number: Long,
        attempt: Int = 1,
        legacyName: Boolean = false,
        name: String = "MeiloX-1.54.6-$number" + if (legacyName) "" else ".$attempt",
        expired: Boolean = false,
        expiresAt: String = "2099-01-01T00:00:00Z",
        runId: Long = 1000 + number,
        sha: String = commitSha(number),
        branch: String = "main",
        repositoryId: Long = 123,
        size: Long = 1024,
    ): String = """{
        "name":"$name", "expired":$expired, "expires_at":"$expiresAt", "size_in_bytes":$size,
        "workflow_run":{"id":$runId, "head_sha":"$sha", "head_branch":"$branch",
            "repository_id":$repositoryId,"head_repository_id":$repositoryId}
    }"""
}
