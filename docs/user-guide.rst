GNMI use-case example
========================================

This example shows gNMI south-bound interface utilized with RESTCONF north-bound interface to manage gNMI devices on the network. The controller is deployed using an OSGi (Karaf) container.
It is capable to connect to gNMI devices and expose connected devices over RESTCONF north-bound APIs. In this example the gNMI simulator is started as gNMI target and all operations are performed on this device.

The example is pre-prepared with `Openconfig YANG models <yangs>`_.
These models are used by both the gNMI Karaf feature and the gNMI device simulator. Device has already preconfigured state data specified in `initialStateJsonData.json <simulator/initialStateJsonData.json>`_ JSON file and initial config data in `initialConfigJsonData.json <simulator/initialConfigJsonData.json>`_.
To communicate with gNMI device it is required to use TLS communication with certificates and username and password authorization.

This example starts:

* **OpenDaylight/Karaf Distribution** containing:

* `gNMI feature <../../features/gnmi-feature>`_

* `gNMI device simulator <../../lighty-modules/lighty-gnmi/lighty-gnmi-device-simulator>`_


Prerequisites
-------------

In order to build and start and run this example locally, you need:

* Java 21 or later
* Maven 3.9.5 or later
* Postman v7.36.5. or later
* Linux-based system with bash

How to run use-case
-------------------

This example shows how to start the Karaf distribution with the gNMI feature and the `gNMI simulator <../../lighty-modules/lighty-gnmi/lighty-gnmi-device-simulator>`_ app.
Next step describes how to perform basic CRUD operations on the gNMI device using RESTCONF.

Clone lighty repository and build it with maven. Then move to lighty-gnmi-community-restconf-app folder.

.. code-block:: bash

   git clone git@github.com:PANTHEONtech/lighty.git
   cd lighty
   mvn clean install
   cd lighty-examples/lighty-gnmi-community-restconf-app

Start the Controller (Karaf)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Navigate to the karaf distribution folder (built from the root pom) and start the container.

1. Go to the karaf target directory:

.. code-block:: bash

   cd ../../target/assembly/bin

2. Start Karaf:

.. code-block:: bash

   ./karaf

3. Once the Karaf console starts, install the gNMI feature:

.. code-block:: bash

   feature:install gnmi-feature

*Note: The RESTCONF and NETCONF features should be installed automatically as dependencies.*

**Bruno Collection:**
A ready-to-use API collection is available in the `bruno-collection.json <bruno.json>`_ file. You can import this file directly into the Bruno API client to execute the requests described below.

Start lighty.io gNMI device simulator
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Open a new terminal window, navigate back to the ``lighty-examples/lighty-gnmi-community-restconf-app`` folder, and unzip/start the simulator.

.. code-block:: bash

   # Assuming you are in lighty-examples/lighty-gnmi-community-restconf-app
   unzip ../../lighty-modules/lighty-gnmi/lighty-gnmi-device-simulator/target/lighty-gnmi-device-simulator-23.0.0-SNAPSHOT-bin.zip

Start the application with pre-prepared configuration `simulator_config.json <simulator/simulator_config.json>`_:

.. code-block:: bash

   java -jar lighty-gnmi-device-simulator-23.0.0-SNAPSHOT/lighty-gnmi-device-simulator-23.0.0-SNAPSHOT.jar -c simulator/simulator_config.json

Add client certificates to lighty.io gNMI keystore
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Certificates used in this example can be found `here <certificates>`_. Only the client certificates are added to keystore with RPC. This RPC stores certificates in configuration data-store and encrypt their private key and passphrase.
Adding required certificates for gNMI device to lighty.io gNMI application is performed by postman request ``'Add Keystore'``.

.. code-block:: bash

   curl --request POST 'http://127.0.0.1:8181/rests/operations/gnmi-certificate-storage:add-keystore-certificate' \
   -u admin:admin \
   --header 'Content-Type: application/json' \
   --data-raw "{
       \"input\": {
           \"keystore-id\": \"keystore-id-1\",
           \"ca-certificate\": \"$(cat certificates/ca.crt)\",
           \"client-key\": \"$(cat certificates/client.key)\",
           \"client-cert\": \"$(cat certificates/client.crt)\"
       }
   }"

Connect simulator to controller
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Simulated gNMI device can be connected with ``'Connect device'`` request.

.. code-block:: bash

   curl --request PUT 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator' \
   -u admin:admin \
   --header 'Content-Type: application/json' \
   --data-raw '{
       "node": [
           {
               "node-id": "gnmi-simulator",
               "connection-parameters": {
                   "host": "127.0.0.1",
                   "port": 10161,
                   "keystore-id": "keystore-id-1",
                   "credentials": {
                       "username": "admin",
                       "password": "admin"
                   }
               },
               "extensions-parameters": {
                   "gnmi-parameters": {
                       "use-model-name-prefix": true
                   }
               }
           }
       ]
   }'

Device state can be also checked by request ``'Get gnmi-simulator node'``. In the section ``gnmi-topology:node-state`` of response, current state of device or information about occurred errors can be found. If the device is connected, then the ``node-status`` property will contain the value ``READY``, in the response.

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator?content=nonconfig' \
   -u admin:admin

Read configuration from device
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The ``'Get*'`` requests show how to obtain information from controller, e.g. to get authentication information:

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication' \
   -u admin:admin

Write configuration to device
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To write authentication information the PUT request ``'Put Authentication config'`` can be used.

.. code-block:: bash

   curl --request PUT 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication' \
   -u admin:admin \
   --header 'Content-Type: application/json' \
   --data-raw '{
       "openconfig-system:authentication": {
           "config": {
               "authentication-method": [
                   "openconfig-aaa-types:TACACS_ALL"
               ]
           }
       }
   }'

This request replaces data in ``authentication/config`` container, remove the ``admin-user`` container and add new container ``state``. All request setting the specific information on gNMI simulator are applying into CONFIGURATION datastore.
To view and check changed configuration, the ``'Get Authentication from CONFIG'`` request is located in postman collection.

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication?content=config' \
   -u admin:admin

To view actual running configuration of device, it is required to sent request with ``?content=nonconfig`` parameter at the end of the URL or execute request from postman collection ``'Get Authentication from STATE'``.

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication?content=nonconfig' \
   -u admin:admin

Update configuration on device
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To update data, the example request ``'Update config data'`` can be used. This request append new type to config authentication-method.

.. code-block:: bash

   curl --request PATCH 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication/config' \
   -u admin:admin \
   --header 'Content-Type: application/json' \
   --data-raw '{
       "openconfig-system:config": {
           "authentication-method": [
               "openconfig-aaa-types:RADIUS_ALL"
           ]
       }
   }'

To validate request send GET request ``'Get Authentication from CONFIG'``.

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication?content=config' \
   -u admin:admin

Delete configuration from device
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

For deleting ``config`` container send request ``'Delete authentication config'``.

.. code-block:: bash

   curl --location --request DELETE 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication/config' \
   -u admin:admin

To validate request send GET request ``'Get Authentication from CONFIG'``.

.. code-block:: bash

   curl --request GET 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator/yang-ext:mount/openconfig-system:system/aaa/authentication?content=config' \
   -u admin:admin

Disconnect the device from controller
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

When is required restart of connection or removal of device, just send request ``'Remove device'``.

.. code-block:: bash

   curl --request DELETE 'http://127.0.0.1:8181/rests/data/network-topology:network-topology/topology=gnmi-topology/node=gnmi-simulator' \
   -u admin:admin

For restarting connection it is required to send request ``'Connect device'``.
