/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.connector.tests.gnoi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.ByteString;
import gnoi.file.FileGrpc;
import gnoi.file.FileOuterClass;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.connector.configuration.SessionConfiguration;
import org.opendaylight.gnmi.connector.gnmi.util.AddressUtil;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiFileInvoker;
import org.opendaylight.gnmi.connector.gnoi.session.api.GnoiSession;
import org.opendaylight.gnmi.connector.session.api.SessionManager;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.connector.tests.commons.TestUtils;
import org.opendaylight.gnmi.connector.tests.commons.TimeoutUtil;
import org.opendaylight.gnmi.connector.tests.gnmi.GnmiTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class GnoiTest {

    private static final Logger LOG = LoggerFactory.getLogger(GnmiTest.class);
    private static final InetSocketAddress DEFAULT_SERVER_ADDRESS = new InetSocketAddress(AddressUtil.LOCALHOST, 9090);

    private static final int GNOI_RESPONSE_FILE_CHUNKS = 2;
    private static final byte[] GNOI_RESPONSE_FILE_FIRST_CHUNK = "test-content".getBytes();
    private static final byte[] GNOI_RESPONSE_FILE_SECOND_CHUNK = "test-content2".getBytes();

    private TestGnoiServiceImpl service;
    private Server server;

    @BeforeEach
    public void before() throws IOException {
        service = new TestGnoiServiceImpl();
        server = ServerBuilder
                .forPort(DEFAULT_SERVER_ADDRESS.getPort())
                .addService(service)
                .build();

        LOG.info("Starting server");
        server.start();
    }

    @AfterEach
    public void after() {
        LOG.info("Shutting down server");
        server.shutdown();
        try {
            if (!server.awaitTermination(TimeoutUtil.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Termination of server failed!");
            }
        } catch (InterruptedException e) {
            LOG.error("Interrupted while waiting for server shutdown");
        }
    }

    @SuppressWarnings({"checkstyle:illegalCatch"})
    @Test
    public void gnoiServicesInitiatedTest() throws Exception {
        final SessionManager sessionManager = TestUtils.createSessionManagerWithCerts();
        try (SessionProvider session =
                     sessionManager.createSession(new SessionConfiguration(DEFAULT_SERVER_ADDRESS, true))) {
            final GnoiSession gnoiSession = session.getGnoiSession();

            assertNotNull(gnoiSession);
            assertNotNull(gnoiSession.getCertInvoker());
            assertNotNull(gnoiSession.getFileInvoker());
            assertNotNull(gnoiSession.getOsInvoker());
            assertNotNull(gnoiSession.getSystemInvoker());
        } catch (Exception e) {
            fail("Exception thrown!" + e);
        }
    }

    @SuppressWarnings({"checkstyle:illegalCatch"})
    @Test
    public void gnoiFileServiceTest() throws Exception {
        final SessionManager sessionManager = TestUtils.createSessionManagerWithCerts();

        try (SessionProvider session =
                     sessionManager.createSession(new SessionConfiguration(DEFAULT_SERVER_ADDRESS, true))) {

            final GnoiFileInvoker fileInvoker = session.getGnoiSession().getFileInvoker();
            gnoi.file.FileOuterClass.GetRequest request = gnoi.file.FileOuterClass.GetRequest.newBuilder()
                    .build();
            // Number of chunks + 1 x onComplete()
            final CountDownLatch syncCounter = new CountDownLatch(GNOI_RESPONSE_FILE_CHUNKS + 1);

            final StreamObserver<gnoi.file.FileOuterClass.GetResponse> responseObserver =
                new StreamObserver<>() {
                    @Override
                    public void onNext(FileOuterClass.GetResponse value) {
                        syncCounter.countDown();
                        ByteString receivedContents = value.getContents();
                        LOG.info("Received content: {}", receivedContents);
                        if (syncCounter.getCount() == 2) {
                            assertArrayEquals(GNOI_RESPONSE_FILE_FIRST_CHUNK, receivedContents.toByteArray());
                        } else if (syncCounter.getCount() == 1) {
                            assertArrayEquals(GNOI_RESPONSE_FILE_SECOND_CHUNK, receivedContents.toByteArray());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        fail("Exception thrown! " + throwable);
                    }

                    @Override
                    public void onCompleted() {
                        syncCounter.countDown();
                    }
                };
            fileInvoker.get(request, responseObserver);
            assertTrue(syncCounter.await(TimeoutUtil.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            fail("Exception thrown!" + e);
        }
    }

    private static final class TestGnoiServiceImpl extends FileGrpc.FileImplBase {

        @Override
        public void get(final gnoi.file.FileOuterClass.GetRequest request,
                        final StreamObserver<gnoi.file.FileOuterClass.GetResponse> responseObserver) {

            LOG.info("Service: got request: {} - {}", request.getClass(), request);
            final FileOuterClass.GetResponse firstChunkResponse = FileOuterClass.GetResponse.newBuilder()
                    .setContents(ByteString.copyFrom(GNOI_RESPONSE_FILE_FIRST_CHUNK))
                    .build();
            LOG.info("Service: returning response: {}", firstChunkResponse);
            responseObserver.onNext(firstChunkResponse);

            final FileOuterClass.GetResponse secondChunkResponse = FileOuterClass.GetResponse.newBuilder()
                    .setContents(ByteString.copyFrom(GNOI_RESPONSE_FILE_SECOND_CHUNK))
                    .build();
            LOG.info("Service: returning response: {}", secondChunkResponse);
            responseObserver.onNext(secondChunkResponse);
            responseObserver.onCompleted();
        }
    }
}
