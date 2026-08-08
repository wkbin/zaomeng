package top.wkbin.zaomeng.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelApiKeyStoreTest {
    @Test
    fun `default profile uses legacy compatible secret name`() {
        assertEquals("model_api_key", ModelApiKeyStore.secretName("default"))
        assertEquals("model_api_key", ModelApiKeyStore.secretName(""))
    }

    @Test
    fun `named profile uses isolated secret name`() {
        assertEquals("model_api_key_profile-123", ModelApiKeyStore.secretName("profile-123"))
    }
}
