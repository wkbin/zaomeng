package top.wkbin.zaomeng.ktor

import org.junit.Test
import top.wkbin.zaomeng.ktor.services.InvalidStorageIdentifierException
import top.wkbin.zaomeng.ktor.services.PathSafety
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PathSafety 测试
 *
 * 验证路径安全检查功能
 */
class PathSafetyTest {

    @Test
    fun `validateStorageId accepts valid identifiers`() {
        val validIds = listOf(
            "test123",
            "run_001",
            "session-abc",
            "ABC_123-def",
            "a",
            "Z"
        )

        validIds.forEach { id ->
            val result = PathSafety.validateStorageId(id, "test")
            assertEquals(id, result, "Valid ID should pass: $id")
        }
    }

    @Test
    fun `validateStorageId rejects invalid identifiers`() {
        val invalidIds = listOf(
            "",                    // empty
            " ",                   // whitespace only
            "test/path",          // slash
            "test\\path",         // backslash
            "../escape",          // parent directory
            "test..",             // ends with dots
            "test@email",         // special char
            "test space",         // space
            "测试",                // non-ASCII
            "a".repeat(129)       // too long
        )

        invalidIds.forEach { id ->
            assertFailsWith<InvalidStorageIdentifierException>(
                message = "Invalid ID should fail: $id"
            ) {
                PathSafety.validateStorageId(id, "test")
            }
        }
    }

    @Test
    fun `resolveStorageChild prevents directory traversal`() {
        val tempDir: Path = java.nio.file.Files.createTempDirectory("path-safety-test").toString().toPath()
        try {
            // Valid child path
            val validChild = PathSafety.resolveStorageChild(tempDir, "child", "test")
            assertTrue(validChild.toString().startsWith(tempDir.toString()), "Valid child should be under root")
            assertEquals(tempDir / "child", validChild)

            // Invalid attempts should throw
            assertFailsWith<InvalidStorageIdentifierException> {
                PathSafety.resolveStorageChild(tempDir, "../escape", "test")
            }

            assertFailsWith<InvalidStorageIdentifierException> {
                PathSafety.resolveStorageChild(tempDir, ".", "test")
            }

            assertFailsWith<InvalidStorageIdentifierException> {
                PathSafety.resolveStorageChild(tempDir, "..", "test")
            }
        } finally {
            java.io.File(tempDir.toString()).deleteRecursively()
        }
    }

    @Test
    fun `STORAGE_ID_PATTERN matches valid IDs`() {
        val validIds = listOf("a", "Z", "0", "test_123", "run-001", "ABC_def-123")
        validIds.forEach { id ->
            assertTrue(
                PathSafety.STORAGE_ID_PATTERN.matches(id),
                "Pattern should match valid ID: $id"
            )
        }
    }

    @Test
    fun `STORAGE_ID_PATTERN rejects invalid IDs`() {
        val invalidIds = listOf("", " ", "test/path", "test.file", "测试", "a b")
        invalidIds.forEach { id ->
            assertTrue(
                !PathSafety.STORAGE_ID_PATTERN.matches(id),
                "Pattern should reject invalid ID: $id"
            )
        }
    }
}
