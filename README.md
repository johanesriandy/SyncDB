# SyncDB — WatermelonDB sync algorithm, ported to Kotlin Multiplatform

A small, reusable **offline-sync engine** that keeps a local SQLite database in
sync with any remote speaking WatermelonDB's pull/push protocol. This is a port
of the WatermelonDB **sync algorithm** (`src/sync/impl`) — not the WatermelonDB
library and not its ORM. The remote is pluggable; the library defines the
protocol shape and the client-side algorithm only.

- **Targets:** `androidTarget`, `iosArm64`, `iosSimulatorArm64` (+ a `jvm` dev/test target).
- **All algorithm code lives in `commonMain`.** The only `expect`/`actual` is the SQLite driver factory.
- **Storage:** SQLDelight. **JSON:** kotlinx.serialization. **Async:** coroutines + Flow.
- **Transport is an interface** (`SyncTransport`); a Ktor implementation ships in a separate module.

---

## Modules

This repo is the **public library** (`com.syncdb`). It contains no
backend-specific code — a consumer supplies the remote by implementing
`SyncTransport` (or configuring the provided `KtorSyncTransport`).

| Module | Source set(s) | What it is |
|---|---|---|
| `synccore` | `commonMain` (+ `androidMain`/`iosMain`/`jvmMain` drivers) | The engine, algorithm, storage, descriptors. No transport dependency. |
| `synctransport-ktor` | `commonMain` | `SyncTransport` over Ktor + a generic `passwordLogin` helper. Depends on `synccore` only. |

Coordinates: `com.syncdb:synccore` and `com.syncdb:synctransport-ktor` (version `0.1.0`).

---

## Prerequisites & setup (Windows)

This repo was scaffolded on a machine with **no JVM toolchain** initially. Current state:

| Component | Needed | Notes |
|---|---|---|
| **Android SDK** | ✅ satisfied | Installed by Android Studio at `%LOCALAPPDATA%\Android\Sdk` (platform `android-37`, build-tools `36`). Path is wired in `local.properties`. |
| **JDK 21 (Temurin)** | ✅ installed | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. Used to run Gradle (JDK 25/JBR is too new for the current AGP/Kotlin). |
| **Gradle** | via wrapper | Pinned to **8.9** in `gradle/wrapper/gradle-wrapper.properties`. Generate the wrapper once (below). |

> **iOS note:** the iOS targets are declared and their code is written, but
> Kotlin/Native iOS binaries can only be **compiled and tested on macOS with
> Xcode**. On Windows, verify the algorithm through the **JVM** test target
> (`jvmTest`) — the tests live in `commonTest` and run identically on every target.

### First-time build

From the project root, in your own terminal (PowerShell), with `JAVA_HOME` set to JDK 21:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
# One-time: generate the Gradle wrapper (gradlew.bat + gradle-wrapper.jar)
gradle wrapper --gradle-version 8.9
# Run the algorithm test suite on the JVM (fast, no emulator):
.\gradlew.bat :synccore:jvmTest :synctransport-ktor:jvmTest
```

If you don't have a `gradle` on PATH, open the project in **Android Studio**
instead and let it sync — it provides Gradle and generates the wrapper for you.
Then run the `jvmTest` tasks from the Gradle tool window.

> **Known environment gotcha:** on some locked-down Windows setups, a security/
> firewall agent blocks the JVM's NIO selector loopback, and Gradle fails to
> start with `Unable to establish loopback connection`. This is not a project
> issue — it reproduces with a bare `Selector.open()`. If you hit it, run the
> build from Android Studio, from a normal user terminal, or in CI (a clean
> Linux runner is unaffected).

---

## The sync lifecycle

`SyncEngine.synchronize()` (port of WatermelonDB `synchronize`):

1. **Read watermark** — `lastPulledAt` from `_sync_state` (null on first sync).
2. **Resolve schema version** (+ migration info if migrations are enabled).
3. **Pull** — `transport.pullChanges(lastPulledAt, schemaVersion, migration)`.
   `timestamp` must be a positive, non-zero number (it becomes the next
   watermark — **never** a device clock).
4. **In ONE write transaction:**
   1. **Concurrency guard** — assert `_sync_state.last_pulled_at` is still the
      value read in step 1; abort the whole sync otherwise (no overlapping syncs).
   2. `applyRemoteChanges(pull.changes)`.
   3. Persist `last_pulled_at = pull.timestamp` (and the schema version when migrations are enabled).
5. **Fetch local changes** — `fetchLocalChanges()`.
6. **If non-empty:** `pushChanges(local, pull.timestamp)`, then
   `markLocalChangesAsSynced(local, push.rejectedIds)`.

Observe progress via `observeSyncState(): Flow<SyncState>`, and check for pending
work with `hasUnsyncedChanges(): Boolean`.

---

## Local bookkeeping model

Every synced table has, besides its `id` (text PK) and domain columns:

- **`_status`** — `synced` | `created` | `updated`.
  A brand-new local row is `created`; an edited previously-synced row is `updated`.
- **`_changed`** — comma-separated names of columns edited locally since the last sync.

Plus two engine tables:

- **`_sync_deleted(table_name, id)`** — tombstones. Deleting a synced row removes
  it from its table and records its id here until the deletion has been pushed.
- **`_sync_state(key, last_pulled_at, last_pulled_schema_version)`** — the watermark.

Rows sent to the remote are **stripped of `_status`/`_changed`** first.

### Write through the helpers, not raw SQL

So bookkeeping stays correct, the app mutates rows via `SqlLocalDatabase`:

```kotlin
db.create("posts", mapOf("id" to "p1", "title" to "Hello"))     // _status = created
db.update("posts", "p1", mapOf("title" to "Edited"))            // _status = updated, _changed += title
db.delete("posts", "p1")                                        // -> tombstone (or vanish if never synced)
```

---

## The conflict rule (per-column)

Default resolution (`resolveConflict`): **the server wins for every column
EXCEPT the columns the user changed locally** (those in `_changed`), which are
preserved. If the local row is (transiently) marked deleted, the deletion wins
and is pushed later.

```
resolved = remote merged over local
resolved.id       = local.id
resolved._status  = local._status
resolved._changed = local._changed
for column in local._changed: resolved[column] = local[column]
```

Override it per-app with a **pluggable resolver**:

```kotlin
val options = SyncOptions(
    conflictResolver = ConflictResolver { table, local, remote, resolved ->
        // return the row to write; return `resolved` to keep default behavior
        resolved
    }
)
```

---

## Registering a syncable table

Tables are **declared**, not hand-coded. Describe columns and the engine derives
all SQL:

```kotlin
val posts = SyncableTable(
    name = "posts",
    columns = listOf(
        SyncableColumn("title", ColumnType.TEXT),
        SyncableColumn("body", ColumnType.TEXT),
        SyncableColumn("is_pinned", ColumnType.BOOLEAN),
    ),
)
val schema = SyncSchema(listOf(posts, /* … */))
```

`id`, `_status`, and `_changed` are implicit — don't list them. Add the matching
`CREATE TABLE` to a SQLDelight `.sq` file (see
`synccore/src/commonMain/sqldelight/com/syncdb/core/db/`). The shipped
`ExampleSchema` (`posts`, `comments`) demonstrates the end-to-end pattern.

---

## Wiring it together

```kotlin
// 1. Platform driver (the only expect/actual)
val driver = DriverFactory(/* Android: context */).createDriver()

// 2. Open the local DB with your schema
val db = SyncCore.openDatabase(driver, ExampleSchema.schema)

// 3. A transport (Ktor impl, or your own SyncTransport)
val transport = KtorSyncTransport(httpClient, ExampleSchema.schema)

// 4. Engine
val engine = SyncCore.createEngine(db, transport, SyncOptions(schemaVersion = 1))

// 5. Sync
val result = engine.synchronize()
```

---

## Options (matching WatermelonDB flags)

| Option | Effect |
|---|---|
| `sendCreatedAsUpdated` | Push locally-created rows in the `updated` bucket (for upserting remotes). |
| `migrationsEnabledAtVersion` + `Migration` | Enable migration sync; schema version + migration are passed to `pullChanges` and persisted with the watermark. |
| `conflictResolver` | Override the default per-column conflict rule. |
| `logger` (`SyncLogger`) | Observe phases, applied counts, and resolved conflicts. |

---

## Consuming the library

Publish locally and depend on it by coordinate:
```bash
./gradlew publishToMavenLocal
```
```kotlin
// in a consumer build with mavenLocal() (or your published repo)
implementation("com.syncdb:synccore:0.1.0")
implementation("com.syncdb:synctransport-ktor:0.1.0")   // optional Ktor transport
```

A consumer wires a driver, a schema, and a transport together:
```kotlin
val driver = DriverFactory(/* Android: context */).createDriver()
val db = SyncCore.openDatabase(driver, mySchema)
val transport = KtorSyncTransport(httpClient, mySchema, /* endpoints, param names, … */)
val engine = SyncCore.createEngine(db, transport, SyncOptions(schemaVersion = 1))
val result = engine.synchronize()
```
`KtorSyncTransport` is fully parameterized (endpoints, query-param names, extra
fixed params, auth header), so it adapts to any WatermelonDB-protocol backend
without changes to the library.

## Testing

Pure/independently-testable functions: `resolveConflict`, `applyRemoteChanges`,
`fetchLocalChanges`, `markLocalChangesAsSynced`. The `commonTest` suite uses an
**in-memory SQLite driver** and a **fake `SyncTransport`**, covering: per-column
conflict resolution (incl. deleted short-circuit), `applyRemoteChanges` edge
cases (created-but-exists, created-but-locally-deleted recreate, updated-but-
missing insert, updated-but-locally-deleted skip, delete clears tombstone), the
`requiresUpdate` skip path, "row edited during push is not marked synced",
`rejectedIds` handling, watermark-from-remote-timestamp, and the concurrency
guard. Run with `:synccore:jvmTest`.
