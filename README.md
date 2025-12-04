[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.opendaylight.gnmi/gnmi-artifacts/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.opendaylight.gnmi/gnmi-artifacts)
[![License](https://img.shields.io/badge/License-EPL%201.0-blue.svg)](https://opensource.org/licenses/EPL-1.0)

# gNMI Project

## Overview

gNMI plugin implementation which provides:
* capabilities to manage network of gNMI capable devices
* ability to users to manage this network with RESTCONF as a northbound
* possibility to use this plugin within other projects such like TPCE

The scope of this project is outlined in the
[project proposal](https://lf-opendaylight.atlassian.net/wiki/spaces/ODL/pages/489062430/gNMI+project).

## Repository organization

The repository is split into the following logical parts:
* the [Bill Of Materials](artifacts)
* a library of [Karaf features](features) packaging all Java artifacts hosted in this repository
* runnable Karaf [distribution](karaf)
* gNMI plugin and simulator [implementation](gnmi-modules)
* YANG [models](gnmi-models) defining plugin behaviour
* user and developer guides [documentation](docs)
