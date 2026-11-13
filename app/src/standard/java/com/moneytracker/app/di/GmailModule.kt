package com.moneytracker.app.di

import com.moneytracker.app.data.statement.gmail.GmailAuthorizer
import com.moneytracker.app.data.statement.gmail.GoogleGmailAuthorizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Gmail statement fetch: Google Play services sign-in. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GmailModule {

    @Binds
    @Singleton
    abstract fun bindGmailAuthorizer(impl: GoogleGmailAuthorizer): GmailAuthorizer
}
