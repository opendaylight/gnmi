.. _gnmi-dev-guide:

gNMI Developer Guide
====================

.. note::

    Reading the gNMI section in the User Guide is likely useful as it
    contains an overview of gNMI in OpenDaylight and a how-to for
    spawning and configuring gNMI devices.

This chapter is recommended for application developers who want to
interact with mounted gNMI devices from their application code. It
demonstrates how to perform operations programmatically using the OpenDaylight
MD-SAL DataBroker API, which acts as an abstraction layer over the gNMI **Get**
and **Set** RPCs.

.. note::

    This guide assumes awareness of basic OpenDaylight application development
    and the MD-SAL DataBroker patterns.

Overview
--------

The gNMI Southbound module manages connections with gNMI targets. It implements
functionality to make **CRUD operations on multiple gNMI targets**, making it
easy to read and manipulate data in gNMI devices.

The module augments the OpenDaylight network-topology model with the
``gnmi-topology`` model. Once a new node is added to this topology, the
southbound plugin establishes a connection to the device and creates a
**Mount Point** containing a ``GnmiDataBroker``.

gNMI Southbound Transaction Implementation
------------------------------------------

The gNMI Southbound plugin utilizes a custom implementation of the MD-SAL DOM Data Broker (``GnmiDataBroker``) to handle
data transactions. Unlike standard ODL brokers that might use default internal transaction logic, the gNMI
implementation explicitly defines transaction behaviors to bridge MD-SAL operations with gNMI ``Get`` and ``Set`` RPCs.

Transaction Architecture
~~~~~~~~~~~~~~~~~~~~~~~~

The transaction logic is encapsulated in three custom classes located in
``org.opendaylight.gnmi.southbound.mountpoint.transactions``. The ``GnmiDataBroker`` acts as a factory that instantiates
and combines these transactions.

1. Read-Only Transaction (ReadOnlyTx)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This transaction handles reading data from the device. It wraps the ``GnmiGet`` provider, which translates the read path
into a gNMI Get Request.

* **Implementation**: ``org.opendaylight.gnmi.southbound.mountpoint.transactions.ReadOnlyTx``
* **Backing Logic**: Delegates ``read`` and ``exists`` calls to ``GnmiGet``.

2. Write-Only Transaction (WriteOnlyTx)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This transaction handles data modifications (PUT, MERGE, DELETE). It wraps the ``GnmiSet`` provider, which collects
modifications and translates them into a gNMI Set Request upon commit.

* **Implementation**: ``org.opendaylight.gnmi.southbound.mountpoint.transactions.WriteOnlyTx``
* **Backing Logic**: Buffers changes and delegates the ``commit`` to ``GnmiSet``.

3. Read-Write Transaction (ReadWriteTx)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The ``ReadWriteTx`` is a **composite transaction**. It does not implement connection logic itself; instead, it
coordinates the specialized ``ReadOnlyTx`` and ``WriteOnlyTx``. This ensures that a single transaction object can
seamlessly handle both reading (via gNMI Get) and writing (via gNMI Set) by delegating to the appropriate underlying
implementation.

* **Implementation**: ``org.opendaylight.gnmi.southbound.mountpoint.transactions.ReadWriteTx``

Usage and Instantiation
~~~~~~~~~~~~~~~~~~~~~~~

When ``GnmiDataBroker.newReadWriteTransaction()`` is called, it does not create a generic ODL transaction. Instead, it
manually constructs a ``ReadWriteTx`` by instantiating the specific read and write components.

The following code demonstrates how these transactions are composed "under the hood" within the broker:

.. code-block:: java

   // Logic inside GnmiDataBroker.java

   @Override
   public DOMDataTreeReadWriteTransaction newReadWriteTransaction() {
       // 1. Create the specialized Read-Only transaction backed by GnmiGet
       DOMDataTreeReadTransaction readDelegate = new ReadOnlyTx(gnmiGet);

       // 2. Create the specialized Write-Only transaction backed by GnmiSet
       DOMDataTreeWriteTransaction writeDelegate = new WriteOnlyTx(gnmiSet);

       // 3. Compose them into the custom ReadWriteTx
       return new ReadWriteTx(readDelegate, writeDelegate);
   }

Delegation in ReadWriteTx
~~~~~~~~~~~~~~~~~~~~~~~~~

The ``ReadWriteTx`` class simply delegates method calls to the respective delegate provided during construction.
This separation of concerns allows the read and write logic to remain distinct while providing a unified interface to
MD-SAL applications.

.. code-block:: java

   // Logic inside ReadWriteTx.java

   public class ReadWriteTx implements DOMDataTreeReadWriteTransaction {

       private final DOMDataTreeReadTransaction delegateReadTx;
       private final DOMDataTreeWriteTransaction delegateWriteTx;

       public ReadWriteTx(final DOMDataTreeReadTransaction delegateReadTx,
                          final DOMDataTreeWriteTransaction delegateWriteTx) {
           this.delegateReadTx = delegateReadTx;
           this.delegateWriteTx = delegateWriteTx;
       }

       // Read operations are delegated to ReadOnlyTx
       @Override
       public FluentFuture<Optional<NormalizedNode>> read(final LogicalDatastoreType store,
                                                          final YangInstanceIdentifier path) {
       return delegateReadTx.read(store, path);
       }

       // Write operations are delegated to WriteOnlyTx
       @Override
       public void put(final LogicalDatastoreType store, final YangInstanceIdentifier path,
                       final NormalizedNode data) {
           delegateWriteTx.put(store, path, data);
       }

       // Commit is delegated to WriteOnlyTx
       @Override
       public FluentFuture<? extends CommitInfo> commit() {
           return delegateWriteTx.commit();
       }
   }

Supported Encodings
-------------------

Since the module operates solely with YANG modeled data, **only JSON_IETF
encoding is supported** for structured data types (containers, lists, etc.), as
per RFC7951.

* **SetRequest**: Encoded in JSON_IETF.
* **GetRequest**: Requests JSON_IETF encoding.
* **GetResponse**: Expects JSON_IETF encoding.

If the device does not declare support for JSON_IETF in its ``CapabilityResponse``,
the connection will be closed.

Initialization
--------------

To use the gNMI Southbound module programmatically, you must ensure the
artifacts are available in your environment.

1.  **Maven Dependency**:
    To compile the Java code examples below, add the southbound module to your ``pom.xml``:

    .. code-block:: xml

        <dependency>
            <groupId>org.opendaylight.gnmi</groupId>
            <artifactId>gnmi-sb</artifactId>
            <version>2.0.0</version>
        </dependency>

2.  **Karaf Feature**:
    Ensure the feature is installed in the runtime:

    .. code-block:: bash

        feature:install odl-gnmi-all


Connecting a Device
-------------------

Connecting a device involves writing a new Node configuration to the
``gnmi-topology`` in the **CONFIGURATION** datastore.

.. code-block:: java

    // 1. Define the Node ID
    final NodeId gnmiNodeId = new NodeId("device-1");
    final InstanceIdentifier<Node> nodeInstanceIdentifier = IdentifierUtils.gnmiNodeIID(gnmiNodeId);

    // 2. Build the Node with Connection Parameters
    // Note: Requires imports from gnmi-topology model
    final Node testGnmiNode = new NodeBuilder()
        .setNodeId(gnmiNodeId)
        .addAugmentation(GnmiTopologyNode.class, new GnmiTopologyNodeBuilder()
            .setConnectionParameters(new ConnectionParametersBuilder()
                .setHost(new Host(new IpAddress(new Ipv4Address("127.0.0.1"))))
                .setPort(new PortNumber(10161))
                .setCredentials(new CredentialsBuilder()
                    .setUsername("admin")
                    .setPassword("admin")
                    .build())
                .build())
            .build())
        .build();

    // 3. Write the Node to the Datastore
    // 'dataBroker' is your local MD-SAL DataBroker service
    final WriteTransaction writeTransaction = dataBroker.newWriteOnlyTransaction();
    writeTransaction.put(LogicalDatastoreType.CONFIGURATION, nodeInstanceIdentifier, testGnmiNode);
    writeTransaction.commit().get();

Once written, the southbound plugin will attempt to connect. The device is
successfully connected when its status in the **OPERATIONAL** datastore becomes
``NodeState.NodeStatus.READY``.

Accessing the Mount Point
-------------------------

To interact with a connected device, you must retrieve its **Mount Point**. The
mount point provides access to the ``DOMDataBroker``, which is used for all
subsequent read/write operations.

.. code-block:: java

    // Retrieve the DOMMountPointService (injected via Blueprint/OSGi)
    final DOMMountPointService domMountPointService = ...;

    // Retrieve the Mount Point using the Node ID
    final Optional<DOMMountPoint> mountPoint = domMountPointService.getMountPoint(
            IdentifierUtils.nodeidToYii(new NodeId("device-1")));

    final DOMMountPoint domMountPoint = mountPoint.orElseThrow();

    // Get the DataBroker service specific to this device
    final Optional<DOMDataBroker> service = domMountPoint.getService(DOMDataBroker.class);
    final DOMDataBroker domDataBroker = service.orElseThrow();


Reading Data (Get Operation)
----------------------------

Reading data triggers a gNMI **Get** request. You can read from either the
``CONFIGURATION`` or ``OPERATIONAL`` datastore.

The following example reads the ``openconfig-interfaces`` data from the device:

.. code-block:: java

    final YangInstanceIdentifier interfacesYIID = YangInstanceIdentifier.builder()
            .node(QName.create("http://openconfig.net/yang/interfaces", "2016-05-26", "interfaces"))
            .build();

    final DOMDataTreeReadTransaction domDataTreeReadTransaction = domDataBroker.newReadOnlyTransaction();

    final Optional<NormalizedNode> normalizedNode = domDataTreeReadTransaction
            .read(LogicalDatastoreType.CONFIGURATION, interfacesYIID)
            .get();


Writing Data (Set Operation)
----------------------------

Writing data triggers a gNMI **Set** request. The MD-SAL transaction types map
directly to gNMI operations:

* **put()** maps to gNMI **Replace**.
* **merge()** maps to gNMI **Update**.
* **delete()** maps to gNMI **Delete**.

Set / Replace Data
~~~~~~~~~~~~~~~~~~

.. code-block:: java

    final YangInstanceIdentifier testPath = ...;
    final ContainerNode testData = ...;

    final DOMDataTreeWriteTransaction writeTransaction = domDataBroker.newWriteOnlyTransaction();

    // Uses gNMI Replace
    writeTransaction.put(LogicalDatastoreType.CONFIGURATION, testPath, testData);
    writeTransaction.commit().get();

Update / Merge Data
~~~~~~~~~~~~~~~~~~~

.. code-block:: java

    final ContainerNode updateData = ...;
    final DOMDataTreeWriteTransaction writeTransaction = domDataBroker.newWriteOnlyTransaction();

    // Uses gNMI Update
    writeTransaction.merge(LogicalDatastoreType.CONFIGURATION, testPath, updateData);
    writeTransaction.commit().get();

Delete Data
~~~~~~~~~~~

.. code-block:: java

    final DOMDataTreeWriteTransaction writeTransaction = domDataBroker.newWriteOnlyTransaction();

    // Uses gNMI Delete
    writeTransaction.delete(LogicalDatastoreType.CONFIGURATION, testPath);
    writeTransaction.commit().get();


Advanced Features
-----------------

Manually Creating GnmiDataBroker
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

If you need to instantiate a ``GnmiDataBroker`` manually (e.g., for testing or custom integration without using the
Mount Point service), you can use the ``GnmiDataBrokerFactoryImpl``. This requires assembling several components,
including the ``DeviceConnection``, which encapsulates the session, security, and status listener logic.

Here is an example of how to manually wire these components:

.. code-block:: java

    public GnmiDataBroker createBroker(DataBroker dataBroker, ExecutorService executorService) {

        // 1. Define Device Configuration (The Node)
        String deviceIp = "127.0.0.1";
        int devicePort = 10161;
        NodeId nodeId = new NodeId("manual-device-1");

        Node node = new NodeBuilder()
            .setNodeId(nodeId)
            // Note: addAugmentation takes the instance directly
            .addAugmentation(new GnmiNodeBuilder()
                .setConnectionParameters(new ConnectionParametersBuilder()
                    .setHost(new Host(new IpAddress(new Ipv4Address(deviceIp))))
                    .setPort(new PortNumber(Uint16.valueOf(devicePort)))
                    // Using Insecure for this example
                    .setSecurityChoice(new InsecureDebugOnlyBuilder()
                        .setConnectionType(
                            org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.security
                            .choice.InsecureDebugOnly.ConnectionType.INSECURE)
                        .build())
                    .build())
                .setExtensionsParameters(new ExtensionsParametersBuilder()
                    .setGnmiParameters(new GnmiParametersBuilder()
                        .setUseModelNamePrefix(true)
                        .build())
                    .build())
                .build())
            .build();

        // 2. Prepare Internal Session Configuration
        // Note: usePlainText=true implies no TLS.
        SessionConfiguration sessionConfig = new SessionConfiguration(
            new InetSocketAddress(deviceIp, devicePort), true);

        // 3. Create Security Object
        // For secure connections, you would use SecurityFactory.createGnmiSecurity(ca, clientCert, key)
        Security security = SecurityFactory.createInsecureGnmiSecurity();

        // 4. Initialize Session Factories
        GnmiSessionFactory gnmiSessionFactory = new GnmiSessionFactoryImpl();
        SessionManagerFactory sessionManagerFactory = new SessionManagerFactoryImpl(gnmiSessionFactory);

        // 5. Create Session Manager & Provider
        SessionManager sessionManager = sessionManagerFactory.createSessionManager(security);
        SessionProvider sessionProvider = sessionManager.createSession(sessionConfig);

        // 6. Create Status Listener
        // This component monitors the gRPC channel and updates the Operational Datastore
        GnmiConnectionStatusListener statusListener = new GnmiConnectionStatusListener(
            sessionProvider,
            dataBroker,
            nodeId,
            executorService);

        // Start listening to state changes
        statusListener.init();

        // 7. Create Device Connection
        // This wrapper holds the session, listener, and node configuration together
        DeviceConnection deviceConnection = new DeviceConnection(
            sessionProvider,
            statusListener,
            node);

        // 8. Create the Data Broker
        GnmiDataBrokerFactory dataBrokerFactory = new GnmiDataBrokerFactoryImpl();
        GnmiDataBroker gnmiDataBroker = dataBrokerFactory.create(deviceConnection);

        return gnmiDataBroker;
    }

Runtime YANG Model Updates
~~~~~~~~~~~~~~~~~~~~~~~~~~

If a device requires YANG models that were not provided during initialization,
they can be uploaded at runtime using the ``upload-yang-model`` RPC. This must
be done **before** connecting the device.

.. code-block:: java

    // 'domRpcService' is the global MD-SAL RPC service (injected)
    final NormalizedNode yangModelInput = getYangModelInput(YANG_NAME, YANG_BODY, YANG_VERSION);

    // Invoke RPC to upload the model
    domRpcService.invokeRpc(UPLOAD_YANG_RPC_QN, yangModelInput).get();

Register Client Certificates
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

For secure TLS connections, certificates (CA, Client Certificate, Client Key)
can be programmatically added to the keystore via the
``add-keystore-certificate`` RPC.

.. code-block:: java

    final NormalizedNode certificateInput = getCertificateInput(
            CERT_ID, CA_VALUE, CLIENT_CERT, CLIENT_KEY, PASSPHRASE);

    // Invoke RPC to add certificate
    domRpcService.invokeRpc(ADD_KEYSTORE_RPC_QN, certificateInput).get();

The ``keystore-id`` used here can then be referenced in the Node configuration
when connecting the device.

gNMI Device Simulator
---------------------

This simulator provides a gNMI device driven by gNMI proto files, with a datastore
defined by a set of YANG models.

Build and Run
~~~~~~~~~~~~~

1.  **Add Maven Dependency**:

    .. code-block:: xml

        <dependency>
            <groupId>org.opendaylight.gnmi</groupId>
            <artifactId>gnmi-device-simulator</artifactId>
            <version>2.0.0</version>
        </dependency>

2.  **Initialize and Start**:

    Setting the configuration for the gNMI device simulator is required. Use
    ``GnmiSimulatorConfUtils`` to load the configuration. You can load a default
    configuration or load it from a file. Specifying the YANG models is required;
    they can be loaded from the classpath via ``schemaServiceConfig`` or from a
    folder via ``yangsPath``.

    **Load Default Configuration:**

    .. code-block:: java

        GnmiSimulatorConfiguration gnmiSimulatorConfiguration = GnmiSimulatorConfUtils.loadDefaultGnmiSimulatorConfiguration();

    **Load Configuration from File:**

    .. code-block:: java

        GnmiSimulatorConfiguration gnmiSimulatorConfiguration = GnmiSimulatorConfUtils
                .loadGnmiSimulatorConfiguration(Files.newInputStream(Path.of(CONFIG_PATH)));

    **Example Configuration JSON:**

    .. code-block:: json

        {
            "gnmi_simulator":{
                "targetAddress": "0.0.0.0",
                "targetPort": 3333,
                "initialStateDataPath": "./simulator/initialJsonData.json",
                "initialConfigDataPath": "./simulator/initialJsonData.json",
                "certPath": "./simulator/certs/server.crt",
                "certKeyPath": "./simulator/certs/server.key",
                "yangsPath": "./yangs",
                "username": "admin",
                "password": "admin",
                "maxConnections": 50,
                "schemaServiceConfig": {
                    "topLevelModels": [
                        { "nameSpace":"http://openconfig.net/yang/aaa","name":"openconfig-aaa","revision":"2020-07-30"},
                        { "nameSpace":"http://openconfig.net/yang/interfaces","name":"openconfig-interfaces","revision":"2021-04-06"}
                        // ... additional models ...
                    ]
                }
            }
        }

    **Start the Simulator:**

    .. code-block:: java

        final SimulatedGnmiDevice simulatedGnmiDevice = new SimulatedGnmiDevice()
                .from(gnmiSimulatorConfiguration)
                .build();
        simulatedGnmiDevice.start();

3.  **Stop the Simulator**:

    .. code-block:: java

        simulatedGnmiDevice.stop();


Configuration Options
~~~~~~~~~~~~~~~~~~~~~

Configuration is managed via the ``GnmiSimulatorConfiguration`` class.

* **setTargetAddress(String)**: (default: "0.0.0.0") Set host address.
* **setTargetPort(int)**: (default: 10161) Set port value.
* **setInitialConfigDataPath(String)**: Path to a JSON file containing initial configuration data.
* **setInitialStateDataPath(String)**: Path to a JSON file containing initial operational data.
* **setMaxConnections(int)**: (default: 50) Number of queued connections.
* **setCertPath(String)**: (default: "certs/server.crt") Path to the certificate file.
* **setCertKeyPath(String)**: (default: "certs/server.key") Path to the private key file.
* **setYangsPath(String)**: Path to a folder containing YANG models.
* **setUsername(String)**: Sets username for authentication.
* **setPassword(String)**: Sets password for authentication.
* **setUsePlaintext(boolean)**: Disables TLS validation if true.
* **setSupportedEncodings(EnumSet<Gnmi.Encoding>)**: Overwrites default supported encodings.
* **setGsonInstance(Gson)**: Customize the Gson parser instance.
* **setYangModulesInfo(Set<YangModuleInfo>)**: Load YANG models from the classpath.


Example with gnmic
~~~~~~~~~~~~~~~~~~

This example shows how to verify the simulator using the ``gnmic`` client.

1.  **Start Simulator**:
    Configure the simulator with custom certificates and credentials.

    .. code-block:: java

        GnmiSimulatorConfiguration config = new GnmiSimulatorConfiguration()
                .setYangsPath(YANG_MODELS_PATH)
                .setInitialConfigDataPath(INITIAL_CONFIGURATION_PATH)
                .setTargetAddress("127.0.0.1")
                .setCertPath(SERVER_CERTIFICATE)
                .setCertKeyPath(SERVER_PKCS8_KEY)
                .setUsername("Admin")
                .setPassword("Admin")
                .setTargetPort(9090)
                .build();

        SimulatedGnmiDevice device = new SimulatedGnmiDevice(config);
        device.start();

2.  **Get Capabilities**:

    .. code-block:: bash

        gnmic -a 127.0.0.1:9090 capabilities --tls-ca CA_CERTIFICATE \
            --tls-cert CLIENT_CERTIFICATE --tls-key CLIENT_KEY \
            -u Admin -p Admin

3.  **Get Data**:

    .. code-block:: bash

        gnmic -a 127.0.0.1:9090 --tls-ca CA_CERTIFICATE --tls-cert CLIENT_CERTIFICATE \
            --tls-key CLIENT_KEY --path interfaces/interface[name=br0]/ethernet/config \
            --encoding json_ietf -u Admin -p Admin

4.  **Set Data**:

    .. code-block:: bash

        gnmic -a 127.0.0.1:9090 --tls-ca CA_CERTIFICATE --tls-cert CLIENT_CERTIFICATE \
            --tls-key CLIENT_KEY set --update-path interfaces/interface[name=br0]/ethernet/config \
            --update-file updateFile.json --encoding json_ietf -u Admin -p Admin


gNOI Support
~~~~~~~~~~~~

The simulator implements specific **gNOI (gRPC Network Operations Interface)** services:

* **file.proto**: ``get`` (downloads dummy file), ``stat`` (returns file stats).
* **system.proto**: ``time`` (returns current time).

Other gNOI RPCs return predefined responses without underlying logic.

gNMI Southbound Connector
-------------------------

This module provides the low-level tools to manage and communicate with gNMI devices.
Details about gNMI can be found in the `official specification <https://github.com/openconfig/reference/blob/master/rpc/gnmi/gnmi-specification.md>`__.

Notable Classes
~~~~~~~~~~~~~~~

* **GnmiSessionManager**: Creates and manages sessions to gNMI devices. Instances can be created by ``GnmiSessionManagerFactory``.
* **GnmiSession**: Provides Get, Set, Capabilities, and Subscribe operations to communicate with gNMI devices. Instances can be created by ``GnmiSessionFactory``.


Certificates
~~~~~~~~~~~~

Valid SSL certificates are necessary for proper gNMI functionality. The project
includes scripts to help generate certificates for testing purposes.

The script ``src/main/scripts/generate_certs.sh`` in the ``gnmi-connector``
module can be used to generate new certificates if the included ones expire.

To check expiration dates:

.. code-block:: bash

    openssl x509 -in <PATH_TO_CERTIFICATE> -dates -noout