/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// The Managed run mirrors the server log: the controller's log line reached the build output once.
def log = new File(basedir, 'build.log').text
assert log.readLines().count { it.contains('[glassfish:domain1]') && it.contains('microprofile-it hello said on the server') } == 1
