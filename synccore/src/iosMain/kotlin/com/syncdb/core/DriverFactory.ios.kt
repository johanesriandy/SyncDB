package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.syncdb.core.db.SyncDatabase

actual class DriverFactory {
    actual fun createDriver(databaseName: String): SqlDriver =
        NativeSqliteDriver(SyncDatabase.Schema, databaseName)
}
