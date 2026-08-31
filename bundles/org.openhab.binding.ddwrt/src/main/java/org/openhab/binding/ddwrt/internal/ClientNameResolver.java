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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.Thing;

/**
 * Resolves client names from metadata published by other openHAB Things and discovery results.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
final class ClientNameResolver {

    private static final Set<String> MAC_KEYS = Set.of("mac", "macaddress", "mac-address", "mac_address");
    private static final Set<String> IP_KEYS = Set.of("ip", "ipaddress", "ip-address", "ip_address", "host");
    private static final Set<String> TAPO_ALIAS_KEYS = Set.of("alias", "nickname");
    private static final Set<String> GENERIC_NAMES = Set.of("device", "unknown", "unknown device", "wemo device");
    private static final String TAPO_BINDING_ID = "tapocontrol";

    private final Map<String, Set<NameCandidate>> namesByMac = new HashMap<>();
    private final Map<String, Set<NameCandidate>> namesByIp = new HashMap<>();

    record Resolution(String name, String source, MatchType matchType) {
    }

    enum MatchType {
        MAC,
        IP
    }

    void addThing(Thing thing) {
        if (DDWRTBindingConstants.THING_TYPE_CLIENT.getBindingId().equals(thing.getThingTypeUID().getBindingId())) {
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>(thing.getConfiguration().getProperties());
        properties.putAll(thing.getProperties());
        addIdentity(thing.getLabel(), properties, "thing " + thing.getUID(), thing.getThingTypeUID().getBindingId());
    }

    void addDiscoveryResult(DiscoveryResult result) {
        if (DDWRTBindingConstants.THING_TYPE_CLIENT.getBindingId().equals(result.getThingTypeUID().getBindingId())) {
            return;
        }
        addIdentity(result.getLabel(), result.getProperties(), "inbox " + result.getThingUID(),
                result.getThingTypeUID().getBindingId());
    }

    void addIdentity(@Nullable String label, Map<String, ?> properties, String source) {
        addIdentity(label, properties, source, "");
    }

    void addIdentity(@Nullable String label, Map<String, ?> properties, String source, String bindingId) {
        String mac = findNormalizedProperty(properties, MAC_KEYS, ClientNameResolver::normalizeMac);
        String ip = findNormalizedProperty(properties, IP_KEYS, ClientNameResolver::normalizeIp);
        String name = normalizeName(label, properties, ip, mac, bindingId);
        if (name.isEmpty()) {
            return;
        }

        NameCandidate candidate = new NameCandidate(name, source);
        if (!mac.isEmpty()) {
            Objects.requireNonNull(namesByMac.computeIfAbsent(mac, ignored -> new HashSet<>())).add(candidate);
        }
        if (!ip.isEmpty()) {
            Objects.requireNonNull(namesByIp.computeIfAbsent(ip, ignored -> new HashSet<>())).add(candidate);
        }
    }

    Optional<Resolution> resolve(String mac, String ip) {
        Optional<NameCandidate> macCandidate = uniqueCandidate(namesByMac.get(normalizeMac(mac)));
        if (macCandidate.isPresent()) {
            NameCandidate candidate = macCandidate.get();
            return Optional.of(new Resolution(candidate.name(), candidate.source(), MatchType.MAC));
        }

        Optional<NameCandidate> ipCandidate = uniqueCandidate(namesByIp.get(normalizeIp(ip)));
        if (ipCandidate.isPresent()) {
            NameCandidate candidate = ipCandidate.get();
            return Optional.of(new Resolution(candidate.name(), candidate.source(), MatchType.IP));
        }
        return Optional.empty();
    }

    private static String findNormalizedProperty(Map<String, ?> properties, Set<String> keys,
            Function<String, String> normalizer) {
        for (Map.Entry<String, ?> entry : properties.entrySet()) {
            if (keys.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                Object value = entry.getValue();
                if (value != null) {
                    String normalized = Objects.requireNonNull(normalizer.apply(value.toString()));
                    if (!normalized.isEmpty()) {
                        return normalized;
                    }
                }
            }
        }
        return "";
    }

    private static Optional<NameCandidate> uniqueCandidate(@Nullable Set<NameCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        Map<String, NameCandidate> candidatesByName = new HashMap<>();
        for (NameCandidate candidate : candidates) {
            candidatesByName.putIfAbsent(candidate.name().toLowerCase(Locale.ROOT), candidate);
        }
        return candidatesByName.size() == 1 ? Optional.of(candidatesByName.values().iterator().next())
                : Optional.empty();
    }

    private static String normalizeName(@Nullable String label, Map<String, ?> properties, String ip, String mac,
            String bindingId) {
        if (label == null) {
            return "";
        }

        String name = label.trim();
        if (TAPO_BINDING_ID.equals(bindingId)) {
            String alias = findNormalizedProperty(properties, TAPO_ALIAS_KEYS, String::trim);
            name = name.startsWith("Tapo ") && !alias.isEmpty() ? alias : unwrapTapoAlias(name);
        }
        if (!ip.isEmpty() && name.endsWith(" (" + ip + ")")) {
            name = name.substring(0, name.length() - ip.length() - 3).trim();
        }
        if (name.isEmpty() || GENERIC_NAMES.contains(name.toLowerCase(Locale.ROOT)) || name.equals(ip)
                || (!mac.isEmpty() && normalizeMac(name).equals(mac))) {
            return "";
        }
        return name;
    }

    /**
     * Tapo Control may present labels as "Tapo &lt;model&gt; &lt;type&gt; (&lt;alias&gt;)". Extract the alias when no
     * explicit
     * alias property is available.
     */
    private static String unwrapTapoAlias(String label) {
        int aliasStart = label.lastIndexOf(" (");
        if (aliasStart > 0 && label.endsWith(")")) {
            String alias = label.substring(aliasStart + 2, label.length() - 1).trim();
            if (!alias.isEmpty()) {
                return alias;
            }
        }
        return label;
    }

    static String normalizeMac(String mac) {
        String normalized = mac.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        return normalized.length() == 12 ? normalized : "";
    }

    static String normalizeIp(String ip) {
        String normalized = ip.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return "";
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return "";
                }
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return normalized;
    }

    private record NameCandidate(String name, String source) {
    }
}
