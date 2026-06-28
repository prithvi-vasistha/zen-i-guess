package dev.zig.notificationfilter.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.domain.llm.LiteRtLlmEngine
import dev.zig.notificationfilter.domain.llm.LlmEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LiteRtLlmEngine): LlmEngine
}
