/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.util.concurrent.TimeUnit

def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path
def log = new File(basedir, 'build.log').text

// Each deployment started a child of its own, which its undeploy ended: no record of one is left.
def records = { new File(basedir, 'target/dist').listFiles().findAll { it.name ==~ /glassfish-embedded-\d+\.pid/ } }
assert records().isEmpty()

// The extension stopped the pool once, after the build; the children's output reached the build output.
assert log.count('Stopping the pool at') == 1
assert log.contains('[glassfish:slot-')

// Both executions ran their tests: two test JVMs at once, each with a child of its own, and the HTTPS test,
// which only the static shell passes, was skipped.
def summary = { String execution -> new File(basedir, "target/failsafe-reports/${execution}.xml").text }
assert summary('two-jvms').contains('<completed>2</completed>')
assert summary('one-jvm').contains('<skipped>1</skipped>')

def answers = { int port ->
    try {
        new Socket('localhost', port).close()
        true
    } catch (IOException e) {
        false
    }
}

// glassfish-pool:run keeps its Maven running, so it's a Maven of its own here, in the background.
def maven = { List<String> args, String logName ->
    new ProcessBuilder([mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}".toString()] + args)
            .directory(basedir)
            .redirectErrorStream(true)
            .redirectOutput(new File(basedir, logName))
            .start()
}

// Ctrl+C runs the shutdown hooks of Maven's JVM, as a SIGTERM does; on Windows, destroy() runs none.
if (!windows) {
    def run = maven(['glassfish-pool:run'], 'run.log')
    def runLog = new File(basedir, 'run.log')
    def url = null
    for (int i = 0; i < 600 && run.alive && !url; i++) {
        url = (runLog.text =~ /Deployed \S+, at (http:\S+)/).with { it.find() ? it.group(1) : null }
        sleep(500)
    }
    assert url : "No URL in run.log:\n${runLog.text}"

    // The slot setup reaches the child that runs the WAR, as it reaches the tests' children.
    assert new URL(url).text.contains('pool-embedded run, present')

    // The extension gives a run's slot a debugger, on localhost and the slot's own port.
    def debugger = (runLog.text =~ /Debugger at (\S+)/).with { it.find() ? it.group(1) : null }
    assert debugger : "No debugger in run.log:\n${runLog.text}"
    int colon = debugger.lastIndexOf(':')
    new Socket(debugger.substring(0, colon), debugger.substring(colon + 1) as int).withCloseable { socket ->
        socket.soTimeout = 10_000
        socket.outputStream.write('JDWP-Handshake'.bytes)
        def reply = new byte[14]
        new DataInputStream(socket.inputStream).readFully(reply)
        assert new String(reply) == 'JDWP-Handshake'
    }
    assert records().size() == 1

    run.destroy()
    assert run.waitFor(5, TimeUnit.MINUTES) : 'The run did not end'
    assert runLog.text.contains('GlassFish Embedded stopped')
    assert runLog.text.contains('Handed slot ')
    assert !answers(new URL(url).port)
    assert records().isEmpty()
}

// The same tests, with an overlay: the children run the static shell of the Full ZIP, which serves HTTPS. From
// GlassFish 9 on, Mojarra comes as its API and its implementation, which the pom then overlays apart.
def glassfishVersion = (new File(basedir, 'pom.xml').text =~ /<glassfish.version>([^<]+)</)[0][1]
def facesSplit = glassfishVersion.tokenize('.')[0].toInteger() >= 9
def overlaid = maven(['clean', 'verify', '-Dpool.it.overlay=true', "-Dfaces.split=${facesSplit}".toString()],
        'overlay.log')
assert overlaid.waitFor(15, TimeUnit.MINUTES) : 'The build with an overlay did not end'
def overlayLog = new File(basedir, 'overlay.log').text
assert overlaid.exitValue() == 0 : overlayLog.takeRight(10_000)
assert overlayLog.contains('Overlaid ')
assert summary('one-jvm').contains('<skipped>0</skipped>')
assert summary('two-jvms').contains('<completed>2</completed>')
assert records().isEmpty()

// The invoker counts a script that returns anything but true or null as failed.
return true
