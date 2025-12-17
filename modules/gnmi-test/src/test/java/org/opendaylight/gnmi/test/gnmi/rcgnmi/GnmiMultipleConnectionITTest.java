/*
 * Copyright (c) 2025 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.test.gnmi.rcgnmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_DEVICE_MOUNTPOINT;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_ID;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_STATUS;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_STATUS_READY;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_TOPOLOGY_PATH;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.OPENCONFIG_INTERFACES;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.simulatordevice.impl.SimulatedGnmiDevice;
import org.opendaylight.gnmi.simulatordevice.utils.EffectiveModelContextBuilder.EffectiveModelContextBuilderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIXME we can easily misconfigure in-memory datastore similar like in: https://lf-opendaylight.atlassian.net/browse/NETCONF-1414
public class GnmiMultipleConnectionITTest extends GnmiITBase {
    private static final Logger LOG = LoggerFactory.getLogger(GnmiMultipleConnectionITTest.class);

    private static final String ANOTHER_GNMI_NODE_ID = "gnmi-another-test-node";
    private static final int ANOTHER_DEVICE_PORT = randomBindablePort();
    private static final String ANOTHER_GNMI_DEVICE_MOUNTPOINT =
        GNMI_TOPOLOGY_PATH + "/node=" + ANOTHER_GNMI_NODE_ID + "/yang-ext:mount";

    private static final String MULTIPLE_DEVICES_PAYLOAD = "{\n"
        + "    \"network-topology:topology\": [\n"
        + "        {\n"
        + "            \"topology-id\": \"gnmi-topology\",\n"
        + "            \"node\": [\n"
        + "                {\n"
        + "                    \"node-id\": \"" + GNMI_NODE_ID + "\",\n"
        + "                    \"gnmi-topology:connection-parameters\": {\n"
        + "                        \"host\": \"" + DEVICE_IP + "\",\n"
        + "                        \"port\": " + DEVICE_PORT + ",\n"
        + "                        \"connection-type\": \"INSECURE\"\n"
        + "                    },\n"
        + "                    \"extensions-parameters\": {\n"
        + "                        \"gnmi-parameters\": {\n"
        + "                            \"use-model-name-prefix\": true\n"
        + "                        }\n"
        + "                    }"
        + "                },\n"
        + "                {\n"
        + "                    \"node-id\": \"" + ANOTHER_GNMI_NODE_ID + "\",\n"
        + "                    \"gnmi-topology:connection-parameters\": {\n"
        + "                        \"host\": \"" + DEVICE_IP + "\",\n"
        + "                        \"port\": " + ANOTHER_DEVICE_PORT + ",\n"
        + "                        \"connection-type\": \"INSECURE\"\n"
        + "                    },\n"
        + "                    \"extensions-parameters\": {\n"
        + "                        \"gnmi-parameters\": {\n"
        + "                            \"use-model-name-prefix\": true\n"
        + "                        }\n"
        + "                    }"
        + "                }]\n"
        + "        }]\n"
        + "}";

    private static SimulatedGnmiDevice anotherDevice;
    private static SimulatedGnmiDevice device;

    @BeforeEach
    public void setupDevice() {
        device = getUnsecureGnmiDevice(DEVICE_IP, DEVICE_PORT);
        anotherDevice = getUnsecureGnmiDevice(DEVICE_IP, ANOTHER_DEVICE_PORT);
        try {
            device.start();
            anotherDevice.start();
        } catch (IOException | EffectiveModelContextBuilderException e) {
            LOG.info("Exception during device startup: ", e);
        }
    }

    @AfterEach
    public void teardownDevice() {
        device.stop();
        anotherDevice.stop();
    }

    @Test
    public void connectMultipleDevicesTest()
            throws IOException, InterruptedException, ExecutionException, TimeoutException, JSONException {
        //assert existing and empty gnmi topology
        final HttpResponse<String> getGnmiTopologyResponse = sendGetRequestJSON(GNMI_TOPOLOGY_PATH);
        final JSONArray topologies =
            new JSONObject(getGnmiTopologyResponse.body()).getJSONArray("network-topology:topology");
        assertEquals(1, topologies.length());
        final JSONObject gnmiTopologyJSON = topologies.getJSONObject(0);
        LOG.info("Empty gnmi-topology check response: {}", gnmiTopologyJSON);
        assertEquals(HttpURLConnection.HTTP_OK, getGnmiTopologyResponse.statusCode());
        assertEquals("gnmi-topology", gnmiTopologyJSON.getString("topology-id"));
        assertThrows(JSONException.class, () -> gnmiTopologyJSON.getJSONArray("node"));

        final HttpResponse<String> addGnmiDeviceResponse =
            sendPutRequestJSON(GNMI_TOPOLOGY_PATH, MULTIPLE_DEVICES_PAYLOAD);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, addGnmiDeviceResponse.statusCode());

        // assert gNMI nodes are connected
        Awaitility.waitAtMost(WAIT_TIME_DURATION)
            .pollInterval(POLL_INTERVAL_DURATION)
            .untilAsserted(() -> {
                final HttpResponse<String> getNodeConnectionStatusResponse =
                    sendGetRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + GNMI_NODE_ID + GNMI_NODE_STATUS);
                final String gnmiDeviceConnectStatus =
                    new JSONObject(getNodeConnectionStatusResponse.body()).getString("gnmi-topology:node-status");
                LOG.info("Response: {}", gnmiDeviceConnectStatus);
                assertEquals(HttpURLConnection.HTTP_OK, getNodeConnectionStatusResponse.statusCode());
                assertEquals(GNMI_NODE_STATUS_READY, gnmiDeviceConnectStatus);
                //
                final HttpResponse<String> getOtherNodeConnectionStatusResponse =
                    sendGetRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + ANOTHER_GNMI_NODE_ID + GNMI_NODE_STATUS);
                final String otherGnmiDeviceConnectStatus =
                    new JSONObject(getOtherNodeConnectionStatusResponse.body()).getString("gnmi-topology:node-status");
                LOG.info("Response: {}", otherGnmiDeviceConnectStatus);
                assertEquals(HttpURLConnection.HTTP_OK, getOtherNodeConnectionStatusResponse.statusCode());
                assertEquals(GNMI_NODE_STATUS_READY, otherGnmiDeviceConnectStatus);
            });

        //assert mountpoints are created
        Awaitility.waitAtMost(WAIT_TIME_DURATION)
            .pollInterval(POLL_INTERVAL_DURATION)
            .untilAsserted(() -> {
                final HttpResponse<String> getDataFromDevice =
                    sendGetRequestJSON(GNMI_DEVICE_MOUNTPOINT + OPENCONFIG_INTERFACES);
                assertEquals(HttpURLConnection.HTTP_OK, getDataFromDevice.statusCode());
                final HttpResponse<String> getDataFromOtherDevice =
                    sendGetRequestJSON(ANOTHER_GNMI_DEVICE_MOUNTPOINT + OPENCONFIG_INTERFACES);
                assertEquals(HttpURLConnection.HTTP_OK, getDataFromOtherDevice.statusCode());
            });

        //assert disconnected devices
        assertTrue(disconnectDevice(GNMI_NODE_ID));
        assertTrue(disconnectDevice(ANOTHER_GNMI_NODE_ID));
    }

    @Test
    public void disconnectMultipleDeviceTest() throws InterruptedException, IOException {
        assertTrue(connectDevice(GNMI_NODE_ID, DEVICE_IP, DEVICE_PORT));
        assertTrue(connectDevice(ANOTHER_GNMI_NODE_ID, DEVICE_IP, ANOTHER_DEVICE_PORT));

        final HttpResponse<String> deleteGnmiDevicesResponse = sendDeleteRequestJSON(GNMI_TOPOLOGY_PATH);
        LOG.info("Response: {}", deleteGnmiDevicesResponse.body());
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, deleteGnmiDevicesResponse.statusCode());

        // assert gNMI nodes are disconnected - gnmi-topology is empty
        Awaitility.waitAtMost(WAIT_TIME_DURATION)
            .pollInterval(POLL_INTERVAL_DURATION)
            .untilAsserted(() -> {
                final HttpResponse<String> getGnmiTopologyResponse = sendGetRequestJSON(GNMI_TOPOLOGY_PATH);
                final JSONObject gnmiTopology = new JSONObject(getGnmiTopologyResponse.body())
                    .getJSONArray("network-topology:topology").getJSONObject(0);
                assertEquals(HttpURLConnection.HTTP_OK, getGnmiTopologyResponse.statusCode());
                assertThrows(JSONException.class, () -> gnmiTopology.getJSONArray("node"));
            });
    }
}
