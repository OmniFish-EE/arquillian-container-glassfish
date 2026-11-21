# GlassFish Maven Plugin

Maven plugin for Eclipse GlassFish. Its `provision` goal resolves a
GlassFish distribution through your Maven repositories, unpacks it under
`target/`, copies overlay jars into it, imports trusted certificates, runs
setup commands on a brief start, and publishes where it landed as
`glassfish.home`. That replaces the usual `maven-dependency-plugin` `unpack`
block, and the [managed connector](../glassfish-managed/) picks the install
up without further configuration.

| Coordinates  | Value                    |
| ------------ | ------------------------ |
| `groupId`    | `ee.omnifish.glassfish`  |
| `artifactId` | `glassfish-maven-plugin` |
| Goal prefix  | `glassfish`              |
| Maven        | 3.9.0+                   |

## Goals

| Goal        | Default phase          | Purpose |
| ----------- | ---------------------- | ------- |
| `provision` | `process-test-classes` | Resolve, unpack and overlay GlassFish, import certificates and run post-boot commands, then set `glassfish.home`. A re-run leaves the install alone while these inputs stay the same. |
| `start`     | `pre-integration-test` | Provision as above, start `domain1`, run `postStartCommands`, and return, leaving it running. See *Starting and stopping GlassFish*. |
| `stop`      | `post-integration-test` | Stop `domain1`. See *Starting and stopping GlassFish*. |

## Quick start

Declare the plugin as an extension and give it a `<distribution>` version:

```xml
<plugin>
    <groupId>ee.omnifish.glassfish</groupId>
    <artifactId>glassfish-maven-plugin</artifactId>
    <version>2.2.2</version>
    <extensions>true</extensions>
    <configuration>
        <distribution>
            <version>9.0.0</version>
        </distribution>
    </configuration>
</plugin>
```

Before the tests run, `mvn test` or `mvn verify` unpacks GlassFish to
`target/dist/glassfish9`. The surefire and failsafe JVMs see that path as
the system property `glassfish.home`. The managed connector reads that
property itself, so neither plugin needs `<systemPropertyVariables>`, and
`arquillian.xml` needs no `glassFishHome`.

`<distribution>9.0.0</distribution>` is short for the block above.

### Lifecycle binding

With `<extensions>true</extensions>`, Maven loads the plugin as a build
extension, and the plugin adds one execution to every project that
declares it (directly or inherited from a parent's `<plugins>`):

| Execution id          | Goal        | Phase                  |
| --------------------- | ----------- | ---------------------- |
| `glassfish-provision` | `provision` | `process-test-classes` |

It picks up the plugin-level `<configuration>`, and a `provision` you bind
yourself is left alone. `process-test-classes` is the last phase before
`test`, so the install is ready for surefire and failsafe alike, while
`mvn test-compile` doesn't fetch GlassFish. When you bind `start`, the
extension adds nothing: `start` provisions the install itself, and runs the
post-boot commands on its own start.

The extension also hands the install to the test JVMs. `provision` sets
the project property `glassfish.home`, but surefire and failsafe don't pass
project properties to their forks. So the plugin adds
`<glassfish.home>${glassfish.home}</glassfish.home>` to the
`<systemPropertyVariables>` of both. Maven resolves that reference when the
tests run, after `provision`. With `start` bound, which runs in
`pre-integration-test`, only failsafe gets it. An entry you configure
yourself wins.

Without `<extensions>true</extensions>`, bind the goal and add the
reference yourself:

```xml
<executions>
    <execution><goals><goal>provision</goal></goals></execution>
</executions>
```

```xml
<systemPropertyVariables>
    <glassfish.home>${glassfish.home}</glassfish.home>
</systemPropertyVariables>
```

### Defaults

The snippet above relies on these defaults. Override one only when its
note applies:

| Parameter | Default | Override when… |
| --------- | ------- | -------------- |
| `distribution` `groupId` / `artifactId` / `type` | `org.glassfish.main.distributions` / `glassfish` / `zip` | you target Payara, a fork, or any other artifact. Set the full coordinates. |
| `stageDir` | `${project.build.directory}/dist` | the install should live elsewhere, e.g. shared across modules. |
| `home` | the zip's top-level directory under `stageDir` (e.g. `target/dist/glassfish9`) | the zip has more than one top-level entry, or you skip the unpack. |
| `homeProperty` | `glassfish.home` | the name is taken, or you provision more than one GlassFish. |

## Configuration reference

| Parameter          | Property                     | Default                           | Notes |
| ------------------ | ---------------------------- | --------------------------------- | ----- |
| `distribution`     | `glassfish.distribution`     | (none)                            | GAV of the distribution to resolve and unpack. Only `<version>` is required. Also takes text: a version, or `groupId:artifactId:version[:type[:classifier]]`. |
| `stageDir`         | `glassfish.stageDir`         | `${project.build.directory}/dist` | Where the zip is unpacked. |
| `home`             | (none)                       | derived from the zip              | The install root. |
| `homeProperty`     | (none)                       | `glassfish.home`                  | Project property set to the install root, and forwarded by the extension. |
| `unpackSkip`       | `glassfish.unpack.skip`      | `false`                           | Skip the unpack. With `home` set, overlays go into that existing install. |
| `overlays`         | (nested `<overlays>`)        | (empty)                           | Artifacts copied into `overlayTargetDir`. See *Overlays*. |
| `overlayTargetDir` | `glassfish.overlayTargetDir` | `${home}/glassfish/modules`       | Where overlay jars land. |
| `overlaySkip`      | `glassfish.overlay.skip`     | `false`                           | Skip all overlays. |
| `trustedCerts`     | (nested `<trustedCerts>`)    | (empty)                           | Certificates imported into the truststore. See *Trusted certificates*. |
| `trustStore`       | `glassfish.trustStore`       | the domain's `cacerts.p12`        | Where they go; `cacerts.jks` in older versions. Doesn't change which truststore GlassFish reads. |
| `trustStorePassword` | `glassfish.trustStorePassword` | `changeit`                    | The truststore's password. |
| `certsSkip`        | `glassfish.certs.skip`       | `false`                           | Skip the certificate import. |
| `postBootCommands` | (none)                       | (none)                            | `asadmin` commands run once, on a brief start during staging, or on `start`'s own start. One per line. See *Post-boot commands*. |
| `failOnCommandError` | `glassfish.failOnCommandError` | `true`                        | Whether a failed post-boot or post-start command fails the build. |
| `skip`             | `glassfish.provision.skip`   | `false`                           | Skip the goal: nothing is unpacked or published. |

`start` and `stop` take the same parameters, since `start` provisions
first and `stop` works out where the install is from them. `start` adds:

| Parameter          | Property                     | Default                           | Notes |
| ------------------ | ---------------------------- | --------------------------------- | ----- |
| `postStartCommands` | (none)                      | (none)                            | `asadmin` commands run after every start. One per line. |
| `postBootRestart`  | `glassfish.postBootRestart`  | `auto`                            | Whether to restart GlassFish after the post-boot commands ran on this start: `auto`, `always` or `never`. See *Starting and stopping GlassFish*. |
| `debug`            | `glassfish.start.debug`      | `false`                           | Start with `start-domain --debug`. |
| `suspend`          | `glassfish.start.suspend`    | `false`                           | Start with `start-domain --suspend`, waiting for a debugger. |
| `startTimeout`     | `glassfish.start.timeout`    | `300`                             | Seconds `start-domain` may take, including the wait for a debugger. |
| `skip`             | `glassfish.start.skip`       | `false`                           | Skip `start`; `stop` has `glassfish.stop.skip`. |

The three goals also skip on `-DskipTests` and `-Dmaven.test.skip`: if the
build runs no tests, there's no reason to fetch or start GlassFish.

`home` and `homeProperty` have no `-D` switch. `glassfish.home` is this
goal's output, and the extension has to know which property it forwards.
Neither has `postBootCommands`: `glassfish.postBootCommands` is what the
Arquillian Containers run after every start. For the same reason,
`postStartCommands` has none: `glassfish.postStartCommands` is the managed
Arquillian Container's.

### How `glassfish.home` is set

`provision` sets `glassfish.home` (or your `homeProperty`) to the absolute
install path. A value the pom already defines is replaced, with a warning,
because the tests would otherwise quietly use something else. Two
consequences:

- Don't also define `glassfish.home` as a pom property. Maven substitutes
  pom properties when it loads the pom, before `provision` runs, so a
  `${glassfish.home}` you write elsewhere in the pom sees the pom's value.
  The references the extension adds are resolved late and see the
  provisioned one.
- `-Dglassfish.home=…` on the command line still wins in the test JVMs.
  With `-Dglassfish.provision.skip`, nothing is published, so the value from
  the pom or the command line is used.

## Staging behaviour

`provision` resolves `<distribution>` through the build's repositories and
unpacks it under `stageDir`, keeping file permissions, so `asadmin` stays
executable. Then it copies the overlays, imports the trusted certificates
and runs the post-boot commands. A marker file in `stageDir` records a
fingerprint of all of that: the distribution and its zip, the install root,
the overlays and their files, the certificates, and the commands with
`failOnCommandError`.

- A re-run with the same fingerprint leaves the staged install alone.
- Any change stages it afresh: `stageDir` is wiped and the zip unpacked
  again. That covers another distribution or `home`, a zip or overlay
  rebuilt with `mvn install`, and an overlay, certificate or command added,
  changed or removed. It costs a full unpack, plus a brief start when there
  are commands, also while you iterate on a SNAPSHOT overlay.
- A staging that didn't finish, for example because the build was
  interrupted or a command failed, is redone on the next run.
- A non-empty `stageDir` without the marker fails the build and is left
  alone, since it may hold an install this plugin didn't unpack. Empty it,
  or point `stageDir` elsewhere.
- A file that can't be deleted, for example because a GlassFish running from
  the install keeps it open, fails the build.
- While `domain1` runs from the install, for example after `glassfish:start`,
  a changed fingerprint fails the build instead, and the install is kept.
  Stop it with `glassfish:stop`, then build again.

With `unpackSkip`, the install isn't the plugin's: overlays are copied and
certificates imported into it on every run. If `stageDir` holds an install
the plugin staged, such a run drops its fingerprint, so the next run
without `unpackSkip` stages it afresh.

Modules of a parallel (`-T`) build that share a `stageDir` stage it once.

## Overlays

Drop additional jars (or replace shipped ones) into the install's
`glassfish/modules` directory. Each `<overlay>` is a Maven GAV plus a
`<destFileName>`:

```xml
<configuration>
    <distribution>9.0.0</distribution>
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

| Overlay element | Type    | Default                     | Notes |
| --------------- | ------- | --------------------------- | ----- |
| `groupId` / `artifactId` / `version` / `type` / `classifier` | String | (GAV) | Maven coordinate to resolve. |
| `destFileName`  | String  | source artifact's file name | File name under `overlayTargetDir`. |
| `skip`          | boolean | `false`                     | Leave just this overlay out. |
| `apply`         | boolean | `true`                      | The reverse of `skip`: `false` leaves the overlay out, e.g. `<apply>${soteria.update}</apply>`. |

An overlay sets `<skip>` or `<apply>`, not both: the goal fails when one
sets both, even if they agree. A property that isn't defined leaves its
element unset.

`overlaySkip` skips them all: `mvn verify -Dglassfish.overlay.skip=true`.

## Trusted certificates

Make GlassFish trust a certificate it doesn't know: an external identity
provider's server certificate for OpenID, or a client certificate for
`CLIENT-CERT`. Each `<trustedCert>` is an `<alias>` and a PEM or DER
`<file>`:

```xml
<configuration>
    <distribution>9.0.0</distribution>
    <trustedCerts>
        <trustedCert>
            <alias>tomcat</alias>
            <file>${project.basedir}/tomcat.cert</file>
        </trustedCert>
    </trustedCerts>
</configuration>
```

They are imported into the staged domain's truststore, `cacerts.p12` (or
`cacerts.jks` in older versions), unless `trustStore` names another. That
happens before GlassFish first starts, which matters: GlassFish reads its
truststore once, when it first opens a TLS connection, so a certificate
added to a running server isn't picked up.

`trustStore` only changes where the certificates go. GlassFish keeps
reading the truststore its domain configuration names
(`javax.net.ssl.trustStore`, the domain's own), so another file only helps
if the domain is set up to use it.

When the plugin unpacks the distribution, a change to the certificates
stages it afresh (see *Staging behaviour*), so the truststore holds exactly
the configured ones.

With `unpackSkip`, the import runs on every build instead, and only adds and
replaces entries. A certificate the truststore already holds under its
alias is left alone, and a changed one replaces it. Removing a
`<trustedCert>`, or setting `certsSkip`, leaves a certificate that an
earlier build imported where it is. The truststore is written to a
temporary file and moved into place, so an interrupted build can't leave
half of it behind.

`certsSkip` skips the import: `mvn verify -Dglassfish.certs.skip=true`.

## Post-boot commands

`<postBootCommands>` are `asadmin` commands that `provision` runs once on
the staged install, during a brief start after the overlays and
certificates. `glassfish:start` runs them on the start it makes anyway; see
*Starting and stopping GlassFish*. They're for setup whose effects persist,
such as users, resources and `set` calls:

```xml
<configuration>
    <distribution>9.0.0</distribution>
    <postBootCommands>
        create-file-user --groups staff j2ee --passwordfile ${project.build.testOutputDirectory}/j2ee.pass
        set configs.config.server-config.cdi-service.enable-implicit-cdi=true
    </postBootCommands>
</configuration>
```

- One command per line. Quotes group words, and backslashes are passed on
  as they are, since `asadmin` reads `\:` and `\=` itself. Blank lines and
  lines starting with `#` are skipped. A line may start with `asadmin`
  options. `--passwordfile <file>`, or `--passwordfile=<file>`, may also
  come later on the line; it's moved in front of the subcommand, where
  `asadmin` needs it. A line with two of them fails.
- The domain runs on three free ports, so it doesn't clash with a server
  that's already running. Afterwards it gets its own ports back, except for
  a port a command set.
- The commands and `failOnCommandError` are part of the fingerprint (see
  *Staging behaviour*). Changing either stages the install afresh; until
  then, re-runs leave it alone, so nothing runs twice.
- A failed command fails the build, and the next build stages afresh. With
  `<failOnCommandError>false</failOnCommandError>`, the failure is logged
  and the remaining commands run.
- A shutdown hook stops the server if the build is interrupted. If the
  build is killed outright, the next one stops the server before staging
  afresh. If the server doesn't stop, that build fails and leaves the
  install as it is, until you stop the server yourself.
- They need an install that `provision` unpacks itself. With `unpackSkip`,
  or without a `<distribution>`, the install isn't the plugin's, so the
  commands would run on every build, and setup such as `create-file-user`
  fails the second time. Setting them then fails the goal.

The managed Arquillian Container runs `glassfish.postStartCommands`, or its
old name `glassfish.postBootCommands`, after every start. Setup moved from
there to `<postBootCommands>` runs once, instead of delaying every start.

## Starting and stopping GlassFish

`glassfish:start` provisions the install, as `provision` does, which costs
nothing when it's staged already, then starts `domain1` and returns once it
answers and its `postStartCommands` have run. The server keeps running,
also after Maven exits, until `glassfish:stop`. The extension binds neither
goal, so a build starts a server only when it says so, and it leaves
`provision` out when you bind `start`. For the remote Arquillian Container:

```xml
<plugin>
    <groupId>ee.omnifish.glassfish</groupId>
    <artifactId>glassfish-maven-plugin</artifactId>
    <version>${version.arquillian.glassfish}</version>
    <configuration>
        <distribution>9.0.0</distribution>
        <postStartCommands>
            set configs.config.server-config.cdi-service.enable-implicit-cdi=true
        </postStartCommands>
    </configuration>
    <executions>
        <!-- start in pre-integration-test, stop in post-integration-test -->
        <execution><id>start</id><goals><goal>start</goal></goals></execution>
        <execution><id>stop</id><goals><goal>stop</goal></goals></execution>
    </executions>
</plugin>
```

From the command line, `mvn glassfish:start` and `mvn glassfish:stop` do
the same, also from a clean checkout.

- **An occupied admin port:** `start` fails before starting anything when
  something already answers on the domain's admin port. That may be another
  domain set up with the same port, or this one, still running.
- **`postBootCommands`**, when staging has them to run, run on this start,
  not on a brief one of their own, and before the `postStartCommands`.
  Some take effect only when GlassFish restarts, such as a JVM option like
  `-Xmx`, or a change to the admin listener. `postBootRestart`
  (`-Dglassfish.postBootRestart`) says whether `start` then restarts
  GlassFish, so that what they configured is active when it returns:
  - `auto`, the default, restarts when GlassFish reports that a change
    needs a restart, as `asadmin _get-restart-required` does, or
    when a `set`, `create-…` or `delete-…` command makes a change GlassFish
    applies only on a restart without reporting it: one to the admin
    listener, the JMX connector or an IIOP listener, such as its port, or
    to a system property that `domain.xml` refers to, as a JVM option
    `-Dderived=${NAME}` does, with `create-system-properties` or `set`. When
    it can't read GlassFish's answer, it restarts. The log says why: what
    changed, or that GlassFish reports a change, but not the commands or
    GlassFish's reasons, which may hold secrets, such as in a `-D` option.
  - `always` restarts, for a change GlassFish needs a restart for without
    reporting it.
  - `never` doesn't, and what needs a restart takes effect from the next
    start.

  The restart happens only on the first start after staging. It stops the
  domain through the admin port it started on, also when a command moved
  it. With
  `suspend`, the start before a restart isn't suspended, so the debugger
  attaches once, to the start that stays; with `auto`, `suspend` restarts
  it for that. A `-D` JVM option needs no restart, unless it starts with
  `-Djava.` or `-Djavax.`: GlassFish sets it in the running server, though
  code that read it at startup doesn't see it. When
  you bind `provision` before it yourself, that runs them on a brief start,
  and they're in effect at once. With `failOnCommandError`, a failed one
  stops the domain again, and the next build stages afresh.
- **`postStartCommands`** run after every start, against the admin port the
  domain started on. One per line, as post-boot commands. With
  `failOnCommandError`, a failed one stops the domain again and fails the
  goal, so a failure in `pre-integration-test` doesn't leave a server
  behind, and one that can't be read, such as one with an unclosed quote,
  fails the goal before anything starts.
- **`debug` and `suspend`** (`-Dglassfish.start.debug`,
  `-Dglassfish.start.suspend`) start the domain with `start-domain --debug`
  and `--suspend`. With `suspend`, the server waits for a debugger before it
  starts, and `start` waits with it, printing where to attach, up to
  `startTimeout` (`-Dglassfish.start.timeout`, 300 seconds). After that,
  `start` ends what it started, and fails.
- **`stop`** runs `stop-domain`, and `stop-domain --kill` if the domain's
  own process still runs after that; it fails when it still runs after
  both. A domain that doesn't run is fine, also when another server answers
  on its admin port: `stop` leaves that server alone. `stop` doesn't
  provision, so it also stops a domain after the pom changed.
- **After `mvn clean`**, which deletes the install while the domain runs from
  it, with `config/pid`, `asadmin` and the staging marker, `stop` finds the
  server by its command line: the server of a domain in any install in
  `stageDir`, also when the pom names another distribution by then. It ends
  it directly: asked first, then killed. Without `clean`, the `config/pid`
  of that install finds it, also on Windows. On Windows, `clean` can't
  delete the files a running GlassFish keeps open, and Java doesn't read
  other processes' command lines, so there, stop the domain before
  `clean`, as in `mvn glassfish:stop clean`.
- `start` and `stop` skip with `-DskipTests` or `-Dmaven.test.skip`, like
  `provision`, or with `-Dglassfish.start.skip` and `-Dglassfish.stop.skip`.

## Recipes

### One-off, outside a project

```
mvn ee.omnifish.glassfish:glassfish-maven-plugin:2.2.2:provision -Dglassfish.distribution=9.0.0
```

This unpacks GlassFish into `./target/dist/glassfish9`. With
`ee.omnifish.glassfish` in the `<pluginGroups>` of `~/.m2/settings.xml`, it
shortens to `mvn glassfish:provision -Dglassfish.distribution=9.0.0`.

### Payara or another distribution

```xml
<distribution>
    <groupId>fish.payara.distributions</groupId>
    <artifactId>payara</artifactId>
    <version>6.2025.1</version>
</distribution>
```

`glassfish.home` then points at `target/dist/payara6`.

### Two GlassFish versions in one build

Give each `provision` execution its own `stageDir` and `homeProperty`. The
extension forwards both properties:

```xml
<executions>
    <execution>
        <id>glassfish8</id>
        <goals><goal>provision</goal></goals>
        <configuration>
            <distribution>8.0.5</distribution>
            <stageDir>${project.build.directory}/gf8</stageDir>
            <homeProperty>glassfish8.home</homeProperty>
        </configuration>
    </execution>
    <execution>
        <id>glassfish9</id>
        <goals><goal>provision</goal></goals>
        <configuration>
            <distribution>9.0.0</distribution>
            <stageDir>${project.build.directory}/gf9</stageDir>
            <homeProperty>glassfish9.home</homeProperty>
        </configuration>
    </execution>
</executions>
```

### Replacing a `maven-dependency-plugin` unpack

1. Remove the `unpack` execution that fetched the GlassFish zip.
2. Remove `glassfish.home` from `<properties>` and from the surefire and
   failsafe `<systemPropertyVariables>`.
3. Add the plugin as in the *Quick start*.
4. If `arquillian.xml` sets `glassFishHome`, change it to
   `${glassfish.home}`, or drop it: the managed connector falls back to
   that system property.

The install moves from wherever the old unpack put it to
`target/dist/glassfish9`.

## Related modules

- [`arquillian-glassfish-server-managed`](../glassfish-managed/) — starts
  the provisioned GlassFish for Arquillian tests.
- [`glassfish-pool-maven-plugin`](../glassfish-pool-maven-plugin/) — a
  pool of GlassFish instances for parallel tests. It stages with the same
  code and accepts the same `<distribution>` and `<overlays>`.
- [`integration-tests/src/it/provision`](../integration-tests/src/it/provision)
  — worked example: this plugin with surefire and failsafe.
