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

`icomm-hpwh-scheduler.groovy` is a Hubitat app that sets the temperature of an
iCOMM heat pump water heater through the day:

Cold → Normal → Boost → Normal → Cold

| Period | Default | When |
| --- | --- | --- |
| Boost | 52°C / 126°F | The warmest hours between 13:00 and 19:00 (4 hours), so extra heat is stored while the heat pump works best and is ready by evening. Without a forecast: 15:00–19:00. |
| Cold | 43°C / 109°F | Hours forecast below 8°C / 46°F, when the heat pump would fall back to the electric element. Without a forecast: 22:00–08:00. Wins over Boost. |
| Normal | 48°C / 118°F | Every other hour. |

- **Forecast**: [Open-Meteo](https://open-meteo.com), no account needed. Uses
  the hub's location, refreshes every 3 hours, and falls back to the fixed
  times if it can't be fetched.
- **Active**: while on, the app puts its temperature back if it's changed
  anywhere else. Turning it off sets Normal once and stops.
- **Force run**: Boost for 2 hours, then back to the schedule.
- **Away**: when the hub's mode is Away, the heater goes into Vacation mode
  for 100 days (renewed if needed). When you're back it returns to its
  previous mode and the schedule resumes.
- **Optional**: switch the heater to Heat Pump only during Boost.

The app creates two switches, *Active* and *Force Run*, for dashboards, voice
assistants and rules. All times and temperatures can be changed in the app.

## Install

1. Install the iCOMM drivers (`icomm-service.groovy` and
   `icomm-water-heater.groovy`) and make sure the water heater device works.
2. *Apps Code → New App*, paste `icomm-hpwh-scheduler.groovy`, *Save*.
3. *Apps → Add User App → iCOMM HPWH Scheduler*, pick the water heater, *Done*.
4. Check that *Settings → Location* has the hub's latitude and longitude, or
   the app will use the fixed times.

# Change Log

* [10/19/2026] Add Heat Pump Water Heater Scheduler app
* [10/18/2026] Hide controls the water heater doesn't support (cooling, fan,
  thermostat mode, schedule, set level). Only Set Heating Setpoint, Set Mode
  and Refresh remain
* [6/7/2026]   Fix breaking iCOMM GraphQL API changes (brand header, app
  version, login locale, flattened device `location` field, new HeatPump /
  RE3Premium device types) and remove the now-unused Brand preference
* [5/9/2025]   Initial release
