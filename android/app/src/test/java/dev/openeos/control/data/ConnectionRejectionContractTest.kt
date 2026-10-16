package dev.openeos.control.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConnectionRejectionContractTest {
    @Test fun sharedSessionCreationRejectionVectors() {
        val stream = javaClass.classLoader!!.getResourceAsStream("session-creation-rejection-vectors.json")
        assertNotNull("Shared connection contract must be packaged as a test resource", stream)
        val vectors = JSONObject(stream!!.bufferedReader().use { it.readText() }).getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            assertEquals(vector.getString("id"), vector.getBoolean("creationRejected"),
                bridgeSessionCreationRejected(
                    if (vector.isNull("status")) null else vector.getInt("status"),
                    if (vector.isNull("code")) null else vector.getString("code"),
                    if (vector.isNull("engine")) null else vector.getString("engine"),
                ))
        }
    }
}
