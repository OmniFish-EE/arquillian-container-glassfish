/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The first invocation started GlassFish, and the second tested it. The third stopped it, although clean had
// deleted its install, and with it the pid file and asadmin. The fourth and fifth started and stopped it again.
def log = new File(basedir, 'build.log').text
assert log.contains('GlassFish runs from')
assert log.contains('Tests run: 1, Failures: 0, Errors: 0, Skipped: 0')
assert log.findAll(/GlassFish at .* stopped/).size() == 2
assert !log.contains("doesn't run")

// start ran the post-boot commands on its own start, once for each install it staged.
assert !log.contains('briefly')
assert log.count('Running the post-boot commands of') == 2
def install = new File(basedir, 'target/dist').listFiles().find { it.name.startsWith('glassfish') }
assert new File(install, 'glassfish/domains/domain1/config/domain.xml').text.contains('name="start.stop.it"')

// GlassFish removes its pid file when it stops.
assert !new File(install, 'glassfish/domains/domain1/config/pid').exists()

// The sixth invocation started GlassFish suspended, and nobody attached: start ended it, so nothing
// answers on the debug port, 9009, or the admin port.
assert log.contains('Waiting up to 20 seconds for a debugger to attach')
// start's own deadline ended it, not asadmin's, which AS_START_TIMEOUT shortens on GlassFish 8: ended that way,
// asadmin leaves the waiting server running.
assert log.contains('timed out after PT20S')
[9009, 4848].each { port ->
    def answers = false
    try {
        new Socket('localhost', port).close()
        answers = true
    } catch (IOException e) {
    }
    assert !answers : "Something still answers on port ${port}"
}

// The invoker counts a script that returns anything but true or null as failed.
return true
