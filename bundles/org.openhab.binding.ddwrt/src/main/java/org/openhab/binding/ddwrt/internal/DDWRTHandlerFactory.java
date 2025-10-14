/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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

import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.BRIDGE_TYPE_NETWORK;
import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.HOSTNAME;
import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.SUPPORTED_THING_TYPES_UIDS;
import static org.openhab.binding.ddwrt.internal.DDWRTBindingConstants.THING_TYPE_DEVICE;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ddwrt.internal.handler.DDWRTDeviceThingHandler;
import org.openhab.binding.ddwrt.internal.handler.DDWRTNetworkBridgeHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DDWRTHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.ddwrt", service = ThingHandlerFactory.class)
public class DDWRTHandlerFactory extends BaseThingHandlerFactory {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (BRIDGE_TYPE_NETWORK.equals(thingTypeUID)) {
            return new DDWRTNetworkBridgeHandler((Bridge) thing);
        } else if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new DDWRTDeviceThingHandler(thing);
        }

        return null;
    }
   
    @Override
    public @Nullable Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration,
                             @Nullable ThingUID thingUID, @Nullable ThingUID bridgeUID) {
        // Device: derive UID from hostname and set friendly label/properties
        
        if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            String hostname = configuration.get(HOSTNAME).toString().trim();
            if (!hostname.isBlank()) {
                String id = toThingId(hostname);
                ThingUID derived =
                        (bridgeUID != null)
                                ? new ThingUID(THING_TYPE_DEVICE, bridgeUID, id)
                                : new ThingUID(THING_TYPE_DEVICE, id);
                if (thingUID == null) {
                    thingUID = derived;
                }
            } else {
                logger.debug("Device created without hostname in configuration; using provided UID");
            }
            Thing t = super.createThing(thingTypeUID, configuration, thingUID, bridgeUID);
            // Label the device with its hostname for UI friendliness and persist as a property
            if (t != null && !hostname.isBlank()) {
                try {
                    t.setLabel(hostname);
                    Map<String, String> props = new HashMap<>(t.getProperties());
                    props.put("hostname", hostname);
                    t.setProperties(props);
                } catch (Exception e) {
                    logger.debug("Could not set device label/properties: {}", e.getMessage(), e);
                }
            }
            return t;
        }

        // Bridge: assign a friendly default label if none was provided
        if (BRIDGE_TYPE_NETWORK.equals(thingTypeUID)) {
            Thing t = super.createThing(thingTypeUID, configuration, thingUID, bridgeUID);
            try {
                if (t != null) {
                    String label = t.getLabel();
                    if (label == null || label.isBlank()) {
                        t.setLabel("DD-WRT Network Bridge");
                    }
                }
            } catch (Exception ignore) { }
            return t;
        }

        return super.createThing(thingTypeUID, configuration, thingUID, bridgeUID);
    }


    private static String toThingId(String hostname) {
        // Normalize hostname into a valid thing-id segment
        String s = hostname.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("[^a-z0-9_\\-]", "-");
        s = s.replaceAll("-{2,}", "-"); // collapse multiple dashes
        if (s.isEmpty()) s = "device";
        return s;
    }
}
