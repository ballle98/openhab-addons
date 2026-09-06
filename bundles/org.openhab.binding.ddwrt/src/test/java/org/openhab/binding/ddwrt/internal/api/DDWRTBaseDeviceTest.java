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
package org.openhab.binding.ddwrt.internal.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ddwrt.internal.DDWRTDeviceConfiguration;
import org.slf4j.Logger;

/**
 * Tests for {@link DDWRTBaseDevice} shared parsing behavior.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class DDWRTBaseDeviceTest {

    @Test
    void testParseStaticDhcpMacsFromDdwrtAndOpenWrtFormats() {
        String output = """
                dhcp-host=AA:BB:CC:DD:EE:01,kitchen-switch,192.168.1.20,infinite
                dhcp-host=set:iot,aa:bb:cc:dd:ee:02,living-room-plug
                aa:bb:cc:dd:ee:03,office-lamp,192.168.1.22
                dhcp-host=aa:bb:cc:dd:ee:05,192.168.1.23
                --dhcp-host=aa:bb:cc:dd:ee:06,192.168.1.24,lodge-camera
                # dhcp-host=aa:bb:cc:dd:ee:04,disabled
                """;

        Map<String, String> assignments = DDWRTBaseDevice.parseStaticDhcpAssignments(output);

        assertThat(assignments.keySet(), containsInAnyOrder("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02",
                "aa:bb:cc:dd:ee:03", "aa:bb:cc:dd:ee:05", "aa:bb:cc:dd:ee:06"));
        assertThat(assignments.get("aa:bb:cc:dd:ee:01"), is("kitchen-switch"));
        assertThat(assignments.get("aa:bb:cc:dd:ee:02"), is("living-room-plug"));
        assertThat(assignments.get("aa:bb:cc:dd:ee:03"), is("office-lamp"));
        assertThat(assignments.get("aa:bb:cc:dd:ee:05"), is(""));
        assertThat(assignments.get("aa:bb:cc:dd:ee:06"), is("lodge-camera"));
    }

    @Test
    void testParseStaticDhcpMacsIgnoresClientIdsAndMalformedAddresses() {
        String output = """
                dhcp-host=id:01:aa:bb:cc:dd:ee:ff,client-id-only
                dhcp-host=aa:bb:cc:dd:ee,too-short
                """;

        assertThat(DDWRTBaseDevice.parseStaticDhcpAssignments(output).entrySet(), empty());
    }

    @SuppressWarnings("null")
    @Test
    void testRefreshDhcpLeasesMarksDnsmasqStaticAssignment() {
        SshRunner runner = mock(SshRunner.class);
        when(runner.execStdout(startsWith("cat \"$(grep"))).thenReturn("""
                2000000000 aa:bb:cc:dd:ee:01 192.168.1.20 HS220 01:aa:bb:cc:dd:ee:01
                2000000000 aa:bb:cc:dd:ee:02 192.168.1.21 HS103 01:aa:bb:cc:dd:ee:02
                """);
        when(runner.execStdout(startsWith("{ grep -sh '^dhcp-host='"))).thenReturn("""
                dhcp-host=aa:bb:cc:dd:ee:01,kitchen-switch,192.168.1.20,infinite
                dhcp-host=aa:bb:cc:dd:ee:02,192.168.1.21
                """);
        DDWRTNetworkCache cache = new DDWRTNetworkCache();
        DDWRTClient existingClient = new DDWRTClient("aa:bb:cc:dd:ee:01");
        existingClient.setOuiHostname("kitchen-switch");
        cache.putWirelessClient(existingClient.getMac(), existingClient);
        DDWRTBroadcomDevice device = new DDWRTBroadcomDevice(new DDWRTDeviceConfiguration(), mock(Logger.class));
        device.networkCache = cache;

        device.refreshDhcpLeases(runner);

        verify(runner).execStdout(contains("pidof dnsmasq"));

        DDWRTDhcpLease lease = Objects.requireNonNull(cache.getDhcpLease("aa:bb:cc:dd:ee:01"));
        DDWRTClient client = Objects.requireNonNull(cache.getWirelessClient("aa:bb:cc:dd:ee:01"));
        assertThat(lease.hasStaticHostname(), is(true));
        assertThat(client.getHostnameSource(), is(DDWRTClient.HostnameSource.STATIC_DHCP));
        assertThat(client.isHostnameAuthoritative(), is(true));
        assertThat(client.getHostname(), is("kitchen-switch"));

        DDWRTDhcpLease addressOnlyLease = Objects.requireNonNull(cache.getDhcpLease("aa:bb:cc:dd:ee:02"));
        DDWRTClient dynamicClient = Objects.requireNonNull(cache.getWirelessClient("aa:bb:cc:dd:ee:02"));
        assertThat(addressOnlyLease.hasStaticHostname(), is(false));
        assertThat(dynamicClient.getHostnameSource(), is(DDWRTClient.HostnameSource.DHCP));
        assertThat(dynamicClient.isHostnameAuthoritative(), is(false));
        assertThat(dynamicClient.getHostname(), is("HS103"));
    }
}
