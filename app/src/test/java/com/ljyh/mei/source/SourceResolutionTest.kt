package com.ljyh.mei.source

import com.ljyh.mei.source.api.*
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.availableFor
import com.ljyh.mei.source.core.*
import com.ljyh.mei.source.platform.SourceWebOwner
import com.ljyh.mei.source.platform.SourceWebRecipe
import com.ljyh.mei.ui.screen.source.mergeFallbackOrder
import com.ljyh.mei.ui.screen.source.orderedPlayableInstances
import com.ljyh.mei.ui.screen.search.SearchType
import com.ljyh.mei.ui.screen.search.browsableProviders
import com.ljyh.mei.ui.screen.search.libraryProviders
import com.ljyh.mei.ui.screen.search.searchableProviders
import com.ljyh.mei.ui.screen.search.sourceSearchHasMore
import com.ljyh.mei.ui.screen.search.supportedSourceSearchType
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import okhttp3.RequestBody.Companion.toRequestBody

class SourceResolutionTest {
    @Test fun sharedArtistInformationPreservesOriginalFieldsAndOpaqueIdentity() = runTest {
        val parser = com.ljyh.mei.source.script.ScriptContentParser("qq", 2, emptySet()) { it }
        val artist = parser.artist(com.google.gson.JsonParser.parseString("""{
            "artistId":"artist/A-001","name":"Artist","description":"Biography",
            "alternateNames":["Alias"],"trackCount":12,"albumCount":4,"musicVideoCount":0,
            "identities":["Composer"],"expertise":[{"name":"Piano","count":9},{"name":"Voice"}],
            "translatedNames":["Translated"],"aliases":["Alias"]
        }""").asJsonObject)
        assertEquals(ArtistRef("qq", "artist/A-001"), artist.ref)
        assertEquals(12, artist.trackCount)
        assertEquals(4, artist.albumCount)
        assertEquals(0, artist.musicVideoCount)
        assertEquals(listOf("Composer"), artist.identities)
        assertEquals(listOf(SourceArtistExpertise("Piano", 9), SourceArtistExpertise("Voice")), artist.expertise)
        assertEquals(listOf("Translated"), artist.translatedNames)
        assertEquals(listOf("Alias"), artist.aliases)
        assertNull(parser.artist(com.google.gson.JsonParser.parseString("""{"artistId":"A","name":"Artist"}""").asJsonObject).trackCount)
        for (count in listOf("-1", "2147483648", "1.5", "\"12\"")) {
            try {
                parser.artist(com.google.gson.JsonParser.parseString("""{"artistId":"A","name":"Artist","trackCount":$count}""").asJsonObject)
                fail("An invalid artist count must not enter the original information UI")
            } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
    }

    @Test fun dailyRecommendationsKeepTypedQueuesReasonsAndEmptyDays() = runTest {
        val parser = com.ljyh.mei.source.shared.SourceOnlineParser("qq", ProviderInstanceId("installed:qq"))
        val tracks = com.ljyh.mei.source.script.ScriptContentParser("qq", 2, emptySet()) { it }
        val value = com.google.gson.JsonParser.parseString("""{
            "title":"Daily","creatorName":"QQ","updatedAtMs":1234,
            "tracks":[{"trackId":"mid:0007","title":"Song","artists":[{"name":"Artist","artistId":"artist/A"}]}],
            "recommendations":[{"trackId":"mid:0007","reason":"A reason"},{"trackId":"other","reason":"Ignore"}]
        }""")
        val page = parser.daily(value, tracks::track)
        assertEquals(TrackRef("qq", "mid:0007"), page.tracks.single().toMediaMetadata().trackRef)
        assertEquals(ArtistRef("qq", "artist/A"), page.tracks.single().artists.single().ref)
        assertEquals(1234L, page.updatedAtMs)
        assertEquals("A reason", page.recommendations.single().reason)
        assertEquals("QQ", page.creatorName)
        assertTrue(parser.daily(com.google.gson.JsonParser.parseString("""{"tracks":[]}"""), tracks::track).tracks.isEmpty())
    }

    @Test fun outgoingSmsLoginDistinguishesSignedInFromCodeSent() {
        fun outcome(json: String) = com.ljyh.mei.source.shared.sourceSmsLoginAuthenticated(com.google.gson.JsonParser.parseString(json))
        assertTrue(outcome("""{"authenticated":true,"id":"user/007"}"""))
        assertFalse(outcome("""{"status":"sent"}"""))
        for (invalid in listOf("""{"id":"stale-user"}""", """{"authenticated":false,"id":"stale-user"}""", """{"authenticated":"true"}""")) {
            try { outcome(invalid); fail("Only an explicit successful login may dismiss the sign-in sheet") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
    }

    @Test fun advancedPresentationModelsPreserveOpaqueProviderIdentities() {
        val parser = com.ljyh.mei.source.shared.SourceOnlineParser("qq", ProviderInstanceId("installed:qq"))
        val profile = parser.profile(com.google.gson.JsonParser.parseString("""{"id":"user:0007","nickname":"Listener","artistId":"artist/A"}"""))
        assertEquals("user:0007", profile.ref.accountId.value)
        assertEquals(ArtistRef("qq", "artist/A"), profile.artistRef)
        val song = parser.cloud(com.google.gson.JsonParser.parseString("""{
            "id":"song-00042","name":"Song","artist":"Artist","album":"Album","durationMs":1000,"fileSize":10,"bitrate":320,"addTime":0
        }"""))
        assertEquals(TrackRef("qq", "song-00042"), song.track.ref)
        val comment = parser.comment(com.google.gson.JsonParser.parseString("""{
            "commentId":"comment:008","user":{"userId":"user:0007","nickname":"Listener"},
            "content":"A reply","time":1,"timeStr":"Now","liked":false,"likedCount":0,"replyCount":2
        }"""))
        assertEquals("comment:008", comment.commentId)
        assertEquals("user:0007", comment.user.id)
        assertEquals(2, comment.replyCount)
        val program = parser.program(com.google.gson.JsonParser.parseString("""{
            "id":"episode:007","name":"Episode","radioId":"podcast:002","radioName":"Podcast","mainSongId":"song-00042"
        }"""))
        assertEquals(TrackRef("qq", "song-00042"), program.mainTrackRef)
        assertEquals(program.mainTrackRef, program.toMediaMetadata().trackRef)
    }

    @Test fun webRecipesSeparatePageOriginsFromApprovedStaticResourceOrigins() {
        val owner = SourceWebOwner(setOf("music.example.com", "static.example.com"), sdkNamespace = "fixture.web")
        val parameters = com.google.gson.JsonObject().apply {
            addProperty("url", "https://music.example.com/account#details")
            add("allowedHosts", com.google.gson.JsonArray().apply { add("music.example.com") })
        }
        val recipe = SourceWebRecipe.parse(SourceHostEffect("web.page", parameters), owner)
        assertEquals("https://static.example.com/app.js", recipe.checkedUrl("https://static.example.com/app.js"))
        assertEquals("https://music.example.com/next", recipe.checkedPageUrl("/next", recipe.url))
        for (url in listOf("https://static.example.com/", "http://music.example.com/", "https://music.example.com:444/",
            "https://user:password@music.example.com/", "https://127.0.0.1/", "https://music.example.com.attacker.test/",
            "file:///data/user/0/file", "content://fixture/file", "javascript:alert(1)", "intent://fixture")) {
            try { recipe.checkedPageUrl(url); fail("An undeclared page URL must be blocked") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        }
    }

    @Test fun webRecipesOnlyMapDeclaredFeatureActionsAndFixedPromptVerbs() {
        val owner = SourceWebOwner(setOf("music.example.com"), sdkNamespace = "fixture.web", features = mapOf("web" to setOf("openPage")))
        val parameters = com.google.gson.JsonParser.parseString("""{
            "url":"https://music.example.com/","bridgeAliases":{"page.setTitle":"page.title"},
            "urlMappings":[{"scheme":"fixture","host":"page","pathPrefix":"/artist","feature":"web","action":"openPage","parameters":{"page":"Artist"}}]
        }""").asJsonObject
        val recipe = SourceWebRecipe.parse(SourceHostEffect("web.page", parameters), owner)
        assertEquals("page.title", recipe.bridgeAliases["page.setTitle"])
        assertEquals("Artist", recipe.navigation("fixture://page/artist/details", owner)?.parameters?.get("page")?.asString)
        assertNull(recipe.navigation("fixture://page/artists", owner))
        assertNull(recipe.navigation("fixture://other/artist", owner))
        assertNull(recipe.navigation("fixture://user@page/artist", owner))
        for (mutate in listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.getAsJsonObject("bridgeAliases").addProperty("execute", "android.reflect") },
            { it.getAsJsonArray("urlMappings")[0].asJsonObject.addProperty("action", "deleteAccount") },
            { it.getAsJsonArray("urlMappings")[0].asJsonObject.addProperty("scheme", "intent") },
        )) {
            val invalid = parameters.deepCopy().also(mutate)
            try { SourceWebRecipe.parse(SourceHostEffect("web.page", invalid), owner); fail("An undeclared bridge action must be blocked") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        }
    }

    @Test fun webAuthenticationRequiresExplicitSourceOwnedCookieNames() {
        val owner = SourceWebOwner(setOf("music.example.com"), cookieHeaders = mapOf("music.example.com" to "SESSION=private-fixture"), sdkNamespace = "fixture.web")
        val parameters = com.google.gson.JsonObject().apply { addProperty("url", "https://music.example.com/login") }
        try { SourceWebRecipe.parse(SourceHostEffect("web.auth", parameters), owner); fail("Authentication must declare returned cookies") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        parameters.add("cookieNames", com.google.gson.JsonArray().apply { add("SESSION") })
        val recipe = SourceWebRecipe.parse(SourceHostEffect("web.auth", parameters), owner)
        assertEquals(setOf("SESSION"), recipe.cookieNames)
        assertFalse(owner.toString().contains("private-fixture"))
        parameters.add("cookieHosts", com.google.gson.JsonArray().apply { add("other.example.com") })
        try { SourceWebRecipe.parse(SourceHostEffect("web.auth", parameters), owner); fail("Cookies may only leave approved page hosts") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
    }

    @Test fun webMultipartKeepsSelectedFilesNativeAndPreservesRepeatedFields() {
        val parameters = com.google.gson.JsonParser.parseString("""{"multipart":[
            {"name":"tag","value":"first"},{"name":"tag","value":"second"},
            {"name":"asset","filename":"fixture.bin","file":{"handle":"selected","offset":2,"length":3}},
            {"name":"generated","filename":"empty.txt","bytes":"","mimeType":"text/plain"}
        ]}""").asJsonObject
        val handles = mutableListOf<String>()
        val body = com.ljyh.mei.source.platform.sourceWebRequestBody(parameters, emptyMap()) { file ->
            handles += file.get("handle").asString
            assertEquals(2, file.get("offset").asInt)
            assertEquals(3, file.get("length").asInt)
            "abc".toRequestBody()
        } as okhttp3.MultipartBody
        assertEquals(listOf("selected"), handles)
        assertEquals(4, body.parts.size)
        val serialized = okio.Buffer().also(body::writeTo).readUtf8()
        assertTrue(serialized.contains("name=\"tag\""))
        assertTrue(serialized.indexOf("first") < serialized.indexOf("second"))
        assertTrue(serialized.contains("filename=\"fixture.bin\""))
        assertTrue(serialized.contains("abc"))
        val empty = com.google.gson.JsonParser.parseString("""{"multipart":[]}""").asJsonObject
        assertNotNull(com.ljyh.mei.source.platform.sourceWebRequestBody(empty, emptyMap()) { error("No file was selected") })
    }

    @Test fun webUploadDescriptorsRejectAmbiguousBodiesAndUnselectedFiles() {
        val inputs = listOf("""{"body":"YWJj","file":{"handle":"selected"}}""",
            """{"multipart":[{"name":"asset","file":{"handle":"missing"}}]}""",
            """{"multipart":[{"name":"asset","bytes":"?"}]}""")
        for (input in inputs) {
            try {
                com.ljyh.mei.source.platform.sourceWebRequestBody(com.google.gson.JsonParser.parseString(input).asJsonObject, emptyMap()) {
                    throw SourceException(SourceErrorCode.PERMISSION_DENIED)
                }
                fail("A malformed or unselected upload must be rejected")
            } catch (_: SourceException) { }
        }
        val repeated = com.google.gson.JsonParser.parseString("""{"multipart":[
            {"name":"a","file":{"handle":"selected"}},{"name":"b","file":{"handle":"selected"}}
        ]}""").asJsonObject
        try {
            com.ljyh.mei.source.platform.sourceWebRequestBody(repeated, emptyMap()) {
                object : okhttp3.RequestBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = 1_073_741_824L
                    override fun writeTo(sink: okio.BufferedSink) { fail("Oversized multipart must reject before opening a file") }
                }
            }
            fail("Repeating a selected handle cannot multiply the upload budget")
        } catch (_: SourceException) { }
    }

    private class EffectProvider : MusicProvider, AccountScopedFeatureCapability, AccountRevisionCapability {
        override val id = "effect-fixture"
        override val instanceId = ProviderInstanceId("installed:effect-fixture")
        override val displayName = "Effect fixture"
        override var version = "1"
        override var status = SourceStatus.READY
        override val capabilities = setOf(SourceCapability.FEATURES)
        override val features = mapOf("account" to setOf("login"), "discovery" to setOf("cloudShuffle", "officialShuffle"))
        override val accountRevision = kotlinx.coroutines.flow.MutableStateFlow("account-A")
        val calls = mutableListOf<com.google.gson.JsonObject>()
        var committedRevision: String? = null
        var respond: suspend (com.google.gson.JsonObject) -> com.google.gson.JsonElement = { parameters ->
            if (parameters.has("\$continuation")) parameters.getAsJsonObject("\$continuation").get("effect")
            else effectEnvelope()
        }
        override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject): com.google.gson.JsonElement {
            calls += parameters.deepCopy()
            return respond(parameters)
        }
        override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject,
            expectedAccountRevision: String?, onSessionCommitted: ((String?) -> Unit)?): com.google.gson.JsonElement {
            if (accountRevision.value != expectedAccountRevision) throw CancellationException("Stale account")
            return invokeFeature(feature, action, parameters).also { response ->
                committedRevision?.let { next ->
                    if (!(response.isJsonObject && response.asJsonObject.has("hostEffect"))) onSessionCommitted?.invoke(next)
                    kotlinx.coroutines.yield()
                    accountRevision.value = next
                    committedRevision = null
                }
            }
        }
    }

    @Test fun aScreenKeepsItsOwnSuccessfulLoginButRejectsLaterExternalAccountChanges() = runTest {
        val source = EffectProvider()
        val router = SourceRouter(ProviderRegistry(listOf(source)))
        val bound = BoundSourceFeatures(router, SourceFeatureExecutor(router), source, source.accountRevision.value,
            "login-screen", SourceHostEffectHandler.Unsupported)
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) { bound.awaitInvalidation() }
        source.respond = {
            source.committedRevision = "account-B"
            com.google.gson.JsonPrimitive("authenticated")
        }
        assertEquals("authenticated", bound.invoke("account", "login", allowSessionChange = true).asString)
        runCurrent()
        bound.ensureCurrent()
        assertTrue(invalidation.isActive)
        source.accountRevision.value = "account-C"
        try { invalidation.await(); fail("An unrelated account replacement must invalidate the screen") }
        catch (_: CancellationException) { }
    }

    @Test fun unavailableSourcesDistinguishMissingAccountsAndRuntimeFailuresFromDisabling() {
        val source = EffectProvider()
        val registry = ProviderRegistry(listOf(source))
        for ((status, code) in listOf(SourceStatus.NEEDS_CONFIGURATION to SourceErrorCode.AUTH_REQUIRED,
            SourceStatus.INCOMPATIBLE to SourceErrorCode.UNSUPPORTED, SourceStatus.FAILED to SourceErrorCode.PLUGIN,
            SourceStatus.DISABLED to SourceErrorCode.MISSING_PROVIDER)) {
            source.status = status
            registry.refresh()
            try { registry.requireProvider(source.id); fail("An unavailable source cannot execute") }
            catch (error: SourceException) { assertEquals(code, error.code) }
        }
    }

    companion object {
        private fun effectEnvelope(type: String = "sdk.call", state: com.google.gson.JsonElement = com.google.gson.JsonObject()): com.google.gson.JsonObject =
            com.google.gson.JsonObject().apply {
                add("hostEffect", com.google.gson.JsonObject().apply {
                    addProperty("type", type)
                    add("parameters", com.google.gson.JsonObject().apply { addProperty("secret", "private-fixture-value") })
                })
                add("continuation", state)
            }
    }

    @Test fun internalSourceContinuationNeverCallsPrivilegedHostHandlers() = runTest {
        val source = EffectProvider()
        source.respond = { parameters ->
            if (!parameters.has("\$continuation")) effectEnvelope("source.continue")
            else parameters.getAsJsonObject("\$continuation").get("effect")
        }
        var dispatched = 0
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val result = executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
            dispatched++
            SourceHostEffectResult.Failure(SourceErrorCode.UNSUPPORTED)
        }).asJsonObject
        assertEquals(0, dispatched)
        assertEquals(2, source.calls.size)
        assertTrue(result.get("ok").asBoolean)
        assertTrue(result.get("result").isJsonNull)
    }

    @Test fun featureExecutionsReleaseOnlyTheirOwnHostResourcesOnCompletionAndCancellation() = runTest {
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val entered = CompletableDeferred<SourceEffectScope>()
        val released = mutableListOf<String>()
        val handler = object : SourceHostEffectHandler {
            override suspend fun handle(effect: SourceHostEffect, scope: SourceEffectScope): SourceHostEffectResult {
                entered.complete(scope)
                awaitCancellation()
            }
            override suspend fun release(executionId: String) { released += executionId }
        }
        val pending = async { executor.execute(source.id, "account", "login", handler = handler) }
        val scope = entered.await()
        pending.cancelAndJoin()
        assertEquals(listOf(scope.executionId), released)
        assertFalse(scope.isActive)
        source.respond = { com.google.gson.JsonPrimitive(true) }
        assertTrue(executor.execute(source.id, "account", "login", handler = handler).asBoolean)
        assertEquals(2, released.distinct().size)
    }

    @Test fun loginSessionOptInOnlyAcceptsFinalResultsFromTheSameProvider() = runTest {
        for (mode in listOf("default", "final", "effect", "replacement")) {
            val source = EffectProvider()
            val registry = ProviderRegistry(listOf(source))
            source.respond = {
                source.accountRevision.value = "account-B"
                if (mode == "replacement") registry.register(EffectProvider())
                if (mode == "effect") effectEnvelope() else com.google.gson.JsonPrimitive("authenticated")
            }
            val executor = SourceFeatureExecutor(SourceRouter(registry))
            var effects = 0
            try {
                val result = executor.execute(source.id, "account", "login", allowSessionChange = mode != "default",
                    handler = SourceHostEffectHandler { _, _ -> effects++; SourceHostEffectResult.Success(com.google.gson.JsonNull.INSTANCE) })
                assertEquals("final", mode)
                assertEquals("authenticated", result.asString)
            } catch (_: CancellationException) {
                assertNotEquals("final", mode)
            }
            assertEquals(0, effects)
            assertEquals(1, source.calls.size)
        }
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val operation = async {
            executor.execute(source.id, "account", "login", allowSessionChange = true,
                handler = SourceHostEffectHandler { _, _ ->
                    try { entered.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
                })
        }
        entered.await()
        source.accountRevision.value = "account-C"
        stopped.await()
        try { operation.await(); fail("Login opt-in must not extend a pending effect across account changes") }
        catch (_: CancellationException) { }
        assertEquals(1, source.calls.size)
    }

    @Test fun hostEffectsResumeTheSameActionWithOpaqueStateAndFreshBoundTokens() = runTest {
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val parameters = com.google.gson.JsonObject().apply { addProperty("intent", "sign-in") }
        val tokens = mutableListOf<String>()
        val scopes = mutableListOf<SourceEffectScope>()
        val expectedState = com.google.gson.JsonObject().apply { addProperty("stage", "challenge") }
        source.respond = { p ->
            if (!p.has("\$continuation")) effectEnvelope(state = expectedState)
            else p.getAsJsonObject("\$continuation").also {
                assertEquals("sign-in", p.get("intent").asString)
                assertEquals(expectedState, it.get("state"))
                assertEquals(tokens.last(), it.get("token").asString)
            }.get("effect")
        }
        val handler = SourceHostEffectHandler { effect, scope ->
            assertEquals("sdk.call", effect.type)
            assertEquals(source.id, scope.providerId)
            assertEquals(source.instanceId, scope.instanceId)
            assertNotEquals(scope.providerId, scope.instanceId.value)
            assertEquals("1", scope.providerVersion)
            assertEquals("account-A", scope.accountRevision)
            assertEquals("account", scope.feature)
            assertEquals("login", scope.action)
            assertEquals("screen-A", scope.scopeId)
            assertTrue(scope.isActive)
            scope.ensureCurrent()
            assertTrue(java.util.Base64.getUrlDecoder().decode(scope.token).size >= 64)
            assertFalse(effect.toString().contains("private-fixture-value"))
            assertFalse(scope.toString().contains(scope.token))
            scopes += scope
            tokens += scope.token
            SourceHostEffectResult.Success(com.google.gson.JsonObject().apply { addProperty("accepted", true) })
        }
        repeat(2) {
            val result = executor.execute(source.id, "account", "login", parameters, "screen-A", handler).asJsonObject
            assertTrue(result.get("ok").asBoolean)
            assertTrue(result.getAsJsonObject("result").get("accepted").asBoolean)
        }
        assertEquals(4, source.calls.size)
        assertEquals(2, tokens.distinct().size)
        assertFalse(parameters.has("\$continuation"))
        for (scope in scopes) {
            assertFalse(scope.isActive)
            try { scope.ensureCurrent(); fail("A completed effect scope must expire") } catch (_: CancellationException) { }
        }
    }

    @Test fun unsupportedAndFailedHostEffectsReturnOnlyTypedFailureCodes() = runTest {
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val unsupported = executor.execute(source.id, "account", "login").asJsonObject
        assertFalse(unsupported.get("ok").asBoolean)
        assertEquals("UNSUPPORTED", unsupported.get("code").asString)
        val rejected = executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
            throw SourceException(SourceErrorCode.AUTH_REQUIRED, "private-host-error")
        }).asJsonObject
        assertEquals("AUTH_REQUIRED", rejected.get("code").asString)
        assertFalse(rejected.toString().contains("private-host-error"))
        val failed = executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
            throw IllegalStateException("private-platform-error")
        }).asJsonObject
        assertEquals("UNAVAILABLE", failed.get("code").asString)
        assertFalse(failed.toString().contains("private-platform-error"))
    }

    @Test fun revokedChangedAndCancelledHostEffectsStopInFlightWithoutCallbacks() = runTest {
        for (change in listOf("remove", "replace", "account", "version", "disable", "cancel")) {
            val source = EffectProvider()
            val registry = ProviderRegistry(listOf(source))
            val executor = SourceFeatureExecutor(SourceRouter(registry))
            val entered = CompletableDeferred<SourceEffectScope>()
            val stopped = CompletableDeferred<Unit>()
            val operation = async {
                executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, scope ->
                    try {
                        entered.complete(scope)
                        awaitCancellation()
                    } finally { stopped.complete(Unit) }
                })
            }
            val scope = entered.await()
            when (change) {
                "remove" -> registry.unregister(source.instanceId)
                "replace" -> registry.register(EffectProvider())
                "account" -> source.accountRevision.value = "account-B"
                "version" -> { source.version = "2"; registry.refresh() }
                "disable" -> { source.status = SourceStatus.DISABLED; registry.refresh() }
                else -> operation.cancel()
            }
            stopped.await()
            try { operation.await(); fail("A retired host effect must cancel: $change") } catch (_: CancellationException) { }
            assertFalse(scope.isActive)
            assertEquals("No callback may reach the retired provider: $change", 1, source.calls.size)
        }
    }

    @Test fun anEffectCannotDispatchItsCallbackToAReplacementProvider() = runTest {
        val source = EffectProvider()
        val replacement = EffectProvider()
        val registry = ProviderRegistry(listOf(source))
        val executor = SourceFeatureExecutor(SourceRouter(registry))
        try {
            executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
                registry.register(replacement)
                SourceHostEffectResult.Success(com.google.gson.JsonPrimitive("retired-result"))
            })
            fail("Replacing the source must retire its callback")
        } catch (_: CancellationException) { }
        assertEquals(1, source.calls.size)
        assertTrue(replacement.calls.isEmpty())
    }

    @Test fun featureCallsCanProceedWhileAnotherScreenIsWaitingForAHostEffect() = runTest {
        val source = EffectProvider()
        val router = SourceRouter(ProviderRegistry(listOf(source)))
        val executor = SourceFeatureExecutor(router)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
                entered.complete(Unit)
                release.await()
                SourceHostEffectResult.Success(com.google.gson.JsonPrimitive(true))
            })
        }
        entered.await()
        val concurrent = router.feature(source.id, "account", "login")
        assertTrue(concurrent.asJsonObject.has("hostEffect"))
        release.complete(Unit)
        assertTrue(first.await().asJsonObject.get("ok").asBoolean)
        assertEquals(3, source.calls.size)
    }

    @Test fun completeShuffleHistoryHasABoundedInputBudgetWithoutExpandingOtherBoundaries() = runTest {
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        val parameters = com.google.gson.JsonObject().apply {
            add("ids", com.google.gson.JsonArray().apply { repeat(10_000) { add((2_000_000_000L + it).toString()) } })
            add("history", com.google.gson.JsonArray().apply {
                repeat(10_000) { index -> add(com.google.gson.JsonObject().apply {
                    addProperty("id", (2_000_000_000L + index).toString())
                    addProperty("play_times", index + 1)
                    addProperty("timestamp", 1_700_000_000_000L + index)
                    addProperty("duration", 0)
                }) }
            })
        }
        assertTrue(parameters.toString().toByteArray().size > 256 * 1024)
        source.respond = { input ->
            assertEquals(parameters.get("ids"), input.get("ids"))
            assertEquals(parameters.get("history"), input.get("history"))
            if (input.has("\$continuation")) com.google.gson.JsonPrimitive(true)
            else effectEnvelope("source.continue")
        }
        assertTrue(executor.execute(source.id, "discovery", "officialShuffle", parameters).asBoolean)
        assertEquals(2, source.calls.size)
        try {
            executor.execute(source.id, "account", "login", parameters)
            fail("Other feature inputs must retain the ordinary budget")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        try {
            executor.execute(source.id, "discovery", "officialShuffle", com.google.gson.JsonObject().apply {
                addProperty("oversized", "i".repeat(2 * 1024 * 1024))
            })
            fail("Complete shuffle inputs must remain bounded")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        source.respond = { com.google.gson.JsonPrimitive("r".repeat(256 * 1024)) }
        try {
            executor.execute(source.id, "discovery", "officialShuffle", parameters)
            fail("A bulk input must not enlarge source responses")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        source.respond = { effectEnvelope(state = com.google.gson.JsonPrimitive("s".repeat(64 * 1024))) }
        try {
            executor.execute(source.id, "discovery", "officialShuffle", parameters)
            fail("A bulk input must not enlarge continuation state")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        source.calls.clear()
        source.respond = { effectEnvelope("source.continue") }
        try {
            executor.execute(source.id, "discovery", "officialShuffle")
            fail("Complete shuffle preparation must still stop an endless continuation chain")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        assertEquals(9, source.calls.size)
    }

    @Test fun hostEffectValidationBoundsPrivilegedDispatchAndContinuationResources() = runTest {
        val source = EffectProvider()
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        var dispatched = 0
        val handler = SourceHostEffectHandler { _, _ -> dispatched++; SourceHostEffectResult.Success(com.google.gson.JsonNull.INSTANCE) }
        val invalid = listOf(
            effectEnvelope("android.reflection") to SourceErrorCode.UNSUPPORTED,
            effectEnvelope(state = com.google.gson.JsonPrimitive("🎵".repeat(17_000))) to SourceErrorCode.INVALID_RESPONSE,
            effectEnvelope(state = com.google.gson.JsonPrimitive("s".repeat(64 * 1024))) to SourceErrorCode.INVALID_RESPONSE,
            effectEnvelope().apply { getAsJsonObject("hostEffect").getAsJsonObject("parameters").addProperty("large", "s".repeat(64 * 1024)) } to SourceErrorCode.INVALID_RESPONSE,
            effectEnvelope().apply { addProperty("unreviewed", true) } to SourceErrorCode.INVALID_RESPONSE,
        )
        for ((response, expected) in invalid) {
            source.respond = { response }
            try { executor.execute(source.id, "account", "login", handler = handler); fail() }
            catch (error: SourceException) { assertEquals(expected, error.code) }
        }
        assertEquals(0, dispatched)
        source.respond = { parameters -> if (parameters.has("\$continuation")) parameters else effectEnvelope() }
        try {
            executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
                SourceHostEffectResult.Success(com.google.gson.JsonPrimitive("r".repeat(128 * 1024)))
            })
            fail("Oversized effect results must not re-enter the plugin")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        assertFalse(source.calls.last().has("\$continuation"))
        source.respond = { effectEnvelope(state = com.google.gson.JsonPrimitive("s".repeat(60 * 1024))) }
        try {
            executor.execute(source.id, "account", "login", com.google.gson.JsonObject().apply { addProperty("initial", "i".repeat(80 * 1024)) },
                handler = SourceHostEffectHandler { _, _ -> SourceHostEffectResult.Success(com.google.gson.JsonPrimitive("r".repeat(120 * 1024))) })
            fail("Individually bounded fields must still fit the complete callback budget")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
    }

    @Test fun repeatedHostEffectsStopAtEightAndRejectInjectedContinuationInputs() = runTest {
        val source = EffectProvider()
        source.respond = { effectEnvelope() }
        val executor = SourceFeatureExecutor(SourceRouter(ProviderRegistry(listOf(source))))
        var handled = 0
        try {
            executor.execute(source.id, "account", "login", handler = SourceHostEffectHandler { _, _ ->
                handled++
                SourceHostEffectResult.Success(com.google.gson.JsonNull.INSTANCE)
            })
            fail("An endless host-effect chain must be bounded")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        assertEquals(8, handled)
        assertEquals(9, source.calls.size)
        try {
            executor.execute(source.id, "account", "login", com.google.gson.JsonObject().apply { add("\$continuation", com.google.gson.JsonObject()) })
            fail("Only the executor may create a callback envelope")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        assertEquals(9, source.calls.size)
    }

    @Test fun optionalSourceChoicesDoNotRequireTrackSearchOrInventLibrarySupport() {
        val feed = object : MusicProvider, FeedCapability {
            override val id = "feed-only"
            override val displayName = "Feed"
            override val capabilities = setOf(SourceCapability.FEED)
            override var feedKinds = setOf(FeedKind.HOME)
            override suspend fun getFeed(kind: FeedKind, categoryId: String?, page: PageRequest): SourceFeedPage =
                error("Selecting a source must not fetch content")
        }
        val library = object : MusicProvider, LibraryCapability {
            override val id = "library-only"
            override val displayName = "Library"
            override val capabilities = setOf(SourceCapability.LIBRARY)
            override var collections = setOf(LibraryCollection.SAVED_ALBUMS)
            override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> =
                error("Selecting a source must not fetch content")
        }
        val albums = object : MusicProvider, TypedSearchCapability {
            override val id = "album-search-only"
            override val displayName = "Albums"
            override val capabilities = setOf(SourceCapability.SEARCH)
            override var searchTypes = setOf(ContentType.ALBUM)
            override var status = SourceStatus.READY
            override suspend fun search(query: String, type: ContentType, page: PageRequest): SourcePage<SourceItem> =
                error("Selecting a source must not fetch content")
        }
        val declarationsOnly = object : MusicProvider {
            override val id = "declarations-only"
            override val displayName = "Declarations"
            override val capabilities = setOf(SourceCapability.FEED, SourceCapability.LIBRARY, SourceCapability.SEARCH)
        }
        val providers = listOf(feed, library, albums, declarationsOnly)
        assertEquals(listOf(feed, library, albums), browsableProviders(providers))
        assertEquals(listOf(library), libraryProviders(providers))
        assertEquals(listOf(albums), searchableProviders(providers))
        feed.feedKinds = emptySet()
        library.collections = emptySet()
        albums.status = SourceStatus.DISABLED
        assertTrue(browsableProviders(providers).isEmpty())
        assertTrue(libraryProviders(providers).isEmpty())
        assertTrue(searchableProviders(providers).isEmpty())
        albums.status = SourceStatus.READY
        albums.searchTypes = emptySet()
        assertTrue(searchableProviders(providers).isEmpty())
        assertTrue(browsableProviders(providers).isEmpty())
    }

    @Test fun albumOnlySearchFallsBackToItsSupportedTabAndEmptyCapabilitiesSelectNothing() {
        val albumOnly = setOf(SearchType.Album)
        assertEquals(SearchType.Album, supportedSourceSearchType(SearchType.Album.type, albumOnly))
        assertEquals(SearchType.Album, supportedSourceSearchType(SearchType.Song.type, albumOnly))
        assertEquals(SearchType.Album, supportedSourceSearchType(SearchType.Podcast.type, albumOnly))
        assertEquals(SearchType.Album, supportedSourceSearchType(Int.MAX_VALUE, albumOnly))
        assertNull(supportedSourceSearchType(SearchType.Song.type, emptySet()))
    }

    @Test fun searchPaginationStopsRepeatedCursorsAndPagesWithoutNewResults() {
        val first = SourceItem.TrackItem(Track(TrackRef("search", "first"), "First", emptyList()))
        val second = SourceItem.TrackItem(Track(TrackRef("search", "second"), "Second", emptyList()))
        val incoming = SourcePage<SourceItem>(listOf(second), hasMore = true, nextCursor = "next")
        val previous = listOf<SourceItem>(first)
        val merged = listOf<SourceItem>(first, second)

        assertTrue(sourceSearchHasMore(incoming, previous, merged, setOf("earlier")))
        assertTrue(sourceSearchHasMore(incoming.copy(nextCursor = null), previous, merged, emptySet()))
        assertFalse(sourceSearchHasMore(incoming, previous, merged, setOf("earlier", "next")))
        assertFalse(sourceSearchHasMore(incoming.copy(nextCursor = "earlier"), previous, merged, setOf("earlier", "next")))
        assertFalse(sourceSearchHasMore(incoming.copy(items = previous), previous, previous, emptySet()))
        assertFalse(sourceSearchHasMore(incoming.copy(items = emptyList()), previous, previous, emptySet()))
        assertFalse(sourceSearchHasMore(incoming.copy(hasMore = false), previous, merged, emptySet()))
    }

    private open class AccountProvider : MusicProvider, AccountCapability {
        override val id = "account"
        override val displayName = "Account"
        override val capabilities = setOf(SourceCapability.ACCOUNT)
        var reads = 0
        var result = SourceAccount(SourceAccountRef(instanceId, ProviderAccountId("user-A")), "Listener")
        var load: suspend () -> SourceAccount? = { result }
        override suspend fun account(): SourceAccount? { reads++; return load() }
    }

    private class RevisionAccountProvider : AccountProvider(), AccountRevisionCapability {
        override val accountRevision = kotlinx.coroutines.flow.MutableStateFlow<String?>("session-A")
    }

    @Test fun concurrentAccountReadsAndExplicitRefreshesShareEachCompletedRequest() = runTest {
        val source = RevisionAccountProvider()
        val gate = CompletableDeferred<Unit>()
        source.load = { gate.await(); source.result }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val initial = List(4) { async { router.account(source.id) } }
        runCurrent()
        assertEquals(1, source.reads)
        gate.complete(Unit)
        assertEquals(List(4) { source.result }, initial.awaitAll())
        assertEquals(source.result, router.account(source.id))
        assertEquals(1, source.reads)

        val refreshGate = CompletableDeferred<Unit>()
        source.result = source.result.copy(displayName = "Updated listener")
        source.load = { refreshGate.await(); source.result }
        val refreshed = List(4) { async { router.account(source.id, refresh = true) } }
        runCurrent()
        assertEquals(2, source.reads)
        refreshGate.complete(Unit)
        assertEquals(List(4) { source.result }, refreshed.awaitAll())
        assertEquals(source.result, router.account(source.id))
        assertEquals(2, source.reads)
    }

    @Test fun failedAccountRequestsAreSharedWithWaitingReadersAndRemainRetryable() = runTest {
        val source = RevisionAccountProvider()
        val gate = CompletableDeferred<Unit>()
        source.load = { gate.await(); throw SourceException(SourceErrorCode.TIMEOUT) }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val pending = List(3) { async { runCatching { router.account(source.id) } } }
        runCurrent()
        assertEquals(1, source.reads)
        gate.complete(Unit)
        pending.awaitAll().forEach {
            assertEquals(SourceErrorCode.TIMEOUT, (it.exceptionOrNull() as? SourceException)?.code)
        }
        assertEquals(1, source.reads)
        source.load = { source.result }
        assertEquals(source.result, router.account(source.id))
        assertEquals(2, source.reads)
    }

    @Test fun accountRevisionChangesRejectAnOldResponseAndInvalidateTheCachedAccount() = runTest {
        val source = RevisionAccountProvider()
        val gate = CompletableDeferred<Unit>()
        source.load = { val original = source.result; gate.await(); original }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val old = async { runCatching { router.account(source.id) } }
        runCurrent()
        source.accountRevision.value = "session-B"
        source.result = source.result.copy(ref = source.result.ref.copy(accountId = ProviderAccountId("user-B")))
        source.load = { source.result }
        assertEquals(source.result, router.account(source.id))
        gate.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(source.result, router.account(source.id))
        assertEquals(2, source.reads)

        source.accountRevision.value = "session-C"
        source.result = source.result.copy(displayName = "Third session")
        assertEquals(source.result, router.account(source.id))
        assertEquals(3, source.reads)
    }

    @Test fun sameVersionProviderReplacementRejectsItsPredecessorsPendingAccountResponse() = runTest {
        val source = RevisionAccountProvider()
        val gate = CompletableDeferred<Unit>()
        source.load = { gate.await(); source.result }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val old = async { runCatching { router.account(source.id) } }
        runCurrent()
        val replacement = RevisionAccountProvider().apply { result = result.copy(displayName = "Replacement listener") }
        assertEquals(source.version, replacement.version)
        assertEquals(source.accountRevision.value, replacement.accountRevision.value)
        registry.register(replacement)
        assertEquals(replacement.result, router.account(replacement.id))
        gate.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals(replacement.result, router.account(replacement.id))
        assertEquals(1, source.reads)
        assertEquals(1, replacement.reads)
    }

    @Test fun cancellingTheFirstAccountReadAllowsTheNextWaitingReaderToRetry() = runTest {
        val source = RevisionAccountProvider()
        source.load = { awaitCancellation() }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val first = async { router.account(source.id) }
        runCurrent()
        assertEquals(1, source.reads)
        source.load = { source.result }
        val next = async { router.account(source.id) }
        runCurrent()
        assertEquals(1, source.reads)
        first.cancelAndJoin()
        assertEquals(source.result, next.await())
        assertEquals(source.result, router.account(source.id))
        assertEquals(2, source.reads)
    }

    @Test fun providersWithoutAccountRevisionsDoNotReuseCompletedAccountReads() = runTest {
        val source = AccountProvider()
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        assertEquals(source.result, router.account(source.id))
        source.result = source.result.copy(ref = source.result.ref.copy(accountId = ProviderAccountId("user-B")))
        assertEquals(source.result, router.account(source.id))
        assertEquals(2, source.reads)
    }

    private open class ContentProvider : MusicProvider, TypedSearchCapability, FeedCapability, LibraryCapability,
        PlaylistCapability, LikeCapability, CollectionCapability, PlaylistEditCapability, AccountRevisionCapability {
        override val accountRevision = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override val id = "content"
        override val displayName = "Content"
        override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.FEED, SourceCapability.LIBRARY,
            SourceCapability.PLAYLIST, SourceCapability.LIKE, SourceCapability.COLLECTIONS, SourceCapability.PLAYLIST_EDIT)
        override val searchTypes = setOf(ContentType.TRACK, ContentType.PLAYLIST)
        override val feedKinds = setOf(FeedKind.HOME)
        override val collections = setOf(LibraryCollection.LIKED_TRACKS, LibraryCollection.CREATED_PLAYLISTS)
        override val collectionTypes = setOf(ContentType.PLAYLIST)
        override val playlistEditActions = PlaylistEditAction.entries.toSet()
        override var canWriteLikes = true
        var rejectWrite = false
        var foreignResult = false
        val writes = mutableListOf<String>()
        val track = Track(TrackRef(id, "track:雪/42"), "Song", listOf(SourceArtist("Artist")))
        val playlist = SourcePlaylist(PlaylistRef(id, "playlist:雪/42"), "Playlist")
        override suspend fun search(query: String, type: ContentType, page: PageRequest): SourcePage<SourceItem> =
            SourcePage(listOf(if (type == ContentType.TRACK) SourceItem.TrackItem(track)
                else SourceItem.PlaylistItem(playlist.copy(ref = if (foreignResult) PlaylistRef("foreign", "42") else playlist.ref))),
                hasMore = true, nextCursor = "next:page")
        override suspend fun getFeed(kind: FeedKind, categoryId: String?, page: PageRequest) = SourceFeedPage(
            listOf(SourceFeedSection("home", "Home", SourceFeedLayout.PLAYLISTS, listOf(SourceItem.PlaylistItem(playlist)))))
        override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> =
            SourcePage(listOf(if (collection == LibraryCollection.LIKED_TRACKS) SourceItem.TrackItem(track) else SourceItem.PlaylistItem(playlist)))
        override suspend fun playlist(ref: PlaylistRef) = playlist
        override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest) = SourcePage(listOf(track))
        override suspend fun liked(ref: TrackRef) = false
        override suspend fun setLiked(ref: TrackRef, liked: Boolean) {
            if (rejectWrite) throw SourceException(SourceErrorCode.AUTH_EXPIRED)
            writes += "like:${ref.trackId}"
        }
        override suspend fun saved(ref: ContentRef) = true
        override suspend fun setSaved(ref: ContentRef, saved: Boolean) { writes += "save:${ref.contentId}" }
        override suspend fun createPlaylist(title: String, description: String?): SourcePlaylist {
            writes += "create"
            return playlist.copy(title = title)
        }
        override suspend fun updatePlaylist(ref: PlaylistRef, title: String?, description: String?) { writes += "update" }
        override suspend fun deletePlaylist(ref: PlaylistRef) { writes += "delete" }
        override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>) { writes += "add" }
        override suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>) { writes += "remove" }
        override suspend fun reorderTracks(ref: PlaylistRef, tracks: List<TrackRef>) { writes += "reorder" }
    }

    @Test fun favoriteTogglesReadUnknownStateAndQueueOppositeConfirmedTargets() = runTest {
        var confirmed = false
        var reads = 0
        var failRead = false
        val targets = mutableListOf<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ContentProvider() {
            override suspend fun liked(ref: TrackRef): Boolean {
                reads++
                if (failRead) throw SourceException(SourceErrorCode.NETWORK)
                return confirmed
            }
            override suspend fun setLiked(ref: TrackRef, liked: Boolean) {
                targets += liked
                if (targets.size == 1) { entered.complete(Unit); release.await() }
                confirmed = liked
            }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val first = async { router.toggleLike(source.track.ref, null, source) }
        entered.await()
        val second = async { router.toggleLike(source.track.ref, null, source) }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(listOf(true), targets)
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(listOf(true, false), targets)
        assertFalse(confirmed)
        assertEquals(2L, router.contentRevision.value)
        failRead = true
        try { router.toggleLike(TrackRef(source.id, "unknown")); fail("An unread favorite must not invent false") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.NETWORK, error.code) }
        assertEquals(listOf(true, false), targets)
        assertEquals(2L, router.contentRevision.value)
    }

    @Test fun acceptedSubscriptionsSurviveCallerCancellationAndReadersWaitForReconciliation() = runTest {
        var confirmed = false
        var reads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ContentProvider() {
            override suspend fun saved(ref: ContentRef): Boolean { reads++; return confirmed }
            override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                entered.complete(Unit)
                release.await()
                confirmed = saved
            }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val caller = launch { router.setSaved(source.playlist.ref, true, null, source) }
        entered.await()
        caller.cancelAndJoin()
        val reader = async { router.saved(source.playlist.ref) }
        runCurrent()
        assertEquals(0, reads)
        assertFalse(reader.isCompleted)
        release.complete(Unit)
        assertTrue(reader.await())
        assertEquals(1L, router.contentRevision.value)
        assertTrue(caller.isCancelled)
    }

    @Test fun readsRacingMutationsRetryStaleValuesAndFailuresAcrossRelatedKeys() = runTest {
        for (likeRead in listOf(false, true)) for (failedResponse in listOf(false, true)) {
            var confirmed = false
            var reads = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun read(): Boolean {
                val snapshot = confirmed
                if (++reads == 1) {
                    entered.complete(Unit)
                    release.await()
                    if (failedResponse) throw SourceException(SourceErrorCode.NETWORK)
                }
                return snapshot
            }
            val source = object : ContentProvider() {
                override suspend fun saved(ref: ContentRef) = read()
                override suspend fun liked(ref: TrackRef) = read()
                override suspend fun setSaved(ref: ContentRef, saved: Boolean) { confirmed = saved }
                override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>) { confirmed = true }
            }
            val registry = ProviderRegistry(listOf(source))
            val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
            val reader = async { if (likeRead) router.liked(source.track.ref) else router.saved(source.playlist.ref) }
            entered.await()
            if (likeRead) router.addTracks(source.playlist.ref, listOf(source.track.ref))
            else router.setSaved(source.playlist.ref, true, null, source)
            release.complete(Unit)
            assertTrue(reader.await())
            assertEquals(if (likeRead) 2 else 1, reads)
        }
    }

    @Test fun duplicateTargetsShareAcceptedWorkAndOppositeTargetsKeepTheirOrder() = runTest {
        val targets = mutableListOf<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ContentProvider() {
            override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                targets += saved
                if (targets.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val first = launch { router.setSaved(source.playlist.ref, true, null, source) }
        entered.await()
        val duplicate = async { router.setSaved(source.playlist.ref, true, null, source) }
        runCurrent()
        val opposite = async { router.setSaved(source.playlist.ref, false, null, source) }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(listOf(true), targets)
        release.complete(Unit)
        duplicate.await()
        opposite.await()
        assertEquals(listOf(true, false), targets)
        assertEquals(2L, router.contentRevision.value)
    }

    @Test fun failedWritesReleaseReadersWithoutInventingConfirmedStateAndCanRetry() = runTest {
        var reject = true
        var confirmed = false
        val source = object : ContentProvider() {
            override suspend fun saved(ref: ContentRef) = confirmed
            override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                if (reject) throw SourceException(SourceErrorCode.UNAVAILABLE)
                confirmed = saved
            }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        try { router.setSaved(source.playlist.ref, true); fail("Expected the business failure") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.UNAVAILABLE, error.code) }
        assertFalse(router.saved(source.playlist.ref))
        assertEquals(0L, router.contentRevision.value)
        reject = false
        router.setSaved(source.playlist.ref, true)
        assertTrue(router.saved(source.playlist.ref))
        assertEquals(1L, router.contentRevision.value)
    }

    @Test fun accountReplacementCancelsRunningAndQueuedWritesBeforeTheyCanCommit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val source = object : ContentProvider() {
            var committed = false
            override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                entered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun deletePlaylist(ref: PlaylistRef) { committed = true }
        }
        source.accountRevision.value = "account-A"
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val running = async { router.setSaved(source.playlist.ref, true, "account-A", source) }
        entered.await()
        val queued = async { router.deletePlaylist(source.playlist.ref, "account-A", source) }
        runCurrent()
        source.accountRevision.value = "account-B"
        runCurrent()
        for (result in listOf(running, queued)) {
            try { result.await(); fail("An old account must not commit a write") }
            catch (_: CancellationException) { }
        }
        assertFalse(source.committed)
        assertEquals(0L, router.contentRevision.value)
        router.deletePlaylist(source.playlist.ref, "account-B", source)
        assertTrue(source.committed)
    }

    @Test fun confirmedCollectionsOverrideStaleAndFailedReadsUntilTheirOwnerChanges() = runTest {
        var raw = false
        var failRead = false
        var rejectWrite = false
        val source = object : ContentProvider() {
            override suspend fun saved(ref: ContentRef): Boolean {
                if (failRead) throw SourceException(SourceErrorCode.NETWORK)
                return raw
            }
            override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                if (rejectWrite) throw SourceException(SourceErrorCode.UNAVAILABLE)
            }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        assertFalse(router.saved(source.playlist.ref))
        router.setSaved(source.playlist.ref, true)
        failRead = true
        assertTrue(router.saved(source.playlist.ref))
        failRead = false
        assertTrue(router.saved(source.playlist.ref))
        rejectWrite = true
        try { router.setSaved(source.playlist.ref, false); fail("Expected a rejected mutation") }
        catch (_: SourceException) { }
        assertTrue(router.saved(source.playlist.ref))
        assertEquals(1L, router.contentRevision.value)
        rejectWrite = false
        router.setSaved(source.playlist.ref, false)
        raw = true
        assertFalse(router.saved(source.playlist.ref))
        source.accountRevision.value = "new-account"
        assertTrue(router.saved(source.playlist.ref))
        registry.register(ContentProvider())
        assertTrue(router.saved(source.playlist.ref))
    }

    @Test fun playlistMutationsInvalidateOnlyAffectedLikesAndNeverLoseTheirConfirmedSuccess() = runTest {
        var serverLiked = true
        var failRead = false
        val source = object : ContentProvider() {
            override suspend fun liked(ref: TrackRef): Boolean {
                if (failRead) throw SourceException(SourceErrorCode.NETWORK)
                return serverLiked
            }
            override suspend fun removeTracks(ref: PlaylistRef, tracks: List<TrackRef>) { serverLiked = false }
        }
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val unaffected = TrackRef(source.id, "other-track")
        router.setLiked(source.track.ref, true)
        router.setLiked(unaffected, true)
        router.removeTracks(source.playlist.ref, listOf(source.track.ref))
        failRead = true
        assertTrue(router.liked(unaffected))
        try { router.liked(source.track.ref); fail("An unread state must stay unknown after a failed refresh") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.NETWORK, error.code) }
        assertEquals(3L, router.contentRevision.value)
        failRead = false
        assertFalse(router.liked(source.track.ref))
    }

    @Test fun ambiguousWriteFailuresDiscardOldReceiptsAndReconcileCanonicalState() = runTest {
        for (likeWrite in listOf(false, true)) for (runtimeInterruption in listOf(false, true)) {
            var confirmed = false
            var loseResponse = false
            val source = object : ContentProvider() {
                override suspend fun liked(ref: TrackRef) = confirmed
                override suspend fun saved(ref: ContentRef) = confirmed
                override suspend fun setLiked(ref: TrackRef, liked: Boolean) {
                    confirmed = liked
                    if (loseResponse) throw SourceException(if (runtimeInterruption) SourceErrorCode.UNAVAILABLE else SourceErrorCode.NETWORK,
                        outcomeUnknown = runtimeInterruption)
                }
                override suspend fun setSaved(ref: ContentRef, saved: Boolean) {
                    confirmed = saved
                    if (loseResponse) throw SourceException(if (runtimeInterruption) SourceErrorCode.UNAVAILABLE else SourceErrorCode.TIMEOUT,
                        outcomeUnknown = runtimeInterruption)
                }
            }
            val registry = ProviderRegistry(listOf(source))
            val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
            if (likeWrite) router.setLiked(source.track.ref, true) else router.setSaved(source.playlist.ref, true)
            loseResponse = true
            try {
                if (likeWrite) router.setLiked(source.track.ref, false) else router.setSaved(source.playlist.ref, false)
                fail("An ambiguous transport result must not be reported as successful")
            } catch (_: SourceException) { }
            assertFalse(if (likeWrite) router.liked(source.track.ref) else router.saved(source.playlist.ref))
            assertEquals(2L, router.contentRevision.value)
        }
    }

    @Test fun everyCommonMutationRejectsTheDisplayedAnonymousOrReplacedAccount() = runTest {
        val source = ContentProvider()
        val registry = ProviderRegistry(listOf(source))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        source.accountRevision.value = "replacement-account"
        for (old in listOf(null, "old-account")) {
            val attempts: List<suspend () -> Unit> = listOf(
                { router.setLiked(source.track.ref, true, old, source) },
                { router.setSaved(source.playlist.ref, true, old, source) },
                { router.createPlaylist(source.id, "New", expectedAccountRevision = old, expectedProvider = source); Unit },
                { router.updatePlaylist(source.playlist.ref, "New", expectedAccountRevision = old, expectedProvider = source) },
                { router.deletePlaylist(source.playlist.ref, old, source) },
                { router.addTracks(source.playlist.ref, listOf(source.track.ref), old, source) },
                { router.removeTracks(source.playlist.ref, listOf(source.track.ref), old, source) },
                { router.reorderTracks(source.playlist.ref, listOf(source.track.ref), old, source) },
            )
            for (attempt in attempts) {
                try { attempt(); fail("A mutation must keep the displayed account, including null") }
                catch (_: CancellationException) { }
            }
        }
        assertTrue(source.writes.isEmpty())
        assertEquals(0L, router.contentRevision.value)
    }

    @Test fun favoriteMutationRejectsAnExpectedNullAccountAndAReplacedProvider() = runTest {
        val provider = ContentProvider()
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry)
        provider.accountRevision.value = "signed-in-after-click"
        try { router.setLiked(provider.track.ref, true, expectedAccountRevision = null, expectedProvider = provider); fail() }
        catch (_: CancellationException) { }
        assertTrue(provider.writes.isEmpty())
        val replacement = ContentProvider()
        registry.register(replacement)
        try { router.setLiked(provider.track.ref, true, expectedAccountRevision = null, expectedProvider = provider); fail() }
        catch (_: CancellationException) { }
        assertTrue(replacement.writes.isEmpty())
        router.setLiked(replacement.track.ref, true, expectedAccountRevision = null, expectedProvider = replacement)
        assertEquals(listOf("like:${replacement.track.ref.trackId}"), replacement.writes)
    }

    @Test fun playerActionsFollowDeclaredSourceFeaturesAndTypedPlaylistCapabilities() {
        val provider = ContentProvider()
        val metadata = provider.track.toMediaMetadata()
        assertTrue(MoreAction.ADD_TO_PLAYLIST.availableFor(metadata, provider))
        assertTrue(MoreAction.SHARE.availableFor(metadata, provider))
        assertFalse(MoreAction.COMMENT.availableFor(metadata, provider))
        assertFalse(MoreAction.SONG_WIKI.availableFor(metadata, provider))
        val withFeatures = object : MusicProvider by provider, SourceFeatureCapability {
            override val capabilities = provider.capabilities + SourceCapability.FEATURES
            override val features = mapOf("social" to setOf("comments", "songWiki"))
            override suspend fun invokeFeature(feature: String, action: String, parameters: com.google.gson.JsonObject): com.google.gson.JsonElement =
                throw SourceException(SourceErrorCode.UNSUPPORTED)
        }
        assertTrue(MoreAction.COMMENT.availableFor(metadata, withFeatures))
        assertTrue(MoreAction.SONG_WIKI.availableFor(metadata, withFeatures))
        assertFalse(MoreAction.SHARE.availableFor(metadata.copy(isLocal = true), withFeatures))
        assertFalse(MoreAction.ADD_TO_PLAYLIST.availableFor(metadata, null))
    }

    @Test fun typedRouterKeepsContentKindsProviderRefsAndPageCursors() = runTest {
        val provider = ContentProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        assertEquals(provider.searchTypes, router.searchTypes(provider.id))
        val found = router.search(provider.id, "query", ContentType.PLAYLIST, PageRequest(cursor = "before"))
        assertEquals(provider.playlist.ref, found.items.single().ref)
        assertEquals("next:page", found.nextCursor)
        assertEquals(provider.playlist.ref, router.getFeed(provider.id, FeedKind.HOME).sections.single().items.single().ref)
        assertEquals(provider.track.ref, router.library(provider.id, LibraryCollection.LIKED_TRACKS).items.single().ref)
        assertEquals(provider.track.ref, router.playlistTracks(provider.playlist.ref).items.single().ref)
        try { router.getFeed(provider.id, FeedKind.DISCOVER); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
        try { router.library(provider.id, LibraryCollection.SAVED_ALBUMS); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
        provider.foreignResult = true
        try { router.search(provider.id, "query", ContentType.PLAYLIST); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
    }

    @Test fun contentRevisionOnlyChangesAfterSuccessfulPermittedMutations() = runTest {
        val provider = ContentProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        val ref = provider.playlist.ref
        val tracks = listOf(provider.track.ref)
        router.setLiked(provider.track.ref, true)
        router.setSaved(ref, true)
        router.createPlaylist(provider.id, "New")
        router.updatePlaylist(ref, "Renamed")
        router.addTracks(ref, tracks)
        router.removeTracks(ref, tracks)
        router.reorderTracks(ref, tracks)
        router.deletePlaylist(ref)
        assertEquals(8L, router.contentRevision.value)
        provider.canWriteLikes = false
        try { router.setLiked(provider.track.ref, false); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
        provider.canWriteLikes = true
        provider.rejectWrite = true
        try { router.setLiked(provider.track.ref, false); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.AUTH_EXPIRED, error.code) }
        try { router.addTracks(ref, listOf(TrackRef("foreign", "42"))); fail() } catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
        assertEquals(8, provider.writes.size)
        assertEquals(8L, router.contentRevision.value)
    }

    @Test fun queuedTrackOrderRejectsAContentChangeBeforeItsSerializedWrite() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : ContentProvider() {
            override suspend fun addTracks(ref: PlaylistRef, tracks: List<TrackRef>) {
                entered.complete(Unit)
                release.await()
                super.addTracks(ref, tracks)
            }
        }
        val registry = ProviderRegistry(listOf(provider))
        val router = SourceRouter(registry, SourceMutationCoordinator(registry, backgroundScope))
        val ref = provider.playlist.ref
        val tracks = listOf(provider.track.ref)
        val expectedRevision = router.contentRevision.value
        val adding = async { router.addTracks(ref, tracks) }
        entered.await()
        val ordering = async {
            runCatching { router.reorderTracks(ref, tracks, expectedContentRevision = expectedRevision) }
        }
        runCurrent()
        assertFalse(ordering.isCompleted)
        release.complete(Unit)
        adding.await()
        val rejected = ordering.await().exceptionOrNull()
        assertEquals(SourceErrorCode.CANCELLED, (rejected as? SourceException)?.code)
        assertEquals(listOf("add"), provider.writes)
        assertEquals(1L, router.contentRevision.value)

        router.reorderTracks(ref, tracks)
        assertEquals(listOf("add", "reorder"), provider.writes)
        assertEquals(2L, router.contentRevision.value)
    }

    @Test fun reorderedFallbackListKeepsDisabledInstancesAndRestoresCompletePlayableOrder() {
        val saved = listOf("netease", "disabled-instance", "a", "missing-instance", "b", "a")
        val reordered = listOf("b", "netease", "a")
        val stored = mergeFallbackOrder(saved, reordered)
        assertEquals(listOf("b", "disabled-instance", "netease", "missing-instance", "a"), stored)
        assertEquals(reordered, orderedPlayableInstances(stored, listOf("netease", "a", "b")))
        assertEquals(listOf("b", "netease", "a", "new-instance"),
            orderedPlayableInstances(stored, listOf("netease", "a", "b", "new-instance")))
        assertEquals(listOf("a", "b"), mergeFallbackOrder(emptyList(), listOf("a", "b", "a")))
    }
    private val original = track("original")
    private fun track(provider: String, title: String = "Song", duration: Long = 200_000) = Track(TrackRef(provider, "same:id/雪"), title, listOf(SourceArtist("Artist")), SourceAlbum("Album"), duration)
    private class Provider(override val id: String, val candidate: Track, val failure: SourceErrorCode? = null) : MusicProvider, CatalogCapability, PlaybackCapability {
        override val displayName = id
        override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK)
        var calls = 0
        override suspend fun search(query: String, page: PageRequest) = SourcePage(listOf(candidate))
        override suspend fun getTrack(ref: TrackRef) = candidate
        override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource {
            calls++
            failure?.let { throw SourceException(it) }
            return AudioResource("https://audio.example/$id", "standard", contentId = "bytes")
        }
    }
    private fun resolver(providers: List<MusicProvider>, settings: SourceSettings = SourceSettings(autoFallbackEnabled = true, fallbackOrder = listOf("a", "b", "c"))) = PlaybackResolver(ProviderRegistry(providers), { settings })

    @Test fun originalSuccessStopsFallbackAndPreservesIdentity() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val result = resolver(listOf(origin, a)).resolve(original, "standard")
        assertEquals(original.ref, result.originTrackRef)
        assertEquals(original.ref, result.resolvedTrackRef)
        assertEquals(0, a.calls)
    }
    @Test fun failuresFollowOrderStopOnSuccessAndKeepOrigin() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val a = Provider("a", track("a"), SourceErrorCode.TIMEOUT)
        val b = Provider("b", track("b"))
        val c = Provider("c", track("c"))
        val result = resolver(listOf(origin, a, b, c)).resolve(original, "standard")
        assertEquals(listOf("original", "a", "b"), result.attempts.map { it.instanceId.value })
        assertEquals(original.ref, result.originTrackRef)
        assertEquals("b", result.resolvedTrackRef.providerId)
        assertEquals(0, c.calls)
    }
    @Test fun disabledFallbackNeverCallsOtherSources() = runTest {
        val origin = Provider("original", original, SourceErrorCode.AUTH_EXPIRED)
        val a = Provider("a", track("a"))
        try { resolver(listOf(origin, a), SourceSettings()).resolve(original, "standard"); fail() }
        catch (error: PlaybackResolutionException) { assertEquals(SourceErrorCode.AUTH_EXPIRED, error.attempts.single().error) }
        assertEquals(0, a.calls)
    }
    @Test fun duplicateSourcesAreAttemptedOnce() = runTest {
        val a = Provider("a", track("a"), SourceErrorCode.UNAVAILABLE)
        try { resolver(listOf(a), SourceSettings(true, true, listOf("a", "a", "a"))).resolve(original, "standard"); fail() }
        catch (_: PlaybackResolutionException) { assertEquals(1, a.calls) }
    }
    @Test fun orderCanPrecedeOriginalWhenExplicitlyConfigured() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val result = resolver(listOf(origin, a), SourceSettings(true, false, listOf("a"))).resolve(original, "standard")
        assertEquals("a", result.actualAudioProviderId)
        assertEquals(0, origin.calls)
    }
    @Test fun originalCallbackCompletesItsQualityLadderBeforeCrossSource() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val attempted = mutableListOf<String>()
        val result = resolver(listOf(origin, a)).resolve(original, "lossless", originalResolver = {
            attempted += listOf("lossless", "exhigh", "standard")
            AudioResource("https://original.example/song", "standard")
        })
        assertEquals(listOf("lossless", "exhigh", "standard"), attempted)
        assertEquals("standard", result.resource.quality)
        assertEquals(0, a.calls)
    }
    @Test fun cancellationDoesNotBecomeFallbackFailure() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        try { resolver(listOf(origin, a)).resolve(original, "standard", originalResolver = { throw CancellationException() }); fail() }
        catch (_: CancellationException) { assertEquals(0, a.calls) }
    }
    @Test fun timeoutIsBoundedAndContinues() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val resolver = PlaybackResolver(ProviderRegistry(listOf(origin, a)), { SourceSettings(true, true, listOf("a")) }, 100)
        val result = resolver.resolve(original, "standard", originalResolver = { delay(500); error("unreachable") })
        assertEquals(SourceErrorCode.TIMEOUT, result.attempts.first().error)
        assertEquals("a", result.actualAudioProviderId)
    }
    @Test fun repeatedConcurrentRequestsShareTheSuccessfulResolution() = runTest {
        val origin = Provider("original", original)
        val resolver = resolver(listOf(origin))
        coroutineScope { List(5) { async { resolver.resolve(original, "standard") } }.awaitAll() }
        assertEquals(1, origin.calls)
    }
    @Test fun rejectedActualBytesNeverResolveAgain() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val resolver = resolver(listOf(origin, a))
        val first = resolver.resolve(original, "standard")
        val next = resolver.resolve(original, "standard", setOf(audioResourceIdentity(first)))
        assertEquals("a", next.actualAudioProviderId)
        assertNotEquals(audioResourceIdentity(first), audioResourceIdentity(next))
    }
    @Test fun matchingRejectsVersionsCoversAndAmbiguity() {
        val matcher = TrackMatcher()
        assertTrue(matcher.rank(original, listOf(track("a", "Song (Live)"), track("b").copy(version = "cover"))).isEmpty())
        assertNull(matcher.automatic(matcher.rank(original, listOf(track("a"), track("b")))))
        assertNull(matcher.automatic(matcher.rank(original, listOf(track("a", duration = 230_000)))))
        assertNotNull(matcher.automatic(matcher.rank(original, listOf(track("a")))))
        assertTrue(matcher.rank(original, listOf(track("a").copy(album = SourceAlbum("Live")))).isEmpty())
    }
    @Test fun manualMatchesAndPerTrackDisableArePersistablePolicyInputs() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val a = Provider("a", track("a", "User-selected alternate"))
        val mappingKey = TrackRefCodec.encode(original.ref) + "|a"
        val settings = SourceSettings(true, true, listOf("a"), manualMatches = mapOf(mappingKey to TrackRefCodec.encode(a.candidate.ref)))
        assertEquals(a.candidate.ref, resolver(listOf(origin, a), settings).resolve(original, "standard").resolvedTrackRef)
        val disabled = settings.copy(disabledTrackFallbacks = setOf(TrackRefCodec.encode(original.ref)))
        try { resolver(listOf(origin, a), disabled).resolve(original, "standard"); fail() }
        catch (_: PlaybackResolutionException) { assertEquals(1, a.calls) }
    }
    @Test fun reservedProviderCannotBeReplacedAndCapabilitiesAreExplicit() = runTest {
        try { ProviderRegistry(listOf(Provider(ProviderIds.LOCAL, track(ProviderIds.LOCAL)))); fail() }
        catch (_: IllegalArgumentException) { }
        val removable = Provider(ProviderIds.NETEASE, track(ProviderIds.NETEASE))
        val sources = ProviderRegistry(listOf(removable))
        sources.unregister(removable.instanceId)
        assertNull(sources.provider(ProviderIds.NETEASE))
        val lyricsOnly = object : MusicProvider {
            override val id = "lyrics"
            override val displayName = "Lyrics"
            override val capabilities = setOf(SourceCapability.LYRICS)
        }
        val registry = ProviderRegistry(listOf(lyricsOnly))
        assertTrue(registry.playable().isEmpty())
        try { SourceRouter(registry).search("lyrics", "song"); fail() }
        catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
    }

    @Test fun parentTimeoutStopsInsteadOfTryingAnotherProvider() = runTest {
        val origin = Provider("original", original)
        val alternate = Provider("a", track("a"))
        val resolver = resolver(listOf(origin, alternate))
        try {
            withTimeout(10) {
                resolver.resolve(original, "standard", originalResolver = { delay(1000); error("unreachable") })
            }
            fail("Expected parent cancellation")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(0, alternate.calls)
        assertTrue(resolver.lastAttempts.value.isEmpty())
    }

    @Test fun invalidationPreventsOldAccountRequestFromPublishingOrCaching() = runTest {
        val origin = Provider("original", original)
        val resolver = resolver(listOf(origin))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            resolver.resolve(original, "standard", originalResolver = {
                entered.complete(Unit)
                release.await()
                AudioResource("https://audio.example/old-account", "standard")
            })
        }
        entered.await()
        resolver.invalidate()
        release.complete(Unit)
        try { old.await(); fail("Expected invalidated resolution to cancel") } catch (_: CancellationException) { }
        assertNull(resolver.lastResolution.value)
        assertTrue(resolver.lastAttempts.value.isEmpty())
        assertEquals("https://audio.example/original", resolver.resolve(original, "standard").resource.uri)
    }

    @Test fun routerRestoresMixedProviderOrderAndDuplicates() = runTest {
        val a = Provider("a", track("a"))
        val b = Provider("b", track("b"))
        val router = SourceRouter(ProviderRegistry(listOf(a, b)))
        val refs = listOf(a.candidate.ref, b.candidate.ref, a.candidate.ref)
        assertEquals(refs, router.getTracks(refs).map { it.ref })
    }

    @Test fun routerUsesOneAdvertisedMetadataBatchAndRetainsLegacyFallbackOrder() = runTest {
        var batchCalls = 0
        var singleCalls = 0
        fun metadata(ref: TrackRef) = track(ref.providerId).copy(ref = ref)
        val batch = object : MusicProvider, CatalogCapability, BatchCatalogCapability {
            override val id = "batch"
            override val displayName = "Batch"
            override val capabilities = setOf(SourceCapability.TRACK)
            override suspend fun search(query: String, page: PageRequest) = SourcePage<Track>(emptyList())
            override suspend fun getTrack(ref: TrackRef): Track? = error("An advertised batch must not read rows individually")
            override suspend fun getTracks(refs: List<TrackRef>): List<Track> {
                batchCalls++
                return refs.distinct().asReversed().filterNot { it.trackId == "missing" }.map(::metadata)
            }
        }
        val legacy = object : MusicProvider, CatalogCapability {
            override val id = "legacy"
            override val displayName = "Legacy"
            override val capabilities = setOf(SourceCapability.TRACK)
            override suspend fun search(query: String, page: PageRequest) = SourcePage<Track>(emptyList())
            override suspend fun getTrack(ref: TrackRef): Track? {
                singleCalls++
                return ref.takeUnless { it.trackId == "missing" }?.let(::metadata)
            }
        }
        assertTrue(batch.supportsBatchTrackRequests)
        val router = SourceRouter(ProviderRegistry(listOf(batch, legacy)))
        for (count in listOf(3, 8, 20)) {
            batchCalls = 0
            val refs = (1..count).map { TrackRef("batch", "$it") }
            assertEquals(refs, router.getTracks(refs).map { it.ref })
            assertEquals(1, batchCalls)
            assertEquals(0, singleCalls)
        }
        batchCalls = 0
        val refs = listOf(TrackRef("batch", "3"), TrackRef("legacy", "1"), TrackRef("batch", "1"),
            TrackRef("batch", "3"), TrackRef("batch", "missing"), TrackRef("legacy", "missing"))
        assertEquals(refs.filterNot { it.trackId == "missing" }, router.getTracks(refs).map { it.ref })
        assertEquals(1, batchCalls)
        assertEquals(2, singleCalls)
    }

    @Test fun manualBindingRejectsAProviderReturningAnotherTrack() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val alternate = Provider("a", track("a"))
        val selected = TrackRef("a", "another-track")
        val settings = SourceSettings(true, true, listOf("a"), manualMatches = mapOf(
            TrackRefCodec.encode(original.ref) + "|a" to TrackRefCodec.encode(selected),
        ))
        try { resolver(listOf(origin, alternate), settings).resolve(original, "standard"); fail() }
        catch (error: PlaybackResolutionException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.attempts.last().error) }
        assertEquals(0, alternate.calls)
    }

    @Test fun invalidStrongIdsCannotBypassRecordingEvidence() {
        val matcher = TrackMatcher()
        val claimed = original.copy(isrc = "unknown")
        assertTrue(matcher.rank(claimed, listOf(track("a", title = "Different Song").copy(isrc = "unknown"))).isEmpty())
        val identified = original.copy(isrc = "USABC2600001")
        assertTrue(matcher.rank(identified, listOf(track("a", duration = 300_000).copy(isrc = identified.isrc))).isEmpty())
        assertNotNull(matcher.automatic(matcher.rank(identified, listOf(track("a").copy(isrc = "US-ABC-26-00001")))))
    }

    @Test fun nativeFeatureRoutesRequireAnEnabledProviderContribution() {
        val native = object : MusicProvider, FeatureContribution {
            override val id = "native-feature-fixture"
            override val displayName = "Native fixture"
            override val capabilities = setOf(SourceCapability.RECOMMENDATIONS)
            override val featureRoutes = setOf("home", "account/login")
            var enabled = true
            override val status get() = if (enabled) SourceStatus.READY else SourceStatus.DISABLED
        }
        val registry = ProviderRegistry(listOf(native, Provider("catalog", track("catalog"))))
        assertTrue(registry.supportsFeatureRoute(native.id, "home"))
        assertTrue(registry.supportsFeatureRoute(native.id, "account/login"))
        assertFalse(registry.supportsFeatureRoute(native.id, "undeclared"))
        assertFalse(registry.supportsFeatureRoute("catalog", "home"))
        native.enabled = false
        registry.refresh()
        assertFalse(registry.supportsFeatureRoute(native.id, "home"))
    }
}
