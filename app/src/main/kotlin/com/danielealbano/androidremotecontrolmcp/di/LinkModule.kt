package com.danielealbano.androidremotecontrolmcp.di

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.link.AlarmTicker
import com.danielealbano.androidremotecontrolmcp.services.link.ConnectionManager
import com.danielealbano.androidremotecontrolmcp.services.link.DefaultNetworkWatch
import com.danielealbano.androidremotecontrolmcp.services.link.LinkParts
import com.danielealbano.androidremotecontrolmcp.services.link.PowerWakeLock
import com.danielealbano.androidremotecontrolmcp.services.link.ServiceHost
import com.danielealbano.androidremotecontrolmcp.services.link.ShortWake
import com.danielealbano.androidremotecontrolmcp.services.remote.ChannelHooks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** The one connection manager and the system parts it works with. */
@Module
@InstallIn(SingletonComponent::class)
object LinkModule {
    @Provides
    @Singleton
    fun provideShortWake(
        @ApplicationContext context: Context,
    ): ShortWake = ShortWake(PowerWakeLock(context, "brooce:link-work"), PowerWakeLock(context, "brooce:link-arrival"))

    @Provides
    @Singleton
    fun provideConnectionManager(
        @ApplicationContext context: Context,
        wake: ShortWake,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ConnectionManager {
        val parts = LinkParts(DefaultNetworkWatch(context), AlarmTicker(context), ServiceHost(context), wake)
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        return ConnectionManager(parts, scope, System::currentTimeMillis) { Log.i("MCP:Link", it) }
    }

    @Provides
    @Singleton
    fun provideChannelHooks(
        manager: ConnectionManager,
        wake: ShortWake,
    ): ChannelHooks = ChannelHooks(manager) { wake.touch() }
}
