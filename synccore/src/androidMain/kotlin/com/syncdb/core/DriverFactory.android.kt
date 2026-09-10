package com.syncdb.core

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.syncdb.core.db.SyncDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(databaseName: String): SqlDriver =
        AndroidSqliteDriver(SyncDatabase.Schema, context, databaseName)
}
