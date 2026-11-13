package com.pennywiseai.tracker.di

import com.pennywiseai.tracker.data.statement.gmail.GmailAuthorizer
import com.pennywiseai.tracker.data.statement.gmail.UnsupportedGmailAuthorizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Gmail statement fetch: the no-Google stub. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmailModule {

    @Binds
    @Singleton
    abstract fun bindGmailAuthorizer(impl: UnsupportedGmailAuthorizer): GmailAuthorizer
}
