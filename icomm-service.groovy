/*
* Hubitat-iCOMM Integration
* Copyright 2025 Mike Bishop,  All Rights Reserved
*
* Based on:
* - py-aosmith by bdr99, https://github.com/bdr99/py-aosmith/
* - Schluter Ditra driver by Marc Reyhner, https://github.com/marcre/hubitat-drivers
*
* This Hubitat driver is designed for use with a State/A.O. Smith iCOMM-enabled
* water heater.
*
* Preferences:
* Email Address = REQUIRED. The e-mail address for the iCOMM account
* Password = REQUIRED. Login password for the iCOMM account
* RefreshRate = REQUIRED; DEFAULT is 5 minutes. The rate at which the water heaters will be polled
* DebugLogs = OPTIONAL - DEFAULT = false. Should debug logging be enabled?
*
* Change Log:
* [6/7/2026]   Fix breaking iCOMM GraphQL API changes (brand header, app
*              version, login locale, flattened device location field, new
*              HeatPump / RE3Premium device types) and remove the now-unused
*              Brand preference
* [5/9/2025]   Initial release
*/

import groovy.transform.Field

@Field static final String BASE_URI = "https://r2.wh8.co"
@Field static final String APP_VERSION = "14.1.0"
@Field static final String USER_AGENT = "okhttp/4.12.0"
// The API expects a fixed brand header for all brands.
@Field static final String BRAND_HEADER = "icomm"

metadata{
    definition ( name: "iCOMM", namespace: "evequefou", author: "Mike Bishop", importUrl: "https://raw.githubusercontent.com/MikeBishop/hubitat-icomm/refs/heads/main/icomm-service.groovy" ) {
        // Attempting to indicate what capabilities the device should be capable of
        capability "Refresh"

        // Keep track of connection status
        attribute "Status", "string"
    }
    preferences{
        section{
            // Login information for the iCOMM account
            input( type: "string", name: "EmailAddress", title: "<font color='FF0000'><b>iCOMM account e-mail address</b></font>", required: true )
            input( type: "password", name: "Password", title: "<font color='FF0000'><b>iCOMM account password</b></font>", required: true )

            // Enum to allow selecting the refresh rate that the device will be checked
            input( type: "enum", name: "RefreshRate", title: "<b>Refresh Rate</b>", required: false, multiple: false, options: [ "15 seconds", "30 seconds", "1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour", "3 hours", "Manual" ], defaultValue: "5 minutes" )

            // Enum to set the level of logging that will be used
            input( type: "bool", name: "DebugLogs", title: "<b>Enable Debug Logging?</b>", required: true, multiple: false, defaultValue: false )
        }
    }
}

// uninstalling device so make sure to clean up children
void uninstalled() {
	// Delete all children
	getChildDevices().each{
		deleteChildDevice( it.deviceNetworkId )
	}
    log.info("Uninstalled")
}

// updated is called whenever device parameters are saved
// Sets the current version of the driver, basic settings, and schedules
def updated() {

    // On update clear out any old session id in case login information changed
    state.remove("accessToken")

    // Check if the refresh rate is not set for some reason and putting it at the default
    if( RefreshRate == null ) {
        RefreshRate = "5 minutes"
    }

    def Hour = ( new Date().format( "h" ) as int )
    def Minute = ( new Date().format( "m" ) as int )
    def Second = ( new Date().format( "s" ) as int )
    Second = ( (Second + 5) % 60 )

    // Check what the refresh rate is set for then run it
    switch( RefreshRate ){
        case "15 seconds": // Schedule the refresh check for every 15 seconds
            schedule( "0/15 * * ? * *", "refresh" )
            break
        case "30 seconds": // Schedule the refresh check for every 30 seconds
            schedule( "0/30 * * ? * *", "refresh" )
            break
        case "1 minute": // Schedule the refresh check for every minute
            schedule( "${ Second } * * ? * *", "refresh" )
            break
        case "5 minutes": // Schedule the refresh check for every 5 minutes
            schedule( "${ Second } 0/5 * ? * *", "refresh" )
            break
        case "10 minutes": // Schedule the refresh check for every 10 minutes
            schedule( "${ Second } 0/10 * ? * *", "refresh" )
            break
        case "15 minutes": // Schedule the refresh check for every 15 minutes
            schedule( "${ Second } 0/15 * ? * *", "refresh" )
            break
        case "30 minutes": // Schedule the refresh check for every 30 minutes
            schedule( "${ Second } 0/30 * ? * *", "refresh" )
            break
        case "1 hour": // Schedule the refresh check for every hour
            schedule( "${ Second } ${ Minute } * ? * *", "refresh" )
            break
        case "3 hours": // Schedule the refresh check for every 3 hours
            schedule( "${ Second } ${ Minute } 0/3 ? * *", "refresh" )
            break
        default:
            unschedule( "refresh" )
            RefreshRate = "Manual"
            break
    }
    log.info( "Refresh rate: ${ RefreshRate }" )

    log.info("Updated")
}

def CanProceed() {
    if( EmailAddress == null || Password == null){
        UpsertAttribute( "Status", "Missing account email or password" )
        log.error( "Cannot update without username and password" )

        return false
    }

    return true
}

// refresh runs the device polling
def refresh() {

    if (!CanProceed()) {
        return
    }

    try {

        if( LoginIfNoToken() ) {
            // Wait for the login to complete
            runIn(5, "refresh")
        }
        else {
            sendGraphQLRequest(GET_DEVICES_QUERY, [:], "ProcessGetDevicesResponse")
        }

    }
    catch (IOException e)
    {
        log.error( "Error connecting to API for status. ${e}" )
        UpsertAttribute( "Status", "Connection Failed: ${e.message}" )
    }
}

def LoginIfNoToken() {

    if (state.containsKey("accessToken")) {
        if (DebugLogsEnabled()) log.trace("Valid session, no need to request new")

        return false
    }

    def credentials = ['email': EmailAddress, 'password': Password, 'locale': 'en'];
    def jsonString = groovy.json.JsonOutput.toJson(credentials);
    def urlEncoded = java.net.URLEncoder.encode(jsonString, "UTF-8");
    def passcode = new String(
        org.apache.commons.codec.binary.Base64.encodeBase64(urlEncoded.getBytes("UTF-8")),
        "UTF-8"
    );

    sendGraphQLRequest(LOGIN_QUERY, [passcode: passcode], "ProcessLoginResponse", false)
    return true
}

def ProcessLoginResponse(response) {
    debug("Login raw data = \"${response.getData()}\"")

    state.accessToken = response.getData()?.data?.login?.user?.tokens?.accessToken

    if (state.accessToken == null) {
        log.error("Login failed, no access token received.")
        UpsertAttribute( "Status", "Login failed" )
        return
    }
    else {
        UpsertAttribute( "Status", "Login successful" )
        debug("Succesfully logged in to iCOMM API.")
    }
}

def ProcessGetDevicesResponse(response) {
    debug("Got response ${response.getData()?.data?.devices}")

    def waterHeaters = response.getData()?.data?.devices.findAll {
        ["HeatPump", "NextGenHeatPump", "RE3Connected", "RE3Premium"].contains(it?.data?.__typename)
    };

    if (waterHeaters == null || waterHeaters.size() == 0) {
        log.warn("No compatible water heaters found.")
        UpsertAttribute( "Status", "No compatible water heaters found" )
        return
    }
    else {
        UpsertAttribute( "Status", "Connected" )
    }

    for (heater in waterHeaters) {
        ProcessDeviceUpdate(heater)
    }

    getChildDevices().each{ cd ->
        if (!waterHeaters.any { it.junctionId == cd.deviceNetworkId }) {
            log.info("Removing child device ${cd.deviceNetworkId}, ${cd.name}.")
            deleteChildDevice( cd.deviceNetworkId );
        }
    }
}

def ProcessDeviceUpdate(heater) {

    if (!getChildDevice(heater.junctionId)) {
        addChildDevice("iCOMMWaterHeater", heater.junctionId, [
            isComponent: true,
            name: heater.name ?: "${heater.location} Water Heater",
        ])
    }

    getChildDevice(heater.junctionId).ProcessUpdate(heater)
}

def ProcessModeChange(response) {
    ProcessChange(response, "updateMode");
}

def ProcessSetpointChange(response) {
    ProcessChange(response, "updateSetpoint")
}

def ProcessChange(response, fieldToCheck) {
    debug("State change response: ${response.getData()}")

    def data = response.getData()?.data;
    if (!data || data[fieldToCheck] != true) {
        log.error("Failed to change state: ${response.getData()}")
    }

    runIn(5, "refresh")
}

def sendGraphQLRequest(query, variables, handler, autologin = true, retry = false) {
    def headers = [
        "brand": BRAND_HEADER,
        "version": APP_VERSION,
        "User-Agent": USER_AGENT,
    ];

    if( state.accessToken != null ) {
        headers["Authorization"] = "Bearer ${state.accessToken}"
    }

    def body = [
        "query": query,
        "variables": variables
    ]

    def uri = "${BASE_URI}/graphql"

    try {
        httpPostJson([
            uri: uri,
            headers: headers,
            body: body
        ]) { response ->
            def status = response.getStatus();
            def data = response.getData();

            if (
                data?.errors?.extensions.any{ it.code == "UNAUTHORIZED_ERROR" } &&
                autologin
            ) {
                debug("Session expired, logging in again")

                state.remove("accessToken")
                LoginIfNoToken()

                // Retry the request after logging in
                runIn(5, "sendGraphQLRequest", [data: [query: query, variables: variables, handler: handler, autologin: false]])
            } else if (status == 200) {
                this."$handler"(response)
            } else {
                log.error("GraphQL request failed with status ${status}: ${response.getData()}")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if( e.getStatusCode() == 401 ) {
            debug("Session expired, logging in again")
            state.remove("accessToken")
            LoginIfNoToken()
            runIn(5, "sendGraphQLRequest", [data: [query: query, variables: variables, handler: handler, autologin: false]])
        }
        else {
            log.error("GraphQL request failed: ${e.message}")
        }
    } catch (java.net.SocketTimeoutException e) {
        if (!retry ) {
            log.warn("GraphQL request timed out: ${e.message}; will retry")
            runIn(5, "sendGraphQLRequest", [data: [query: query, variables: variables, handler: handler, autologin: autologin, retry: true]])
        }
        else {
            log.error("GraphQL request failed: ${e.message}")
        }
    }
}

def sendGraphQLRequest(Map params) {
    def query = params.query
    def variables = params.variables
    def handler = params.handler
    def autologin = params.autologin ?: true
    def retry = params.retry ?: false

    sendGraphQLRequest(query, variables, handler, autologin, retry)
}


// Update an attribute if it has changed
def UpsertAttribute( name, value, unit = null ) {

    if (device.currentValue(name) != value) {
        if (unit != null) {
            log.info("Event: ${name} = ${value}${unit}")
            sendEvent(name: name, value: value, unit: unit)
        } else {
            log.info("Event: ${name} = ${value}")
            sendEvent(name: name, value: value)
        }
    }
}

def DebugLogsEnabled() {
     return DebugLogs
}

def debug(String message) {
    if (DebugLogsEnabled()) {
        log.debug(message)
    }
}

@Field static final String LOGIN_QUERY = """
query login(\$passcode: String)
{
    login(passcode: \$passcode)
    {
        user
        {
            tokens
            {
                accessToken
                idToken
                refreshToken
            }
        }
    }
}
"""

@Field static final String GET_DEVICES_QUERY = """
query devices(\$forceUpdate: Boolean, \$junctionIds: [String]) {
    devices(forceUpdate: \$forceUpdate, junctionIds: \$junctionIds) {
        brand
        model
        deviceType
        dsn
        junctionId
        name
        serial
        location
        data {
            __typename
            temperatureSetpoint
            temperatureSetpointPending
            temperatureSetpointPrevious
            temperatureSetpointMaximum
            modes {
                mode
                controls
            }
            isOnline
            ... on HeatPump {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on NextGenHeatPump {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on RE3Connected {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
            }
            ... on RE3Premium {
                firmwareVersion
                hotWaterStatus
                mode
                modePending
                hotWaterPlusLevel
            }
        }
    }
}
"""
