package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.syncdb.core.db.SyncDatabase

actual fun createTestDriver(): SqlDriver = inMemoryDriver(SyncDatabase.Schema)
