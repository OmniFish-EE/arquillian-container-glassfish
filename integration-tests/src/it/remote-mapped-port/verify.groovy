/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */

// Both tests ran: the client test, and the one inside the server.
def log = new File(basedir, 'build.log').text
assert log.contains('Tests run: 2, Failures: 0, Errors: 0, Skipped: 0')

// With start bound, the extension leaves provision out, so the post-boot commands ran on start's own start.
assert !log.contains('briefly')
assert log.contains('Running the post-boot commands of')

// The invoker counts a script that returns anything but true or null as failed.
return true
