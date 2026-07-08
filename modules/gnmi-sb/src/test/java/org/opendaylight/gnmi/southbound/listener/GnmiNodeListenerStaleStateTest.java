/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.southbound.device.connection.DeviceConnectionManager;
import org.opendaylight.gnmi.southbound.identifier.IdentifierUtils;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractConcurrentDataBrokerTest;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Host;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeStateBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.yang.common.Uint16;

/**
 * Regression test for the stale-operational-state ordering defect in
 * {@link GnmiNodeListener}.
 *
 * <p>When a node is deleted from configuration while its asynchronous connection attempt is still
 * in flight, {@code disconnectNode()} deletes the operational subtree, but the connection's
 * {@code onFailure} callback later merges a FAILURE {@code node-state} back into operational — with
 * nothing left to remove it. The result is an orphaned operational entry for a node that no longer
 * exists in configuration.</p>
 *
 * <p>This test drives the production listener over a real datastore and forces the interleaving
 * deterministically: the config delete is fully committed first, and only then does the connection
 * future fail. The invariant asserted is "a node deleted from config must leave no operational
 * state". With the current code the FAILURE merge violates it, so this test FAILS until the
 * listener is fixed to not write state for an already-removed node.</p>
 */
class GnmiNodeListenerStaleStateTest extends AbstractConcurrentDataBrokerTest {
    private static final NodeId NODE_ID = new NodeId("stale-node");
    private static final long TIMEOUT_MS = 10_000L;

    GnmiNodeListenerStaleStateTest() {
        super(true);
    }

    @BeforeEach
    void before() throws Exception {
        setup();
        // The gnmi-topology parent must exist before writing nodes under it (mirrors
        // GnmiSouthboundProvider#initGnmiTopology).
        final Topology topology = new TopologyBuilder()
            .setTopologyId(new TopologyId(IdentifierUtils.GNMI_TOPOLOGY_ID))
            .build();
        for (final LogicalDatastoreType store
                : new LogicalDatastoreType[] {LogicalDatastoreType.CONFIGURATION, LogicalDatastoreType.OPERATIONAL}) {
            final WriteTransaction tx = getDataBroker().newWriteOnlyTransaction();
            tx.merge(store, IdentifierUtils.GNMI_TOPOLOGY_PATH, topology);
            tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void failureCallbackAfterDeleteMustNotLeaveStaleOperationalState() throws Exception {
        final DataBroker dataBroker = getDataBroker();
        // Run the listener's connection callbacks inline on the caller thread so we control exactly
        // when onFailure runs (when we complete the future below).
        final ListeningExecutorService directExecutor = MoreExecutors.newDirectExecutorService();

        // The connection future we complete by hand, after the delete has committed.
        final SettableFuture<CommitInfo> connectFuture = SettableFuture.create();
        final CountDownLatch connectInvoked = new CountDownLatch(1);

        final DeviceConnectionManager dcm = mock(DeviceConnectionManager.class);
        when(dcm.connectDevice(any(Node.class))).thenAnswer(inv -> {
            connectInvoked.countDown();
            return connectFuture;
        });
        // Model the vulnerable window: at delete time the node is neither "connecting" nor "active"
        // (channel reached READY, capabilities failing), so closeConnection cancels nothing.
        doNothing().when(dcm).closeConnection(any(NodeId.class));

        final GnmiNodeListener listener = new GnmiNodeListener(dcm, dataBroker, directExecutor);
        final var registration = dataBroker.registerTreeChangeListener(LogicalDatastoreType.CONFIGURATION,
            IdentifierUtils.GNMI_NODE_DTI, listener);

        try {
            // 1. Create the node in config -> listener starts the (in-flight) connection.
            writeConfigNode();
            assertTrue(connectInvoked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "listener never started the device connection");

            // 2. Seed operational state for the node so we can deterministically observe the
            // delete being applied by disconnectNode().
            seedOperationalState();
            assertTrue(awaitOperationalPresent(dataBroker, true), "seed operational state not visible");

            // 3. Delete the node from config -> disconnectNode() deletes operational. Wait until the
            // delete has actually committed (operational becomes absent).
            deleteConfigNode();
            assertTrue(awaitOperationalPresent(dataBroker, false),
                "disconnectNode did not remove operational state");

            // 4. Only NOW does the connection fail (non-cancellation). onFailure runs inline and, in
            // the buggy code, merges a FAILURE node-state back into operational.
            connectFuture.setException(new RuntimeException("UNAUTHENTICATED: No authentication header"));

            // 5. Invariant: a node deleted from config must have no operational state.
            final Optional<Node> leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(),
                "STALE STATE: operational node-state was resurrected after the node was deleted "
                    + "from config: " + leftover.orElse(null));
        } finally {
            registration.close();
            directExecutor.shutdownNow();
        }
    }

    private void writeConfigNode() throws Exception {
        final Node node = new NodeBuilder()
            .setNodeId(NODE_ID)
            .addAugmentation(new GnmiNodeBuilder()
                .setConnectionParameters(new ConnectionParametersBuilder()
                    .setHost(new Host(new IpAddress(Ipv4Address.getDefaultInstance("127.0.0.1"))))
                    .setPort(new PortNumber(Uint16.valueOf(9339)))
                    .build())
                .build())
            .build();
        final WriteTransaction tx = getDataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(NODE_ID), node);
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteConfigNode() throws Exception {
        final WriteTransaction tx = getDataBroker().newWriteOnlyTransaction();
        tx.delete(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(NODE_ID));
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void seedOperationalState() throws Exception {
        final Node node = new NodeBuilder()
            .setNodeId(NODE_ID)
            .addAugmentation(new GnmiNodeBuilder()
                .setNodeState(new NodeStateBuilder()
                    .setNodeStatus(NodeState.NodeStatus.CONNECTING)
                    .build())
                .build())
            .build();
        final WriteTransaction tx = getDataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE_ID), node);
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Optional<Node> readOperational(final DataBroker dataBroker) throws Exception {
        try (var tx = dataBroker.newReadOnlyTransaction()) {
            return tx.read(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE_ID))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean awaitOperationalPresent(final DataBroker dataBroker, final boolean present)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (readOperational(dataBroker).isPresent() == present) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
