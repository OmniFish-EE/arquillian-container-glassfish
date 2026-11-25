# Arquillian Container GlassFish Pool — Maven Plugin

Maven plugin that drives the lifecycle of an
[`arquillian-glassfish-server-pool`](../glassfish-pool/) pool. Declared
with `<extensions>true</extensions>`, it starts the pool in
`pre-integration-test` and stops it once the build is done, so a consumer
pom only has to add two plugin blocks (this one with a `<distribution>` element,
and failsafe) to get a parallel-fork-friendly pool of GlassFish instances.

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
| `down`      | `post-integration-test`  | Stop every slot's domain. Fails if a slot keeps running, since the next `up` would reuse it. The extension binds it only with `multiModule` off. |
| `provision` | (none)                   | Provision a single extra slot at `-Dgf.pool.slot=N`. Mostly debugging — `SlotLeaser` grows the pool itself when test JVMs need it. |
| `status`    | (none)                   | Print one row per slot: index, admin port, alive, leased. Live-refreshes on a TTY (like `top`); one-shot when piped or under `-B`. |
| `nuke`      | (none)                   | Stop every slot AND remove the pool directory. Forceful reset before a clean `up`. |
| `run`       | (none)                   | Package the project, lease a slot, deploy the project to it, and keep running, with the slot's `server.log` on the console, until Ctrl+C. See [Running an application](#running-an-application). |

## Quick start

Declare the plugin as an extension, give it a `<distribution>` version so
it resolves and unpacks Eclipse GlassFish itself, and run `mvn verify`:

```xml
<plugin>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>glassfish-pool-maven-plugin</artifactId>
    <version>2.2.2</version>
    <extensions>true</extensions>
    <configuration>
        <distribution>
            <version>9.0.0</version>
        </distribution>
    </configuration>
</plugin>
```

`<extensions>true</extensions>` is what starts and stops the pool, and
hands its location to the test JVMs: see [Lifecycle binding](#lifecycle-binding)
below. That provisions a single slot. Add `<poolSize>4</poolSize>` (and a
matching failsafe `forkCount`) to fan tests out across four GlassFish
instances.

Failsafe itself still has to be declared with its `integration-test`/`verify`
goals, as for any integration-test build, but it needs no pool settings.
For parallel forks and the optional `arquillian.xml`, see
[`../glassfish-pool/README.md`](../glassfish-pool/README.md#end-to-end-maven-setup).

### Lifecycle binding

With `<extensions>true</extensions>`, Maven loads the plugin as a build
extension, and the plugin adds an execution to every project that
declares it (directly or inherited from a parent's `<plugins>`):

| Execution id | Goal   | Phase                   |
| ------------ | ------ | ----------------------- |
| `pool-up`    | `up`   | `pre-integration-test`  |
| `pool-down`  | `down` | `post-integration-test`, only with `multiModule` off |

They pick up the plugin-level `<configuration>`, and they work with any
packaging (`jar`, `war`, `pom`, …). A goal you already bind yourself is
left alone, so existing poms that keep an `<executions>` block don't run
`up` or `down` twice. A plugin declared only in `<pluginManagement>` binds
nothing. Instead of binding `down`, the extension stops the pool once,
after the whole build (see *One pool per build*).

The extension also forwards the pool layout and the
[slot setup](#slot-setup) to the test JVMs. After the pool is up, `up` sets
the project properties `gf.pool.dir`, `gf.pool.source`, `gf.pool.adminBase`
and `gf.pool.portStride`, and the slot setup's `gf.pool.*` properties, each
only if it isn't already set. The extension adds matching
`<gf.pool.dir>${gf.pool.dir}</gf.pool.dir>`-style entries to failsafe's
`<systemPropertyVariables>`, because failsafe doesn't pass project
properties to its forks on its own. Entries you configure yourself win.
Forwarding `gf.pool.source` lets test JVMs grow the pool when every slot is
busy; see
[Forwarding to failsafe](../glassfish-pool/README.md#forwarding-to-failsafe)
for the details and how to opt out. `up` also sets `gf.pool.size`, the size
of the pool it started, for the pom itself (see *Size the pool and the
forks from `-T`*). The test JVMs don't read it, so it isn't forwarded.

To keep explicit control instead, leave out `<extensions>` and bind the
goals yourself. The phases come from the goals' defaults. Then also add the
`${gf.pool.dir}`-style references to failsafe yourself:

```xml
<executions>
    <execution><id>pool-up</id><goals><goal>up</goal></goals></execution>
    <execution><id>pool-down</id><goals><goal>down</goal></goals></execution>
</executions>
```

That suits a single-module build. In a multi-module build, all modules
share one pool, and each module's `down` would stop it while later modules
still need it. There, turn `multiModule` off, so each module has its own
pool, or bind `down` once, in a module that depends on every module that
uses the pool. The last module in the list won't do: under `-T`, it isn't
necessarily the last to finish.

### Defaults

The snippet above relies on these defaults. Override one only when its
note applies:

| Parameter | Default | Override when… |
| --------- | ------- | -------------- |
| `poolDir` | `pool` in the build directory of the pool's root project (see *One pool per build*), or with `multiModule` off in the project's own | two `mvn` runs on one machine need separate pools. Failsafe picks up the new value automatically. |
| `poolSource` | the distribution zip's top-level directory under `stageDir` (e.g. `target/dist/glassfish9`) | you skip the unpack or bring your own (no `<distribution>`), or the zip has more than one top-level entry. |
| `distribution` `groupId` / `artifactId` / `type` | `org.glassfish.main.distributions` / `glassfish` / `zip` | you target Payara, a fork, or any other non-canonical artifact. Set the full coordinates. |
| `stageDir` | `dist` in the build directory of the pool's root project, or with `multiModule` off in the project's own | the unpacked dist should live elsewhere. |
| `poolSize` | `1` | you run tests in parallel forks. Match failsafe's `forkCount`, or set that to `${gf.pool.size}`. |
| `adminPortBase` / `portStride` | `14848` / `100` | the default port window clashes with something else on the machine. |

If something else in your build already unpacks GlassFish
(`glassfish:provision`, or a `maven-dependency-plugin` `unpack`), drop
`<distribution>` and point `<poolSource>` at it — see *Bring your own
unpack* below.

## Configuration reference

All goals share the parameters in *Common* below. `up` and `provision` add
the *Staging* and *Slot setup* blocks.

### Common (all goals)

| Parameter       | Property             | Default                         | Notes                                                  |
| --------------- | -------------------- | ------------------------------- | ------------------------------------------------------ |
| `poolDir`       | `gf.pool.dir`        | `target/pool`, see *Defaults*   | Pool root. Created if missing.                         |
| `multiModule`   | `gf.pool.multiModule` | `true`                         | One pool for the build, which all modules share, and which stops after the build. Turn it off only when a later step needs GlassFish stopped, when long-running modules follow the one that uses the pool, or when modules need different servers. See *One pool per build*. |
| `poolSource`    | `gf.pool.source`     | derived from `<distribution>`   | Source GlassFish install. Required for `up`/`provision`; defaults to `stageDir/<zip root>` when a `<distribution>` is unpacked. |
| `poolSize`      | `gf.pool.size`       | `1`                             | Number of slots `up` provisions.                       |
| `adminPortBase` | `gf.pool.adminBase`  | `14848`                         | Admin port for slot 1.                                 |
| `portStride`    | `gf.pool.portStride` | `100`                           | Per-slot port spacing. Must be ≥ 10.                   |
| `skip`          | `gf.pool.skip`       | `false`                         | Skip the goal. Combine with `-DskipTests`/`-Dmaven.test.skip` to disable the lifecycle without removing the binding. |

`up` additionally honours `-DskipTests` and `-Dmaven.test.skip` — if the
build isn't running tests there's no reason to pay for a pool.

### Staging (`up`, `provision`, `run`)

| Parameter         | Property               | Default                          | Notes                                                                  |
| ----------------- | ---------------------- | -------------------------------- | ---------------------------------------------------------------------- |
| `distribution`    | (nested `<distribution>`) | (none)                        | Optional GAV of a GlassFish dist to resolve and unpack into `stageDir`. Only `<version>` is required; `groupId`/`artifactId`/`type` default to `org.glassfish.main.distributions`/`glassfish`/`zip`. |
| `stageDir`        | `gf.pool.stageDir`     | `target/dist`, see *Defaults*    | Where the dist zip is unpacked.                                        |
| `unpackSkip`      | `gf.pool.unpack.skip`  | `false`                          | Skip the unpack — assume `poolSource` already points at a staged install. |
| `overlays`        | (nested `<overlays>`)  | (empty)                          | Artifacts copied into `overlayTargetDir` after unpack. See *Overlays*.  |
| `overlayTargetDir`| `gf.pool.overlayTargetDir` | `${poolSource}/glassfish/modules` | Where overlay jars land. Default matches the standard GlassFish layout. |
| `overlaySkip`     | `gf.pool.overlay.skip` | `false`                          | Skip overlay copying.                                                  |
| `trustedCerts`    | (nested `<trustedCerts>`) | (empty)                       | Certificates imported into the staged install's truststore, so every slot trusts them. See *Trusted certificates*. |
| `trustStore`      | `gf.pool.trustStore`   | the domain's `cacerts.p12`       | Where they go; `cacerts.jks` in older versions. Doesn't change which truststore GlassFish reads. |
| `trustStorePassword` | `gf.pool.trustStorePassword` | `changeit`               | The truststore's password.                                             |
| `certsSkip`       | `gf.pool.certs.skip`   | `false`                          | Skip the certificate import.                                           |
| `sourcePostBootCommands` | `gf.pool.sourcePostBootCommands` | (none)             | `asadmin` commands run once on the staged install, on a brief start, before slots are cloned from it. One per line. See *Commands on the source*. |

### Slot setup (`up`, `provision`, `run`)

What every slot gets besides its ports. See [Slot setup](#slot-setup).

| Parameter            | Property                     | Default | Notes |
| -------------------- | ---------------------------- | ------- | ----- |
| `systemProperties`   | `glassfish.systemProperties` | (none)  | One `key=value` per line, baked into each slot's `domain.xml` as `-Dkey=value` jvm-options. |
| `maxHeapSize`        | `glassfish.maxHeapSize`      | (none)  | Each slot's `-Xmx`, e.g. `2048m`. |
| `enableAssertions`   | `glassfish.enableAssertions` | (none)  | What follows each slot's `-ea`, e.g. `:org.jboss.cdi.tck...`. |
| `postBootCommands`   | `gf.pool.postBootCommands`   | (none)  | `asadmin` commands run once per slot, after its domain first starts. One per line. |
| `postStartCommands`  | `gf.pool.postStartCommands`  | (none)  | `asadmin` commands run after every slot start and restart. One per line. |
| `failOnCommandError` | `gf.pool.failOnCommandError` | `true`  | Whether a failed command fails the slot's setup, instead of being logged. |
| `debug` / `suspend`  | `glassfish.debug` / `glassfish.suspend` | `false` | Start slots with `--debug` / `--suspend`. Single-slot pools only; see [Debugging a slot](../glassfish-pool/README.md#debugging-a-slot). |

`provision` additionally requires `-Dgf.pool.slot=N`.

## Staging behaviour

The plugin uses Aether to resolve `<distribution>` through your usual
repositories and unpacks it under `<stageDir>`
(default `${project.build.directory}/dist`) before provisioning runs.
Overlays are copied, trusted certificates imported and source commands run
after the unpack. A marker file records a fingerprint of all of that, so
re-runs leave the staged install alone until something changes: another
distribution or `poolSource`, a zip or overlay rebuilt with `mvn install`,
or an overlay, certificate or source command added, changed or removed.
Then `stageDir` is wiped and staged afresh. A non-empty `stageDir` without the marker fails the build and is
left alone. With `<unpackSkip>true</unpackSkip>`, the plugin doesn't own the
install: it copies the overlays and imports the certificates into it on
every run. If `stageDir` holds an install the plugin staged, such a run
drops its fingerprint, so the next run without `unpackSkip` stages it
afresh.

This is the same staging as `glassfish:provision` in the
[GlassFish Maven Plugin](../glassfish-maven-plugin/), with the same
`<distribution>` and `<overlays>` elements. Only the `-D` switches differ
(`gf.pool.*` here).

### Bring your own unpack

If something else in the build already unpacks GlassFish, drop the
`<distribution>` block from this plugin and set `<poolSource>` to that
install (it is only derived when the plugin does the unpack itself). The
pool plugin then skips staging and clones slots directly from there.

When [`glassfish:provision`](../glassfish-maven-plugin/) does the unpack,
for instance because managed-connector tests in the same module need
GlassFish too, `<poolSource>${glassfish.home}</poolSource>` picks up the
path it publishes, as long as the pom doesn't define `glassfish.home`
itself. `provision` runs at `process-test-classes`, well before `up`.

A regular `maven-dependency-plugin` `unpack` execution works as well:

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

with `<poolSource>${project.build.directory}/dist/glassfish9</poolSource>`.

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
| `skip`          | boolean | `false`                          | Leave just this overlay out.                            |
| `apply`         | boolean | `true`                           | The reverse of `skip`: `false` leaves the overlay out. Property-driven for per-profile toggling, e.g. `<apply>${soteria.update}</apply>`. |

An overlay sets `<skip>` or `<apply>`, not both: the goal fails when one
sets both, even if they agree. A property that isn't defined leaves its
element unset.

`overlaySkip` is the master kill-switch (skip *all* overlays); per-overlay
`<skip>` or `<apply>` lets you hold back a subset (e.g. keep the API jar pinned but
opt out of the impl overlay against a SNAPSHOT it doesn't yet match).

Skip everything in CI without editing the pom: `mvn verify -Dgf.pool.overlay.skip=true`.

## Trusted certificates

Make every slot trust a certificate GlassFish doesn't know, such as an
external identity provider's server certificate for OpenID. Each
`<trustedCert>` is an `<alias>` and a PEM or DER `<file>`:

```xml
<configuration>
    <distribution>
        <version>9.0.0</version>
    </distribution>
    <trustedCerts>
        <trustedCert>
            <alias>tomcat</alias>
            <file>${project.basedir}/tomcat.cert</file>
        </trustedCert>
    </trustedCerts>
</configuration>
```

With the plugin in a multi-module project's parent, write a path from the
root as `${gf.pool.rootDirectory}/...`, which stays the root's when Maven
runs in a module; see [One pool per build](#one-pool-per-build).

Staging imports them into the staged install's truststore before any slot
is cloned from it, so the slots have them from their first start. That
matters: GlassFish reads its truststore once, when it first opens a TLS
connection, so a certificate added to a running slot isn't picked up. Slots
still running from an earlier build keep their truststore, as `up` reuses
them as they are.

A change to the certificates stages the install afresh, so slots cloned
from it from then on have exactly the configured ones. With `unpackSkip`,
the import only adds and replaces entries: a removed `<trustedCert>` stays
in the install, and in the slots cloned from it. And `trustStore` only
changes where the certificates go: for the slots to use them, it must be
the truststore GlassFish reads, inside the staged install, as the default
is.

This is the same import as `glassfish:provision`'s; see
[Trusted certificates](../glassfish-maven-plugin/README.md#trusted-certificates)
for the details.

## Slot setup

Every slot gets the same server settings and `asadmin` commands, whether
`up` created it or a test JVM did, to grow the pool or to replace a dead
slot: `up` forwards the setup to the test JVMs together with the pool
layout.

```xml
<configuration>
    <distribution>
        <version>9.0.0</version>
    </distribution>
    <maxHeapSize>2048m</maxHeapSize>
    <enableAssertions>:org.jboss.cdi.tck...</enableAssertions>
    <systemProperties>
        javax.net.ssl.trustStorePassword=changeit
    </systemProperties>
    <postBootCommands>
        create-jms-resource --restype jakarta.jms.Queue --property Name=queue_test queue_test
        set configs.config.server-config.cdi-service.enable-implicit-cdi=true
    </postBootCommands>
</configuration>
```

- The settings go into each slot's own `domain.xml`; the staged install
  stays as it is.
- **Post-boot commands** run once per slot, after its domain first starts,
  for setup whose effects persist: users, resources, `set` calls. If they
  leave changes that GlassFish only applies after a restart, the slot
  restarts once.
- **Post-start commands** run after every start and restart, including a
  [`restartOnRelease`](../glassfish-pool/README.md#restarting-glassfish-between-tests)
  restart, for what a restart loses.
- One command per line. Quotes group words, as in
  `--description "two words"`. Backslashes are passed on as they are,
  because `asadmin` reads `\:` and `\=` itself. Blank lines and lines
  starting with `#` are skipped.
- `--passwordfile <file>`, or `--passwordfile=<file>`, may come anywhere on
  the line, as in `create-file-user --groups staff j2ee --passwordfile
  ${project.build.testOutputDirectory}/j2ee.pass`. It's moved in front of
  the subcommand, where `asadmin` needs it. A line with two of them fails.
- With `failOnCommandError`, on by default, a failed command fails `up`, or
  the lease of the test JVM that created the slot. After a
  `restartOnRelease` restart, the test class has already finished, so a
  failed command stops the slot instead, and the slot is re-created before
  its next lease. Set it to `false` to log failures and carry on.

### Commands on the source

`<sourcePostBootCommands>` run once on the staged install, during a brief
start after the overlays and certificates, before any slot is cloned from
it. Unlike `<postBootCommands>`, they don't run on every slot, but slots
get only the effects that cloning carries over: what the commands write to
files a clone copies, such as `domain.xml` and `keyfile`. What lives in
memory, in the JMS broker's store (`imq/instances`) or in the OSGi cache
doesn't carry over, since cloning skips those directories. Commands like
`create-file-user`, `create-jdbc-resource` and `set` qualify.

They are staged like the rest of the source (see *Staging behaviour*):
changing them stages it afresh, and so does changing `failOnCommandError`,
which covers them. The brief start runs the domain on three free ports,
and gives it its own ports back afterwards. Like `glassfish:provision`'s `<postBootCommands>`,
they need a source this plugin unpacks itself, so they can't be combined
with `unpackSkip`, and need a `<distribution>`. For a source staged by
`glassfish:provision`, give that goal the commands instead.

### Moving settings from failsafe

With the managed Arquillian Container, these settings live in failsafe's
`<systemPropertyVariables>`. For a pool, move them to this plugin:

- `glassfish.postBootCommands` still works in the test JVM, but the pool's
  Arquillian Container runs it on every lease, so a command that can't run
  twice, such as `create-file-user`, fails from the second lease on. It logs
  a hint once per JVM. Move the commands to `<postBootCommands>`, or to
  `<postStartCommands>` if they must run after every restart.
- `glassfish.systemProperties`, `glassfish.maxHeapSize` and
  `glassfish.enableAssertions` in the test JVM don't reach the slots. The
  pool's Arquillian Container warns once when the leased slot lacks them.

### Slots from an earlier build

`up` reuses slots still running from an earlier build as they are, without
applying a changed setup. Normally there are none: the extension stops
every slot after the build, or `down` does in `post-integration-test` with
`multiModule` off, and a shutdown hook stops them when the build is
interrupted. If `stop-domain` fails, `down` retries with
`stop-domain --kill`, and fails if a slot still answers after that. If a
pool outlives its build anyway, for example because Maven was killed, stop
it with `glassfish-pool:down` before changing its configuration.

## One pool per build

A build has one pool, whether it has one module or many (`multiModule`, on
by default). In a multi-module build, such as a TCK with a module per area,
all modules share it, so configure the plugin in the parent:

```xml
<plugin>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>glassfish-pool-maven-plugin</artifactId>
    <extensions>true</extensions>
    <configuration>
        <distribution>9.0.0</distribution>
    </configuration>
</plugin>
```

- `poolDir` and `stageDir` default to the build directory of the pool's
  root project: `target/pool` and `target/dist` there, instead of in each
  module. The root is the topmost project around the one being built whose
  own pom configures the plugin, like the parent above, or the project
  where Maven runs, when that's higher. Only parents in a directory around
  the module count, not one from a repository. So the pool is the same
  whether Maven runs at the root, with `-pl`, or in a module's directory.
  Explicit values win. `up` publishes `gf.pool.dir` as always, so the test
  JVMs get the shared directory.
- With `<extensions>true</extensions>`, the root's directory is available
  as `${gf.pool.rootDirectory}`, for paths in the configuration that are
  relative to it, such as a trusted certificate's:
  `<file>${gf.pool.rootDirectory}/certs/idp.pem</file>`. Unlike
  `${maven.multiModuleProjectDirectory}`, it doesn't need a `.mvn`
  directory to stay the same when Maven runs in a module. A value you set
  yourself wins.
- The first `up` in the build starts the pool. The others find it running
  and return straight away. If it starts only partly, the slots that did
  start are stopped after the build all the same.
- The extension doesn't bind `down` after each module, which would stop the
  pool while later modules still need it. It stops the pool once, after the
  whole build, also when the build failed. A slot that keeps running then
  fails the build, as with `down`; Maven reports that after its build
  summary. A shutdown hook still stops the pool when the build is
  interrupted.
- A `down` you bind yourself still runs where you bind it. In a
  multi-module build, that stops the pool while later modules still need
  it, so remove it, or turn `multiModule` off. In a single-module build, it
  works as before, and the stop after the build finds the pool already
  stopped.
- All modules share one pool and one slot setup, so configure the plugin
  in the parent. A module's own settings don't reach slots that already
  run.
- Under `-T`, modules run their tests at the same time, each in one test
  JVM with failsafe's default `forkCount` of 1. Each test JVM leases a
  slot, and adds one to the pool when every slot is busy. At `-T5`, the
  pool needs at most 5 slots.
- The extension reads `multiModule` from the plugin-level
  `<configuration>`, or else from the `gf.pool.multiModule` property, set
  with `-D` or in the pom's `<properties>`; not from an `<execution>`.
- Without `<extensions>true</extensions>`, `multiModule` only changes the
  defaults, and binding `down` is up to you; see *Lifecycle binding*.

### When to turn `multiModule` off

Rarely. Having a single module is no reason: the pool then only stops a
little later, at the end of the build instead of in
`post-integration-test`. Turn it off, in a multi-module build in the
parent's configuration, when:

- a later step needs GlassFish stopped, such as a `verify` step that
  collects the server logs after shutdown, or changes the install offline;
- long-running modules follow the one that uses the pool. With
  `multiModule` on, GlassFish keeps running until the whole build is done;
- modules need different servers, such as another `<distribution>` or
  other commands. A shared pool has one setup: the first `up` starts the
  slots with its configuration, and the other modules reuse them as they
  are.

```xml
<configuration>
    <multiModule>false</multiModule>
</configuration>
```

Each module then has its own pool in its own `target`, and the extension
binds `down` to stop it in `post-integration-test`. Pools that run at the
same time, as under `-T`, need their own `adminPortBase`, since every pool
starts at 14848.

## Running an application

`mvn glassfish-pool:run` packages the project, leases a slot of the pool as
a test does, deploys the project's WAR, EAR, EJB jar or RAR to it, and keeps
running, with the slot's `server.log` on the console, until Ctrl+C. The slot
is the GlassFish the tests run on: staged with the pool's overlays and
certificates, and set up with its system properties and commands. The
plugin needs no configuration beyond what the tests use.

```
$ mvn glassfish-pool:run
...
[INFO] Leased slot 1 of the pool at .../target/pool
[INFO] Deployed app-mem-basic, at http://localhost:14849/app-mem-basic/
[INFO] Ctrl+C ends the run, and hands slot 1 back to the pool
```

- **The slot:** a free one, or else one the pool grows by. Each slot has
  its own ports: with the defaults, slot 1 serves HTTP on 14849, the port
  after `adminPortBase`, and slot 2 on 14949. When the build binds `up`
  before `package`, as the Jakarta Security TCK does at `initialize`, the
  pool already runs by the time `run` leases. Otherwise `run` stages
  GlassFish, and the pool grows by the one slot it needs.
- **Names:** `-Dglassfish.run.name` sets the application's name, and
  `-Dglassfish.run.contextRoot` a WAR's context root, as for
  `glassfish:run`. The artifact replaces an application of the same name.
- **The log's messages:** the console shows only the messages of the
  slot's `server.log` records, without the header before each, with its
  time, level, logger and thread, and without the `]]` after each, as for
  `glassfish:run`:

  ```
  [glassfish:slot-1] Initializing Soteria 5.0.0 for context '/app-mem-basic'
  ```

  `-Dglassfish.run.logMessagesOnly=false`, or
  `<logMessagesOnly>false</logMessagesOnly>`, shows the records whole, as
  the file has them.
- **Ending it:** Ctrl+C undeploys the application and hands the slot back.
  The pool then stops, as it does after tests, except for the slots that
  runs in other terminals hold. `mvn glassfish-pool:down` from another
  terminal stops every slot, and so ends every run.
- **Two modules at once:** each run leases its own slot of one pool, in
  the root's `target/pool`. From the reactor root:

  ```
  mvn -pl :app-mem-basic glassfish-pool:run
  mvn -pl :app-db-basic glassfish-pool:run
  ```

  When one ends, the slot the other holds keeps running, and the last run
  to end stops the pool. Staging isn't coordinated between Maven processes,
  so on a fresh checkout, start the second run once the first has staged
  GlassFish. Runs started in their modules' directories share the pool
  too.

  A slot's GlassFish shares the terminal of the Maven that started it, so
  that terminal's Ctrl+C reaches it too. With a pool size of 1, the
  default, each run's slot is its own: the second run grows the pool by the
  slot it leases. With a larger pool, the first run's `up` starts slots the
  second run may lease, and Ctrl+C in the first terminal then ends both.
- **From a module's directory:** `cd app-mem-basic` and
  `mvn glassfish-pool:run` work the same: the pool is the root's, as above.
  Paths in the configuration that belong to the root, such as a trusted
  certificate's, need `${gf.pool.rootDirectory}`, since
  `${maven.multiModuleProjectDirectory}` is the module's directory there,
  without a `.mvn` directory at the root.
- **Sibling modules:** a module that depends on others needs them installed
  first, with `mvn install`: the `package` that `run` forks builds only that
  module.
- **`-DskipTests`** skips the tests of the `package` that `run` forks, and
  an `up` bound in it, but not `run`. Projects other than `war`, `ear`,
  `ejb` and `rar` have nothing to deploy, and `run` does nothing there.

## Recipes

### Size the pool and the forks from `-T`

In a single-module build, such as a TCK runner, both can follow Maven's
`-T`, so `mvn verify -T5` runs 5 forks against 5 slots:

```xml
<plugin>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>glassfish-pool-maven-plugin</artifactId>
    <extensions>true</extensions>
    <configuration>
        <poolSize>${session.request.degreeOfConcurrency}</poolSize>
    </configuration>
</plugin>

<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <forkCount>${gf.pool.size}</forkCount>
    </configuration>
</plugin>
```

- `${session.request.degreeOfConcurrency}` is Maven's processed `-T`: 5 for
  `-T5`, and the number of cores times N for `-TNC`. Without `-T`, it is 1,
  as with `-T1`.
- `forkCount` can't take that expression itself: it's a `String`, and Maven
  passes the expression on as an `Integer`, which fails. So `up` publishes
  the size of the pool it started as the project property `gf.pool.size`,
  which failsafe resolves when it runs, after `up`.
- Size both with `-T` then. `-Dgf.pool.size` would only change `forkCount`,
  since the pom sets `<poolSize>`.
- Keep this to single-module builds. In a multi-module build, `-T` already
  runs the modules side by side, so leave `forkCount` at its default of 1
  there; see *One pool per build*.

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
`poolDir`s (or distinct `adminPortBase` values). `glassfish-pool:run` is
the exception: runs in separate terminals share one pool, each on a slot of
its own. See [Running an application](#running-an-application).

A JVM shutdown hook installed by `up` or `run` stops the pool if the Maven
process is killed (Ctrl+C, hard build failure), so slots don't get
orphaned. It stops every pool `up` or `run` started in that JVM and `down`
didn't stop yet. Like the stop after the build, it leaves running the slots
that a `run` in another process holds.

## Troubleshooting

- **`Pool up failed: ... Address already in use`** — another process holds a
  port in the slot's window. Pick a different `adminPortBase`, or `nuke`
  any stale pool that didn't shut down cleanly.
- **`Slot N provisioning failed during grow`** — usually means
  `gf.pool.source` is wrong or the source install is corrupt. The slot stays,
  shown as `orphan` by `mvn glassfish-pool:status`, and the next grow
  re-creates it. Fix the source, or `nuke` and retry.
- **`Command failed: …`** — a post-boot or post-start command failed, with
  `asadmin`'s output in the message. Fix the command, or set
  `failOnCommandError` to `false` to only log it.
- **`Pool slots still running after stopping them`** — `down` couldn't stop
  a slot, even with `stop-domain --kill`. Stop that GlassFish yourself (its
  pid is in the slot's `glassfish/glassfish/domains/domain1/config/pid`),
  then run `glassfish-pool:down` again.
- **Status table garbled in CI** — CI is being detected as interactive.
  Add `-B` (batch mode) or `-Dgf.pool.once`.
- **`portStride must be >= 10`** — a GlassFish domain uses ~10 ports;
  shrinking the stride collides them.

## Related modules

- [`arquillian-glassfish-server-pool`](../glassfish-pool/) — the runtime
  this plugin drives. Read its README for the architecture and the
  `arquillian.xml` setup.
- [`glassfish-maven-plugin`](../glassfish-maven-plugin/) — provisions
  GlassFish without a pool (`glassfish:provision`), e.g. for the managed
  connector. This plugin builds on it for staging.
- [`integration-tests/src/it/pool`](../integration-tests/src/it/pool) —
  worked example: this plugin (staging GlassFish from `<distribution>`) +
  failsafe + Arquillian, all in one pom.
