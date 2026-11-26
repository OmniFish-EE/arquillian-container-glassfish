/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.security.KeyStore
import java.util.concurrent.TimeUnit

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer

// glassfish:run keeps its Maven running, so each run is a Maven of its own here, in the background.
def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path
def keytool = new File(System.getProperty('java.home'), windows ? 'bin/keytool.exe' : 'bin/keytool').path

def maven = { String goal, String logName ->
    new ProcessBuilder(mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}", goal)
            .directory(basedir)
            .redirectErrorStream(true)
            .redirectOutput(new File(basedir, logName))
            .start()
}

def finished = { Process process, String what ->
    assert process.waitFor(5, TimeUnit.MINUTES) : "${what} didn't end"
    process.exitValue()
}

// The URL run prints once it deployed, as long as its Maven runs.
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
    connection.inputStream.text
}

// What run prints after the URL: where a debugger attaches.
def printed = { String logName, String what ->
    def value = (new File(basedir, logName).text =~ /${what} at (\S+)/).with { it.find() ? it.group(1) : null }
    assert value : "No '${what} at' in ${logName}"
    value
}

// A debugger's handshake, with which an IDE's attach starts: JDWP answers with the same 14 bytes.
def debuggerAnswers = { String address ->
    int colon = address.lastIndexOf(':')
    new Socket(address.substring(0, colon), address.substring(colon + 1) as int).withCloseable { socket ->
        socket.soTimeout = 10_000
        socket.outputStream.write('JDWP-Handshake'.bytes)
        socket.outputStream.flush()
        def reply = new byte[14]
        new DataInputStream(socket.inputStream).readFully(reply)
        new String(reply) == 'JDWP-Handshake'
    }
}

def answers = { int port ->
    try {
        new Socket('localhost', port).close()
        true
    } catch (IOException e) {
        false
    }
}

def keys = { List<String> args ->
    def process = new ProcessBuilder([keytool] + args).directory(basedir).redirectErrorStream(true).start()
    def output = process.inputStream.text
    assert process.waitFor() == 0 : "keytool ${args.join(' ')}:\n${output}"
}

// HTTPS on a free port of localhost, with the key in keyStore.
def https = { String keyStore ->
    def store = KeyStore.getInstance(new File(basedir, keyStore), 'changeit'.toCharArray())
    def keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
    keyManagers.init(store, 'changeit'.toCharArray())
    def ssl = SSLContext.getInstance('TLS')
    ssl.init(keyManagers.keyManagers, null, null)
    def server = HttpsServer.create(new InetSocketAddress('localhost', 0), 0)
    server.httpsConfigurator = new HttpsConfigurator(ssl)
    server.createContext('/') { exchange ->
        def body = "reply from ${keyStore}".bytes
        exchange.sendResponseHeaders(200, body.length)
        exchange.responseBody.withCloseable { it.write(body) }
    }
    server.start()
    server
}

// A CA, which the pom trusts, a localhost key it signed, and a localhost key nobody signed.
def store = ['-storepass', 'changeit', '-keyalg', 'RSA', '-validity', '2']
keys(['-genkeypair', '-alias', 'ca', '-dname', 'CN=run-embedded-it CA', '-ext', 'bc:c', '-keystore', 'ca.p12'] + store)
keys(['-exportcert', '-alias', 'ca', '-keystore', 'ca.p12', '-storepass', 'changeit', '-rfc', '-file', 'trusted-ca.pem'])
keys(['-genkeypair', '-alias', 'server', '-dname', 'CN=localhost', '-keystore', 'server.p12'] + store)
keys(['-certreq', '-alias', 'server', '-keystore', 'server.p12', '-storepass', 'changeit', '-file', 'server.csr'])
keys(['-gencert', '-alias', 'ca', '-keystore', 'ca.p12', '-storepass', 'changeit', '-infile', 'server.csr',
      '-outfile', 'server.pem', '-ext', 'san=dns:localhost', '-validity', '2', '-rfc'])
keys(['-importcert', '-alias', 'ca', '-keystore', 'server.p12', '-storepass', 'changeit', '-file', 'trusted-ca.pem',
      '-noprompt'])
keys(['-importcert', '-alias', 'server', '-keystore', 'server.p12', '-storepass', 'changeit', '-file', 'server.pem'])
keys(['-genkeypair', '-alias', 'untrusted', '-dname', 'CN=localhost', '-ext', 'san=dns:localhost', '-keystore',
      'untrusted.p12'] + store)

def trusted = https('server.p12')
def untrusted = https('untrusted.p12')
def runs = []
try {
    def first = maven('glassfish:run', 'run-1.log')
    runs << first
    def url = deployedAt(first, 'run-1.log')
    assert url == 'http://localhost:8080/run-embedded-it/'
    assert new File(basedir, 'run-1.log').text.contains('http-listener.port=8080')
    assert page(url).contains('marker: from a post-boot command')
    assert page("${url}trust.jsp?port=${trusted.address.port}").contains('reply from server.p12')
    assert page("${url}trust.jsp?port=${untrusted.address.port}").contains('javax.net.ssl.SSLHandshakeException')
    // GlassFish's own *:9009, on localhost only.
    assert printed('run-1.log', 'Debugger') == 'localhost:9009'
    assert debuggerAnswers('localhost:9009')

    // A second run fails on the occupied port, before it stages anything, and leaves the first running.
    def second = maven('glassfish:run', 'run-2.log')
    runs << second
    assert finished(second, 'The second run') != 0
    assert new File(basedir, 'run-2.log').text.contains('Something already answers on port 8080')
    assert page(url).contains('marker: from a post-boot command')

    // Ctrl+C: a SIGTERM runs the JVM's shutdown hooks, as Ctrl+C's SIGINT does, and bin/mvn execs that JVM; the
    // plugin stops the child. On Windows, destroy() ends a process without running them, also Maven's own JVM
    // under mvn.cmd, so only the child, which the stage directory names, is ended there, which ends the run.
    if (windows) {
        def pid = new File(basedir, 'target/dist/glassfish-embedded.pid').text.tokenize()[0] as long
        ProcessHandle.of(pid).ifPresent { it.destroy() }
        assert finished(first, 'The first run') == 0
    } else {
        first.destroy()
        finished(first, 'The first run')
        assert new File(basedir, 'run-1.log').text.contains('GlassFish Embedded stopped')
        assert !new File(basedir, 'target/glassfish-embedded').listFiles().any { it.name.startsWith('run-') }
    }
    assert !answers(8080)
    assert !answers(9009)
} finally {
    // Whatever failed, leave no server behind.
    runs.findAll { it.alive }.each { run ->
        run.toHandle().descendants().each { it.destroyForcibly() }
        run.destroyForcibly()
    }
    trusted.stop(0)
    untrusted.stop(0)
}

true
