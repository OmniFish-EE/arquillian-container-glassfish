/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer

// glassfish:run keeps its Maven running, so each run is a Maven of its own here, in the background. The project is
// in a directory with a space in its name, as GlassFish Embedded's deploy command splits a path where it has one.
def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path
def keytool = new File(System.getProperty('java.home'), windows ? 'bin/keytool.exe' : 'bin/keytool').path
def project = new File(basedir, 'web app')
// The Mojarra overlaid, as the harness filtered it into the pom.
def mojarraOverlay = (new File(basedir, 'pom.xml').text =~ /<mojarra.overlay>([^<]+)</)[0][1]

def maven = { List<String> args, String logName ->
    new ProcessBuilder([mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}".toString()] + args)
            .directory(project)
            .redirectErrorStream(true)
            .redirectOutput(new File(project, logName))
            .start()
}

def finished = { Process process, String what ->
    assert process.waitFor(5, TimeUnit.MINUTES) : "${what} didn't end"
    process.exitValue()
}

// The URL run prints once it deployed, as long as its Maven runs.
def deployedAt = { Process run, String logName ->
    def log = new File(project, logName)
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

// The status of url over HTTPS, trusting whatever certificate GlassFish's own listener has.
def httpsStatus = { String url ->
    def trustAll = SSLContext.getInstance('TLS')
    trustAll.init(null, [[checkClientTrusted: { chain, type -> }, checkServerTrusted: { chain, type -> },
                          getAcceptedIssuers: { new X509Certificate[0] }] as X509TrustManager] as TrustManager[], null)
    HttpsURLConnection connection = new URL(url).openConnection()
    connection.SSLSocketFactory = trustAll.socketFactory
    connection.hostnameVerifier = { host, session -> true }
    connection.responseCode
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
    def process = new ProcessBuilder([keytool] + args).directory(project).redirectErrorStream(true).start()
    def output = process.inputStream.text
    assert process.waitFor() == 0 : "keytool ${args.join(' ')}:\n${output}"
}

// HTTPS on a free port of localhost, with the key in keyStore.
def https = { String keyStore ->
    def store = KeyStore.getInstance(new File(project, keyStore), 'changeit'.toCharArray())
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
keys(['-genkeypair', '-alias', 'ca', '-dname', 'CN=run-embedded-overlay-it CA', '-ext', 'bc:c', '-keystore', 'ca.p12']
        + store)
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
def run = null
try {
    run = maven(['glassfish:run'], 'run.log')
    def url = deployedAt(run, 'run.log')
    assert url == 'http://localhost:8080/overlay-it/'
    assert page("${url}faces.jsp").contains("faces ${mojarraOverlay}")
    assert page("${url}trust.jsp?port=${trusted.address.port}").contains('reply from server.p12')
    assert page("${url}trust.jsp?port=${untrusted.address.port}").contains('javax.net.ssl.SSLHandshakeException')
    // The static shell's instance has domain1's key, so it serves HTTPS too.
    assert httpsStatus('https://localhost:8181/overlay-it/faces.jsp') == 200

    // Staging afresh, here without the overlay, would pull the install out from under the run.
    def provision = maven(['glassfish:provision', '-Dglassfish.overlay.skip=true'], 'provision.log')
    assert finished(provision, 'The provision') != 0
    if (!windows) {
        // On Windows, Java doesn't say what a process runs, and the files the child keeps open fail the wipe.
        assert new File(project, 'provision.log').text.contains('GlassFish Embedded runs from it')
    }
    assert page("${url}faces.jsp").contains("faces ${mojarraOverlay}")

    // The Web profile, with the overlay, whose domain1 GlassFish Embedded didn't run on.
    def install = new File(project, 'target/dist').listFiles().find { it.directory && it.name.startsWith('glassfish') }
    assert new File(install, 'glassfish/modules/jakarta.faces.jar').file
    assert !new File(install, 'glassfish/modules/com.ibm.jbatch.container.jar').exists()
    assert !new File(install, 'glassfish/domains/domain1/logs/server.log').exists()

    // Ctrl+C: see run-embedded.
    if (windows) {
        run.toHandle().descendants().each { it.destroy() }
        assert finished(run, 'The run') == 0
    } else {
        run.destroy()
        finished(run, 'The run')
        assert new File(project, 'run.log').text.contains('GlassFish Embedded stopped')
    }
    assert !answers(8080)
    assert !answers(8181)
} finally {
    // Whatever failed, leave no server behind.
    if (run?.alive) {
        run.toHandle().descendants().each { it.destroyForcibly() }
        run.destroyForcibly()
    }
    trusted.stop(0)
    untrusted.stop(0)
}

true
