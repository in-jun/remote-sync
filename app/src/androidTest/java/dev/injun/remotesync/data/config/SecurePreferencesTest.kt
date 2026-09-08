package dev.injun.remotesync.data.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurePreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SecurePreferences

    @Before
    fun setUp() {
        context.deleteSharedPreferences(NAME)
        prefs = SecurePreferences(context, NAME, KEY_ALIAS)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(NAME)
    }

    @Test
    fun valuesRoundTripThroughEveryType() {
        prefs.edit {
            putString("s", "p@ss:word")
            putLong("l", Long.MAX_VALUE)
            putInt("i", 445)
            putFloat("f", 0.5f)
            putBoolean("b", true)
        }

        assertEquals("p@ss:word", prefs.getString("s"))
        assertEquals(Long.MAX_VALUE, prefs.getLong("l", 0L))
        assertEquals(445, prefs.getInt("i", 0))
        assertEquals(0.5f, prefs.getFloat("f", 0f))
        assertEquals(true, prefs.getBoolean("b", false))
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        assertNull(prefs.getString("nope"))
        assertEquals(15L, prefs.getLong("nope", 15L))
        assertFalse(prefs.contains("nope"))
    }

    @Test
    fun removeDeletesTheValue() {
        prefs.edit { putString("s", "x") }
        prefs.edit { remove("s") }

        assertFalse(prefs.contains("s"))
    }

    @Test
    fun plaintextNeverReachesDisk() {
        prefs.edit { putString("pass", "hunter2") }

        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("pass", null)
        assertNotEquals("hunter2", raw)
        assertFalse(raw.orEmpty().contains("hunter2"))
    }

    @Test
    fun aCiphertextMovedToAnotherKeyIsRejected() {
        prefs.edit { putString("a", "secret") }
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        raw.edit().putString("b", raw.getString("a", null)).commit()

        assertThrows(Exception::class.java) { prefs.getString("b") }
    }

    private companion object {
        const val NAME = "secure-prefs-test"
        const val KEY_ALIAS = "secure-prefs-test-key"
    }
}
