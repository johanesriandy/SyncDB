package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver

/**
 * The ONLY expect/actual in the library: constructing the platform SQLite driver.
 * Android's actual takes a `Context`; iOS/JVM take no arguments. Each actual
 * returns a driver with the [EngineSchema] (engine tables) already created.
 */
expect class DriverFactory {
    fun createDriver(databaseName: String = "syncdb.db"): SqlDriver
}
