package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver

actual fun createTestDriver(): SqlDriver = inMemoryDriver(EngineSchema)
