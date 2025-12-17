/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.test.gnmi.rcgnmi;

import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_ID;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_STATUS;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_NODE_STATUS_READY;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.GNMI_TOPOLOGY_PATH;
import static org.opendaylight.gnmi.test.gnmi.rcgnmi.GnmiITBase.GeneralConstants.OPENCONFIG_INTERFACES;

import gnmi.Gnmi;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.eclipse.jdt.annotation.NonNull;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.opendaylight.aaa.encrypt.impl.AAAEncryptionServiceImpl;
import org.opendaylight.gnmi.simulatordevice.config.GnmiSimulatorConfiguration;
import org.opendaylight.gnmi.simulatordevice.impl.SimulatedGnmiDevice;
import org.opendaylight.gnmi.simulatordevice.utils.GnmiSimulatorConfUtils;
import org.opendaylight.gnmi.southbound.yangmodule.GnmiSouthboundModule;
import org.opendaylight.gnmi.southbound.yangmodule.config.GnmiConfiguration;
import org.opendaylight.mdsal.binding.api.ActionProviderService;
import org.opendaylight.mdsal.binding.api.RpcProviderService;
import org.opendaylight.mdsal.binding.dom.adapter.BindingAdapterFactory;
import org.opendaylight.mdsal.binding.dom.adapter.BindingDOMRpcProviderServiceAdapter;
import org.opendaylight.mdsal.binding.dom.adapter.ConstantAdapterContext;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractDataBrokerTest;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.broker.DOMMountPointServiceImpl;
import org.opendaylight.mdsal.dom.broker.DOMNotificationRouter;
import org.opendaylight.mdsal.dom.broker.DOMRpcRouter;
import org.opendaylight.mdsal.dom.broker.RouterDOMActionProviderService;
import org.opendaylight.mdsal.dom.broker.RouterDOMActionService;
import org.opendaylight.mdsal.dom.broker.RouterDOMNotificationService;
import org.opendaylight.mdsal.dom.broker.RouterDOMRpcProviderService;
import org.opendaylight.mdsal.dom.broker.RouterDOMRpcService;
import org.opendaylight.mdsal.dom.spi.FixedDOMSchemaService;
import org.opendaylight.netconf.odl.device.notification.SubscribeDeviceNotificationRpc;
import org.opendaylight.netconf.sal.remote.impl.CreateDataChangeEventSubscriptionRpc;
import org.opendaylight.netconf.transport.http.ConfigUtils;
import org.opendaylight.netconf.transport.http.EventStreamService;
import org.opendaylight.netconf.transport.http.HttpClientStackConfiguration;
import org.opendaylight.netconf.transport.tcp.BootstrapFactory;
import org.opendaylight.restconf.api.query.PrettyPrintParam;
import org.opendaylight.restconf.server.AAAShiroPrincipalService;
import org.opendaylight.restconf.server.MessageEncoding;
import org.opendaylight.restconf.server.NettyEndpointConfiguration;
import org.opendaylight.restconf.server.SimpleNettyEndpoint;
import org.opendaylight.restconf.server.mdsal.MdsalDatabindProvider;
import org.opendaylight.restconf.server.mdsal.MdsalRestconfServer;
import org.opendaylight.restconf.server.mdsal.MdsalRestconfStreamRegistry;
import org.opendaylight.restconf.server.spi.ErrorTagMapping;
import org.opendaylight.restconf.server.spi.RpcImplementation;
import org.opendaylight.yang.gen.v1.config.aaa.authn.encrypt.service.config.rev240202.AaaEncryptServiceConfig;
import org.opendaylight.yang.gen.v1.config.aaa.authn.encrypt.service.config.rev240202.AaaEncryptServiceConfigBuilder;
import org.opendaylight.yang.gen.v1.config.aaa.authn.encrypt.service.config.rev240202.EncryptServiceConfig;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.http.client.rev240208.HttpClientStackGrouping;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.http.server.rev240208.HttpServerStackGrouping;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.http.server.rev240208.http.server.stack.grouping.Transport;
import org.opendaylight.yangtools.binding.DataContainer;
import org.opendaylight.yangtools.binding.data.codec.impl.di.DefaultBindingDOMCodecServices;
import org.opendaylight.yangtools.yang.common.Uint16;
import org.opendaylight.yangtools.yang.common.Uint32;
import org.opendaylight.yangtools.yang.parser.impl.DefaultYangParserFactory;
import org.opendaylight.yangtools.yang.xpath.impl.AntlrXPathParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GnmiITBase extends AbstractDataBrokerTest {
    private static final Logger LOG = LoggerFactory.getLogger(GnmiITBase.class);

    private static final ErrorTagMapping ERROR_TAG_MAPPING = ErrorTagMapping.RFC8040;
    private static final String USERNAME = "username";
    private static final String PASSWORD = "pa$$w0Rd";
    private static final Duration REQUEST_TIMEOUT_DURATION = Duration.ofMillis(10_000L);

    protected static final int CONTROLLER_PORT = randomBindablePort();
    protected static final int DEVICE_PORT = randomBindablePort();
    protected static final String DEVICE_IP = "127.0.0.1";
    protected static final Duration POLL_INTERVAL_DURATION = Duration.ofMillis(2_000L);
    protected static final Duration WAIT_TIME_DURATION = Duration.ofMillis(30_000L);

    protected static final String INITIAL_JSON_DATA_PATH = "src/test/resources/json/initData";
    private static final String TEST_SCHEMA_PATH = "src/test/resources/additional/models";
    private static final String SIMULATOR_CONFIG = "/json/simulator_config.json";

    protected ExecutorService httpClientExecutor;
    protected HttpClient httpClient;

    protected String localAddress = "127.0.0.1";

    protected BootstrapFactory bootstrapFactory;
    protected HttpClientStackGrouping clientStackGrouping;
    protected HttpClientStackGrouping invalidClientStackGrouping;
    protected DOMMountPointService domMountPointService;
    private DOMRpcRouter domRpcRouter;
    protected RpcProviderService rpcProviderService;
    protected ActionProviderService actionProviderService;

    protected volatile EventStreamService clientStreamService;
    protected volatile EventStreamService.StreamControl streamControl;

    private GnmiSouthboundModule gnmiSouthboundModule;
    private SimpleNettyEndpoint endpoint;
    private String host;
    private DOMNotificationRouter domNotificationRouter;
    private MdsalRestconfStreamRegistry streamRegistry;

    @BeforeEach
    public void startUp() throws Exception {
        setup();
        httpClientExecutor = Executors.newSingleThreadExecutor();
        httpClient = HttpClient.newBuilder()
            .executor(httpClientExecutor)
            .authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
                }
            })
            .build();

        bootstrapFactory = new BootstrapFactory("gnmi-it-group", 1);

        // transport configuration
        host = localAddress + ":" + CONTROLLER_PORT;
        LOG.info("RESTCONF Server starting on: {}", host);

        final var serverTransport = ConfigUtils.serverTransportTcp(localAddress, CONTROLLER_PORT);
        final var serverStackGrouping = new HttpServerStackGrouping() {
            @Override
            public Class<? extends HttpServerStackGrouping> implementedInterface() {
                return HttpServerStackGrouping.class;
            }

            @Override
            public Transport getTransport() {
                return serverTransport;
            }
        };
        clientStackGrouping = new HttpClientStackConfiguration(
            ConfigUtils.clientTransportTcp(localAddress, CONTROLLER_PORT, USERNAME, PASSWORD));
        invalidClientStackGrouping = new HttpClientStackConfiguration(
            ConfigUtils.clientTransportTcp(localAddress, CONTROLLER_PORT, USERNAME, "wrong-password"));

        // AAA services
        final var securityManager = new DefaultWebSecurityManager(new AuthenticatingRealm() {
            @Override
            protected AuthenticationInfo doGetAuthenticationInfo(final AuthenticationToken token)
                throws AuthenticationException {
                final var principal = (String) token.getPrincipal();
                final var credentials = new String((char[]) token.getCredentials());
                if (USERNAME.equals(principal) && PASSWORD.equals(credentials)) {
                    return new SimpleAuthenticationInfo(principal, credentials, "user");
                }
                return null;
            }
        });
        final var principalService = new AAAShiroPrincipalService(securityManager);

        // MDSAL services
        final var domDataBroker = getDomBroker();
        final var schemaContext = getRuntimeContext().modelContext();
        final var schemaService = new FixedDOMSchemaService(schemaContext);
        final var dataBindProvider = new MdsalDatabindProvider(schemaService);
        domRpcRouter = new DOMRpcRouter(schemaService);
        domMountPointService = new DOMMountPointServiceImpl();
        final var adapterContext = new ConstantAdapterContext(new DefaultBindingDOMCodecServices(getRuntimeContext()));
        final var adapterFactory = new BindingAdapterFactory(adapterContext);
        actionProviderService = adapterFactory.createActionProviderService(
            new RouterDOMActionProviderService(domRpcRouter));
        rpcProviderService = new BindingDOMRpcProviderServiceAdapter(adapterContext,
            new RouterDOMRpcProviderService(domRpcRouter));
        domNotificationRouter = new DOMNotificationRouter(32);

        final GnmiConfiguration gnmiConfiguration = new GnmiConfiguration();
        gnmiConfiguration.addInitialYangsPaths(Collections.singletonList(TEST_SCHEMA_PATH));

        // Initialize GnmiSouthboundModule to register RPCs
        gnmiSouthboundModule = new GnmiSouthboundModule(
            getDataBroker(),
            rpcProviderService,
            domMountPointService,
            createEncryptionService(),
            new DefaultYangParserFactory(),
            new AntlrXPathParserFactory(),
            gnmiConfiguration
        );
        gnmiSouthboundModule.init();

        streamRegistry = new MdsalRestconfStreamRegistry(domDataBroker,
            new RouterDOMNotificationService(domNotificationRouter),
            schemaService, uri -> uri.resolve("streams"), dataBindProvider);
        final var rpcImplementations = List.<RpcImplementation>of(
            new CreateDataChangeEventSubscriptionRpc(streamRegistry, dataBindProvider, domDataBroker),
            new SubscribeDeviceNotificationRpc(streamRegistry, domMountPointService)
        );
        final var server = new MdsalRestconfServer(dataBindProvider, domDataBroker,
            new RouterDOMRpcService(domRpcRouter), new RouterDOMActionService(domRpcRouter), domMountPointService,
            rpcImplementations);

        // Netty endpoint
        final var configuration = new NettyEndpointConfiguration(
            ERROR_TAG_MAPPING, PrettyPrintParam.FALSE, Uint16.ZERO, Uint32.valueOf(1000),
            "rests", MessageEncoding.JSON, serverStackGrouping);

        endpoint = new SimpleNettyEndpoint(server, principalService, streamRegistry, bootstrapFactory, configuration);
    }

    @AfterEach
    public void teardown() throws Exception {
        cleanup();

        if (httpClientExecutor != null) {
            httpClientExecutor.shutdownNow();
        }
        if (endpoint != null) {
            endpoint.close();
        }
        if (bootstrapFactory != null) {
            bootstrapFactory.close();
        }
        if (gnmiSouthboundModule != null) {
            gnmiSouthboundModule.close();
        }
    }

    @AfterEach
    public void cleanup() {
        LOG.info("Performing cleanup!");
        /*
        disconnect device GNMI_NODE_ID after each test in all of inherited classes
        even when in the end of some tests there is assert disconnecting device, it needs to be there
        as a failsafe to ensure when some test fails that device will be disconnected and wont affect other tests
        */
        try {
            if (httpClientExecutor != null && !httpClientExecutor.isShutdown()) {
                final HttpResponse<String> getGnmiTopologyResponse = sendGetRequestJSON(GNMI_TOPOLOGY_PATH);
                if (getGnmiTopologyResponse.body().contains(GNMI_NODE_ID)) {
                    if (!disconnectDevice(GNMI_NODE_ID)) {
                        LOG.info("Problem when disconnecting device {}", GNMI_NODE_ID);
                    }
                }
            }
        } catch (ConnectException e) {
            // 3. Ignore ConnectException: This happens if startUp failed (NPE) and the server never started.
            // We don't want this error to mask the real NPE.
            LOG.warn("Could not connect to server during cleanup (Server likely didn't start): {}", e.getMessage());
        } catch (ExecutionException | InterruptedException | TimeoutException | IOException e) {
            LOG.info("Problem when disconnecting device {}: {}", GNMI_NODE_ID, e);
        }
        LOG.info("Cleanup done!");
    }

    protected static SimulatedGnmiDevice getUnsecureGnmiDevice(final String host, final int port) {

        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(GnmiITBase.class.getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(host);
        simulatorConfiguration.setTargetPort(port);
        simulatorConfiguration.setYangsPath(TEST_SCHEMA_PATH);
        simulatorConfiguration.setInitialConfigDataPath(INITIAL_JSON_DATA_PATH + "/config.json");
        simulatorConfiguration.setInitialStateDataPath(INITIAL_JSON_DATA_PATH + "/state.json");

        return new SimulatedGnmiDevice(simulatorConfiguration);
    }

    protected static SimulatedGnmiDevice getUnsecureGnmiDevice(final String host, final int port,
                                                              final String username, final String password) {
        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(GnmiITBase.class.getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(host);
        simulatorConfiguration.setTargetPort(port);
        simulatorConfiguration.setYangsPath(TEST_SCHEMA_PATH);
        simulatorConfiguration.setInitialConfigDataPath(INITIAL_JSON_DATA_PATH + "/config.json");
        simulatorConfiguration.setInitialStateDataPath(INITIAL_JSON_DATA_PATH + "/state.json");
        simulatorConfiguration.setUsername(username);
        simulatorConfiguration.setPassword(password);

        return new SimulatedGnmiDevice(simulatorConfiguration);
    }

    protected static SimulatedGnmiDevice getNonCompliableEncodingDevice(final String host, final int port) {
        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(GnmiITBase.class.getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(host);
        simulatorConfiguration.setTargetPort(port);
        simulatorConfiguration.setYangsPath(TEST_SCHEMA_PATH);
        simulatorConfiguration.setInitialConfigDataPath(INITIAL_JSON_DATA_PATH + "/config.json");
        simulatorConfiguration.setInitialStateDataPath(INITIAL_JSON_DATA_PATH + "/state.json");
        simulatorConfiguration.setSupportedEncodings(EnumSet.of(Gnmi.Encoding.JSON));

        return new SimulatedGnmiDevice(simulatorConfiguration);
    }

    protected static SimulatedGnmiDevice getSecureGnmiDevice(final String host, final int port,
                                                             final String keyPath, final String certPath,
                                                             final String username, final String password) {
        final GnmiSimulatorConfiguration simulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(GnmiITBase.class.getResourceAsStream(SIMULATOR_CONFIG));
        simulatorConfiguration.setTargetAddress(host);
        simulatorConfiguration.setTargetPort(port);
        simulatorConfiguration.setYangsPath(TEST_SCHEMA_PATH);
        simulatorConfiguration.setInitialConfigDataPath(INITIAL_JSON_DATA_PATH + "/config.json");
        simulatorConfiguration.setInitialStateDataPath(INITIAL_JSON_DATA_PATH + "/state.json");
        simulatorConfiguration.setCertPath(certPath);
        simulatorConfiguration.setCertKeyPath(keyPath);
        simulatorConfiguration.setUsername(username);
        simulatorConfiguration.setPassword(password);

        return new SimulatedGnmiDevice(simulatorConfiguration);
    }

    protected boolean connectDevice(final String nodeId, final String ipAddr, final int port)
        throws InterruptedException, IOException {
        LOG.info("Connecting device!");
        //check there is not present device with nodeId in gnmi-topology topology
        final HttpResponse<String> getGnmiNodeResponse = sendGetRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + nodeId);
        if (getGnmiNodeResponse.statusCode() == HttpURLConnection.HTTP_OK) {
            LOG.info("Gnmi node {} is already in the topology", nodeId);
            return false;
        }

        // add gNMI node to topology
        final String newDevicePayload = createDevicePayload(nodeId, ipAddr, port);
        LOG.info("Adding gnmi device with ID {} on IP ADDRESS:PORT {}:{}", nodeId, ipAddr, port);
        final HttpResponse<String> addGnmiDeviceResponse =
            sendPutRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + nodeId, newDevicePayload);
        if (addGnmiDeviceResponse.statusCode() != HttpURLConnection.HTTP_CREATED) {
            LOG.info("Problem when adding node {} into gnmi topology: {}y", nodeId, addGnmiDeviceResponse);
            return false;
        }

        // check if gNMI node is connected
        try {
            Awaitility.waitAtMost(WAIT_TIME_DURATION)
                .pollInterval(POLL_INTERVAL_DURATION)
                .until(() -> {
                    final HttpResponse<String> getConnectionStatusResponse =
                        sendGetRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + nodeId + GNMI_NODE_STATUS);
                    if (getConnectionStatusResponse.statusCode() != HttpURLConnection.HTTP_OK) {
                        return false;
                    }
                    final String gnmiDeviceConnectStatus = new JSONObject(
                        getConnectionStatusResponse.body()).getString("gnmi-topology:node-status");
                    LOG.info("Check node {} connection status response: {}", nodeId, gnmiDeviceConnectStatus);
                    return gnmiDeviceConnectStatus.equals(GNMI_NODE_STATUS_READY);
                });
        } catch (ConditionTimeoutException e) {
            LOG.info("Failure during connecting the device - gnmi node status is not READY!");
            return false;
        }

        //check if mountpoint is created
        try {
            Awaitility.waitAtMost(WAIT_TIME_DURATION)
                .pollInterval(POLL_INTERVAL_DURATION)
                .until(() -> {
                    final HttpResponse<String> getDataFromDevice = sendGetRequestJSON(GNMI_TOPOLOGY_PATH
                        + "/node=" + nodeId + "/yang-ext:mount" + OPENCONFIG_INTERFACES);
                    LOG.info("Check mountpoint for node {} is created response {}", nodeId, getDataFromDevice);
                    return getDataFromDevice.statusCode() == HttpURLConnection.HTTP_OK;
                });
        } catch (ConditionTimeoutException e) {
            LOG.info("Failure during connecting the device - mountpoint is not created or unreachable!");
            return false;
        }

        LOG.info("Device successfully connected!");
        return true;
    }

    protected boolean disconnectDevice(final String nodeId) throws ExecutionException, InterruptedException,
        TimeoutException, IOException {
        LOG.info("Disconnecting device!");
        final HttpResponse<String> deleteGnmiDeviceResponse =
            sendDeleteRequestJSON(GNMI_TOPOLOGY_PATH + "/node=" + nodeId);
        LOG.info("Delete gnmi node {} response: {}", nodeId, deleteGnmiDeviceResponse);
        if (deleteGnmiDeviceResponse.statusCode() != HttpURLConnection.HTTP_NO_CONTENT) {
            LOG.info("Failure during disconnecting the device - node {} was not deleted: {}!", nodeId,
                deleteGnmiDeviceResponse);
            return false;
        }

        // check if gNMI node is disconnected - gnmi node is not present
        try {
            Awaitility.waitAtMost(WAIT_TIME_DURATION)
                .pollInterval(POLL_INTERVAL_DURATION)
                .until(() -> {
                    final HttpResponse<String> getGnmiNodeResponse = sendGetRequestJSON(
                        GNMI_TOPOLOGY_PATH + "/node=" + nodeId);
                    LOG.info("Get node {} from topology when disconnecting: {}", nodeId, getGnmiNodeResponse);
                    return HttpURLConnection.HTTP_CONFLICT == getGnmiNodeResponse.statusCode();
                });
        } catch (ConditionTimeoutException e) {
            LOG.info("Failure during disconnecting the device - node {} was not deleted!", nodeId);
            return false;
        }

        LOG.info("Device disconnected!");
        return true;
    }

    protected String createDevicePayload(final String nodeId, final String ipAddr, final int port) {
        return "{\n"
            + "    \"network-topology:node\" : [{\n"
            + "        \"node-id\": \"" + nodeId + "\",\n"
            + "        \"gnmi-topology:connection-parameters\": {\n"
            + "            \"host\": \"" + ipAddr + "\",\n"
            + "            \"port\": " + port + ",\n"
            + "            \"connection-type\": \"INSECURE\"\n"
            + "        },\n"
            + "        \"extensions-parameters\": {\n"
            + "            \"gnmi-parameters\": {\n"
            + "                \"use-model-name-prefix\": true\n"
            + "            }\n"
            + "        }"
            + "    }]\n"
            + "}";
    }

    protected String createDevicePayloadWithAdditionalCapabilities(final String nodeId, final String ipAddr,
                                                                   final int port, final String modelName,
                                                                   final String modelVersion) {
        return "{\n"
            + "    \"node\": [\n"
            + "        {\n"
            + "            \"node-id\": \"" + nodeId + "\",\n"
            + "            \"connection-parameters\": {\n"
            + "                \"host\": \"" + ipAddr + "\",\n"
            + "                \"port\": " + port + ",\n"
            + "                \"connection-type\": \"INSECURE\"\n"
            + "            },\n"
            + "            \"extensions-parameters\": {\n"
            + "                \"gnmi-parameters\": {\n"
            + "                    \"overwrite-data-type\": \"NONE\",\n"
            + "                    \"use-model-name-prefix\": true,\n"
            + "                    \"path-target\": \"OC_YANG\"\n"
            + "                },\n"
            + "                \"force-capability\": [\n"
            + "                    {\n"
            + "                        \"name\": \"" + modelName + "\",\n"
            + "                        \"version\": \"" + modelVersion + "\"\n"
            + "                    }\n"
            + "                ]\n"
            + "            }\n"
            + "        }\n"
            + "    ]\n"
            + "}";
    }

    protected HttpResponse<String> sendDeleteRequestJSON(final String path) throws InterruptedException, IOException {
        LOG.info("Sending DELETE request to path: {}", path);
        final HttpRequest deleteRequest = HttpRequest.newBuilder()
            .uri(URI.create(path))
            .header("Content-Type", "application/json")
            .DELETE()
            .timeout(REQUEST_TIMEOUT_DURATION)
            .build();
        return httpClient.send(deleteRequest, BodyHandlers.ofString());
    }

    protected HttpResponse<String> sendPutRequestJSON(final String path, final String payload)
        throws InterruptedException, IOException {
        LOG.info("Sending PUT request with {} payload to path: {}", payload, path);
        final HttpRequest putRequest = HttpRequest.newBuilder()
            .uri(URI.create(path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .PUT(BodyPublishers.ofString(payload))
            .timeout(REQUEST_TIMEOUT_DURATION)
            .build();
        return httpClient.send(putRequest, BodyHandlers.ofString());
    }

    protected HttpResponse<String> sendPostRequestJSON(final String path, final String payload)
        throws InterruptedException, IOException {
        LOG.info("Sending POST request with {} payload to path: {}", payload, path);
        final HttpRequest postRequest = HttpRequest.newBuilder()
            .uri(URI.create(path))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(payload))
            .timeout(REQUEST_TIMEOUT_DURATION)
            .build();
        return httpClient.send(postRequest, BodyHandlers.ofString());
    }

    protected HttpResponse<String> sendGetRequestJSON(final String path) throws InterruptedException, IOException {
        LOG.info("Sending GET request to path: {}", path);
        final HttpRequest getRequest = HttpRequest.newBuilder()
            .uri(URI.create(path))
            .header("Content-Type", "application/json")
            .GET()
            .timeout(REQUEST_TIMEOUT_DURATION)
            .build();
        return httpClient.send(getRequest, BodyHandlers.ofString());
    }

    protected HttpResponse<String> sendPatchRequestJSON(final String path, final String payload)
            throws InterruptedException, IOException {
        LOG.info("Sending PATCH request to path: {}", path);
        final HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(path))
                .header("Content-Type", "application/json")
                .method("PATCH", BodyPublishers.ofString(payload))
                .timeout(REQUEST_TIMEOUT_DURATION)
                .build();
        return httpClient.send(getRequest, BodyHandlers.ofString());
    }

    protected static final class GeneralConstants {
        public static final String RESTCONF_DATA_PATH = "http://localhost:%d/rests/data".formatted(CONTROLLER_PORT);
        public static final String GNMI_NODE_ID = "gnmi-node-test";
        public static final String GNMI_NODE_STATUS = "/gnmi-topology:node-state/node-status";
        public static final String GNMI_TOPOLOGY_PATH =
            RESTCONF_DATA_PATH + "/network-topology:network-topology/topology=gnmi-topology";
        public static final String GNMI_DEVICE_MOUNTPOINT =
            GNMI_TOPOLOGY_PATH + "/node=" + GNMI_NODE_ID + "/yang-ext:mount";
        public static final String OPENCONFIG_INTERFACES = "/openconfig-interfaces:interfaces";
        public static final String OPENCONFIG_OPENFLOW = "/openconfig-openflow:openflow";
        public static final String OPENCONFIG_SYSTEM = "/openconfig-system:system";
        public static final String GNMI_NODE_STATUS_READY = "READY";

        public static final String ERR_MSG_RELEVANT_MODEL_NOT_EXIST =
                "{\"errors\":{\"error\":[{\"error-message\":"
              + "\"Request could not be completed because the relevant data model content does not "
              + "exist\",\"error-tag\":\"data-missing\",\"error-type\":\"protocol\"}]}}";
        protected static final String INTERFACE_ETH3_CONFIG_NAME_PAYLOAD = "{\n"
            + "\"openconfig-interfaces:name\": \"updated-config-name\"\n"
            + "}";

        private GeneralConstants() {
            //Hide constructor
        }
    }

    private static AAAEncryptionServiceImpl createEncryptionService()
        throws NoSuchAlgorithmException, InvalidKeySpecException {
        final AaaEncryptServiceConfig config = getDefaultAaaEncryptServiceConfig();
        return new AAAEncryptionServiceImpl(new EncryptServiceConfig() {
            @Override
            public @NonNull Class<? extends DataContainer> implementedInterface() {
                return config.implementedInterface();
            }

            @Override
            public String getEncryptKey() {
                return config.getEncryptKey();
            }

            @Override
            public byte[] getEncryptSalt() {
                return config.getEncryptSalt().getBytes();
            }

            @Override
            public String getEncryptMethod() {
                return config.getEncryptMethod();
            }

            @Override
            public String getEncryptType() {
                return config.getEncryptType();
            }

            @Override
            public Integer getEncryptIterationCount() {
                return config.getEncryptIterationCount();
            }

            @Override
            public Integer getEncryptKeyLength() {
                return config.getEncryptKeyLength();
            }

            @Override
            public Integer getAuthTagLength() {
                return config.getAuthTagLength();
            }

            @Override
            public String getCipherTransforms() {
                return config.getCipherTransforms();
            }
        });
    }

    private static AaaEncryptServiceConfig getDefaultAaaEncryptServiceConfig() {
        return new AaaEncryptServiceConfigBuilder().setEncryptKey("V1S1ED4OMeEh")
            .setPasswordLength(12).setEncryptSalt("TdtWeHbch/7xP52/rp3Usw==")
            .setEncryptMethod("PBKDF2WithHmacSHA1").setEncryptType("AES")
            .setEncryptIterationCount(32768).setEncryptKeyLength(128)
            .setAuthTagLength(128).setCipherTransforms("AES/GCM/NoPadding").build();
    }

    /**
     * Find a local port which has a good chance of not failing {@code bind()} due to a conflict.
     *
     * @return a local port
     */
    static int randomBindablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
