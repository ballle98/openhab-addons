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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.jupnp.model.ValidationException;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.DeviceType;
import org.jupnp.model.types.UDN;

/**
 * Tests for {@link WemoClientNameDiscovery}.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
class WemoClientNameDiscoveryTest {

    @Test
    void contributesBelkinFriendlyNameByDescriptorAddress() throws ValidationException, MalformedURLException {
        ClientNameRegistry registry = new ClientNameRegistry();
        WemoClientNameDiscovery discovery = new WemoClientNameDiscovery(registry);
        RemoteDevice device = createDevice("Belkin International", "Porch Switch", "192.168.1.35");

        assertThat(discovery.createResult(device), is(nullValue()));

        ClientNameResolver resolver = new ClientNameResolver();
        registry.addTo(resolver);
        assertThat(resolver.resolve("", "192.168.1.35").orElseThrow().name(), is("Porch Switch"));
    }

    @Test
    void ignoresNonBelkinDevice() throws ValidationException, MalformedURLException {
        ClientNameRegistry registry = new ClientNameRegistry();
        WemoClientNameDiscovery discovery = new WemoClientNameDiscovery(registry);

        discovery.createResult(createDevice("Other", "Unrelated Device", "192.168.1.36"));

        assertThat(registry.size(), is(0));
    }

    private static RemoteDevice createDevice(String manufacturer, String name, String ip)
            throws ValidationException, MalformedURLException {
        URL descriptorUrl = URI.create("http://" + ip + "/setup.xml").toURL();
        RemoteDeviceIdentity identity = new RemoteDeviceIdentity(new UDN("wemo-" + ip), 60, descriptorUrl, null, null);
        DeviceDetails details = new DeviceDetails(descriptorUrl, name, new ManufacturerDetails(manufacturer),
                new ModelDetails("Socket"), "serial", "upc", null);
        return new RemoteDevice(identity, new DeviceType("Belkin", "controllee"), details, (RemoteService) null);
    }
}
