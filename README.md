# iCOMM for Hubitat

Water heaters made by A.O. Smith / State use a connectivity service called
iCOMM. This can be accessed by their app, but the API can also be called
directly.

This driver implements a polling approach to retrieving data from the API and
exposing it as a device in Hubitat.

Based on:
- [py-aosmith](https://github.com/bdr99/py-aosmith/) by Brandon Rothweiler for
  the API
- [Schluter Ditra driver](https://github.com/marcre/hubitat-drivers) by Marc
  Reyhner for the driver framework

# Change Log

* [6/7/2026]   Fix breaking iCOMM GraphQL API changes (brand header, app
  version, login locale, flattened device `location` field, new HeatPump /
  RE3Premium device types) and remove the now-unused Brand preference
* [5/9/2025]   Initial release
