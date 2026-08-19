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
import static org.hamcrest.Matchers.nullValue;

import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TPLinkClientNameDiscovery}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class TPLinkClientNameDiscoveryTest {

    @Test
    void decryptsEncryptedPayload() {
        String payload = "{\"system\":{\"get_sysinfo\":{}}}";

        assertThat(TPLinkClientNameDiscovery.decrypt(TPLinkClientNameDiscovery.encrypt(payload), payload.length()),
                is(payload));
    }

    @Test
    void parsesAliasMacAndPacketAddress() {
        String payload = "{\"system\":{\"get_sysinfo\":{\"alias\":\"Kitchen Lamp\","
                + "\"mac\":\"AA:BB:CC:DD:EE:FF\"}}}";
        byte[] response = TPLinkClientNameDiscovery.encrypt(payload);

        TPLinkClientNameDiscovery.Identity identity = Objects
                .requireNonNull(TPLinkClientNameDiscovery.parseResponse(response, response.length, "192.168.1.10"));

        assertThat(identity.name(), is("Kitchen Lamp"));
        assertThat(identity.mac(), is("AA:BB:CC:DD:EE:FF"));
        assertThat(identity.ip(), is("192.168.1.10"));
    }

    @Test
    void ignoresResponseWithoutAlias() {
        String payload = "{\"system\":{\"get_sysinfo\":{\"mac\":\"AA:BB:CC:DD:EE:FF\"}}}";
        byte[] response = TPLinkClientNameDiscovery.encrypt(payload);

        assertThat(TPLinkClientNameDiscovery.parseResponse(response, response.length, "192.168.1.10"), is(nullValue()));
    }

    @Test
    void ignoresMalformedResponse() {
        byte[] response = TPLinkClientNameDiscovery.encrypt("not JSON");

        assertThat(TPLinkClientNameDiscovery.parseResponse(response, response.length, "192.168.1.10"), is(nullValue()));
    }
}
