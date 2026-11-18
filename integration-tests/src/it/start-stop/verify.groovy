/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The first invocation started GlassFish, the second tested it, and the third stopped it.
def log = new File(basedir, 'build.log').text
assert log.contains('GlassFish runs from')
assert log.contains('Tests run: 1, Failures: 0, Errors: 0, Skipped: 0')
assert log =~ /GlassFish at .* stopped/

// GlassFish removes its pid file when it stops.
def install = new File(basedir, 'target/dist').listFiles().find { it.name.startsWith('glassfish') }
assert !new File(install, 'glassfish/domains/domain1/config/pid').exists()
