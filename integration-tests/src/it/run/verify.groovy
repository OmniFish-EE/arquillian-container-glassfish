/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.util.concurrent.TimeUnit

// glassfish:run keeps its Maven running, so each run is a Maven of its own here, in the background.
def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path

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
    connection.readTimeout = 10_000
    connection.inputStream.text
}

// What run prints after the URL: where a debugger attaches, and where the whole server log is.
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

def runs = []
try {
    def first = maven('glassfish:run', 'run-1.log')
    runs << first
    def url = deployedAt(first, 'run-1.log')
    assert url == 'http://localhost:8080/run-it/'
    assert page(url).contains('marker one')
    // GlassFish's own *:9009, on localhost only.
    assert printed('run-1.log', 'Debugger') == 'localhost:9009'
    assert debuggerAnswers('localhost:9009')
    assert new File(printed('run-1.log', 'Server log')).text.contains('run-it')

    // A second run fails on the occupied admin port, and leaves the domain of the first running.
    def second = maven('glassfish:run', 'run-2.log')
    runs << second
    assert finished(second, 'The second run') != 0
    assert new File(basedir, 'run-2.log').text.contains('Something already answers on admin port 4848')
    assert page(url).contains('marker one')

    // Ctrl+C: a SIGTERM runs the JVM's shutdown hooks, as Ctrl+C's SIGINT does, and bin/mvn execs that JVM. On
    // Windows, destroy() ends a process without running them, so glassfish:stop ends the run there instead.
    if (windows) {
        assert finished(maven('glassfish:stop', 'stop-1.log'), 'glassfish:stop') == 0
        assert finished(first, 'The first run') == 0
    } else {
        first.destroy()
        finished(first, 'The first run')
    }
    assert !answers(4848)
    assert !answers(8080)

    // After a change, without clean: the WAR replaces the one deployed under its name.
    def index = new File(basedir, 'src/main/webapp/index.html')
    index.text = index.text.replace('marker one', 'marker two')
    def third = maven('glassfish:run', 'run-3.log')
    runs << third
    assert deployedAt(third, 'run-3.log') == url
    assert page(url).contains('marker two')

    // glassfish:stop, as from another terminal, ends the run.
    assert finished(maven('glassfish:stop', 'stop-2.log'), 'glassfish:stop') == 0
    assert finished(third, 'The third run') == 0
    assert new File(basedir, 'run-3.log').text.contains('BUILD SUCCESS')
    assert !answers(4848)
} finally {
    // Whatever failed, leave no server behind.
    runs.findAll { it.alive }.each { it.destroy() }
    if (answers(4848)) {
        finished(maven('glassfish:stop', 'stop-cleanup.log'), 'glassfish:stop')
    }
}

true
