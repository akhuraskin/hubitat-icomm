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

# Heat Pump Water Heater Scheduler (app)

`icomm-hpwh-scheduler.groovy` is a Hubitat app that runs an iCOMM heat pump
water heater on a fixed daily schedule with three temperature levels:

| Time | Level | Default |
| --- | --- | --- |
| 07:00–14:00 | Medium | 48°C / 118°F |
| 14:00–19:00 | High | 52°C / 126°F |
| 19:00–22:00 | Medium | 48°C / 118°F |
| 22:00–07:00 | Low | 35°C / 95°F (the heater's minimum, so it stays idle) |

High stores heat in the warmest part of the day, when the heat pump works
best. Low keeps the heater idle overnight, when it would work worst. Up to six
changes can be set, each with a time (in half-hour steps) and a level.

- **Heat Pump only**: while active, the app keeps the heater in Heat Pump
  only mode so the electric element isn't used.
- **Active**: while on, the app puts its temperature back if it's changed
  anywhere else. Turning it off sets Medium once and stops.
- **Force run**: High for 2 hours, then back to the schedule.
- **Away**: when the hub's mode is Away, the heater goes into Vacation mode
  for 100 days (renewed if needed). When you're back it returns to Heat Pump
  only mode and the schedule.

The app creates two switches, *Active* and *Force Run*, for dashboards, voice
assistants and rules.

## Install

1. Install the iCOMM drivers (`icomm-service.groovy` and
   `icomm-water-heater.groovy`) and make sure the water heater device works.
2. *Apps Code → New App*, paste `icomm-hpwh-scheduler.groovy`, *Save*.
3. *Apps → Add User App → iCOMM HPWH Scheduler*, pick the water heater, *Done*.

# Change Log

* [10/20/2026] Add Heat Pump Water Heater Scheduler app
* [10/18/2026] Hide controls the water heater doesn't support (cooling, fan,
  thermostat mode, schedule, set level). Only Set Heating Setpoint, Set Mode
  and Refresh remain
* [6/7/2026]   Fix breaking iCOMM GraphQL API changes (brand header, app
  version, login locale, flattened device `location` field, new HeatPump /
  RE3Premium device types) and remove the now-unused Brand preference
* [5/9/2025]   Initial release
