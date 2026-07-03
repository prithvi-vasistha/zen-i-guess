package dev.zig.notificationfilter.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.domain.embedding.MediaPipeTextEmbedder
import dev.zig.notificationfilter.domain.embedding.TextEmbedder
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.memory.PersonalMemoryRepository
import javax.inject.Singleton

/**
 * Retrieval-Augmented Classification bindings: the on-device text embedder and the
 * Personal Memory corpus. VectorSearchEngine and EnsembleClassifier are constructor-injected
 * and need no @Binds.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RacModule {

    @Binds
    @Singleton
    abstract fun bindTextEmbedder(impl: MediaPipeTextEmbedder): TextEmbedder

    @Binds
    @Singleton
    abstract fun bindPersonalMemory(impl: PersonalMemoryRepository): PersonalMemory
}
