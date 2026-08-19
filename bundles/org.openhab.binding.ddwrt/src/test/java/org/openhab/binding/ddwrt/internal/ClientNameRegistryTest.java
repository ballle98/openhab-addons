/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ddwrt.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClientNameRegistry}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class ClientNameRegistryTest {

    @Test
    void contributesAndRemovesDirectDiscoveryNames() {
        ClientNameRegistry registry = new ClientNameRegistry();
        registry.put("tplink:device", "Kitchen Lamp", "aa:bb:cc:dd:ee:ff", "192.168.1.10", "TP-Link UDP discovery");
        ClientNameResolver resolver = new ClientNameResolver();

        registry.addTo(resolver);

        assertThat(resolver.resolve("aa:bb:cc:dd:ee:ff", "").orElseThrow().name(), is("Kitchen Lamp"));
        registry.remove("tplink:device");
        assertThat(registry.size(), is(0));
    }
}
