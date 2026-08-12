package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class SecureStoreNamesTest {
    @Test
    fun `default profile uses normalized secret name`() {
        assertEquals("model_api_key_default", SecureStoreNames.secretName("default"))
        assertEquals("model_api_key_default", SecureStoreNames.secretName(""))
    }

    @Test
    fun `named profile uses isolated secret name`() {
        assertEquals("model_api_key_profile-123", SecureStoreNames.secretName("profile-123"))
    }
}
