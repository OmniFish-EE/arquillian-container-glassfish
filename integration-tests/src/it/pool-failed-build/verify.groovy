/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The build failed for the test that saw the pool run, and then failed on purpose.
def log = new File(basedir, 'build.log').text
assert log.contains('Failing on purpose, with the pool running')

// The extension bound no down, and still stopped the pool once, after the failed build.
assert !log.contains(':down (pool-down)')
assert log.count('Stopping the pool at') == 1
assert log.indexOf('Stopping the pool at') > log.indexOf('BUILD FAILURE')

// GlassFish removes its pid file when it stops.
assert !new File(basedir, 'target/pool/slot-1/glassfish/glassfish/domains/domain1/config/pid').exists()
