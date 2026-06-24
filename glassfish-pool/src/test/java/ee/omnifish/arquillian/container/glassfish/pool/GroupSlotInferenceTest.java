/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 */
package ee.omnifish.arquillian.container.glassfish.pool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.jboss.arquillian.config.descriptor.api.ArquillianDescriptor;
import org.jboss.arquillian.config.descriptor.api.GroupDef;
import org.jboss.shrinkwrap.descriptor.api.Descriptors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the group-qualifier lookup that drives slot-group inference.
 * The observer wiring (event timing, config mapping) is exercised end-to-end by
 * the integration test; here we pin the pure container-to-group resolution.
 */
class GroupSlotInferenceTest {

    private ArquillianDescriptor httpHttpsGroup() {
        ArquillianDescriptor descriptor = Descriptors.create(ArquillianDescriptor.class);
        GroupDef group = descriptor.group("glassfish-servers");
        group.container("http");
        group.container("https");
        return descriptor;
    }

    @Test
    void resolvesGroupOfAMember() {
        ArquillianDescriptor descriptor = httpHttpsGroup();
        assertThat(GroupSlotInference.groupNameFor(descriptor, "http"), equalTo("glassfish-servers"));
        assertThat(GroupSlotInference.groupNameFor(descriptor, "https"), equalTo("glassfish-servers"));
    }

    @Test
    void returnsNullForStandaloneOrUnknownContainer() {
        ArquillianDescriptor descriptor = Descriptors.create(ArquillianDescriptor.class);
        descriptor.container("solo"); // top-level, not in a group

        assertThat(GroupSlotInference.groupNameFor(descriptor, "solo"), nullValue());
        assertThat(GroupSlotInference.groupNameFor(httpHttpsGroup(), "absent"), nullValue());
    }

    @Test
    void nullDescriptorYieldsNoGroup() {
        assertThat(GroupSlotInference.groupNameFor(null, "http"), nullValue());
    }

    // --- inferredSlotGroup: precedence rules ---

    @Test
    void infersGroupQualifierWhenEnabledAndNothingExplicit() {
        assertThat(
                GroupSlotInference.inferredSlotGroup(httpHttpsGroup(), "https", null, null, true),
                equalTo("glassfish-servers"));
    }

    @Test
    void doesNotInferWhenDisabled() {
        assertThat(
                GroupSlotInference.inferredSlotGroup(httpHttpsGroup(), "https", null, null, false),
                nullValue());
    }

    @Test
    void explicitContainerPropertyWins() {
        assertThat(
                GroupSlotInference.inferredSlotGroup(httpHttpsGroup(), "https", "mine", null, true),
                nullValue());
    }

    @Test
    void globalDefaultWins() {
        assertThat(
                GroupSlotInference.inferredSlotGroup(httpHttpsGroup(), "https", null, "global", true),
                nullValue());
    }

    @Test
    void standaloneContainerInfersNothing() {
        ArquillianDescriptor descriptor = Descriptors.create(ArquillianDescriptor.class);
        descriptor.container("solo");
        assertThat(
                GroupSlotInference.inferredSlotGroup(descriptor, "solo", null, null, true),
                nullValue());
    }
}
