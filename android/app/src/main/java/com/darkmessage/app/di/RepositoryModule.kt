package com.darkmessage.app.di

import com.darkmessage.app.data.repository.ChatRepository
import com.darkmessage.app.data.repository.ChatRepositoryImpl
import com.darkmessage.app.data.security.SecureStorage
import com.darkmessage.app.data.security.TinkSecureStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindSecureStorage(impl: TinkSecureStorage): SecureStorage
}
