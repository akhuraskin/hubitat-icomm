/*
* iCOMM Water Heater
*
* Description:
* This Hubitat driver is designed for use with an iCOMM Water Heater.  This is
* the child device per water heater.  It takes no direct configuration
*
* Licensing:
* Copyright 2025 Mike Bishop
*
* Based on:
* - py-aosmith by bdr99, https://github.com/bdr99/py-aosmith/
* - Schluter Ditra driver by Marc Reyhner, https://github.com/marcre/hubitat-drivers
*/

import groovy.transform.Field
import groovy.json.JsonOutput

metadata{
    definition ( name: "iCOMMWaterHeater", namespace: "evequefou", author: "Mike Bishop", importUrl: "https://raw.githubusercontent.com/MikeBishop/hubitat-icomm/refs/heads/main/icomm-water-heater.groovy" ) {
        capability "Sensor"
        capability "Refresh"
        capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"
        capability "ThermostatSetpoint"
        capability "SwitchLevel"
        attribute "Brand", "string"
        attribute "Model", "string"
        attribute "Device Type", "string"
        attribute "DSN", "string"
        attribute "Serial Number", "string"
        attribute "Install Location", "string"
        attribute "Maximum Temperature", "number"
        attribute "Schedule Mode", "string"
        attribute "Online", "string"
        attribute "Firmware Version", "string"
        attribute "Mode", "string"
        attribute "supportedThermostatFanModes", "string"
        attribute 'supportedThermostatModes', "string"
        attribute "thermostatFanMode", "string"
        attribute "thermostatMode", "string"

        command "setMode", [
            [
                name: "Mode",
                type: "ENUM",
                constraints: [
                    "HYBRID",
                    "HEAT_PUMP",
                    "ELECTRIC",
                    "VACATION"
                ]
            ],
            [
                name: "Number of days",
                type: "NUMBER",
                required: false,
                description: "Optional, not supported by all modes"
            ]
        ]
        command "setHeatingSetpoint", [
            [
                name: "Temperature",
                type: "NUMBER",
                required: true,
                description: "Temperature in ${location.temperatureScale}"
            ]
        ]
    }
}

def installed() {
    // Set static attributes at install time
    UpsertAttribute('supportedThermostatFanModes', JsonOutput.toJson(["off"]) )
    UpsertAttribute('supportedThermostatModes', JsonOutput.toJson(["heat"]) )
    UpsertAttribute("thermostatFanMode", "off")
    UpsertAttribute("thermostatMode", "heat")
}

def ProcessUpdate(heater) {
    debug("Thermostat raw data ${heater.toString()}")

    UpsertAttribute("Brand", heater.brand)
    UpsertAttribute("Model", heater.model)
    UpsertAttribute("Device Type", heater.deviceType)
    UpsertAttribute("DSN", heater.dsn)
    device.setName(heater.name)
    UpsertAttribute("Serial Number", heater.serial)
    UpsertAttribute("Install Location", heater?.install?.location)

    def setpoint = toHubScale(heater?.data?.temperatureSetpoint)
    UpsertAttribute("thermostatSetpoint", setpoint, location.temperatureScale)
    UpsertAttribute("heatingSetpoint", setpoint, location.temperatureScale)
    UpsertAttribute(
        "Maximum Temperature",
        toHubScale(heater?.data?.temperatureSetpointMaximum),
        location.temperatureScale
    );
    UpsertAttribute("Mode", heater?.data?.mode);
    UpsertAttribute("Online", heater?.data?.isOnline.toString());
    UpsertAttribute("Firmware Version", heater?.data?.firmwareVersion);

    def waterLevel = 100;
    def waterLevelVal = heater?.data?.hotWaterStatus;
    if( waterLevelVal instanceof String ){
        switch(waterLevelVal){
            case "LOW":
                waterLevel = 0;
                break;
            case "MEDIUM":
                waterLevel = 50;
                break;
            case "HIGH":
                waterLevel = 100;
                break;
        }
    }
    else if ( waterLevelVal instanceof Number ){
        // Reported number goes up as water is used; need to convert
        waterLevel = 100 - waterLevelVal;
    }
    UpsertAttribute("level", waterLevel, "%")

    state.supportedModes = heater?.data?.modes

    if (heater?.data?.modePending || heater?.data?.temperatureSetpointPending) {
        debug("Pending changes on ${device.getDisplayName()}")
        runIn(5, "refresh")
    }

    if (device.currentValue("thermostatMode") != "heat" ) {
        installed();
    }
}

def setMode(mode, days = null) {
    debug("setMode(${mode}) on ${device.getDisplayName()} invoked.")

    def targetMode = state.supportedModes.find { it.mode == mode }

    if (targetMode == null) {
        log.warn("setMode(${mode}) on ${device.getDisplayName()} is not supported.")
        return
    }

    if (targetMode.controls == "SELECT_DAYS") {
        // Number of days is required
        if( days == null ) {
            days = 100
        }
        else if ( days < 1 || days > 100 ) {
            log.warn("Mode ${mode} on ${device.getDisplayName()} only supports 1-100 days.")
            days = days > 100 ? 100 : 1
        }
    }
    else {
        // Number of days is not supported
        if( days != null ) {
            log.warn("Mode ${mode} on ${device.getDisplayName()} does not support setting the number of days.")
            days = null
        }

        if( targetMode.mode == device.currentValue("Mode") ) {
            debug("Mode ${mode} on ${device.getDisplayName()} is already set.")
            return
        }
    }

    def modePayload = ["mode": targetMode.mode];
    if (days != null) {
        modePayload["days"] = days
    }

    getParent().sendGraphQLRequest(SET_MODE, [
        "junctionId": device.deviceNetworkId,
        "mode": modePayload
    ], "ProcessModeChange")
}

def refresh() {
    debug("refresh() on ${device.getDisplayName()} invoked.")
    getParent().refresh()
}

def normalizeTemperature(temperature) {
    def minimumInHubScale = toHubScale(95)

    if (temperature == null) {
        return minimumInHubScale
    }

    if (toApiScale(temperature) < 95) {
        log.warn("${temperature} on ${device.getDisplayName()} is less than minimum temperature ${minimumInHubScale}${location.temperatureScale}.  Will set to minimum temperature.")
        return minimumInHubScale
    }

    def maximumTemperature = device.currentValue("Maximum Temperature")
    if (maximumTemperature != null && temperature > maximumTemperature) {
        log.warn("setHeatingSetpoint(${temperature}) on ${device.getDisplayName()} is greater than maximum temperature ${maximumTemperature}${location.temperatureScale}.  Will set to maximum temperature.")
        log.info("You may be able to increase the maximum temperature from the water heater's control panel.")
        return maximumTemperature
    }

    return temperature
}

def setHeatingSetpoint(temperature) {
    debug("setHeatingSetpoint(${temperature}) on ${device.getDisplayName()} invoked.")

    temperature = normalizeTemperature(temperature)
    if (temperature == null) {
        log.warn("setHeatingSetpoint(${temperature}) on ${device.getDisplayName()} is null.  Will not set.")
        return
    }

    def canonical = snapToCanonical(temperature)
    if (canonical != temperature) {
        log.info("Adjusted requested setpoint ${temperature}${location.temperatureScale} to ${canonical}${location.temperatureScale} (closest value the device can store).")
    }

    if (canonical == device.currentValue("thermostatSetpoint")) {
        debug("setHeatingSetpoint(${canonical}) on ${device.getDisplayName()} is already set.")
        return
    }

    getParent().sendGraphQLRequest(
        SET_HEATING_SETPOINT,
        [
            "junctionId": device.deviceNetworkId,
            "value": toApiScale(canonical)
        ],
        "ProcessSetpointChange"
    )
}

def debug(String message) {
    if (getParent().DebugLogsEnabled()) {
        log.debug(message)
    }
}


// The iCOMM API is strictly Fahrenheit. These helpers translate between the
// API's units and the hub's configured display scale (location.temperatureScale).
def toHubScale(fahrenheit) {
    if (fahrenheit == null) return null
    if (location.temperatureScale == "C") {
        return Math.round((fahrenheit - 32) * 5 / 9 * 2) / 2.0
    }
    return fahrenheit as Integer
}

def toApiScale(value) {
    if (value == null) return null
    if (location.temperatureScale == "C") {
        return Math.round(value * 9 / 5 + 32) as Integer
    }
    return value as Integer
}

// 0.5 °C steps don't line up with integer °F steps, so a user-entered °C value
// can round-trip to a different °C on the next refresh. Snap to the canonical
// °C for the °F that would actually be sent, so write→read→write is stable.
def snapToCanonical(value) {
    if (value == null) return null
    return toHubScale(toApiScale(value))
}

def UpsertAttribute( Variable, Value, Unit = null ){
    if( device.currentValue(Variable) != Value ){

        if( Unit != null ){
            log.info( "Event: ${ Variable } = ${ Value }${ Unit }" )
            sendEvent( name: "${ Variable }", value: Value, unit: Unit )
        } else {
            log.info( "Event: ${ Variable } = ${ Value }" )
            sendEvent( name: "${ Variable }", value: Value )
        }
    }
}

// Dummy functions
def setLevel(level) {
    log.warn("You cannot set the level on your water heater. It refills automatically.");
}

def cool() {
    log.warn("cool() is not supported and takes no action.")
}

def emergencyHeat() {
    log.warn("emergencyHeat() is not supported and takes no action.")
}

def setCoolingSetpoint(requestedTemperator) {
    log.warn("setCoolingSetpoint() is not supported and takes no action.")
}

def fanAuto() {
    log.warn("fanAuto() is not supported and takes no action.")
}

def fanCirculate() {
    log.warn("fanCirculate() is not supported and takes no action.")
}

def fanOn() {
    log.warn("fanOn() is not supported and takes no action.")
}

def setSchedule(schedule) {
    log.warn("setSchedule() is not supported and takes no action.")
}

def setThermostatFanMode(fanmode) {
    log.warn("setThermostatFanMode() is not supported and takes no action.")
}

def setThermostatMode(mode) {
    log.warn("setThermostatMode() is not supported and takes no action.")
}

def auto() {
    log.warn("auto() is not supported and takes no action. Consider setting the thermostat mode instead.")
}

def heat() {
    log.warn("heat() is not supported and takes no action. Consider setting the thermostat mode instead.")
}

def off() {
    log.warn("off() is not supported and takes no action. Consider setting the thermostat to Vacation Mode instead.")
}

@Field static final String SET_MODE = "mutation updateMode(\$junctionId: String!, \$mode: ModeInput!) { updateMode(junctionId: \$junctionId, mode: \$mode) }";
@Field static final String SET_HEATING_SETPOINT = "mutation updateSetpoint(\$junctionId: String!, \$value: Int!) { updateSetpoint(junctionId: \$junctionId, value: \$value) }";
