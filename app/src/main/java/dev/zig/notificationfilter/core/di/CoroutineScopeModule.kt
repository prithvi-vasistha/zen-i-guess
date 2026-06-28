package dev.zig.notificationfilter.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {

    // SupervisorJob ensures that a failed child coroutine (e.g. a DB write that
    // throws) does not cancel the scope or any other in-flight coroutines.
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
}
