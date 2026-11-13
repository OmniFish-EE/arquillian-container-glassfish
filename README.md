# Arquillian Connectors Suite

Set of tools that provide a GlassFish Server container adapter for Arquillian.

## Documentation

Visit the [Arquillian Container Documentation page](https://omnifish-ee.github.io/arquillian-container-glassfish/glassfish-containers-docs.html).

## History

Originally forked from:
 * https://github.com/arquillian/arquillian-container-glassfish

Then forked from:
 * https://github.com/payara/ecosystem-arquillian-connectors

## Examples

 Quick example usage for the managed connector:

 Declare dependency to connector (container adapter):

```xml
 <dependency>
    <groupId>ee.omnifish.arquillian</groupId>
    <artifactId>arquillian-glassfish-server-managed</artifactId>
    <version>2.1.2</version>
 </dependency>
```

 Configure surefire or failsafe. Some examples given below. Only `glassfish.home` is required.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire.version}</version>
    <configuration>
        <systemPropertyVariables>
            <glassfish.home>${glassfish.root}/glassfish7</glassfish.home>
            <glassfish.enableDerby>true</glassfish.enableDerby>
            <glassfish.maxHeapSize>2048m</glassfish.maxHeapSize>
            <glassfish.enableAssertions>:org.jboss.cdi.tck...</glassfish.enableAssertions>

            <glassfish.postBootCommands>
                create-jms-resource --restype jakarta.jms.Queue --property Name=queue_test queue_test
                create-jms-resource --restype jakarta.jms.Topic --property Name=topic_test topic_test
                set configs.config.server-config.cdi-service.enable-implicit-cdi=true
                create-file-user --groups student student
                create-file-user --groups printer printer
                create-file-user --groups student:alarm alarm
            </glassfish.postBootCommands>
        </systemPropertyVariables>
    </configuration>
</plugin>

```

 To get GlassFish itself, let the [GlassFish Maven Plugin](glassfish-maven-plugin/) download and
 unpack it during the build. It sets `glassfish.home` for surefire and failsafe, so the entry above
 can go:

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
