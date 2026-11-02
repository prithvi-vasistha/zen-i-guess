package dev.zig.notificationfilter.data.local

import androidx.room.withTransaction
import dev.zig.notificationfilter.data.local.db.AppDatabase
import dev.zig.notificationfilter.data.local.db.DefaultRuleSeeder
import dev.zig.notificationfilter.data.local.db.KeywordRuleType
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import dev.zig.notificationfilter.core.errors.AppErrors

enum class FilterReadiness { LOADING, READY, ERROR }

/** One readiness barrier for every entry point, including a listener-only cold start. */
@Singleton
class FilterState @Inject constructor(
    private val database: AppDatabase,
    private val defaultRuleSeeder: DefaultRuleSeeder,
    private val contactsSyncManager: ContactsSyncManager,
    private val preferences: ZigUserPreferences,
) {
    private val mutex = Mutex()
    private val _readiness = MutableStateFlow(FilterReadiness.LOADING)
    val readiness = _readiness.asStateFlow()

    suspend fun awaitReady() {
        var retryDelay = 1_000L
        while (_readiness.value != FilterReadiness.READY) {
            try {
                mutex.withLock {
                    if (_readiness.value != FilterReadiness.READY) {
                        defaultRuleSeeder.seedIfNeeded()
                        loadSnapshot()
                        if (preferences.onboardingCompleted && preferences.contactsBypassEnabled) {
                            contactsSyncManager.syncNow()
                        }
                        _readiness.value = FilterReadiness.READY
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                AppErrors.report("Loading saved notification rules", failure, 60_000L)
                _readiness.value = FilterReadiness.ERROR
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Call after a saved edit; serialize reloads so an older snapshot cannot win. */
    suspend fun refresh() {
        awaitReady()
        try {
            mutex.withLock { loadSnapshot() }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            AppErrors.report("Refreshing notification rules", failure, 60_000L)
            _readiness.value = FilterReadiness.ERROR
            awaitReady()
        }
    }

    private suspend fun loadSnapshot() {
        val (packages, rules) = database.withTransaction {
            database.managedAppDao().getAllPackageNames().toSet() to
                database.keywordRuleDao().getAllSnapshot()
        }
        NativeBridge.replaceRules(
            packages,
            rules.filter { it.ruleType != KeywordRuleType.BLOCK.name }
                .map { it.conditions.joinToString("||") },
            rules.filter { it.ruleType == KeywordRuleType.BLOCK.name }
                .map { it.conditions.joinToString("||") },
        )
    }
}
