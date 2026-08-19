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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Discovers aliases of legacy TP-Link/Kasa devices using their UDP broadcast protocol.
 *
 * @author Lee Ballard - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true)
public class TPLinkClientNameDiscovery {

    private static final int PORT = 9999;
    private static final int TIMEOUT_MILLIS = 4000;
    private static final String GET_SYSINFO = "{\"system\":{\"get_sysinfo\":{}}}";
    private static final byte INITIAL_KEY = (byte) 0xAB;

    private final Logger logger = LoggerFactory.getLogger(TPLinkClientNameDiscovery.class);
    private final ClientNameRegistry registry;
    private final ScheduledExecutorService scheduler = ThreadPoolManager
            .getScheduledPool(TPLinkClientNameDiscovery.class.getName());

    private volatile @Nullable DatagramSocket socket;
    private @Nullable ScheduledFuture<?> discoveryJob;

    @Activate
    public TPLinkClientNameDiscovery(@Reference ClientNameRegistry registry) {
        this.registry = registry;
        discoveryJob = scheduler.scheduleWithFixedDelay(this::scanSafely, 0, 1, TimeUnit.MINUTES);
    }

    @Deactivate
    protected void deactivate() {
        ScheduledFuture<?> job = discoveryJob;
        if (job != null) {
            job.cancel(true);
            discoveryJob = null;
        }
        closeSocket();
    }

    private void scanSafely() {
        try {
            scan();
        } catch (IOException | RuntimeException e) {
            logger.debug("TP-Link client-name discovery failed: {}", e.getMessage());
        }
    }

    private void scan() throws IOException {
        byte[] request = encrypt(GET_SYSINFO);
        InetAddress broadcast = InetAddress.getByName("255.255.255.255");
        try (DatagramSocket scanSocket = new DatagramSocket(null)) {
            socket = scanSocket;
            scanSocket.setReuseAddress(true);
            scanSocket.setBroadcast(true);
            scanSocket.setSoTimeout(TIMEOUT_MILLIS);
            scanSocket.bind(new InetSocketAddress(0));
            scanSocket.send(new DatagramPacket(request, request.length, broadcast, PORT));

            while (!scanSocket.isClosed()) {
                byte[] buffer = new byte[4096];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    scanSocket.receive(response);
                    Identity identity = parseResponse(response.getData(), response.getLength(),
                            response.getAddress().getHostAddress());
                    if (identity != null) {
                        String sourceId = "tplink:" + (!identity.mac().isEmpty() ? identity.mac() : identity.ip());
                        registry.put(sourceId, identity.name(), identity.mac(), identity.ip(), "TP-Link UDP discovery");
                        logger.debug("Learned TP-Link client name '{}' for {}", identity.name(), identity.ip());
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } finally {
            socket = null;
        }
    }

    private void closeSocket() {
        DatagramSocket current = socket;
        if (current != null) {
            current.close();
        }
    }

    static byte[] encrypt(String value) {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = new byte[plain.length];
        byte key = INITIAL_KEY;
        for (int i = 0; i < plain.length; i++) {
            encrypted[i] = (byte) (plain[i] ^ key);
            key = encrypted[i];
        }
        return encrypted;
    }

    static String decrypt(byte[] data, int length) {
        byte[] decrypted = new byte[length];
        byte key = INITIAL_KEY;
        for (int i = 0; i < length; i++) {
            byte nextKey = data[i];
            decrypted[i] = (byte) (nextKey ^ key);
            key = nextKey;
        }
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    static @Nullable Identity parseResponse(byte[] data, int length, String ip) {
        try {
            return parseIdentity(JsonParser.parseString(decrypt(data, length)), ip);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Identity parseIdentity(JsonElement rootElement, String ip) {
        if (!rootElement.isJsonObject()) {
            return null;
        }
        JsonObject root = rootElement.getAsJsonObject();
        JsonObject system = object(root, "system");
        JsonObject sysinfo = system == null ? null : object(system, "get_sysinfo");
        if (sysinfo == null) {
            return null;
        }
        JsonObject nested = object(sysinfo, "system");
        if (nested != null) {
            sysinfo = nested;
        }

        String alias = string(sysinfo, "alias");
        String mac = firstNonBlank(string(sysinfo, "mac"), string(sysinfo, "mic_mac"), string(sysinfo, "ethernet_mac"));
        if (alias.isEmpty()) {
            return null;
        }
        return new Identity(alias, mac, Objects.requireNonNull(ip));
    }

    private static @Nullable JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    record Identity(String name, String mac, String ip) {
    }
}
