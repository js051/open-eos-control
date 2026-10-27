package dev.openeos.control.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runners.model.MultipleFailureException

/** Runs in the ordinary Gradle unit task. Supplemental runs must identify themselves as such. */
class AndroidUnitRuntimeContractTest {
    @Test fun runtimeUsesDeclaredJavaAndDependencyOrigins() {
        val profileBytes = javaClass.classLoader!!.getResourceAsStream("unit-test-runtime.properties")
            ?.use { it.readBytes() } ?: error("The shared unit-runtime profile must be a test resource")
        val profile = Properties().apply { profileBytes.inputStream().use { load(it) } }
        val actualMajor = System.getProperty("java.specification.version").toInt()
        assertEquals("UNIT_RUNTIME_JAVA_MAJOR", profile.getProperty("java.major").toInt(), actualMajor)
        val formal = System.getProperty("oec.gradle.runtimeManifest")
        val supplemental = System.getProperty("oec.supplemental.runtimeManifest")
        assertTrue("Exactly one runtime receipt must identify this invocation", (formal != null) xor (supplemental != null))
        val receiptFile = File(formal ?: supplemental!!)
        assertTrue("The selected runtime receipt must exist", receiptFile.isFile)
        val receipt = JSONObject(receiptFile.readText())
        assertEquals(1, receipt.getInt("schema"))
        assertEquals(actualMajor, receipt.getInt("java_major"))
        assertEquals(sha256(profileBytes), receipt.getString("profile_sha256"))
        if (formal != null) {
            assertTrue(receipt.getString("entrypoint").matches(Regex(":app:test.+UnitTest")))
            // Existing Gradle XML/stdout artifacts retain this without adding a CI upload route.
            println("OEC_GRADLE_RUNTIME_MANIFEST " + receipt.toString())
        } else {
            assertEquals("supplemental-jvm-diagnostic", receipt.getString("entrypoint"))
            assertEquals("SUPPLEMENTAL_DIAGNOSTIC_NOT_GRADLE_APP_ACCEPTANCE", receipt.getString("verification_boundary"))
        }
        val declared = receipt.getJSONArray("runtime_artifacts")
        val actual = JSONObject()
        for ((label, type) in listOf("kotlin" to KotlinVersion::class.java, "coroutines" to Job::class.java, "junit" to Test::class.java)) {
            val origin = File(type.protectionDomain.codeSource.location.toURI())
            assertTrue("A runtime dependency must come from a concrete jar", origin.isFile && origin.extension == "jar")
            val hash = origin.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            verifyLoadedRuntimeArtifact(declared, formal != null, origin.name, hash)
            actual.put(label, JSONObject().put("file", origin.name).put("sha256", hash))
        }
        // Closed fields only: no absolute paths, user assets, environment dump or credentials.
        println("OEC_UNIT_RUNTIME " + JSONObject().put("entrypoint", receipt.getString("entrypoint"))
            .put("java_major", actualMajor).put("profile_sha256", sha256(profileBytes)).put("loaded", actual))
    }

    @Test fun runtimeEnablesAssertionsAndCoroutineRecovery() {
        assertTrue("UNIT_RUNTIME_ASSERTIONS_ENABLED", javaClass.desiredAssertionStatus())
        assertEquals("UNIT_RUNTIME_DEBUG_ON", "on", System.getProperty("kotlinx.coroutines.debug"))
        assertEquals("UNIT_RUNTIME_RECOVERY_TRUE", "true", System.getProperty("kotlinx.coroutines.stacktrace.recovery"))
    }

    @Test fun formalRuntimeAcceptsOneCompleteIdentityWithoutRewritingReceipt() {
        val declared = JSONArray().put(runtimeArtifact())
        val before = declared.toString()
        verifyLoadedRuntimeArtifact(declared, true, "runtime.jar", "a".repeat(64))
        assertEquals(before, declared.toString())
    }

    @Test fun formalRuntimeAcceptsOnlyCompleteIdenticalDuplicateIdentities() {
        val declared = JSONArray().put(runtimeArtifact()).put(runtimeArtifact())
            .put(runtimeArtifact(file = "other.jar", coordinate = "test:other:1"))
            .put(runtimeArtifact(file = "other.jar", coordinate = "test:other:1"))
        val before = declared.toString()
        verifyLoadedRuntimeArtifact(declared, true, "runtime.jar", "a".repeat(64))
        assertEquals(4, declared.length())
        assertEquals(before, declared.toString())
    }

    @Test fun formalRuntimeRejectsConflictingCoordinateOrHashInEitherOrder() {
        for (conflict in listOf(runtimeArtifact(coordinate = "test:replacement:1"), runtimeArtifact(hash = "b".repeat(64)))) {
            for (reverse in listOf(false, true)) {
                val rows = listOf(runtimeArtifact(), conflict).let { if (reverse) it.reversed() else it }
                assertThrows(IllegalArgumentException::class.java) {
                    verifyLoadedRuntimeArtifact(JSONArray(rows), true, "runtime.jar", "a".repeat(64))
                }
            }
        }
    }

    @Test fun formalRuntimeRejectsConflictEvenForAnUnloadedArtifact() {
        val declared = JSONArray().put(runtimeArtifact())
            .put(runtimeArtifact(file = "other.jar", coordinate = "test:other:1"))
            .put(runtimeArtifact(file = "other.jar", coordinate = "test:replacement:1"))
        assertThrows(IllegalArgumentException::class.java) {
            verifyLoadedRuntimeArtifact(declared, true, "runtime.jar", "a".repeat(64))
        }
    }

    @Test fun formalRuntimeRejectsIncompleteOrCoercedIdentityFields() {
        for (field in listOf("coordinate", "file", "sha256")) {
            for (value in listOf(JSONObject.NULL, "", " ", "bad\nvalue", 17)) {
                val invalid = runtimeArtifact(file = "unloaded.jar", coordinate = "test:unloaded:1").put(field, value)
                // A valid loaded entry cannot hide an incomplete additional row.
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact()).put(invalid), true, "runtime.jar", "a".repeat(64))
                }
                assertEquals("Runtime artifact requires a nonempty control-free string $field", failure.message)
            }
            val missing = runtimeArtifact(file = "unloaded.jar", coordinate = "test:unloaded:1").apply { remove(field) }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact()).put(missing), true, "runtime.jar", "a".repeat(64))
            }
            assertEquals("Runtime artifact requires a nonempty control-free string $field", failure.message)
        }
    }

    @Test fun formalRuntimeRejectsMalformedDigestAndPathBearingFilenames() {
        for (hash in listOf("a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact(hash = hash)), true, "runtime.jar", hash)
            }
        }
        for (file in listOf("directory/runtime.jar", "/runtime.jar", "directory\\runtime.jar", "C:\\runtime.jar", "C:runtime.jar", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact()).put(runtimeArtifact(file = file)), true, "runtime.jar", "a".repeat(64))
            }
        }
    }

    @Test fun runtimeRejectsMissingOrMismatchedLoadedArtifact() {
        for (formal in listOf(false, true)) {
            for ((file, hash) in listOf("missing.jar" to "a".repeat(64), "runtime.jar" to "b".repeat(64))) {
                assertThrows(IllegalArgumentException::class.java) {
                    verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact()), formal, file, hash)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                verifyLoadedRuntimeArtifact(JSONArray(), formal, "runtime.jar", "a".repeat(64))
            }
        }
    }

    @Test fun runtimeRejectsNonObjectManifestRows() {
        for (formal in listOf(false, true)) {
            for (row in listOf(JSONObject.NULL, "runtime.jar", 17)) {
                assertThrows(IllegalArgumentException::class.java) {
                    verifyLoadedRuntimeArtifact(JSONArray().put(runtimeArtifact()).put(row), formal, "runtime.jar", "a".repeat(64))
                }
            }
        }
    }

    @Test fun supplementalRuntimeKeepsItsExplicitCoordinateFreeMode() {
        val declared = JSONArray().put(runtimeArtifact(coordinate = null))
        verifyLoadedRuntimeArtifact(declared, false, "runtime.jar", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            verifyLoadedRuntimeArtifact(declared, true, "runtime.jar", "a".repeat(64))
        }
    }

    @Test fun supplementalRuntimeStillRejectsEvenIdenticalDuplicateRows() {
        val declared = JSONArray().put(runtimeArtifact(coordinate = null)).put(runtimeArtifact(coordinate = null))
        assertThrows(IllegalArgumentException::class.java) {
            verifyLoadedRuntimeArtifact(declared, false, "runtime.jar", "a".repeat(64))
        }
    }

    @Test fun suspendedReleaseFailurePreservesTypedPrimaryAndSuppressedIdentity() = runTest {
        supervisorScope {
            val session = ShutterReleaseSession()
            val entered = CompletableDeferred<Unit>()
            val allowPressFailure = CompletableDeferred<Unit>()
            val pressFailure = IOException("synthetic ambiguous press")
            val releaseFailure = IOException("synthetic lost release")
            var presses = 0
            var releases = 0
            var callerFailure: Throwable? = null
            val result = async {
                try {
                    session.start({
                        presses++
                        entered.complete(Unit)
                        allowPressFailure.await()
                        throw pressFailure
                    }, {
                        if (++releases == 1) throw releaseFailure
                    })
                } catch (failure: Throwable) {
                    callerFailure = failure
                    throw failure
                }
            }
            entered.await()
            allowPressFailure.complete(Unit)
            val failure = runCatching { result.await() }.exceptionOrNull()
                ?: error("The synthetic uncertain release must fail")
            val caller = callerFailure ?: error("The original helper caller must observe its failure")
            println("OEC_RELEASE_BOUNDARY " + JSONObject().put("case", "existing-witness")
                .put("caller", boundaryObservation(caller, pressFailure, releaseFailure))
                .put("receiver", boundaryObservation(failure, pressFailure, releaseFailure)))
            val failures = mutableListOf<Throwable>()
            var checked = 0
            fun verify(assertion: () -> Unit) {
                checked++
                runCatching(assertion).exceptionOrNull()?.let(failures::add)
            }
            verify { assertTrue(failure is ShutterReleaseException) }
            val chain = primaryChain(failure)
            verify { assertTrue("UNIT_RUNTIME_RECOVERY_COPY_REQUIRED", chain.count { it is ShutterReleaseException } >= 2) }
            verify { assertTrue("Only typed release wrappers may precede the original primary", chain.dropLast(1).all { it is ShutterReleaseException }) }
            verify { assertSame("The exact release failure remains the primary root", releaseFailure, chain.last()) }
            val suppressed = primaryChain(caller).flatMap { it.suppressed.toList() }
            verify { assertTrue("The exact original ambiguous press survives as suppressed evidence", suppressed.isNotEmpty()) }
            verify { assertTrue("No unrelated suppressed failure may replace the original press", suppressed.all { it === pressFailure }) }
            verify { assertTrue(releaseFailure.suppressed.isEmpty()) }
            verify { assertTrue(session.hasPendingRelease) }
            verify { assertTrue(session.releaseUnconfirmed) }
            val repressRejected = runCatching { session.start({ presses++ }, {}) }.isFailure
            verify { assertTrue(repressRejected) }
            // Preserve every original recovery assertion even when the earlier witness is red.
            runCatching { session.retryRelease() }.exceptionOrNull()?.let(failures::add)
            runCatching { session.retryRelease() }.exceptionOrNull()?.let(failures::add)
            verify { assertEquals(1, presses) }
            verify { assertEquals(2, releases) }
            verify { assertFalse(session.hasPendingRelease) }
            println("OEC_RELEASE_SAFETY " + JSONObject().put("case", "existing-witness")
                .put("assertions_executed", checked).put("presses", presses).put("releases", releases)
                .put("pending_after_retry", session.hasPendingRelease).put("failed_checks", failures.size))
            MultipleFailureException.assertEmpty(failures)
        }
    }

    @Test fun callerAndAwaitBoundariesKeepOriginalReleaseAuthority() = runTest {
        supervisorScope {
            val failures = mutableListOf<Throwable>()
            fun verify(assertion: () -> Unit) {
                runCatching(assertion).exceptionOrNull()?.let(failures::add)
            }
            for (extraAwait in listOf(false, true)) {
                val session = ShutterReleaseSession()
                val entered = CompletableDeferred<Unit>()
                val proceed = CompletableDeferred<Unit>()
                val pressFailure = IOException("synthetic original press")
                val releaseFailure = IOException("synthetic original release")
                val originalOwner = Any()
                val stoppedOwners = mutableListOf<Any>()
                var callerFailure: Throwable? = null
                var presses = 0
                var replacementStops = 0
                suspend fun invokeOriginalHelper() {
                    try {
                        session.start({
                            presses++
                            entered.complete(Unit)
                            proceed.await()
                            throw pressFailure
                        }, {
                            stoppedOwners += originalOwner
                            if (stoppedOwners.size == 1) throw releaseFailure
                        })
                    } catch (failure: Throwable) {
                        callerFailure = failure
                        throw failure
                    }
                }
                val receiver: Throwable? = if (extraAwait) {
                    val operation = async { invokeOriginalHelper() }
                    entered.await()
                    proceed.complete(Unit)
                    runCatching { operation.await() }.exceptionOrNull()
                } else {
                    // Actual VM shape: launch owns the operation and catches in that caller.
                    val operation = launch { runCatching { invokeOriginalHelper() } }
                    entered.await()
                    proceed.complete(Unit)
                    operation.join()
                    callerFailure
                }
                val caller = callerFailure ?: error("The original helper caller did not observe its error")
                val received = receiver ?: error("The selected receiver did not observe its error")
                println("OEC_RELEASE_BOUNDARY " + JSONObject().put("case", if (extraAwait) "extra-await" else "real-caller")
                    .put("same_throwable", caller === received)
                    .put("caller", boundaryObservation(caller, pressFailure, releaseFailure))
                    .put("receiver", boundaryObservation(received, pressFailure, releaseFailure)))
                val callerChain = primaryChain(caller)
                val receiverChain = primaryChain(received)
                verify { assertTrue(caller is ShutterReleaseException) }
                verify { assertTrue(received is ShutterReleaseException) }
                verify { assertSame(releaseFailure, callerChain.last()) }
                verify { assertSame(releaseFailure, receiverChain.last()) }
                val originalEvidence = callerChain.flatMap { it.suppressed.toList() }
                    .flatMap { primaryChain(it) }
                verify { assertTrue("The actual caller must retain the exact original press evidence", originalEvidence.any { it === pressFailure }) }
                verify { assertTrue(session.hasPendingRelease) }
                verify { assertTrue(session.releaseUnconfirmed) }
                val repressRejected = runCatching { session.start({ presses++ }, { replacementStops++ }) }.isFailure
                verify { assertTrue(repressRejected) }
                runCatching { session.retryRelease() }.exceptionOrNull()?.let(failures::add)
                runCatching { session.retryRelease() }.exceptionOrNull()?.let(failures::add)
                verify { assertEquals(1, presses) }
                verify { assertEquals(0, replacementStops) }
                verify { assertEquals(2, stoppedOwners.size) }
                verify { assertTrue(stoppedOwners.all { it === originalOwner }) }
                verify { assertFalse(session.hasPendingRelease) }
                verify { assertFalse(session.releaseUnconfirmed) }
                println("OEC_RELEASE_SAFETY " + JSONObject().put("case", if (extraAwait) "extra-await" else "real-caller")
                    .put("presses", presses).put("releases", stoppedOwners.size)
                    .put("original_owner_only", stoppedOwners.all { it === originalOwner })
                    .put("pending_after_retry", session.hasPendingRelease))
            }
            MultipleFailureException.assertEmpty(failures)
        }
    }

    @Test fun cancelledReleaseRetainsOriginalStopOnly() = runTest {
        val session = ShutterReleaseSession()
        val entered = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Throwable?>()
        val originalOwner = Any()
        val stoppedOwners = mutableListOf<Any>()
        var presses = 0
        var replacementStops = 0
        val job = launch {
            outcome.complete(runCatching {
                session.start({ presses++; entered.complete(Unit); awaitCancellation() }, {
                    stoppedOwners += originalOwner
                    if (stoppedOwners.size == 1) throw IOException("synthetic cancellation release lost")
                })
            }.exceptionOrNull())
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(outcome.await() is ShutterReleaseException)
        assertTrue(session.hasPendingRelease)
        assertTrue(session.releaseUnconfirmed)
        assertTrue(runCatching { session.start({ presses++ }, { replacementStops++ }) }.isFailure)
        session.retryRelease()
        session.retryRelease()
        assertEquals(1, presses)
        assertEquals(0, replacementStops)
        assertEquals(2, stoppedOwners.size)
        assertTrue(stoppedOwners.all { it === originalOwner })
        assertFalse(session.hasPendingRelease)
        assertFalse(session.releaseUnconfirmed)
    }

    private data class RuntimeArtifactIdentity(val coordinate: String?, val file: String, val sha256: String)

    private fun verifyLoadedRuntimeArtifact(declared: JSONArray, formal: Boolean, loadedFile: String, loadedHash: String) {
        val identities = linkedMapOf<String, RuntimeArtifactIdentity>()
        for (index in 0 until declared.length()) {
            val row = declared.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("Runtime receipt artifacts must be objects")
            fun requiredString(field: String): String {
                val value = row.opt(field) as? String
                require(value != null && value.isNotBlank() && value.none(Char::isISOControl)) {
                    "Runtime artifact requires a nonempty control-free string $field"
                }
                return value
            }
            val file = requiredString("file")
            require('/' !in file && '\\' !in file && ':' !in file && file != "." && file != "..") {
                "Runtime artifact file must be a filename, not a path"
            }
            val hash = requiredString("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Runtime artifact requires a complete SHA256" }
            val identity = RuntimeArtifactIdentity(if (formal) requiredString("coordinate") else null, file, hash)
            val previous = identities[file]
            // Formal Gradle may repeat one resolved artifact; all three fields must agree.
            // Supplemental receipts deliberately have no coordinates and remain unique.
            require(previous == null || (formal && previous == identity)) { "Conflicting or duplicate runtime artifact identity" }
            identities[file] = identity
        }
        val selected = requireNotNull(identities[loadedFile]) { "Loaded artifact is absent from runtime receipt" }
        require(selected.sha256 == loadedHash) { "Loaded artifact hash differs from runtime receipt" }
    }

    private fun runtimeArtifact(file: String = "runtime.jar", coordinate: String? = "test:runtime:1", hash: String = "a".repeat(64)): JSONObject =
        JSONObject().put("file", file).put("sha256", hash).apply {
            if (coordinate != null) put("coordinate", coordinate)
        }

    private fun boundaryObservation(failure: Throwable, press: Throwable, release: Throwable): JSONObject {
        val chain = primaryChain(failure)
        val direct = chain.flatMap { it.suppressed.toList() }
        val nested = direct.flatMap { primaryChain(it) }
        return JSONObject().put("typed_release", failure is ShutterReleaseException)
            .put("typed_wrappers", chain.count { it is ShutterReleaseException })
            .put("primary_is_original", chain.last() === release)
            .put("direct_suppressed_count", direct.size)
            .put("direct_original_press", direct.any { it === press })
            .put("nested_original_press", nested.any { it === press })
    }

    private fun primaryChain(failure: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (true) {
            val node = current ?: break
            check(chain.size < 16 && chain.none { it === node }) { "Unexpected cyclic/deep exception chain" }
            chain += node
            current = node.cause
        }
        return chain
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
