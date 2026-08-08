package top.wkbin.zaomeng.backend

import org.junit.Assert.assertEquals
import org.junit.Test
import top.wkbin.zaomeng.backend.SecureStoreNames

class ModelApiKeyStoreTest {
    @Test
    fun `default profile uses legacy compatible secret name`() {
        assertEquals("model_api_key", SecureStoreNames.secretName("default"))
        assertEquals("model_api_key", SecureStoreNames.secretName(""))
    }

    @Test
    fun `named profile uses isolated secret name`() {
        assertEquals("model_api_key_profile-123", SecureStoreNames.secretName("profile-123"))
    }
}
