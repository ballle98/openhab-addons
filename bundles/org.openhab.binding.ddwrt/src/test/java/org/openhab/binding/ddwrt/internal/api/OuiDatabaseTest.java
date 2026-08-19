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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OuiDatabase}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class OuiDatabaseTest {

    // ---- lookupVendor ----

    @Test
    void testLookupKnownAppleVendor() {
        assertThat(OuiDatabase.lookupVendor("00:17:f2:aa:bb:cc"), is(equalTo("Apple")));
    }

    @Test
    void testLookupKnownEspressifVendor() {
        assertThat(OuiDatabase.lookupVendor("24:0a:c4:11:22:33"), is(equalTo("Espressif")));
    }

    @Test
    void testLookupKnownAmazonVendor() {
        assertThat(OuiDatabase.lookupVendor("00:fc:8b:de:ad:00"), is(equalTo("Amazon")));
    }

    @Test
    void testUpdatedIeeeAssignments() {
        assertThat(OuiDatabase.lookupVendor("3c:6a:d2:97:99:e2"), is(equalTo("TPLink")));
        assertThat(OuiDatabase.lookupVendor("70:70:aa:cb:73:88"), is(equalTo("Amazon")));
        assertThat(OuiDatabase.lookupVendor("74:ab:93:fe:fe:9c"), is(equalTo("Blink")));
        assertThat(OuiDatabase.lookupVendor("c0:f8:53:54:c9:17"), is(equalTo("Tuya")));
        assertThat(OuiDatabase.lookupVendor("f0:2f:9e:8c:e9:9e"), is(equalTo("Amazon")));
    }

    @Test
    void testCurrentRokuAssignments() {
        assertThat(OuiDatabase.lookupVendor("60:92:c8:98:73:6e"), is(equalTo("Roku")));
        assertThat(OuiDatabase.lookupVendor("d0:4d:2c:11:22:33"), is(equalTo("Roku")));
        assertThat(OuiDatabase.lookupVendor("8a:c7:2e:11:22:33"), is(equalTo("Roku")));
    }

    @Test
    void testLookupUnknownVendor() {
        assertThat(OuiDatabase.lookupVendor("ff:ff:ff:aa:bb:cc"), is(nullValue()));
    }

    @Test
    void testLookupCaseInsensitive() {
        assertThat(OuiDatabase.lookupVendor("00:17:F2:AA:BB:CC"), is(equalTo("Apple")));
    }

    @Test
    void testLookupShortMacReturnsNull() {
        assertThat(OuiDatabase.lookupVendor("00:17"), is(nullValue()));
    }

    // ---- generateHostname ----

    @Test
    void testGenerateHostnameKnownVendor() {
        assertThat(OuiDatabase.generateHostname("e8:fc:af:a3:a0:12"), is(equalTo("TPLink-a3a012")));
    }

    @Test
    void testGenerateHostnameUnknownVendor() {
        assertThat(OuiDatabase.generateHostname("ff:ff:ff:aa:bb:cc"), is(equalTo("")));
    }

    @Test
    void testGenerateHostnameDellVendor() {
        assertThat(OuiDatabase.generateHostname("b0:8b:a8:7f:99:2c"), is(equalTo("Dell-7f992c")));
    }

    // ---- isRandomizedMac ----

    @Test
    void testRandomizedMacWithLocalBitSet() {
        // 0xc2 = 1100_0010 — bit 1 (locally administered) is set
        assertThat(OuiDatabase.isRandomizedMac("c2:af:b0:aa:9c:ef"), is(true));
    }

    @Test
    void testNonRandomizedMac() {
        // 0xb0 = 1011_0000 — bit 1 (locally administered) is NOT set
        assertThat(OuiDatabase.isRandomizedMac("b0:8b:a8:7f:99:2c"), is(false));
    }

    @Test
    void testRandomizedMacShortInput() {
        assertThat(OuiDatabase.isRandomizedMac("c"), is(false));
    }

    @Test
    void testRandomizedMacEmptyInput() {
        assertThat(OuiDatabase.isRandomizedMac(""), is(false));
    }

    @Test
    void testRandomizedMacInvalidHex() {
        assertThat(OuiDatabase.isRandomizedMac("zz:ff:ff:ff:ff:ff"), is(false));
    }

    @Test
    void testRandomizedMac0x02() {
        // 0x02 = 0000_0010 — locally administered bit set
        assertThat(OuiDatabase.isRandomizedMac("02:00:00:00:00:00"), is(true));
    }

    @Test
    void testNonRandomizedMac0x00() {
        // 0x00 = 0000_0000 — globally unique
        assertThat(OuiDatabase.isRandomizedMac("00:17:f2:aa:bb:cc"), is(false));
    }

    @Test
    void testVendorCidIsNotTreatedAsRandomized() {
        // Roku's IEEE CID has the locally-administered bit set but identifies a vendor-managed address block.
        assertThat(OuiDatabase.isRandomizedMac("8a:c7:2e:11:22:33"), is(false));
    }
}
