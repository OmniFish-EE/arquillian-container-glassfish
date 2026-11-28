/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.security.KeyStore
import java.util.concurrent.TimeUnit

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer

// glassfish-pool:run keeps its Maven running, so each run is a Maven of its own here, in the background. Each hosts
// GlassFish Embedded in its own JVM, as <embeddedMode>in-process</embeddedMode> has it, without skip flags: the
// package a run forks runs the up the pom binds to initialize, which sees in-process first.
def mvn = new File(System.getProperty('maven.home'), 'bin/mvn').path
def glassfishVersion = (new File(basedir, 'pom.xml').text =~ /<glassfish.version>([^<]+)</)[0][1]
def mojarra = (new File(basedir, 'pom.xml').text =~ /<mojarra.overlay>([^<]+)</)[0][1]

// A run of module, from this project with -pl, or from the module's directory, with mavenOpts as MAVEN_OPTS.
def maven = { String logName, String dir, List<String> args, String mavenOpts ->
    def builder = new ProcessBuilder([mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}".toString()] + args)
            .directory(dir ? new File(basedir, dir) : basedir)
            .redirectErrorStream(true)
            .redirectOutput(new File(basedir, logName))
    builder.environment().put('MAVEN_OPTS', mavenOpts)
    builder.start()
}

def ended = { Process process, String what ->
    assert process.waitFor(5, TimeUnit.MINUTES) : "${what} didn't end"
    process.exitValue()
}

// The URL a run prints once it deployed, as long as its Maven runs.
def deployedAt = { Process run, String logName ->
    def log = new File(basedir, logName)
    for (int i = 0; i < 600 && run.alive; i++) {
        def url = (log.text =~ /Deployed \S+, at (http:\S+)/).with { it.find() ? it.group(1) : null }
        if (url) {
            return url
        }
        sleep(500)
    }
    assert false : "No URL in ${logName}:\n${log.text}"
}

def page = { String url ->
    def connection = new URL(url).openConnection()
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.inputStream.text.trim()
}

def answers = { int port ->
    try {
        new Socket('localhost', port).close()
        true
    } catch (IOException e) {
        false
    }
}

// An HTTPS server on the loopback address with a key pair of https/: the certificates name 127.0.0.1.
def https = { String keyStore ->
    def store = KeyStore.getInstance('PKCS12')
    new File(basedir, "https/${keyStore}").withInputStream { store.load(it, 'changeit'.toCharArray()) }
    def keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keys.init(store, 'changeit'.toCharArray())
    def context = SSLContext.getInstance('TLS')
    context.init(keys.keyManagers, null, null)
    def server = HttpsServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)
    server.httpsConfigurator = new HttpsConfigurator(context)
    server.createContext('/') { exchange ->
        def body = 'hello over TLS'.bytes
        exchange.sendResponseHeaders(200, body.length)
        exchange.responseBody.withStream { it.write(body) }
    }
    server.start()
    server
}

// Slot N's HTTP port is 14849 + (N - 1) * 100.
def runs = []
def servers = []
try {
    // Without GlassFish's module options, which only its command line can give, the run names the lines to add,
    // and hosts nothing. Those lines are what the runs below get, so they're shown to work as they're given.
    def withoutOptions = maven('run-without-options.log', null, ['-pl', 'module-a', 'glassfish-pool:run'], '')
    runs << withoutOptions
    assert ended(withoutOptions, 'The run without module options') != 0
    def withoutLog = new File(basedir, 'run-without-options.log').text
    assert withoutLog.contains('Add these lines to .mvn/jvm.config') : withoutLog.takeRight(5_000)
    def options = (withoutLog.substring(withoutLog.indexOf('MAVEN_OPTS:')) =~ /--add-(?:opens|exports)=[^\s]+/)
            .collect { it }.unique()
    assert options : withoutLog.takeRight(5_000)
    assert withoutLog.contains('Handed slot 1 back to the pool')
    def mavenOpts = options.join(' ')

    // Your acceptance case: A from this project with -pl, B from its module's directory, each a Maven that
    // hosts GlassFish itself, on a slot of its own, as the page's pid, bin/mvn's, which execs that JVM, says.
    def a = maven('run-module-a.log', null, ['-pl', 'module-a', 'glassfish-pool:run'], mavenOpts)
    runs << a
    def aUrl = deployedAt(a, 'run-module-a.log')
    assert aUrl == 'http://localhost:14849/a/'
    assert page(aUrl) == "module a, from-the-pool, pid ${a.pid()}".toString()

    // GlassFish trusts the imported certificate, through the TLS context it makes Maven's JVM's default.
    def trusted = https('trusted.p12')
    def untrusted = https('untrusted.p12')
    servers << trusted << untrusted
    def fetch = { HttpsServer server ->
        page(aUrl + 'fetch.jsp?url=' + URLEncoder.encode("https://127.0.0.1:${server.address.port}/", 'UTF-8'))
    }
    assert fetch(trusted) == 'hello over TLS'
    assert fetch(untrusted).startsWith('failed: javax.net.ssl.SSLHandshakeException')

    // B runs status after the run, which a down from another terminal ends.
    def b = maven('run-module-b.log', 'module-b', ['glassfish-pool:run', 'glassfish-pool:status'], mavenOpts)
    runs << b
    def bUrl = deployedAt(b, 'run-module-b.log')
    assert bUrl == 'http://localhost:14949/b/'
    assert page(bUrl) == "module b, from-the-pool, pid ${b.pid()}".toString()
    assert page(aUrl).startsWith('module a, from-the-pool')

    // Ctrl+C on A: a SIGTERM runs the JVM's shutdown hooks, as Ctrl+C's SIGINT does. A's GlassFish stops, and its
    // slot goes back; B keeps serving.
    a.destroy()
    ended(a, 'Run A')
    def aLog = new File(basedir, 'run-module-a.log').text
    assert aLog.contains('GlassFish Embedded stopped')
    assert aLog.contains('Handed slot 1 back to the pool')
    assert !answers(14849)
    assert page(bUrl).startsWith('module b, from-the-pool')

    // A down from another terminal asks B to stop, as it can't end B's Maven, which then runs status, and ends.
    def down = maven('down.log', null, ['-N', 'glassfish-pool:down'], '')
    assert ended(down, 'glassfish-pool:down') == 0 : new File(basedir, 'down.log').text.takeRight(5_000)
    assert ended(b, 'Run B') == 0 : new File(basedir, 'run-module-b.log').text.takeRight(5_000)
    def bLog = new File(basedir, 'run-module-b.log').text
    def handedBack = bLog.indexOf('Handed slot 2 back to the pool')
    assert handedBack > 0
    assert bLog.indexOf(':status (default-cli)', handedBack) > 0
    assert new File(basedir, 'down.log').text.contains('Asked the glassfish-pool:run on slot 2 to stop')
    assert !answers(14949)

    // With an overlay, up stages the Web ZIP, and the run hosts GlassFish through its static shell, from which the
    // application loads the overlaid Faces. From GlassFish 9 on, its API is a JAR of its own.
    def facesSplit = glassfishVersion.tokenize('.')[0].toInteger() >= 9
    def overlaid = maven('run-overlay.log', null, ['-pl', 'module-a', 'glassfish-pool:run', '-Dpool.it.overlay=true',
            "-Dfaces.split=${facesSplit}".toString()], mavenOpts)
    runs << overlaid
    def overlaidUrl = deployedAt(overlaid, 'run-overlay.log')
    assert page(overlaidUrl) == "module a, from-the-pool, pid ${overlaid.pid()}".toString()
    def faces = page(overlaidUrl + 'faces.jsp')
    assert faces.contains('/glassfish/modules/jakarta.faces') : faces
    assert faces.endsWith(' ' + mojarra) : faces
    overlaid.destroy()
    ended(overlaid, 'The run with an overlay')
    assert new File(basedir, 'run-overlay.log').text.contains('Handed slot 1 back to the pool')
} catch (Throwable e) {
    // CI keeps only this script's output, so print whether each run still runs, and the end of what each printed.
    runs.eachWithIndex { process, i ->
        println "Run ${i + 1}: ${process.alive ? 'runs' : 'ended, with exit code ' + process.exitValue()}"
    }
    ['run-without-options.log', 'run-module-a.log', 'run-module-b.log', 'down.log', 'run-overlay.log'].each {
        def file = new File(basedir, it)
        if (file.exists()) {
            println "----- The end of ${file} -----"
            println file.readLines().takeRight(100).join('\n')
        }
    }
    throw e
} finally {
    servers.each { it.stop(0) }
    // Whatever failed, leave no server behind.
    runs.findAll { it.alive }.each { it.destroy() }
    runs.each { ended(it, 'A run') }
}

true
