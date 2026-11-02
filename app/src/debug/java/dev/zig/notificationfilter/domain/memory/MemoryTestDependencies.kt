package dev.zig.notificationfilter.domain.memory

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.domain.embedding.TextEmbedder

// Exercise the application's actual model and native runtime in instrumentation tests.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryTestDependencies {
    fun embedder(): TextEmbedder
}
