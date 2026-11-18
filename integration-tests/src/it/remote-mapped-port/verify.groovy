/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// Both tests ran: the client test, and the one inside the server.
def log = new File(basedir, 'build.log').text
assert log.contains('Tests run: 2, Failures: 0, Errors: 0, Skipped: 0')
