package com.darkmessage.app.di

import com.darkmessage.app.crypto.ChatQrCodec
import com.darkmessage.app.crypto.CryptoEngine
import com.darkmessage.app.crypto.CryptoEngineImpl
import com.darkmessage.app.crypto.KeyDeriver
import com.darkmessage.app.crypto.Pbkdf2KeyDeriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideKeyDeriver(): KeyDeriver {
        // Use PBKDF2 for cross-platform compatibility with iOS
        return Pbkdf2KeyDeriver()
    }

    @Provides
    @Singleton
    fun provideCryptoEngine(keyDeriver: KeyDeriver): CryptoEngine {
        return CryptoEngineImpl(keyDeriver)
    }

    @Provides
    @Singleton
    fun provideChatQrCodec(keyDeriver: KeyDeriver): ChatQrCodec {
        // QR chat-key exchange: same PBKDF2 deriver as the message crypto
        return ChatQrCodec(keyDeriver)
    }
}
