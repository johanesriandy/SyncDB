# SyncDB

A small, reusable **offline-sync engine for Kotlin Multiplatform**. It keeps a
local SQLite database in sync with any remote that speaks
[WatermelonDB](https://github.com/Nozbe/WatermelonDB)'s pull/push protocol.

SyncDB is a faithful port of WatermelonDB's **sync algorithm** (`src/sync/impl`) —
not the WatermelonDB library and not its ORM. It defines the client-side
algorithm and the protocol shape only; the remote is entirely pluggable.

- **Platforms:** Android, iOS (`iosArm64`, `iosSimulatorArm64`), and a JVM target for fast tests.
- **All sync logic lives in `commonMain`.** The only `expect`/`actual` is the SQLite driver factory.
- **Storage:** [SQLDelight](https://cashapp.github.io/sqldelight/) · **JSON:** kotlinx.serialization · **Async:** coroutines + Flow.
- **Transport is an interface** (`SyncTransport`) — bring your own, or use the optional Ktor implementation.

## Modules

| Module | Coordinate | What it is |
|---|---|---|
| `synccore` | `com.syncdb:synccore` | The engine, algorithm, local storage, and table descriptors. No transport dependency. |
| `synctransport-ktor` | `com.syncdb:synctransport-ktor` | An optional `SyncTransport` over [Ktor](https://ktor.io/) + a generic `passwordLogin` helper. Depends on `synccore`. |

Current version: `0.1.0`.

## Requirements

- **JDK 17+** to build.
- **Android SDK** for the Android target (set `sdk.dir` in `local.properties` or the `ANDROID_HOME` env var).
- **macOS + Xcode** to compile/test the iOS targets (an Apple-toolchain requirement; on other OSes the iOS targets configure but don't build).
- The **Gradle wrapper is included** — use `./gradlew` (or `gradlew.bat` on Windows). No global Gradle needed.

## Installation

SyncDB is not yet published to a public repository. To use it today, build it
from source and publish to your local Maven repository:

```bash
git clone https://github.com/johanesriandy/SyncDB.git
cd SyncDB
./gradlew publishToMavenLocal        # publishes com.syncdb:*:0.1.0 to ~/.m2
```

Then depend on it from your app (a Kotlin Multiplatform or Android/JVM project):

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    implementation("com.syncdb:synccore:0.1.0")
    implementation("com.syncdb:synctransport-ktor:0.1.0") // optional Ktor transport
}
```

Alternatively, consume it directly from source with a
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html):
add `includeBuild("path/to/SyncDB")` to your `settings.gradle.kts` and depend on
the same coordinates.

## Quick start

```kotlin
// 1. Describe the tables you sync (id, _status, _changed are implicit).
val posts = SyncableTable(
    name = "posts",
    columns = listOf(
        SyncableColumn("title", ColumnType.TEXT),
        SyncableColumn("body", ColumnType.TEXT),
        SyncableColumn("is_pinned", ColumnType.BOOLEAN),
    ),
)
val schema = SyncSchema(listOf(posts))

// 2. Create the local database. DriverFactory is the one expect/actual:
//    Android: DriverFactory(context) · iOS/JVM: DriverFactory()
val driver = DriverFactory(/* context on Android */).createDriver()
val db = SyncCore.openDatabase(driver, schema)

// 3. Provide a transport. Implement SyncTransport yourself, or use the Ktor one:
val transport = KtorSyncTransport(
    client = httpClient,
    schema = schema,
    pullPath = "https://api.example.com/sync/pull",
    pushPath = "https://api.example.com/sync/push",
)

// 4. Build the engine and sync.
val engine = SyncCore.createEngine(db, transport, SyncOptions(schemaVersion = 1))
val result: SyncResult = engine.synchronize()
```

Your app mutates rows through helpers so sync bookkeeping stays correct:

```kotlin
db.create("posts", mapOf("id" to "p1", "title" to "Hello"))  // _status = created
db.update("posts", "p1", mapOf("title" to "Edited"))         // _status = updated, tracks _changed
db.delete("posts", "p1")                                     // tombstoned (or removed if never synced)
```

Observe progress with `engine.observeSyncState(): Flow<SyncState>` and check for
pending work with `engine.hasUnsyncedChanges(): Boolean`.

## The protocol

Your `SyncTransport` connects the engine to any WatermelonDB-style backend:

```kotlin
interface SyncTransport {
    suspend fun pullChanges(lastPulledAt: Long?, schemaVersion: Int, migration: Migration?): PullResult
    suspend fun pushChanges(changes: DatabaseChangeSet, lastPulledAt: Long): PushResult
}
```

- `pullChanges` returns `{ changes, timestamp }`. `lastPulledAt == null` on the
  first sync (the remote returns a full snapshot). `timestamp` is the remote
  clock and becomes the next `lastPulledAt` — **never** a device clock.
- `pushChanges` sends local `created`/`updated` (full rows) and `deleted` (ids
  only). Rows are stripped of the internal `_status`/`_changed` columns first.
- `PushResult.rejectedIds` (optional) leaves rejected rows dirty to retry.

`KtorSyncTransport` is fully parameterized — endpoints, query-parameter names,
extra fixed params, and the auth header — so it adapts to most backends without
touching the library. For anything unusual, implement `SyncTransport` directly.

## The sync lifecycle

`SyncEngine.synchronize()`:

1. **Read the watermark** — `lastPulledAt` from `_sync_state` (null on first sync).
2. **Resolve the schema version** (+ migration info if migrations are enabled).
3. **Pull** — `pullChanges(lastPulledAt, schemaVersion, migration)`. `timestamp` must be positive and non-zero.
4. **In one write transaction:** guard that the watermark is unchanged (no
   overlapping syncs), `applyRemoteChanges(...)`, then persist the new watermark.
5. **Collect local changes** — `fetchLocalChanges()`.
6. **If any:** `pushChanges(...)`, then `markLocalChangesAsSynced(...)`.

## Local bookkeeping model

Every synced table carries, besides its `id` (text PK) and domain columns:

- **`_status`** — `synced` | `created` | `updated`. A brand-new local row is
  `created`; an edited previously-synced row is `updated`.
- **`_changed`** — comma-separated names of columns edited locally since the last sync.

Plus two engine tables:

- **`_sync_deleted(table_name, id)`** — tombstones. Deleting a synced row removes
  it from its table and records its id here until the deletion has been pushed.
- **`_sync_state(key, last_pulled_at, last_pulled_schema_version)`** — the watermark.

## The conflict rule

Default resolution (`resolveConflict`), per column: **the server wins for every
column except the ones the user changed locally** (those listed in `_changed`),
which are preserved. A locally-deleted row keeps its deletion (pushed later).

Override it per app with a pluggable resolver:

```kotlin
SyncOptions(
    conflictResolver = ConflictResolver { table, local, remote, resolved ->
        resolved // return the row to write; `resolved` keeps the default behavior
    },
)
```

## Registering a table

Tables are **declared**, not hand-coded — the engine derives all SQL from the
descriptor. Add the matching `CREATE TABLE` to a SQLDelight `.sq` file (or use
`SyncableTable.createTableSql()` to generate it), including the `_status` and
`_changed` columns. The bundled `ExampleSchema` (`posts`, `comments`) shows the
end-to-end pattern in `synccore/src/commonMain/sqldelight/`.

## Options

| Option | Effect |
|---|---|
| `sendCreatedAsUpdated` | Push locally-created rows in the `updated` bucket (for upserting remotes). |
| `migrationsEnabledAtVersion` + `Migration` | Enable migration sync; schema version + migration are passed to `pullChanges` and persisted with the watermark. |
| `conflictResolver` | Override the default per-column conflict rule. |
| `logger` (`SyncLogger`) | Observe phases, applied counts, and resolved conflicts. |

## Building & testing

```bash
# Run the algorithm test suite on the JVM (fast, no emulator/simulator):
./gradlew :synccore:jvmTest :synctransport-ktor:jvmTest

# Build everything (Android + JVM; iOS requires macOS + Xcode):
./gradlew build
```

The `commonTest` suite runs against an in-memory SQLite driver and a fake
`SyncTransport`, covering per-column conflict resolution, every `applyRemoteChanges`
edge case, the `requiresUpdate` skip path, "row edited during push is not marked
synced", `rejectedIds` handling, the remote-timestamp watermark, and the
concurrency guard. Because the tests live in `commonTest`, they run identically
on every target.

## Credits

The algorithm is ported from [WatermelonDB](https://github.com/Nozbe/WatermelonDB)
(`src/sync/impl`). SyncDB has no dependency on WatermelonDB itself.

## License

No license has been chosen yet. Add a `LICENSE` file before external use.
