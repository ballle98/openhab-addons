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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.io.transport.mdns.MDNSClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects client names and MAC addresses from selected mDNS advertisements.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true)
public class MdnsClientNameDiscovery implements ServiceListener {

    static final Set<String> SERVICE_TYPES = Set.of("_device-info._tcp.local.", "_apple-mobdev2._tcp.local.",
            "_companion-link._tcp.local.", "_airplay._tcp.local.", "_raop._tcp.local.");

    private final Logger logger = LoggerFactory.getLogger(MdnsClientNameDiscovery.class);
    private final ClientNameRegistry nameRegistry;
    private final MDNSClient mdnsClient;
    private final ScheduledExecutorService scheduler = ThreadPoolManager
            .getScheduledPool(MdnsClientNameDiscovery.class.getName());

    private @Nullable ScheduledFuture<?> refreshJob;

    @Activate
    public MdnsClientNameDiscovery(@Reference ClientNameRegistry nameRegistry, @Reference MDNSClient mdnsClient) {
        this.nameRegistry = nameRegistry;
        this.mdnsClient = mdnsClient;
        for (String serviceType : SERVICE_TYPES) {
            mdnsClient.addServiceListener(serviceType, this);
        }
        refreshJob = scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, 5, TimeUnit.MINUTES);
    }

    @Deactivate
    protected void deactivate() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(true);
            refreshJob = null;
        }
        SERVICE_TYPES.forEach(type -> mdnsClient.removeServiceListener(type, this));
    }

    private void refreshSafely() {
        try {
            for (String serviceType : SERVICE_TYPES) {
                for (ServiceInfo info : mdnsClient.list(serviceType)) {
                    update(info);
                }
            }
        } catch (RuntimeException e) {
            logger.debug("mDNS client-name refresh failed: {}", e.getMessage());
        }
    }

    @Override
    public void serviceAdded(@Nullable ServiceEvent event) {
        // JmDNS follows this with serviceResolved once address and TXT data are available.
    }

    @Override
    public void serviceResolved(@Nullable ServiceEvent event) {
        if (event != null) {
            update(event.getInfo());
        }
    }

    private void update(@Nullable ServiceInfo info) {
        if (info == null) {
            return;
        }
        String name = extractName(info);
        if (name.isEmpty()) {
            return;
        }
        String mac = extractMac(info);
        for (InetAddress address : info.getInetAddresses()) {
            if (address instanceof Inet4Address) {
                String ip = address.getHostAddress();
                nameRegistry.put(sourceId(info, ip), name, mac, ip, "mDNS discovery");
                logger.debug("Learned mDNS client name '{}' for {} from {}", name, ip, info.getType());
            }
        }
    }

    @Override
    public void serviceRemoved(@Nullable ServiceEvent event) {
        if (event == null) {
            return;
        }
        ServiceInfo info = event.getInfo();
        if (info == null) {
            return;
        }
        for (InetAddress address : info.getInetAddresses()) {
            if (address instanceof Inet4Address) {
                nameRegistry.remove(sourceId(info, address.getHostAddress()));
            }
        }
    }

    static String extractName(ServiceInfo info) {
        String name = clean(info.getName());
        int separator = name.indexOf('@');
        if (separator > 0 && name.substring(0, separator).matches("(?i)[0-9a-f]{12}")) {
            name = name.substring(separator + 1).trim();
        }
        if (name.isEmpty() || name.matches("(?i)[0-9a-f-]{16,}")) {
            name = cleanServer(info.getServer());
        }
        return name;
    }

    static String extractMac(ServiceInfo info) {
        String instanceName = clean(info.getName());
        int separator = instanceName.indexOf('@');
        if (separator > 0) {
            String mac = ClientNameResolver.normalizeMac(instanceName.substring(0, separator));
            if (!mac.isEmpty()) {
                return mac;
            }
        }
        for (String property : Set.of("mac", "macAddress", "deviceid", "deviceId")) {
            String value = info.getPropertyString(property);
            if (value != null) {
                String mac = ClientNameResolver.normalizeMac(value);
                if (!mac.isEmpty()) {
                    return mac;
                }
            }
        }
        return "";
    }

    private static String cleanServer(String server) {
        String result = clean(server);
        if (result.toLowerCase(Locale.ROOT).endsWith(".local.")) {
            result = result.substring(0, result.length() - 7);
        } else if (result.toLowerCase(Locale.ROOT).endsWith(".local")) {
            result = result.substring(0, result.length() - 6);
        }
        return result;
    }

    private static String clean(String value) {
        return value.replace("\\032", " ").trim();
    }

    private static String sourceId(ServiceInfo info, String ip) {
        return "mdns:" + info.getType() + ":" + info.getName() + ":" + ip;
    }
}
