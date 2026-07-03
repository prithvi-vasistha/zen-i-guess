package dev.zig.notificationfilter.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.domain.classifier.LocalModelEngine
import dev.zig.notificationfilter.domain.classifier.ZigClassifierEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClassifierModule {

    @Binds
    @Singleton
    abstract fun bindClassifierEngine(impl: LocalModelEngine): ZigClassifierEngine
}
