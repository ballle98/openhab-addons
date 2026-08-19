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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClientNameResolver}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class ClientNameResolverTest {

    @Test
    void resolvesKasaAliasByNormalizedMac() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Kitchen Lamp", Map.of("mac", "AA:BB:CC:DD:EE:FF", "ipAddress", "192.168.1.25"),
                "thing tplinksmarthome:lb130:kitchen");

        ClientNameResolver.Resolution resolution = resolver.resolve("aa-bb-cc-dd-ee-ff", "192.168.1.99").orElseThrow();

        assertThat(resolution.name(), is("Kitchen Lamp"));
        assertThat(resolution.matchType(), is(ClientNameResolver.MatchType.MAC));
    }

    @Test
    void exactMacTakesPrecedenceOverCurrentIp() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Kasa Plug", Map.of("macAddress", "aabbccddeeff"), "thing kasa");
        resolver.addIdentity("Old IP Owner", Map.of("ip", "192.168.1.25"), "inbox mdns");

        ClientNameResolver.Resolution resolution = resolver.resolve("aa:bb:cc:dd:ee:ff", "192.168.1.25").orElseThrow();

        assertThat(resolution.name(), is("Kasa Plug"));
        assertThat(resolution.matchType(), is(ClientNameResolver.MatchType.MAC));
    }

    @Test
    void stripsFireTvIpSuffix() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Living Room Fire TV (192.168.1.30)",
                Map.of("macAddress", "11:22:33:44:55:66", "ip", "192.168.1.30"), "inbox androiddebugbridge");

        ClientNameResolver.Resolution resolution = resolver.resolve("11:22:33:44:55:66", "").orElseThrow();

        assertThat(resolution.name(), is("Living Room Fire TV"));
    }

    @Test
    void extractsAliasFromTapoPresentationLabel() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Tapo HS200 Light-Switch (Jack's fan)", Map.of("macAddress", "D8-07-B6-AC-65-5A"),
                "inbox tapocontrol:HS200:bridge:D807B6AC655A", "tapocontrol");

        ClientNameResolver.Resolution resolution = resolver.resolve("d8:07:b6:ac:65:5a", "").orElseThrow();

        assertThat(resolution.name(), is("Jack's fan"));
    }

    @Test
    void preservesParenthesizedAliasFromOtherBindings() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Switch (West)", Map.of("macAddress", "D8-07-B6-AC-65-5A"), "inbox another:device:id",
                "another");

        ClientNameResolver.Resolution resolution = resolver.resolve("d8:07:b6:ac:65:5a", "").orElseThrow();

        assertThat(resolution.name(), is("Switch (West)"));
    }

    @Test
    void resolvesFriendlyNameByIpWithoutMac() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Porch Switch", Map.of("host", "wemo.local", "ipAddress", "192.168.1.35"), "inbox upnp");

        ClientNameResolver.Resolution resolution = resolver.resolve("", "192.168.1.35").orElseThrow();

        assertThat(resolution.name(), is("Porch Switch"));
        assertThat(resolution.matchType(), is(ClientNameResolver.MatchType.IP));
    }

    @Test
    void rejectsAmbiguousIpMatch() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("Current Device", Map.of("ip", "192.168.1.40"), "thing one");
        resolver.addIdentity("Stale Device", Map.of("ipAddress", "192.168.1.40"), "inbox two");

        assertThat(resolver.resolve("", "192.168.1.40").isEmpty(), is(true));
    }

    @Test
    void ignoresGenericOrUncorrelatedNames() {
        ClientNameResolver resolver = new ClientNameResolver();
        resolver.addIdentity("WeMo Device", Map.of("ip", "192.168.1.50"), "inbox wemo");
        resolver.addIdentity("Named but uncorrelated", Map.of("serialNumber", "1234"), "thing echo");

        assertThat(resolver.resolve("aa:bb:cc:dd:ee:ff", "192.168.1.50").isEmpty(), is(true));
    }
}
