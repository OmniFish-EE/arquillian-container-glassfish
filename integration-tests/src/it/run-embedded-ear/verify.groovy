/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.util.concurrent.TimeUnit

// glassfish:run keeps its Maven running, so it runs in a Maven of its own here, in the background, in the EAR's
// module, whose WARs the invoker's install put in the local repository.
def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path
def ear = new File(basedir, 'ear')

def finished = { Process process, String what ->
    assert process.waitFor(5, TimeUnit.MINUTES) : "${what} didn't end"
    process.exitValue()
}

def page = { String url ->
    def connection = new URL(url).openConnection()
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.inputStream.text
}

def answers = { int port ->
    try {
        new Socket('localhost', port).close()
        true
    } catch (IOException e) {
        false
    }
}

def log = new File(ear, 'run.log')
def run = new ProcessBuilder(mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}", 'glassfish:run')
        .directory(ear)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()
try {
    // The URLs run prints once it deployed, as long as its Maven runs.
    def urls = null
    for (int i = 0; i < 600 && run.alive && urls == null; i++) {
        urls = (log.text =~ /Deployed \S+, at (.+)/).with { it.find() ? it.group(1).trim().split(', ') as List : null }
        if (urls == null) {
            sleep(500)
        }
    }
    assert urls == ['http://localhost:8080/alpha-site/', 'http://localhost:8080/beta-site/'] : log.text
    assert page(urls[0]).contains('alpha module')
    assert page(urls[1]).contains('beta module')

    // Ctrl+C: see run-embedded.
    if (windows) {
        def pid = new File(ear, 'target/dist/glassfish-embedded.pid').text.tokenize()[0] as long
        ProcessHandle.of(pid).ifPresent { it.destroy() }
        assert finished(run, 'The run') == 0
    } else {
        run.destroy()
        finished(run, 'The run')
        assert log.text.contains('GlassFish Embedded stopped')
    }
    assert !answers(8080)
} finally {
    // Whatever failed, leave no server behind.
    if (run.alive) {
        run.toHandle().descendants().each { it.destroyForcibly() }
        run.destroyForcibly()
    }
}

true
