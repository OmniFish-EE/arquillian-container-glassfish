/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The modules' ups started the pool once, and the extension stopped it once, after the build.
def log = new File(basedir, 'build.log').text
// -T2 took effect, so the modules built at the same time.
assert log.contains('thread count of 2')
assert log.count('Cloning source install for slot 1') == 1
assert log.count('Stopping the shared pool at') == 1

// GlassFish removes its pid file when it stops.
assert !new File(basedir, 'target/pool/slot-1/glassfish/glassfish/domains/domain1/config/pid').exists()
