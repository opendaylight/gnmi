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

Supported Encodings
~~~~~~~~~~~~~~~~~~~

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
            <groupId>org.opendaylight.gnmi.modules</groupId>
            <artifactId>odl-gnmi-sb</artifactId>
            <version>${project.version}</version>
        </dependency>

2.  **Karaf Feature**:
    Ensure the feature is installed in the runtime:

    .. code-block:: bash

        feature:install odl-gnmi


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
            <groupId>org.opendaylight.gnmi.modules</groupId>
            <artifactId>odl-gnmi-device-simulator</artifactId>
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

The script ``src/main/scripts/generate_certs.sh`` in the ``odl-gnmi-connector``
module can be used to generate new certificates if the included ones expire.

To check expiration dates:

.. code-block:: bash

    openssl x509 -in <PATH_TO_CERTIFICATE> -dates -noout