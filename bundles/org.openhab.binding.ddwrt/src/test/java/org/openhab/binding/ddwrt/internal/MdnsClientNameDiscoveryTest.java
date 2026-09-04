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

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MdnsClientNameDiscovery}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class MdnsClientNameDiscoveryTest {

    @Test
    void stripsRaopMacPrefix() {
        ServiceInfo info = ServiceInfo.create("_raop._tcp.local.", "AABBCCDDEEFF@Living Room Apple TV", 7000, "");

        assertThat(MdnsClientNameDiscovery.extractName(info), is("Living Room Apple TV"));
        assertThat(MdnsClientNameDiscovery.extractMac(info), is("aabbccddeeff"));
    }

    @Test
    void decodesEscapedSpaces() {
        ServiceInfo info = ServiceInfo.create("_device-info._tcp.local.", "Lees\\032iPhone", 0, "");

        assertThat(MdnsClientNameDiscovery.extractName(info), is("Lees iPhone"));
    }
}
