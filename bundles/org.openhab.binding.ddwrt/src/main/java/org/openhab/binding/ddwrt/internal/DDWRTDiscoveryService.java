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

import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.*;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ddwrt.internal.api.DDWRTBaseDevice;
import org.openhab.binding.ddwrt.internal.api.DDWRTClient;
import org.openhab.binding.ddwrt.internal.api.DDWRTNetwork;
import org.openhab.binding.ddwrt.internal.api.DDWRTNetworkCache;
import org.openhab.binding.ddwrt.internal.api.RefreshListener;
import org.openhab.binding.ddwrt.internal.handler.DDWRTNetworkBridgeHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.inbox.Inbox;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DDWRTDiscoveryService} is the discovery service for detecting things in DD-WRT network.
 *
 * @author Lee Ballard - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = DDWRTDiscoveryService.class)
@NonNullByDefault
public class DDWRTDiscoveryService extends AbstractThingHandlerDiscoveryService<DDWRTNetworkBridgeHandler>
        implements RefreshListener {

    private static final int DISCOVERY_TIMEOUT_SECONDS = 120;
    private static final int BACKGROUND_DISCOVERY_INITIAL_DELAY_SECONDS = 10;
    private static final int REFRESH_DEBOUNCE_SECONDS = 2;

    private final Logger logger = LoggerFactory.getLogger(DDWRTDiscoveryService.class);

    private @Nullable ScheduledFuture<?> backgroundDiscoveryJob;
    private @Nullable ScheduledFuture<?> refreshTriggeredScan;

    @Reference
    private @Nullable Inbox inbox;

    @Reference
    private @Nullable ThingRegistry thingRegistry;

    @Reference
    private @Nullable ClientNameRegistry clientNameRegistry;

    public DDWRTDiscoveryService() {
        super(DDWRTNetworkBridgeHandler.class, SUPPORTED_THING_TYPES_UIDS, DISCOVERY_TIMEOUT_SECONDS);
    }

    @Override
    protected void startScan() {
        try {
            logger.debug("Starting DD-WRT discovery scan");

            final DDWRTNetwork net = thingHandler.getNetwork();
            if (net == null) {
                return;
            }
            discoverDevices(net);
            discoverRadios(net);
            discoverClients(net);
            discoverFirewallRules(net);
        } catch (RuntimeException e) {
            logger.warn("Error during DD-WRT discovery scan: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Starting DD-WRT background discovery");
        ScheduledFuture<?> job = backgroundDiscoveryJob;
        if (job == null || job.isCancelled()) {
            backgroundDiscoveryJob = scheduler.scheduleWithFixedDelay(this::startScan,
                    BACKGROUND_DISCOVERY_INITIAL_DELAY_SECONDS, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        // Register as refresh listener so discovery runs immediately after device refresh
        DDWRTNetwork net = thingHandler.getNetwork();
        if (net != null) {
            net.addRefreshListener(this);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        logger.debug("Stopping DD-WRT background discovery");
        ScheduledFuture<?> job = backgroundDiscoveryJob;
        if (job != null) {
            job.cancel(true);
            backgroundDiscoveryJob = null;
        }
        ScheduledFuture<?> pending = refreshTriggeredScan;
        if (pending != null) {
            pending.cancel(false);
            refreshTriggeredScan = null;
        }
        // Unregister refresh listener
        DDWRTNetwork net = thingHandler.getNetwork();
        if (net != null) {
            net.removeRefreshListener(this);
        }
    }

    @Override
    public void onRefreshComplete(DDWRTBaseDevice device) {
        // Debounce: schedule a scan 2s from now, replacing any pending scan.
        // This coalesces rapid refresh events (e.g., multiple devices refreshing)
        // into a single discovery scan.
        ScheduledFuture<?> pending = refreshTriggeredScan;
        if (pending != null) {
            pending.cancel(false);
        }
        refreshTriggeredScan = scheduler.schedule((Runnable) this::startScan, REFRESH_DEBOUNCE_SECONDS,
                TimeUnit.SECONDS);
    }

    private void discoverDevices(DDWRTNetwork net) {
        final ThingUID bridgeUID = thingHandler.getThing().getUID();
        final DDWRTNetworkConfiguration netCfg = net.getConfig();
        if (netCfg == null) {
            logger.warn("No configuration available for discovery.");
            return;
        }

        net.getDevices().forEach(device -> {
            final DDWRTDeviceConfiguration devCfg = device.getConfig();
            final String label = device.getHostname().isEmpty() ? device.getMac() : device.getHostname();
            final String macClean = device.getMac().toLowerCase(Locale.ROOT).replace(":", "");

            final ThingUID thingUID = new ThingUID(THING_TYPE_DEVICE, bridgeUID,
                    macClean.isEmpty() ? devCfg.hostname : macClean);

            logger.debug("Discovered device: '{}'", thingUID);

            final Map<String, Object> props = Map.of(HOSTNAME, devCfg.hostname, PORT, devCfg.port, USER, devCfg.user,
                    REFRESH_INTERVAL, devCfg.refreshInterval);

            final DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withLabel(label).withProperties(props).withRepresentationProperty("hostname").build();

            thingDiscovered(result);
        });
    }

    private void discoverRadios(DDWRTNetwork net) {
        final ThingUID bridgeUID = thingHandler.getThing().getUID();
        final DDWRTNetworkCache cache = net.getCache();

        cache.getRadios().forEach(radio -> {
            // Get parent device for hostname
            DDWRTBaseDevice device = cache.getDevice(radio.getParentDeviceMac());
            final String hostname = device != null && !device.getHostname().isEmpty() ? device.getHostname()
                    : radio.getParentDeviceMac();

            final ThingUID thingUID = createRadioThingUID(bridgeUID, radio.getParentDeviceMac(), radio.getIfaceName());

            // Label: hostname interface (e.g., "gateway-ap wlan0")
            final String label = hostname + " " + radio.getIfaceName();

            logger.debug("Discovered radio: '{}'", thingUID);

            final Map<String, Object> props = Map.of(INTERFACE_ID, radio.getInterfaceId(), PARENT_DEVICE_MAC,
                    radio.getParentDeviceMac(), "ifaceName", radio.getIfaceName());

            final DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withLabel(label).withProperties(props).withRepresentationProperty(INTERFACE_ID).build();

            thingDiscovered(result);
        });
    }

    static ThingUID createRadioThingUID(ThingUID bridgeUID, String parentDeviceMac, String ifaceName) {
        // Thing UID segments cannot contain dots used by virtual interfaces such as wlan0.1.
        String id = (parentDeviceMac.replace(":", "-") + "-" + ifaceName).replaceAll("[^\\w-]", "-");
        return new ThingUID(THING_TYPE_RADIO, bridgeUID, id);
    }

    private void discoverClients(DDWRTNetwork net) {
        final ThingUID bridgeUID = thingHandler.getThing().getUID();
        final DDWRTNetworkCache cache = net.getCache();
        final ClientNameResolver nameResolver = createClientNameResolver();

        for (DDWRTClient originalClient : cache.getWirelessClients()) {
            DDWRTClient client = enrichClientName(cache, originalClient, nameResolver);
            String label = client.getHostname();
            if (label.isEmpty()) {
                // Skip clients without a hostname — hostname is required for client things
                logger.debug("Skipping client without hostname: MAC={}", client.getMac());
                continue;
            }
            String hostname = toHostname(label);
            if (hostname.isEmpty()) {
                hostname = "client-" + client.getMac().replace(":", "");
            }
            final String thingId = hostname.replace("-", "");
            final ThingUID thingUID = new ThingUID(THING_TYPE_CLIENT, bridgeUID, thingId);

            logger.debug("Discovered client: '{}'", thingUID);

            final Map<String, Object> props = new HashMap<>();
            props.put(HOSTNAME, hostname);
            props.put(MAC, client.getMac());
            if (!client.getIpAddress().isEmpty()) {
                props.put("ipAddress", client.getIpAddress());
            }
            if (!client.getDiscoveredHostnameSource().isEmpty()) {
                props.put("hostnameSource", client.getDiscoveredHostnameSource());
            }

            final DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withLabel(label).withProperties(props)
                    // Keep hostname as the representation property. Some clients use
                    // MAC randomization, so the MAC is not stable enough to be the primary
                    // representation key in the inbox/UI.
                    .withRepresentationProperty(HOSTNAME).build();

            logger.debug(
                    "Submitting discovery result for client: {} ({}) - AP: {}, SSID: {}, Channel: {}, Signal: {}dBm, SNR: {}",
                    thingUID, client.getHostname(), client.getApMac(), client.getSsid(), client.getChannel(),
                    client.getSignalDbm(), client.getSnr());

            // If this client is already sitting in the discovery inbox under an
            // older placeholder hostname (for example an OUI-generated TPLink-e916b1) or an
            // earlier MAC, replace the pending inbox entry before submitting the new result.
            // Matching is intentionally by hostname OR MAC. Hostname remains the preferred
            // representation property because MAC randomization can cause the MAC to change
            // for some devices.
            replacePendingClientInboxDuplicates(thingUID, hostname, client.getMac());

            thingDiscovered(result);
        }
    }

    private ClientNameResolver createClientNameResolver() {
        ClientNameResolver resolver = new ClientNameResolver();
        ClientNameRegistry nameRegistryRef = clientNameRegistry;
        if (nameRegistryRef != null) {
            nameRegistryRef.addTo(resolver);
        }
        ThingRegistry registryRef = thingRegistry;
        if (registryRef != null) {
            registryRef.getAll().forEach(resolver::addThing);
        }
        Inbox inboxRef = inbox;
        if (inboxRef != null) {
            inboxRef.getAll().forEach(resolver::addDiscoveryResult);
        }
        return resolver;
    }

    private DDWRTClient enrichClientName(DDWRTNetworkCache cache, DDWRTClient client, ClientNameResolver nameResolver) {
        // Preserve administrator-assigned names while allowing trusted local discovery to replace generic dynamic
        // DHCP names such as model numbers.
        Optional<ClientNameResolver.Resolution> resolution = Optional.empty();
        if (!client.isHostnameAuthoritative()) {
            String arpIp = Objects.requireNonNullElse(cache.getArpIp(client.getMac()), "");
            String verifiedIp = arpIp.equals(client.getIpAddress()) ? arpIp : "";
            resolution = nameResolver.resolve(client.getMac(), verifiedIp);
        }
        String discoveredName = Objects.requireNonNull(resolution.map(ClientNameResolver.Resolution::name).orElse(""));
        String source = Objects.requireNonNull(resolution
                .map(candidate -> candidate.source() + " via " + candidate.matchType().name().toLowerCase(Locale.ROOT))
                .orElse(""));

        if (discoveredName.equals(client.getDiscoveredHostname())
                && source.equals(client.getDiscoveredHostnameSource())) {
            return client;
        }

        DDWRTClient updated = cache.computeWirelessClient(client.getMac(), current -> {
            current.setDiscoveredHostname(discoveredName, source);
            return current;
        });
        if (!discoveredName.isEmpty()) {
            logger.debug("Resolved client {} as '{}' from {}", client.getMac(), discoveredName, source);
        }
        return updated;
    }

    private void replacePendingClientInboxDuplicates(ThingUID newThingUID, String hostname, String mac) {
        String normalizedHostname = normalizeHostname(hostname);
        String normalizedMac = normalizeMac(mac);

        if (normalizedHostname.isEmpty() && normalizedMac.isEmpty()) {
            return;
        }

        Inbox inboxRef = inbox;
        if (inboxRef == null) {
            return;
        }
        Collection<DiscoveryResult> entries = inboxRef.getAll();
        if (entries.isEmpty()) {
            return;
        }

        for (DiscoveryResult entry : new ArrayList<>(entries)) {
            ThingUID existingUID = entry.getThingUID();
            if (existingUID.equals(newThingUID)) {
                continue;
            }

            // Only operate on pending client inbox entries
            if (!THING_TYPE_CLIENT.equals(entry.getThingTypeUID())) {
                continue;
            }

            // Only operate on entries that belong to the same bridge
            ThingUID existingBridgeUID = entry.getBridgeUID();
            ThingUID newBridgeUID = thingHandler.getThing().getUID();
            if (existingBridgeUID == null || !existingBridgeUID.equals(newBridgeUID)) {
                continue;
            }

            String existingHostname = normalizeHostname(String.valueOf(entry.getProperties().get(HOSTNAME)));
            String existingMac = normalizeMac(String.valueOf(entry.getProperties().get(MAC)));

            boolean sameHostname = !normalizedHostname.isEmpty() && normalizedHostname.equals(existingHostname);
            boolean sameMac = !normalizedMac.isEmpty() && normalizedMac.equals(existingMac);

            if (sameHostname || sameMac) {
                logger.debug(
                        "Removing stale client inbox entry {} before adding replacement {} (hostname match={}, mac match={})",
                        existingUID, newThingUID, sameHostname, sameMac);
                inboxRef.remove(existingUID);
            }
        }
    }

    private static String normalizeHostname(String hostname) {
        String trimmed = hostname.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return toHostname(trimmed);
    }

    static String toHostname(String value) {
        String hostname = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("['’]", "").replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (hostname.length() > 63) {
            hostname = hostname.substring(0, 63).replaceFirst("-+$", "");
        }
        return hostname;
    }

    private static String normalizeMac(String mac) {
        String trimmed = mac.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private void discoverFirewallRules(DDWRTNetwork net) {
        final ThingUID bridgeUID = thingHandler.getThing().getUID();
        final DDWRTNetworkCache cache = net.getCache();

        cache.getFirewallRules().forEach(rule -> {
            final String id = rule.getRuleId().replaceAll("[^a-zA-Z0-9_]", "_");
            final ThingUID thingUID = new ThingUID(THING_TYPE_FIREWALL_RULE, bridgeUID, id);

            final String label = rule.getDescription().isEmpty() ? rule.getRuleId() : rule.getDescription();

            logger.debug("Discovered firewall rule: '{}'", thingUID);

            final Map<String, Object> props = Map.of(RULE_ID, rule.getRuleId());

            final DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID)
                    .withLabel(label).withProperties(props).withRepresentationProperty(RULE_ID).build();

            thingDiscovered(result);
        });
    }
}
