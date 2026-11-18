/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.glassfish.maven.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/** Runs in the second invocation: the server the first one started still runs. */
public class RunningServerIT {

    @Test
    public void glassFishStillAnswers() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:8080/")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }
}
