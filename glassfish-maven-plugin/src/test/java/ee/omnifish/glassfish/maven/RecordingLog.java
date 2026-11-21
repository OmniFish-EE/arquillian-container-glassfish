/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;

/** Console log that also records warnings, so tests can assert on them. */
class RecordingLog extends SystemStreamLog {

    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();

    @Override
    public void info(CharSequence content) {
        infos.add(content.toString());
        super.info(content);
    }

    @Override
    public void warn(CharSequence content) {
        warnings.add(content.toString());
        super.warn(content);
    }
}
