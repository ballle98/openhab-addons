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

import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.THING_TYPE_CLIENT;

import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.UpnpService;
import org.jupnp.model.meta.RemoteDevice;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects WeMo friendly names from openHAB's shared UPnP discovery service.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
@Component(service = UpnpDiscoveryParticipant.class)
public class WemoClientNameDiscovery implements UpnpDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(WemoClientNameDiscovery.class);
    private final ClientNameRegistry nameRegistry;
    private final @Nullable UpnpService upnpService;
    private final ScheduledExecutorService scheduler = ThreadPoolManager
            .getScheduledPool(WemoClientNameDiscovery.class.getName());

    private @Nullable ScheduledFuture<?> refreshJob;

    @Activate
    public WemoClientNameDiscovery(@Reference ClientNameRegistry nameRegistry, @Reference UpnpService upnpService) {
        this.nameRegistry = nameRegistry;
        this.upnpService = upnpService;
        refreshJob = scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, 5, TimeUnit.MINUTES);
    }

    WemoClientNameDiscovery(ClientNameRegistry nameRegistry) {
        this.nameRegistry = nameRegistry;
        upnpService = null;
    }

    @Deactivate
    protected void deactivate() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(true);
            refreshJob = null;
        }
    }

    private void refreshSafely() {
        UpnpService service = upnpService;
        if (service == null) {
            return;
        }
        try {
            for (RemoteDevice device : service.getRegistry().getRemoteDevices()) {
                recordDevice(device);
            }
        } catch (RuntimeException e) {
            logger.debug("WeMo client-name refresh failed: {}", e.getMessage());
        }
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Set.of(THING_TYPE_CLIENT);
    }

    @Override
    public @Nullable DiscoveryResult createResult(RemoteDevice device) {
        recordDevice(device);
        // The DD-WRT scan creates the client Thing after associating the IP with a router.
        return null;
    }

    private void recordDevice(RemoteDevice device) {
        try {
            String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
            if (manufacturer == null || !manufacturer.toLowerCase(Locale.ROOT).contains("belkin")) {
                return;
            }
            String name = device.getDetails().getFriendlyName();
            URL descriptor = device.getIdentity().getDescriptorURL();
            String ip = descriptor.getHost();
            String udn = device.getIdentity().getUdn().getIdentifierString();
            nameRegistry.put("wemo:" + udn, name, "", ip, "WeMo UPnP discovery");
            logger.debug("Learned WeMo client name '{}' for {}", name, ip);
        } catch (RuntimeException e) {
            logger.debug("Could not read WeMo UPnP identity: {}", e.getMessage());
        }
    }

    @Override
    public @Nullable ThingUID getThingUID(RemoteDevice device) {
        return null;
    }
}
