package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * JVM driver — primarily a dev/desktop convenience. `databaseName` is treated as
 * a JDBC path; pass [JdbcSqliteDriver.IN_MEMORY] for an ephemeral database.
 */
actual class DriverFactory {
    actual fun createDriver(databaseName: String): SqlDriver {
        val url = if (databaseName == JdbcSqliteDriver.IN_MEMORY) {
            JdbcSqliteDriver.IN_MEMORY
        } else {
            "jdbc:sqlite:$databaseName"
        }
        return JdbcSqliteDriver(url).also { EngineSchema.create(it) }
    }
}
