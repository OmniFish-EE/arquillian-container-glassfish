# Arquillian Container GlassFish Pool — Maven Plugin

Maven plugin that drives the lifecycle of an
[`arquillian-glassfish-server-pool`](../glassfish-pool/) pool. Binds
naturally to `pre-integration-test` / `post-integration-test` so a
consumer pom only has to add two plugin blocks (this one with a
`<distribution>` element, and failsafe) to get a parallel-fork-friendly
pool of GlassFish instances.

| Coordinates  | Value                              |
| ------------ | ---------------------------------- |
| `groupId`    | `ee.omnifish.arquillian`           |
| `artifactId` | `glassfish-pool-maven-plugin`      |
| Goal prefix  | `glassfish-pool`                   |
| Maven        | 3.9.0+                             |

## Goals

| Goal        | Default phase            | Purpose                                             |
| ----------- | ------------------------ | --------------------------------------------------- |
| `up`        | `pre-integration-test`   | Stage GlassFish (optional), provision and start every slot in parallel. Idempotent — re-running fast-exits if all slots are healthy. |
| `down`      | `post-integration-test`  | Stop every slot's domain. Best-effort.              |
| `provision` | (none)                   | Provision a single extra slot at `-Dgf.pool.slot=N`. Mostly debugging — `SlotLeaser` grows the pool itself when test JVMs need it. |
| `status`    | (none)                   | Print one row per slot: index, admin port, alive, leased. Live-refreshes on a TTY (like `top`); one-shot when piped or under `-B`. |
| `nuke`      | (none)                   | Stop every slot AND remove the pool directory. Forceful reset before a clean `up`. |

## Quick start

Bind `up` and `down` to the integration-test phases, declare a
`<distribution>` so the plugin resolves and unpacks GlassFish itself,
point `failsafe` at the same `poolDir`, and run `mvn verify`:

```xml
<plugin>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>glassfish-pool-maven-plugin</artifactId>
    <version>2.2.0</version>
    <configuration>
        <poolDir>${project.build.directory}/pool</poolDir>
        <poolSource>${project.build.directory}/dist/glassfish9</poolSource>
        <poolSize>4</poolSize>
        <distribution>
            <groupId>org.glassfish.main.distributions</groupId>
            <artifactId>glassfish</artifactId>
            <version>9.0.0</version>
            <type>zip</type>
        </distribution>
    </configuration>
    <executions>
        <execution><id>pool-up</id><goals><goal>up</goal></goals></execution>
        <execution><id>pool-down</id><goals><goal>down</goal></goals></execution>
    </executions>
</plugin>
```

For the failsafe side (`gf.pool.dir` forwarding, parallel forks, the
`arquillian.xml` setup), see
[`../glassfish-pool/README.md`](../glassfish-pool/README.md#end-to-end-maven-setup).

If your build already runs `maven-dependency-plugin` for unrelated reasons,
drop `<distribution>` and let the existing `unpack` execution land the dist
at `${project.build.directory}/dist` — see *Bring your own unpack* below.

## Configuration reference

All goals share the parameters in *Common* below. `up` and `provision` add
the *Staging* block.

### Common (all goals)

| Parameter       | Property             | Default                         | Notes                                                  |
| --------------- | -------------------- | ------------------------------- | ------------------------------------------------------ |
| `poolDir`       | `gf.pool.dir`        | `${project.build.directory}/pool` | Pool root. Created if missing. **Required.**         |
| `poolSource`    | `gf.pool.source`     | (none)                          | Source GlassFish install. Required for `up`/`provision`. |
| `poolSize`      | `gf.pool.size`       | `1`                             | Number of slots `up` provisions.                       |
| `adminPortBase` | `gf.pool.adminBase`  | `14848`                         | Admin port for slot 1.                                 |
| `portStride`    | `gf.pool.portStride` | `100`                           | Per-slot port spacing. Must be ≥ 10.                   |
| `skip`          | `gf.pool.skip`       | `false`                         | Skip the goal. Combine with `-DskipTests`/`-Dmaven.test.skip` to disable the lifecycle without removing the binding. |

`up` additionally honours `-DskipTests` and `-Dmaven.test.skip` — if the
build isn't running tests there's no reason to pay for a pool.

### Staging (`up`, `provision`)

| Parameter         | Property               | Default                          | Notes                                                                  |
| ----------------- | ---------------------- | -------------------------------- | ---------------------------------------------------------------------- |
| `distribution`    | (nested `<distribution>`) | (none)                        | Optional GAV of a GlassFish dist to resolve and unpack into `stageDir`. |
| `stageDir`        | `gf.pool.stageDir`     | `${project.build.directory}/dist`| Where the dist zip is unpacked.                                        |
| `unpackSkip`      | `gf.pool.unpack.skip`  | `false`                          | Skip the unpack — assume `poolSource` already points at a staged install. |
| `overlays`        | (nested `<overlays>`)  | (empty)                          | Artifacts copied into `overlayTargetDir` after unpack. See *Overlays*.  |
| `overlayTargetDir`| `gf.pool.overlayTargetDir` | `${poolSource}/glassfish/modules` | Where overlay jars land. Default matches the standard GlassFish layout. |
| `overlaySkip`     | `gf.pool.overlay.skip` | `false`                          | Skip overlay copying.                                                  |

`provision` additionally requires `-Dgf.pool.slot=N`.

## Staging behaviour

The plugin uses Aether to resolve `<distribution>` through your usual
repositories and unpacks it under `<stageDir>`
(default `${project.build.directory}/dist`) before provisioning runs.
Staging runs once per pool — re-runs fast-exit when the marker file
written after a successful unpack is still present. Set
`<unpackSkip>true</unpackSkip>` to short-circuit the check entirely.

### Bring your own unpack

If your build already runs `maven-dependency-plugin` for unrelated reasons,
drop the `<distribution>` block from this plugin and add a regular `unpack`
execution that lands the dist in the same directory `<poolSource>` points at:

```xml
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>unpack-glassfish</id>
            <phase>process-test-classes</phase>
            <goals><goal>unpack</goal></goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>org.glassfish.main.distributions</groupId>
                        <artifactId>glassfish</artifactId>
                        <version>9.0.0</version>
                        <type>zip</type>
                        <outputDirectory>${project.build.directory}/dist</outputDirectory>
                    </artifactItem>
                </artifactItems>
            </configuration>
        </execution>
    </executions>
</plugin>
```

The pool plugin will then skip staging and clone slots directly from
`${project.build.directory}/dist/glassfish9`.

## Overlays

Drop additional jars (or replace shipped ones) into the staged install's
`modules/` directory before slot cloning. Each `<overlay>` is a Maven GAV
plus a `<destFileName>`:

```xml
<configuration>
    <overlays>
        <overlay>
            <groupId>org.glassfish</groupId>
            <artifactId>jakarta.faces</artifactId>
            <version>5.0.0</version>
            <destFileName>jakarta.faces.jar</destFileName>
        </overlay>
    </overlays>
</configuration>
```

| Overlay element | Type    | Default                          | Notes                                                   |
| --------------- | ------- | -------------------------------- | ------------------------------------------------------- |
| `groupId` / `artifactId` / `version` / `type` / `classifier` | String  | (GAV)                            | Maven coordinate to resolve.                            |
| `destFileName`  | String  | source artifact's file name      | File name to copy under `overlayTargetDir`.             |
| `skip`          | boolean | `false`                          | Skip just this overlay. Property-driven for per-profile toggling, e.g. `<skip>${soteria.noupdate}</skip>`. |

`overlaySkip` is the master kill-switch (skip *all* overlays); per-overlay
`<skip>` lets you hold back a subset (e.g. keep the API jar pinned but
opt out of the impl overlay against a SNAPSHOT it doesn't yet match).

Skip everything in CI without editing the pom: `mvn verify -Dgf.pool.overlay.skip=true`.

## Recipes

### Run from the command line, no project binding

```
mvn ee.omnifish.arquillian:glassfish-pool-maven-plugin:status \
    -Dgf.pool.dir=target/pool
```

`status` and `nuke` declare `requiresProject = false`, so they work outside a
project (handy for poking at a pool another build left behind).

### Live status table

```
mvn glassfish-pool:status -Dgf.pool.dir=target/pool
```

On an interactive TTY the table redraws every second until Ctrl+C.
Force one-shot in a TTY with `-Dgf.pool.once`. CI runs (`-B` /
`--batch-mode`) always print one frame and exit — they don't fill logs with
redraw frames.

### Add an extra slot to a running pool

```
mvn glassfish-pool:provision \
    -Dgf.pool.dir=target/pool \
    -Dgf.pool.source=target/dist/glassfish9 \
    -Dgf.pool.slot=5
```

Day-to-day this is rarely needed — the test-JVM leaser grows the pool
itself when it can't find an idle slot.

### Wipe and start over

```
mvn glassfish-pool:nuke -Dgf.pool.dir=target/pool
```

Stops every slot, then deletes the pool directory. The next `up` cold-starts
everything.

### Skip the lifecycle in a build

`-Dgf.pool.skip=true` short-circuits every goal. `-DskipTests` /
`-Dmaven.test.skip` skip just `up` (and by transitive intent, `down` —
there's nothing to stop).

## Cross-build coordination

Locks inside `poolDir` (`grow.lock`, per-slot `lock`) coordinate JVMs that
share the same `poolDir`. They do **not** coordinate two `mvn` invocations
that target the same `poolDir` at once — the second `up` will collide with
the first on cloning. Run parallel `mvn` invocations against distinct
`poolDir`s (or distinct `adminPortBase` values).

A JVM shutdown hook installed by `up` calls `down` if the Maven process is
killed (Ctrl+C, hard build failure), so slots don't get orphaned.

## Troubleshooting

- **`Pool up failed: ... Address already in use`** — another process holds a
  port in the slot's window. Pick a different `adminPortBase`, or `nuke`
  any stale pool that didn't shut down cleanly.
- **`Slot N provisioning failed during grow`** — usually means
  `gf.pool.source` is wrong or the source install is corrupt. `mvn
  glassfish-pool:status` will show the slot stuck in `bootstrap`; `nuke` and
  retry.
- **Status table garbled in CI** — CI is being detected as interactive.
  Add `-B` (batch mode) or `-Dgf.pool.once`.
- **`portStride must be >= 10`** — a GlassFish domain uses ~10 ports;
  shrinking the stride collides them.

## Related modules

- [`arquillian-glassfish-server-pool`](../glassfish-pool/) — the runtime
  this plugin drives. Read its README for the architecture and the
  `arquillian.xml` setup.
- [`integration-tests/src/it/pool`](../integration-tests/src/it/pool) —
  worked example: dependency-plugin unpack + this plugin + failsafe +
  Arquillian, all in one ~150-line pom.
