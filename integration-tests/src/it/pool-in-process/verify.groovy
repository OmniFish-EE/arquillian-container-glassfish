/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

import java.util.concurrent.TimeUnit

def windows = System.getProperty('os.name').startsWith('Windows')
def mvn = new File(System.getProperty('maven.home'), windows ? 'bin/mvn.cmd' : 'bin/mvn').path
def log = new File(basedir, 'build.log').text

// Each test JVM that hosted GlassFish removed its record from the stage directory once GlassFish stopped.
def records = { new File(basedir, 'target/dist').listFiles().findAll { it.name ==~ /glassfish-embedded-\d+\.pid/ } }
assert records().isEmpty()

// The extension stopped the pool once, after the build; each test JVM hosted GlassFish on a leased slot.
assert log.count('Stopping the pool at') == 1
assert log.contains('Started GlassFish Embedded in this test JVM, on pool slot ')

// All three executions ran their tests: the HTTPS test, which only the static shell passes, was skipped.
def summary = { String execution -> new File(basedir, "target/failsafe-reports/${execution}.xml").text }
assert summary('one-jvm').contains('<skipped>1</skipped>')
assert summary('two-jvms').contains('<completed>2</completed>')
assert summary('manual').contains('<completed>9</completed>')

// The same tests, with an overlay: the test JVMs host GlassFish through the static shell of the Full ZIP, which
// serves HTTPS. From GlassFish 9 on, Mojarra comes as its API and its implementation, which the pom then overlays
// apart.
def glassfishVersion = (new File(basedir, 'pom.xml').text =~ /<glassfish.version>([^<]+)</)[0][1]
def facesSplit = glassfishVersion.tokenize('.')[0].toInteger() >= 9
def overlaid = new ProcessBuilder(mvn, '-B', "-Dmaven.repo.local=${localRepositoryPath}".toString(), 'clean',
        'verify', '-Dpool.it.overlay=true', "-Dfaces.split=${facesSplit}".toString())
        .directory(basedir)
        .redirectErrorStream(true)
        .redirectOutput(new File(basedir, 'overlay.log'))
        .start()
assert overlaid.waitFor(15, TimeUnit.MINUTES) : 'The build with an overlay did not end'
def overlayLog = new File(basedir, 'overlay.log').text
assert overlaid.exitValue() == 0 : overlayLog.takeRight(10_000)
assert overlayLog.contains('Overlaid ')
assert summary('one-jvm').contains('<skipped>0</skipped>')
assert summary('two-jvms').contains('<completed>2</completed>')
assert summary('manual').contains('<completed>9</completed>')
assert records().isEmpty()

// The invoker counts a script that returns anything but true or null as failed.
return true
