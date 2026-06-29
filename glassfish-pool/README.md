# Arquillian Container GlassFish Pool

A pool of pre-started GlassFish instances (slots) that Arquillian test JVMs
lease for the duration of a single JVM run. Designed for parallel builds (e.g.
`mvn -T 4`): each forked test JVM grabs an idle slot, runs its deployments
against that slot's DAS, and releases the lock when the JVM exits.

Provisioning, starting, stopping and the lease protocol are pure Java — no
shell scripts, no `-javaagent`, no surefire `argLine` plumbing. Pool lifecycle
is normally driven by the [`glassfish-pool-maven-plugin`](../glassfish-pool-maven-plugin/);
this module ships the runtime types that the test JVM needs in order to lease
and use a slot.

| Coordinates  | Value                                          |
| ------------ | ---------------------------------------------- |
| `groupId`    | `ee.omnifish.arquillian`                       |
| `artifactId` | `arquillian-glassfish-server-pool`             |
| Java         | 11+                                            |
| Arquillian   | Jakarta protocol (`Servlet 5.0`)               |

## When to use this

- You have many Arquillian tests and want to fan them out across cores.
- You don't want each test class to pay the GlassFish cold-start cost.
- You want a single pre-warmed install (or N installs) that survives across
  forked JVMs within a single `mvn` invocation.

If you only need a single, JVM-lifetime-scoped GlassFish, use
`arquillian-glassfish-server-managed` instead — this pool module exists to
amortise startup cost across parallel forks.

## Architecture in 30 seconds

1. **Provisioning** clones a source GlassFish install into `slot-1/`,
   `slot-2/`, … under `poolDir`. Each slot's `domain.xml` is rewritten so its
   admin/http/https/etc. ports land in a non-overlapping window
   (`adminBase + (slot - 1) * portStride`).
2. **Lease** is an exclusive `FileChannel.tryLock()` on `slot-N/lock`. The
   slot's coordinates are published in `slot-N/ports.properties`, written
   only after `start-domain` succeeds.
3. **Test JVM** picks an idle slot (least-recently-used wins), reads its
   ports, hands them to `CommonGlassFishManager`, deploys via REST, releases
   the lock at JVM shutdown.

```
poolDir/
├── grow.lock                        # serialises pool-grow across JVMs
├── slot-1/
│   ├── lock                         # FileLock holds the slot
│   ├── ports.properties             # adminPort, httpPort, httpsPort, glassFishHome
│   └── glassfish9/                  # the cloned install
├── slot-2/
│   └── ...
└── ...
```

## Test-JVM consumer setup

Add the pool container as a `test`-scope dependency:

```xml
<dependency>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>arquillian-glassfish-server-pool</artifactId>
    <version>2.1.4</version>
    <scope>test</scope>
</dependency>
```

…and an `arquillian.xml` that points at the same `poolDir` the build is
provisioning into:

```xml
<arquillian xmlns="http://jboss.org/schema/arquillian"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://jboss.org/schema/arquillian
                                https://jboss.org/schema/arquillian/arquillian_1_0.xsd">

    <container qualifier="glassfish-pool" default="true">
        <configuration>
            <property name="poolDir">${project.build.directory}/pool</property>
            <property name="leaseTimeoutSeconds">120</property>
        </configuration>
    </container>
</arquillian>
```

`poolDir` may also be supplied as a system property (`-Dgf.pool.dir=...`),
which is what the integration tests do — see [`integration-tests/src/it/pool`](../integration-tests/src/it/pool).

## End-to-end Maven setup

Two plugin blocks: the pool plugin (which resolves and unpacks GlassFish
itself via its `<distribution>` element), and failsafe pointed at the
same `poolDir`.

```xml
<properties>
    <glassfish.version>9.0.0</glassfish.version>
    <pool.size>4</pool.size>
</properties>

<build>
    <plugins>
        <!-- 1. Provision/start the pool before failsafe; tear it down after. -->
        <plugin>
            <groupId>ee.omnifish.arquillian</groupId>
            <artifactId>glassfish-pool-maven-plugin</artifactId>
            <version>2.2.0</version>
            <configuration>
                <poolDir>${project.build.directory}/pool</poolDir>
                <poolSource>${project.build.directory}/dist/glassfish9</poolSource>
                <poolSize>${pool.size}</poolSize>
                <distribution>
                    <groupId>org.glassfish.main.distributions</groupId>
                    <artifactId>glassfish</artifactId>
                    <version>${glassfish.version}</version>
                    <type>zip</type>
                </distribution>
            </configuration>
            <executions>
                <execution><id>pool-up</id><goals><goal>up</goal></goals></execution>
                <execution><id>pool-down</id><goals><goal>down</goal></goals></execution>
            </executions>
        </plugin>

        <!-- 2. Forward gf.pool.dir to test JVMs, run tests in parallel forks. -->
        <plugin>
            <artifactId>maven-failsafe-plugin</artifactId>
            <configuration>
                <forkCount>${pool.size}</forkCount>
                <reuseForks>true</reuseForks>
                <systemPropertyVariables>
                    <gf.pool.dir>${project.build.directory}/pool</gf.pool.dir>
                </systemPropertyVariables>
            </configuration>
            <executions>
                <execution>
                    <goals><goal>integration-test</goal><goal>verify</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

The plugin resolves `<distribution>` through your usual Maven repositories
and unpacks the zip under `${project.build.directory}/dist` before
provisioning runs. Staging is idempotent: re-runs fast-exit when the
marker file written after a successful unpack is still present.

Run it:

```
mvn verify
```

For per-class fork parallelism across `${pool.size}` slots, that's all you
need. To go wider than `${pool.size}`, see *Growing on demand* below.

### Bring your own unpack

If your build already runs `maven-dependency-plugin` for unrelated reasons,
drop the `<distribution>` block from the pool plugin and let the existing
`unpack` execution land the dist in the same directory `<poolSource>`
points at:

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
                        <version>${glassfish.version}</version>
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

## Configuration reference

### Test-JVM configuration (`arquillian.xml` properties)

| Property              | Type    | Default                          | Notes                                                                                                       |
| --------------------- | ------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `poolDir`             | String  | `-Dgf.pool.dir`                  | Required. Must match the build's `poolDir`.                                                                 |
| `leaseTimeoutSeconds` | long    | `600`                            | Lease wait before failing the test JVM.                                                                     |
| `restartOnRelease`    | boolean | `-Dgf.pool.restartOnRelease`/`false` | Restart GF on the leased slot before releasing the lock. See [Restarting GlassFish between tests](#restarting-glassfish-between-tests). |
| `slotGroup`           | String  | `-Dgf.pool.slotGroup`/(empty)    | Slot-sharing key; containers sharing it reuse one slot. Inferred from the `<group>` qualifier when `gf.pool.shareGroupSlot=true`. See [Sharing one slot across containers](#sharing-one-slot-across-containers). |
| `adminUser`           | String  | `admin`                          | Inherited from `CommonGlassFishConfiguration`.                                                              |
| `adminPassword`       | String  | (empty)                          | Inherited.                                                                                                  |
| `httpPort`            | int     | overwritten at lease             | Set from the slot's `ports.properties`.                                                                     |
| `httpsPort`           | int     | overwritten at lease             | Set from the slot's `ports.properties`.                                                                     |
| `glassFishHome`       | String  | overwritten at lease             | Set from the slot's `ports.properties`.                                                                     |

`adminHost`/`adminPort` are also overwritten at lease time. Anything you set
in `arquillian.xml` for those four host/port fields is informational only —
the leased slot wins.

### System properties forwarded by the build

These configure the pool layout. The Maven plugin sets them automatically;
you only need them if you're driving lifecycle from antrun, an IDE, or a
shell script.

| System property            | Default | Meaning                                                |
| -------------------------- | ------- | ------------------------------------------------------ |
| `gf.pool.dir`              | (req)   | Pool root directory.                                   |
| `gf.pool.source`           | (none)  | Source GlassFish install (template for slot clones).   |
| `gf.pool.size`             | `1`     | Initial number of slots `up` provisions.               |
| `gf.pool.adminBase`        | `14848` | Admin port for slot 1.                                 |
| `gf.pool.portStride`       | `100`   | Per-slot port spacing. Must be ≥ 10.                   |
| `gf.pool.systemProperties` | (none)  | Newline-separated `key=value` jvm options (see below). |
| `gf.pool.restartOnRelease` | `false` | Seeds `restartOnRelease` for every test JVM the build forks. |
| `gf.pool.shareGroupSlot`   | `false` | When `true`, members of a `<group>` share one slot (http+https idiom). Default keeps a slot per member. |
| `glassfish.debug`          | `false` | Start each slot's domain in debug mode (`start-domain --debug`). Same property name as the managed container. |
| `glassfish.suspend`        | `false` | Start the domain suspended, waiting for a debugger (`start-domain --suspend`). Requires `gf.pool.size=1` (see below). Same property name as the managed container. |

Slot N's admin port = `adminBase + (N-1) * portStride`; HTTP/HTTPS land at
`+1` and `+2`. The other GlassFish ports (JMX, IIOP, …) are placed within the
same window by `DomainXmlEditor`.

### Baking system properties into the GlassFish JVM

Some properties have to be on the GF JVM at startup — e.g.
`javax.net.ssl.trustStorePassword`, which a PKCS12 truststore needs in order
to load any trust anchors at all. Pass them to the plugin as a multiline
`<systemProperties>` block (one `key=value` per line; `#` comments and blank
lines are ignored, mirroring the
[`arquillian-glassfish-server-managed`](../glassfish-managed/) convention):

```xml
<plugin>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>glassfish-pool-maven-plugin</artifactId>
    <configuration>
        <poolDir>${project.build.directory}/pool</poolDir>
        <poolSource>${project.build.directory}/dist/glassfish9</poolSource>
        <systemProperties>
            javax.net.ssl.trustStorePassword=changeit
            java.awt.headless=true
        </systemProperties>
    </configuration>
    ...
</plugin>
```

Each entry becomes a `<jvm-options>-Dkey=value</jvm-options>` child of every
`<java-config>` in each slot's `domain.xml`, written through the same
inode-replacing atomic move as port rewrites (so the source install stays
intact). Override at the command line with `-Dglassfish.systemProperties=…`.

### Debugging a slot

`-Dglassfish.debug` and `-Dglassfish.suspend` pass `--debug` / `--suspend` to
each slot's `start-domain`, using the same property names as the managed
container. `--suspend` blocks the server JVM at startup until a debugger
attaches, so `glassfish-pool:up` will not return until you connect.

Because suspend halts the JVM and every slot clone shares one JDWP debug port,
it is only valid for a **single-slot pool**. The plugin throws
`IllegalArgumentException` when `glassfish.suspend` is set with `gf.pool.size`
> 1 — run a suspended pool with `-T1` (and `gf.pool.size=1`).

## Growing on demand (optional)

If `gf.pool.source` is forwarded to test JVMs, `SlotLeaser` will provision a
new slot when every existing slot is busy and the wait would otherwise
deadlock. To enable, add to `failsafe`'s `<systemPropertyVariables>`:

```xml
<gf.pool.source>${project.build.directory}/dist/glassfish9</gf.pool.source>
<gf.pool.adminBase>14848</gf.pool.adminBase>
<gf.pool.portStride>100</gf.pool.portStride>
```

Without `gf.pool.source` the leaser still works against the existing pool —
it just blocks for an idle slot instead of growing one. That's the right
default when `forkCount` ≤ `poolSize`.

## Sharing one slot across containers

Each Arquillian container leases its **own** slot — its own GlassFish, its own
ports, its own lock. That's the right default even for a `<group>`: a group
generally means several servers a test drives together (clustering/failover),
which want distinct instances.

The exception is the `http`+`https` idiom, where a group's members are really
*one* server addressed two ways (a slot already publishes both ports). For that
case, enable sharing with **`-Dgf.pool.shareGroupSlot=true`**: the members of a
`<group>` then share a single leased slot, so a size-1 pool suffices.

```xml
<group qualifier="glassfish-servers" default="true">
    <container qualifier="http" default="true">
        <configuration>
            <property name="httpsPortAsDefault">false</property>
        </configuration>
    </container>
    <container qualifier="https">
        <configuration>
            <property name="httpsPortAsDefault">true</property>
        </configuration>
    </container>
</group>
```

With `-Dgf.pool.shareGroupSlot=true` and that arquillian.xml, both members
lease one slot per test JVM: the first leases it, the rest attach, and the slot
is released only when the last one stops. One GlassFish, many container views —
the managed container's behavior on a size-1 pool. Each container still applies
its own `httpsPortAsDefault`, so `http` publishes the http port and `https` the
https port of the same instance.

Under the hood this is slot sharing keyed by `(slotGroup, poolDir)`; with the
switch on, the group's qualifier is inferred as each member's `slotGroup`. The
`slotGroup` property gives finer control:

| You want… | Set |
| --------- | --- |
| Group members = **distinct** GlassFish instances (default) | nothing — and `pool.size ≥` group size (or forward `gf.pool.source`) |
| Group members = one server (http+https) | `-Dgf.pool.shareGroupSlot=true` |
| Sharing without the global switch, or across *standalone* (non-group) containers | matching `slotGroup` property on each |
| Distinct slots for specific members of a shared group | a **distinct** `slotGroup` value per member (overrides inference) |

Setting `slotGroup` by hand covers what inference can't — e.g. two
**standalone** containers (no `<group>`, so nothing to infer from) that should
still land on one GlassFish. Give them the same value:

```xml
<container qualifier="http" default="true">
    <configuration>
        <property name="poolDir">${gf.pool.dir}</property>
        <property name="slotGroup">my-server</property>
        <property name="httpsPortAsDefault">false</property>
    </configuration>
</container>
<container qualifier="https">
    <configuration>
        <property name="poolDir">${gf.pool.dir}</property>
        <property name="slotGroup">my-server</property>
        <property name="httpsPortAsDefault">true</property>
    </configuration>
</container>
```

The value is just a token — any string works, as long as it **matches** across
the containers that should share (and they use the same `poolDir`). Conversely,
to keep two members of a shared `<group>` on their *own* slots, give them
**different** `slotGroup` values; an explicit value always overrides the
inferred group qualifier.

Notes:

- All sharing containers must use the **same `poolDir`** — it is part of the
  share key.
- Deployments across the sharing containers must have **distinct names**; they
  all deploy to the same DAS.
- `restartOnRelease` (if set) fires once, when the last sharer releases. Keep
  it the same on every member of a group — the finalizing container is whichever
  stops last, so mixed settings make the restart non-deterministic.

## Restarting GlassFish between tests (optional)

By default a slot's GlassFish JVM survives across the test JVMs that lease
it — only the deployments come and go. That's fine for most suites, but
some tests leak JVM-scoped state that `undeploy` alone doesn't clear:
classloader pins, datasource pool handles, ThreadLocals, EclipseLink
session caches, etc. Set `restartOnRelease=true` and the container will
run `asadmin restart-domain domain1` against the leased slot's install
just before the lock is released, so the next leaser sees a fresh GF
JVM on the same ports.

The flag is exposed at three layers of increasing granularity. Pick the
narrowest one that fits.

### 1. Build-wide default (parent pom)

Forward the sysprop to every test JVM the build forks. This is also the
right place to set the *default* the build ships with — individual
modules can override it later.

```xml
<properties>
    <gf.pool.restartOnRelease>false</gf.pool.restartOnRelease>
</properties>

<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <gf.pool.dir>${project.build.directory}/pool</gf.pool.dir>
            <gf.pool.source>${project.build.directory}/dist/glassfish9</gf.pool.source>
            <gf.pool.restartOnRelease>${gf.pool.restartOnRelease}</gf.pool.restartOnRelease>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

Forwarding `gf.pool.source` is strongly recommended when this is on: if a
restart fails, the next leaser's port-health probe marks the slot dead and
`tryGrow` recycles it by re-provisioning from `gf.pool.source`. Without a
source forwarded, a failed restart leaves a permanently dead slot.

### 2. Per-module override (child pom)

With the parent's failsafe block forwarding `${gf.pool.restartOnRelease}`,
any child module can flip the flag for its own test JVMs by overriding
the Maven property:

```xml
<!-- e.g. a stateful-EJB module that leaks JDBC pool handles -->
<properties>
    <gf.pool.restartOnRelease>true</gf.pool.restartOnRelease>
</properties>
```

No other change needed — surefire/failsafe interpolation picks up the
local value automatically.

### 3. Per-arquillian.xml (one container declaration)

If a module has its own `src/test/resources/arquillian.xml` (test
resources win over inherited ones on the classpath), the flag can be
set right next to `poolDir`:

```xml
<container qualifier="glassfish-pool" default="true">
    <configuration>
        <property name="poolDir">${gf.pool.dir}</property>
        <property name="restartOnRelease">true</property>
    </configuration>
</container>
```

This overrides whatever sysprop default the build forwarded for the
test JVMs that resolve this `arquillian.xml`.

### Cost and safety

Restart adds the GF cold-start cost (typically 5–15 s) to each lease
release. On a `forkCount=4`, hundreds-of-test-class suite this can be
significant — measure before enabling globally. The restart is bounded
to a 90 s timeout; if it times out, the asadmin process is force-killed,
the lease releases anyway, and the next leaser will recycle the slot.

The container holds the slot's file lock *during* the restart, so a
concurrent test JVM cannot pick up the slot mid-restart and misclassify
it as dead.

## Programmatic use

The lifecycle API is in `PoolBootstrap`. Useful when wiring from JUnit
extensions, gradle, or one-off scripts:

```java
PoolConfig config = new PoolConfig(
        Paths.get("target/pool"),                  // poolDir
        Paths.get("target/dist/glassfish9"),       // source install
        4,                                         // size
        PoolConfig.DEFAULT_ADMIN_BASE,             // 14848
        PoolConfig.DEFAULT_PORT_STRIDE);           // 100

PoolBootstrap.up(config);                          // provision + start, parallel
// ... run things against the pool ...
PoolBootstrap.down(config);                        // stop every slot
```

`up()` is idempotent: repeated calls fast-exit if every slot already has a
healthy admin port. A JVM shutdown hook calls `down()` when the JVM exits
normally or via Ctrl+C.

For ad-hoc lease use:

```java
SlotLeaser leaser = new SlotLeaser(config, /* timeoutSeconds */ 120);
try (SlotLease lease = leaser.lease()) {
    SlotPorts ports = lease.ports();
    // ports.adminPort(), ports.httpPort(), ports.httpsPort(), ports.glassFishHome()
}
```

## Troubleshooting

- **`Could not lease a GlassFish pool slot within Ns`** — every slot is busy
  and growing is disabled (or failed). Increase `leaseTimeoutSeconds`,
  reduce `forkCount`, or wire `gf.pool.source` so the leaser can grow.
- **`poolDir is required`** — `arquillian.xml` is missing `<property
  name="poolDir">` and `-Dgf.pool.dir` isn't set. The test JVM doesn't know
  where to look.
- **Slot `alive=no` in `glassfish-pool:status`** — the slot's GlassFish died
  (OOM, killed). The leaser will recycle it on next grow if `gf.pool.source`
  is set; otherwise run `mvn glassfish-pool:nuke` then `:up`.
- **`portStride must be >= 10`** — a GlassFish domain uses ~10 ports;
  smaller strides collide.
- **Two `mvn` invocations stomp on each other** — JVM-wide locks only protect
  one Maven run. Use distinct `poolDir`s (or distinct `adminBase`s) for
  parallel `mvn` invocations on the same machine.

## Related modules

- [`glassfish-pool-maven-plugin`](../glassfish-pool-maven-plugin/) — Maven
  goals (`up`, `down`, `provision`, `status`, `nuke`) wrapping this module.
- [`integration-tests/src/it/pool`](../integration-tests/src/it/pool) —
  end-to-end smoke test that exercises both modules.
- `arquillian-glassfish-server-managed` — single-JVM-scoped GlassFish, no pool.
