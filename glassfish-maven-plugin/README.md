# GlassFish Maven Plugin

Maven plugin for Eclipse GlassFish. Its `provision` goal resolves a
GlassFish distribution through your Maven repositories, unpacks it under
`target/`, copies overlay jars into it, and publishes where it landed as
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
| `provision` | `process-test-classes` | Resolve, unpack and overlay GlassFish, then set `glassfish.home`. Idempotent: a re-run skips the unpack while the same distribution is in place. |

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
`mvn test-compile` doesn't fetch GlassFish.

The extension also hands the install to the test JVMs. `provision` sets
the project property `glassfish.home`, but surefire and failsafe don't pass
project properties to their forks. So the plugin adds
`<glassfish.home>${glassfish.home}</glassfish.home>` to the
`<systemPropertyVariables>` of both. Maven resolves that reference when the
tests run, after `provision`. An entry you configure yourself wins.

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
| `skip`             | `glassfish.provision.skip`   | `false`                           | Skip the goal: nothing is unpacked or published. |

`provision` also skips on `-DskipTests` and `-Dmaven.test.skip`: if the
build runs no tests, there's no reason to fetch GlassFish.

`home` and `homeProperty` have no `-D` switch. `glassfish.home` is this
goal's output, and the extension has to know which property it forwards.

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
executable. A marker file in `stageDir` records the distribution:

- a re-run with the same distribution skips the unpack;
- a different distribution replaces the old one (`stageDir` is wiped first),
  and so does a zip that is newer than the unpack, such as a SNAPSHOT you
  rebuilt with `mvn install`;
- overlays are copied on every run, so a new overlay version applies
  without a wipe.

Modules of a parallel (`-T`) build that share a `stageDir` unpack it once.

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
| `skip`          | boolean | `false`                     | Skip just this overlay, e.g. `<skip>${soteria.noupdate}</skip>`. |

`overlaySkip` skips them all: `mvn verify -Dglassfish.overlay.skip=true`.

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
