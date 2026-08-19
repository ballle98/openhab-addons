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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.osgi.service.component.annotations.Component;

/**
 * Thread-safe cache of names learned directly from LAN discovery protocols.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
@Component(service = ClientNameRegistry.class)
public class ClientNameRegistry {

    private static final Duration ENTRY_TTL = Duration.ofMinutes(10);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(String sourceId, String name, String mac, String ip, String source) {
        String normalizedName = name.trim();
        String normalizedMac = ClientNameResolver.normalizeMac(mac);
        String normalizedIp = ClientNameResolver.normalizeIp(ip);
        if (normalizedName.isEmpty() || (normalizedMac.isEmpty() && normalizedIp.isEmpty())) {
            return;
        }
        entries.put(sourceId, new Entry(normalizedName, normalizedMac, normalizedIp, source, Instant.now()));
    }

    public void remove(String sourceId) {
        entries.remove(sourceId);
    }

    void addTo(ClientNameResolver resolver) {
        Instant oldestAllowed = Instant.now().minus(ENTRY_TTL);
        entries.entrySet().removeIf(entry -> entry.getValue().updated().isBefore(oldestAllowed));
        entries.values().forEach(entry -> resolver.addIdentity(entry.name(),
                Map.of("mac", entry.mac(), "ipAddress", entry.ip()), entry.source()));
    }

    int size() {
        return entries.size();
    }

    private record Entry(String name, String mac, String ip, String source, Instant updated) {
    }
}
