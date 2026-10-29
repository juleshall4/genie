package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChirpSettingsCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val name = "chirp-check-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), name).apply { mkdirs() }
        }
        val preferences = isolated.getSharedPreferences("chirp", Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("month", "2000-01").putLong("characters", 900_000).commit()
            val settings = ChirpSettings(isolated)
            check(settings.usage().characters == 0L)
            check(settings.reserve(900_000))
            check(!ChirpSettings(isolated).reserve(1))
            check(settings.usage().characters == 900_000L)

            val pending = java.io.File(isolated.noBackupFilesDir, "chirp-api-key.pending")
            pending.writeText("test-only-chirp-key")
            check(ChirpSettings(isolated).apiKey == "test-only-chirp-key")
            check(!pending.exists())
            check(preferences.getString("key", "")?.contains("test-only-chirp-key") == false)
            check(ChirpSettings(isolated).apiKey == "test-only-chirp-key")
            check(settings.usage().characters == 900_000L)
            settings.apiKey = ""
            check(ChirpSettings(isolated).apiKey.isEmpty())

            preferences.edit().putString("month", YearMonth.now(ZoneOffset.UTC).toString()).putLong("characters", 0).commit()
            val pool = Executors.newFixedThreadPool(4)
            val accepted = AtomicInteger()
            repeat(12) { pool.execute { if (ChirpSettings(isolated).reserve(100_000)) accepted.incrementAndGet() } }
            pool.shutdown()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
            check(accepted.get() == 9)
            check(ChirpSettings(isolated).usage().characters == 900_000L)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "Passed: month rollover, persistent cutoff, private-file import and encryption, key changes retain usage, concurrent reservations.\n")
            })
        } catch (failure: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${failure.javaClass.simpleName}\n") })
        } finally {
            isolated.deleteSharedPreferences(name)
            isolated.noBackupFilesDir.deleteRecursively()
        }
    }
}
