/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The extension bound no down, and stopped the pool once, after the build.
def log = new File(basedir, 'build.log').text
assert !log.contains(':down (pool-down)')
assert log.count('Stopping the pool at') == 1

// GlassFish removes its pid file when it stops, on every slot.
def slots = new File(basedir, 'target/pool').listFiles().findAll { it.name ==~ /slot-\d+/ }
assert !slots.isEmpty()
slots.each { assert !new File(it, 'glassfish/glassfish/domains/domain1/config/pid').exists() }
