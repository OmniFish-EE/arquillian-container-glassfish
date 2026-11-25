/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// <edition>web</edition> unpacks the Web profile: the web container, but no Batch, which only the Full profile has.
def build = new File(basedir, 'build.log').text
assert build.contains('Unpacking org.glassfish.main.distributions:web:')

def install = new File(basedir, 'target/dist').listFiles().find { it.directory && it.name.startsWith('glassfish') }
assert install : "No install in ${basedir}/target/dist"
assert new File(install, 'glassfish/modules/web-core.jar').file
assert !new File(install, 'glassfish/modules/com.ibm.jbatch.container.jar').exists()

true
