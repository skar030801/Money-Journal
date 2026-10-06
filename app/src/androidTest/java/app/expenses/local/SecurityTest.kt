package app.expenses.local

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityTest {
    @Test
    fun keystoreCredentialsRecoveryAndRateLimit() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "security-test-${UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$prefix-$name", mode)
            }
        try {
            val security = Security(context)
            val (master, code) = security.setup("123456")
            assertArrayEquals(master, security.unlock("123456"))
            assertThrows(IllegalArgumentException::class.java) { security.unlock("000000") }
            assertEquals(code, security.pendingRecovery(master))
            security.acknowledgeRecovery()
            assertNull(security.pendingRecovery(master))
            assertArrayEquals(master, security.recover(code, "654321"))
            assertThrows(IllegalArgumentException::class.java) { security.unlock("123456") }
            assertArrayEquals(master, security.unlock("654321"))
            repeat(5) {
                assertThrows(IllegalArgumentException::class.java) { security.unlock("000000") }
            }
            val exception =
                assertThrows(IllegalArgumentException::class.java) { security.unlock("654321") }
            assertTrue(exception.message!!.contains("Too many attempts"))
            master.fill(0)
        } finally {
            base.deleteSharedPreferences("$prefix-security")
        }
    }
}
