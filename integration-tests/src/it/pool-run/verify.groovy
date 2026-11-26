/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.util.concurrent.TimeUnit

// glassfish-pool:run keeps its Maven running, so each run is a Maven of its own here, in the background: A from
// this project with -pl, as from a reactor root, and B from its module's directory.
def mvn = new File(System.getProperty('maven.home'), 'bin/mvn').path

def run = { String module, boolean inModule ->
    def command = [mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}".toString()]
    command += inModule ? ['glassfish-pool:run'] : ['-pl', module, 'glassfish-pool:run']
    new ProcessBuilder(command)
            .directory(inModule ? new File(basedir, module) : basedir)
            .redirectErrorStream(true)
            .redirectOutput(new File(basedir, "run-${module}.log"))
            .start()
}

// The pool's root directory, as gf.pool.rootDirectory, which a page shows.
def rootOn = { String text ->
    def root = (text =~ /root (\S+)/).with { it.find() ? it.group(1) : null }
    assert root : "No root in ${text}"
    new File(root).canonicalFile
}

def ended = { Process process, String what ->
    assert process.waitFor(5, TimeUnit.MINUTES) : "${what} didn't end"
    process.exitValue()
}

// The URL run prints once it deployed, as long as its Maven runs.
def deployedAt = { Process maven, String module ->
    def log = new File(basedir, "run-${module}.log")
    for (int i = 0; i < 600 && maven.alive; i++) {
        def url = (log.text =~ /Deployed \S+, at (http:\S+)/).with { it.find() ? it.group(1) : null }
        if (url) {
            return url
        }
        sleep(500)
    }
    assert false : "No URL in run-${module}.log:\n${log.text}"
}

def page = { String url ->
    def connection = new URL(url).openConnection()
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.inputStream.text
}

// What a run prints after the URL: where a debugger attaches, and where the whole server log is.
def printed = { String module, String what ->
    def value = (new File(basedir, "run-${module}.log").text =~ /${what} at (\S+)/).with { it.find() ? it.group(1) : null }
    assert value : "No '${what} at' in run-${module}.log"
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

// Slot N's admin port is 14848 + (N - 1) * 100, and its HTTP port the one after.
def runs = []
try {
    // The pool starts slot 1 in the package that run forks, and A leases it.
    def a = run('module-a', false)
    runs << a
    def aUrl = deployedAt(a, 'module-a')
    assert aUrl == 'http://localhost:14849/a/'
    assert page(aUrl).contains('module a, from-the-pool')
    assert rootOn(page(aUrl)) == basedir.canonicalFile
    // The up in the package A forks started slot 1, with a debugger on its own port: the extension turned it on.
    assert printed('module-a', 'Debugger') == 'localhost:14857'
    assert debuggerAnswers('localhost:14857')
    assert new File(printed('module-a', 'Server log')).with { it.path.contains('slot-1') && it.text.contains('/a') }

    // Slot 1 is A's, so B's lease grows the pool by slot 2, set up as slot 1 is. B runs in its module's directory,
    // and still finds the pool in this project, and this project as the root.
    def b = run('module-b', true)
    runs << b
    def bUrl = deployedAt(b, 'module-b')
    assert bUrl == 'http://localhost:14949/b/'
    assert page(bUrl).contains('module b, from-the-pool')
    assert rootOn(page(bUrl)) == basedir.canonicalFile
    assert page(aUrl).contains('module a, from-the-pool')
    assert printed('module-b', 'Debugger') == 'localhost:14957'
    assert debuggerAnswers('localhost:14957')
    assert new File(printed('module-b', 'Server log')).text.contains('/b')

    // Ctrl+C on A: a SIGTERM runs the JVM's shutdown hooks, as Ctrl+C's SIGINT does, and bin/mvn execs that JVM.
    // A's pool stops, except for B's slot.
    a.destroy()
    ended(a, 'Run A')
    assert !answers(14848)
    assert page(bUrl).contains('module b, from-the-pool')
    assert new File(basedir, 'run-module-a.log').text.contains('Handed slot 1 back to the pool')

    // Ctrl+C on B, the last run: the pool is down.
    b.destroy()
    ended(b, 'Run B')
    assert !answers(14948)
    assert !answers(14848)
} catch (Throwable e) {
    // CI keeps only this script's output, so print what says who ended what, before the cleanup below ends the
    // runs: whether each run still runs, whether each slot's admin port answers, the end of what each run
    // printed, and the end of each slot's server.log, which a run no longer mirrors once it ended.
    runs.eachWithIndex { process, i ->
        println "Run ${['A', 'B'][i]}: ${process.alive ? 'runs' : 'ended, with exit code ' + process.exitValue()}"
    }
    [1, 2].each { slot ->
        int adminPort = 14848 + (slot - 1) * 100
        println "Slot ${slot}'s admin port ${adminPort}: ${answers(adminPort) ? 'answers' : 'closed'}"
    }
    def printEnd = { File file ->
        if (file.exists()) {
            println "----- The end of ${file} -----"
            println file.readLines().takeRight(100).join('\n')
        }
    }
    ['module-a', 'module-b'].each { printEnd(new File(basedir, "run-${it}.log")) }
    [1, 2].each { slot ->
        printEnd(new File(basedir, "target/pool/slot-${slot}/glassfish/glassfish/domains/domain1/logs/server.log"))
    }
    throw e
} finally {
    // Whatever failed, leave no server behind.
    runs.findAll { it.alive }.each { it.destroy() }
    runs.each { ended(it, 'A run') }
    if (answers(14848) || answers(14948)) {
        def down = new ProcessBuilder(mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}", '-N',
                'glassfish-pool:down')
                .directory(basedir)
                .redirectErrorStream(true)
                .redirectOutput(new File(basedir, 'down-cleanup.log'))
                .start()
        ended(down, 'glassfish-pool:down')
    }
}

true
