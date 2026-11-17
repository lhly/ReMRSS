package dev.remrss.platform.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RearClientBindingReleaseTest {
    private class BindingContext(private val result: (Int) -> Boolean) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val attempted = mutableListOf<ServiceConnection>()
        val released = mutableListOf<ServiceConnection>()

        override fun getApplicationContext(): Context = this

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(RearClient.HOST_PACKAGE, intent.component?.packageName)
            assertEquals(RearClient.HOST_SERVICE, intent.component?.className)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            attempted += connection
            return result(attempted.size)
        }

        override fun unbindService(connection: ServiceConnection) {
            released += connection
        }
    }

    private fun code(result: Result<Unit>) = result.exceptionOrNull()?.message

    @Test fun failedFrameworkBindReleasesTheOriginalConnectionForFalseAndException() {
        listOf<() -> Boolean>({ false }, { throw SecurityException("private framework reason") }).forEach { bind ->
            val context = BindingContext { bind() }
            val results = mutableListOf<String?>()
            val client = RearClient(context, Executor { it.run() }) { }
            try {
                client.connect { results += code(it) }
                assertEquals(listOf(RearSdkErrorCode.SERVICE_UNAVAILABLE), results)
                client.close()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, context.attempted.size)
                assertSame(context.attempted.single(), context.released.single())
            } finally {
                client.close()
            }
        }
    }

    @Test fun immediateReconnectSurvivesTheOldAttemptRelease() {
        val context = BindingContext { number -> number == 2 }
        val results = mutableListOf<String?>()
        val client = RearClient(context, Executor { it.run() }) { }
        try {
            client.connect { first ->
                results += code(first)
                client.connect { second -> results += code(second) }
            }
            assertEquals(2, context.attempted.size)
            assertNotSame(context.attempted[0], context.attempted[1])
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(RearSdkErrorCode.SERVICE_UNAVAILABLE), results)
            assertSame(context.attempted[0], context.released.single())

            client.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(RearSdkErrorCode.SERVICE_UNAVAILABLE, RearSdkErrorCode.CLIENT_CLOSED), results)
            assertEquals(2, context.released.size)
            assertSame(context.attempted[1], context.released[1])
        } finally {
            client.close()
        }
    }

    @Test fun closedClientDoesNotCallFrameworkBindOrUnbind() {
        val context = BindingContext { error("bind after close") }
        val results = mutableListOf<String?>()
        val client = RearClient(context, Executor { it.run() }) { }
        client.close()
        client.connect { results += code(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(RearSdkErrorCode.CLIENT_CLOSED), results)
        assertTrue(context.attempted.isEmpty())
        assertTrue(context.released.isEmpty())
    }
}
