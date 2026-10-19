/*
* iCOMM Heat Pump Water Heater Scheduler
*
* Description:
* Hubitat app that sets the temperature of an iCOMM heat pump water heater
* (driver: iCOMMWaterHeater) through the day:
*
*   Cold -> Normal -> Boost -> Normal -> Cold
*
* - Boost:  stores extra heat during the warmest hours of the afternoon, when
*           the heat pump works best, finishing by the time hot water is
*           needed in the evening.
* - Cold:   lowers the temperature for hours forecast below the cold threshold,
*           when the heat pump would fall back to the electric element. Uses
*           fixed night hours when no forecast is available.
* - Normal: every other hour.
*
* While active, the app overrides any temperature set elsewhere. When the hub
* is in an Away mode, the heater is put in Vacation mode and restored when you
* return. A force run raises the temperature to Boost for a set time.
*
* The forecast comes from Open-Meteo (https://open-meteo.com), which needs no
* account, using the hub's location.
*
* Change Log:
* [10/19/2026] Initial release
*/

import groovy.transform.Field

@Field static final Map DEFAULTS_C = [boost: 52, normal: 48, cold: 43, threshold: 8]
@Field static final Map DEFAULTS_F = [boost: 126, normal: 118, cold: 109, threshold: 46]
@Field static final Integer VACATION_DAYS = 100
@Field static final Long VACATION_RENEW_MS = 99L * 24 * 3600 * 1000
@Field static final Long FORECAST_MAX_AGE_MS = 12L * 3600 * 1000
@Field static final Long COMMAND_RETRY_MS = 4L * 60 * 1000
@Field static final Long RESTORE_TIMEOUT_MS = 60L * 60 * 1000
@Field static final Integer API_MIN_F = 95
@Field static final String FORECAST_URI = "https://api.open-meteo.com"

definition(
    name: "iCOMM HPWH Scheduler",
    namespace: "evequefou",
    author: "akhuraskin",
    description: "Schedules an iCOMM heat pump water heater using the weather forecast",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/akhuraskin/hubitat-icomm/refs/heads/main/icomm-hpwh-scheduler.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------------------
// Settings page
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "iCOMM Heat Pump Water Heater Scheduler", install: true, uninstall: true) {
        if (app.getInstallationState() == "COMPLETE" && heater) {
            section("Status") {
                paragraph statusHtml()
                input name: "btnActive", type: "button", title: state.active ? "Turn off" : "Turn on"
                if (forceRunActive()) {
                    input name: "btnCancelForceRun", type: "button", title: "Cancel force run"
                } else {
                    input name: "btnForceRun", type: "button", title: "Force run (${fmtHours(forceRunHours())})"
                }
                if (forecastEnabled()) {
                    input name: "btnRefreshForecast", type: "button", title: "Refresh forecast"
                }
            }
        }

        section("Water heater") {
            input "heater", "device.iCOMMWaterHeater", title: "iCOMM water heater", required: true
        }

        def unit = "°${scale()}"
        section("Temperatures") {
            input "boostTemp", "decimal", title: "Boost (${unit})", required: true,
                defaultValue: defaultFor("boost"), range: tempRange()
            input "normalTemp", "decimal", title: "Normal (${unit})", required: true,
                defaultValue: defaultFor("normal"), range: tempRange()
            input "coldTemp", "decimal", title: "Cold (${unit})", required: true,
                defaultValue: defaultFor("cold"), range: tempRange()
            paragraph "Values above the heater's maximum temperature are capped at the maximum."
        }

        section("Schedule") {
            input "useForecast", "bool", title: "Use weather forecast (Open-Meteo)", defaultValue: true
            input "coldThreshold", "decimal", title: "Cold when the forecast is below (${unit})", required: true,
                defaultValue: defaultFor("threshold"), range: (scale() == "C" ? "-40..40" : "-40..104")
            input "boostLength", "enum", title: "Boost length (hours)", required: true,
                options: (1..8).collect { it.toString() }, defaultValue: "4"
            input "boostEarliest", "enum", title: "Boost may start from", required: true,
                options: hourOptions(), defaultValue: "13"
            input "boostLatest", "enum", title: "Hot water needed by (Boost ends)", required: true,
                options: hourOptions(), defaultValue: "19"
            if (boostLatestHour() <= boostEarliestHour()) {
                paragraph "<span style='color:red'>Boost must end later than it may start. No Boost will run.</span>"
            }
            paragraph "With a forecast, Boost runs in the warmest hours in that range. " +
                "Without one, it runs for the last hours before hot water is needed, " +
                "and Cold uses the night hours below."
            input "coldStart", "enum", title: "Night starts (no forecast)", required: true,
                options: hourOptions(), defaultValue: "22"
            input "coldEnd", "enum", title: "Night ends (no forecast)", required: true,
                options: hourOptions(), defaultValue: "8"
        }

        section("Force run") {
            input "forceRunHours", "decimal", title: "Force run length (hours)", required: true,
                defaultValue: 2, range: "0.5..12"
        }

        section("Away") {
            input "awayModes", "mode", title: "Hubitat modes that mean Away", multiple: true, required: false,
                defaultValue: ["Away"]
            paragraph "When Away, the heater is put in Vacation mode for ${VACATION_DAYS} days. " +
                "It returns to its previous mode when you're back."
        }

        section("Options") {
            input "heatPumpDuringBoost", "bool", title: "Switch the heater to Heat Pump only during Boost",
                defaultValue: false
            input "debugLogs", "bool", title: "Debug logging", defaultValue: false
            label title: "App name", required: false
        }
    }
}

def appButtonHandler(String btn) {
    if (!heater) return
    switch (btn) {
        case "btnActive":
            setActive(!state.active)
            break
        case "btnForceRun":
            startForceRun()
            break
        case "btnCancelForceRun":
            cancelForceRun()
            break
        case "btnRefreshForecast":
            fetchForecast()
            break
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    state.active = true
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (state.active == null) state.active = true

    createChildSwitches()
    syncSwitches()

    subscribe(heater, "heatingSetpoint", "heaterHandler")
    subscribe(heater, "Mode", "heaterHandler")
    subscribe(location, "mode", "locationModeHandler")
    if (activeSwitch()) subscribe(activeSwitch(), "switch", "activeSwitchHandler")
    if (forceRunSwitch()) subscribe(forceRunSwitch(), "switch", "forceRunSwitchHandler")

    // Every 5 minutes, on the hour too, so period changes apply on time
    schedule("0 0/5 * ? * *", "applySchedule")
    // Forecast every 3 hours, at a random minute to spread load
    def minute = 2 + (int) (Math.random() * 50)
    schedule("0 ${minute} 0/3 ? * *", "fetchForecast")

    if (forceRunActive()) {
        runIn(Math.max(1L, (long) ((state.boostUntil - now()) / 1000)), "endForceRun")
    }

    fetchForecast()
    applySchedule()
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

def heaterHandler(evt) {
    logDebug("Heater ${evt.name} changed to ${evt.value}")
    applySchedule()
}

def locationModeHandler(evt) {
    logDebug("Hub mode changed to ${evt.value}")
    applySchedule()
}

def activeSwitchHandler(evt) {
    setActive(evt.value == "on")
}

def forceRunSwitchHandler(evt) {
    if (evt.value == "on") {
        if (!forceRunActive()) startForceRun()
    } else {
        if (forceRunActive()) cancelForceRun()
    }
}

// ---------------------------------------------------------------------------
// Active / force run
// ---------------------------------------------------------------------------

def setActive(Boolean on) {
    if (state.active == on) return
    state.active = on
    if (on) {
        logInfo("Turned on")
        state.deactivating = false
    } else {
        logInfo("Turned off; setting Normal and stopping")
        stopForceRun()
        state.deactivating = true
        state.deactivatingSince = now()
    }
    syncSwitches()
    applySchedule()
}

def startForceRun() {
    if (!state.active) {
        logInfo("Force run ignored: the app is turned off")
        switchOff(forceRunSwitch())
        return
    }
    if (isAway()) {
        logInfo("Force run ignored: Away")
        switchOff(forceRunSwitch())
        return
    }
    def hours = forceRunHours()
    state.boostUntil = now() + (long) (hours * 3600 * 1000)
    runIn((long) (hours * 3600), "endForceRun")
    logInfo("Force run started until ${fmtTime(state.boostUntil)}")
    switchOn(forceRunSwitch())
    applySchedule()
}

def cancelForceRun() {
    if (!forceRunActive()) return
    logInfo("Force run cancelled")
    stopForceRun()
    applySchedule()
}

def endForceRun() {
    if (state.boostUntil == null) return
    logInfo("Force run finished")
    stopForceRun()
    applySchedule()
}

def stopForceRun() {
    state.boostUntil = null
    unschedule("endForceRun")
    switchOff(forceRunSwitch())
}

Boolean forceRunActive() {
    return state.boostUntil != null && now() < state.boostUntil
}

// ---------------------------------------------------------------------------
// Main logic
// ---------------------------------------------------------------------------

def applySchedule() {
    if (!heater) return

    if (state.boostUntil != null && now() >= state.boostUntil) {
        logInfo("Force run finished")
        stopForceRun()
    }

    if (!state.active) {
        runDeactivation()
        return
    }

    def mode = heater.currentValue("Mode")

    if (isAway()) {
        handleAway(mode)
        return
    }
    if (state.awayActive) {
        handleReturn()
    }

    if (state.restoreMode) {
        if (mode == "VACATION" && now() - (state.restoreSince ?: now()) < RESTORE_TIMEOUT_MS) {
            sendMode(state.restoreMode)
            setStatus("Back from Away: restoring ${modeName(state.restoreMode)} mode")
            return
        }
        if (mode == "VACATION") {
            log.warn("Heater did not leave Vacation mode after returning from Away")
        }
        state.restoreMode = null
        state.restoreSince = null
    }

    if (mode == "VACATION") {
        setStatus("Heater is in Vacation mode (set outside this app); waiting")
        return
    }

    def target = currentTarget()
    applyBoostMode(target.period == "Boost" || target.period == "Force run", mode)
    applySetpoint(target.temp)
    setStatus("${target.period} ${fmtTemp(target.temp)}" + (target.source ? " (${target.source})" : ""))
}

def handleAway(mode) {
    if (!state.awayActive) {
        logInfo("Away: putting heater in Vacation mode")
        state.awayActive = true
        state.restoreMode = null
        state.restoreSince = null
        if (state.boostUntil != null) {
            logInfo("Force run cancelled: Away")
            stopForceRun()
        }
    }

    if (mode == null) {
        setStatus("Away: waiting for heater status")
        return
    }

    if (mode != "VACATION") {
        if (!state.weSetVacation) {
            state.modeBeforeAway = state.modeBeforeBoost ?: mode
            state.weSetVacation = true
        }
        state.modeBeforeBoost = null
        sendVacation(false)
    } else if (state.weSetVacation && state.vacationSentAt != null &&
               now() - state.vacationSentAt > VACATION_RENEW_MS) {
        logInfo("Renewing Vacation mode for another ${VACATION_DAYS} days")
        sendVacation(true)
    }
    setStatus("Away: heater in Vacation mode")
}

def handleReturn() {
    state.awayActive = false
    if (state.weSetVacation) {
        state.restoreMode = state.modeBeforeAway ?: "HYBRID"
        state.restoreSince = now()
        logInfo("Back from Away: restoring ${modeName(state.restoreMode)} mode")
    }
    state.weSetVacation = false
    state.modeBeforeAway = null
    state.vacationSentAt = null
}

// Undo what the app changed, set Normal once, then stop.
def runDeactivation() {
    if (!state.deactivating) {
        setStatus("Off")
        return
    }

    def mode = heater.currentValue("Mode")
    if (state.awayActive) handleReturn()

    if (state.restoreMode && mode == "VACATION" &&
        now() - (state.deactivatingSince ?: now()) < RESTORE_TIMEOUT_MS) {
        sendMode(state.restoreMode)
        setStatus("Turning off: restoring ${modeName(state.restoreMode)} mode")
        return
    }
    state.restoreMode = null
    state.restoreSince = null

    if (state.modeBeforeBoost) {
        if (mode == "HEAT_PUMP") sendMode(state.modeBeforeBoost)
        state.modeBeforeBoost = null
    }

    if (mode != "VACATION") {
        applySetpoint(clampTemp(normalTemp()))
    }
    state.deactivating = false
    state.deactivatingSince = null
    setStatus("Off")
}

def applyBoostMode(Boolean boosting, mode) {
    if (boosting && settings.heatPumpDuringBoost) {
        if (mode && mode != "HEAT_PUMP" && state.modeBeforeBoost == null) {
            logInfo("Boost: switching heater to Heat Pump only")
            state.modeBeforeBoost = mode
            sendMode("HEAT_PUMP")
        }
    } else if (state.modeBeforeBoost) {
        if (mode == "HEAT_PUMP") {
            logInfo("Boost over: restoring ${modeName(state.modeBeforeBoost)} mode")
            sendMode(state.modeBeforeBoost)
        }
        state.modeBeforeBoost = null
    }
}

def applySetpoint(target) {
    def current = heater.currentValue("heatingSetpoint")
    if (current != null && toF(current) == toF(target)) return

    // Don't resend while the heater hasn't reported yet, unless its
    // setpoint changed again in the meantime (someone else set it).
    def currentF = current != null ? toF(current) : null
    def last = state.lastSetpointCmd
    if (last && last.f == toF(target) && last.seenF == currentF && now() - last.at < COMMAND_RETRY_MS) return

    logInfo("Setting ${heater.displayName} to ${fmtTemp(target)} (was ${fmtTemp(current)})")
    heater.setHeatingSetpoint(target)
    state.lastSetpointCmd = [f: toF(target), seenF: currentF, at: now()]
}

Boolean sendMode(String mode, Integer days = null, Boolean force = false) {
    def last = state.lastModeCmd
    if (!force && last && last.mode == mode && now() - last.at < COMMAND_RETRY_MS) return false

    logInfo("Setting ${heater.displayName} mode to ${modeName(mode)}" + (days ? " for ${days} days" : ""))
    if (days) {
        heater.setMode(mode, days)
    } else {
        heater.setMode(mode)
    }
    state.lastModeCmd = [mode: mode, at: now()]
    return true
}

def sendVacation(Boolean force) {
    if (sendMode("VACATION", VACATION_DAYS, force)) {
        state.vacationSentAt = now()
    }
}

Map currentTarget() {
    if (forceRunActive()) {
        return [period: "Force run", temp: targetTemp("Boost"), source: null]
    }
    def entry = buildPlan(0)[currentHour()]
    return [period: entry.period, temp: targetTemp(entry.period), source: entry.source]
}

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

// One entry per hour of the local day: [hour, period, outdoor, source]
List buildPlan(int dayOffset) {
    def temps = forecastForDay(dayOffset)
    def source = temps ? "forecast" : "fixed times"
    def boostHours = temps ? pickBoostHours(temps) : fixedBoostHours()
    def threshold = coldThreshold()
    def nightStart = hourSetting("coldStart", 22)
    def nightEnd = hourSetting("coldEnd", 8)

    return (0..23).collect { h ->
        def cold = temps ? (temps[h] < threshold) : inHourRange(h, nightStart, nightEnd)
        def period = cold ? "Cold" : (boostHours.contains(h) ? "Boost" : "Normal")
        [hour: h, period: period, outdoor: temps ? temps[h] : null, source: source]
    }
}

// Warmest run of boostLength hours between boostEarliest and boostLatest.
// Ties go to the later start, closer to when hot water is needed.
List pickBoostHours(List temps) {
    int earliest = boostEarliestHour()
    int latest = boostLatestHour()
    if (latest <= earliest) return []
    int len = Math.min(boostLengthHours(), latest - earliest)

    Integer best = null
    def bestSum = null
    for (int start = earliest; start + len <= latest; start++) {
        def sum = temps.subList(start, start + len).sum()
        if (best == null || sum >= bestSum) {
            best = start
            bestSum = sum
        }
    }
    return (best..<(best + len)).toList()
}

List fixedBoostHours() {
    int earliest = boostEarliestHour()
    int latest = boostLatestHour()
    if (latest <= earliest) return []
    int start = Math.max(earliest, latest - boostLengthHours())
    return (start..<latest).toList()
}

Boolean inHourRange(int h, int start, int end) {
    if (start == end) return false
    if (start < end) return h >= start && h < end
    return h >= start || h < end
}

// ---------------------------------------------------------------------------
// Forecast
// ---------------------------------------------------------------------------

def fetchForecast() {
    if (!forecastEnabled()) return
    if (location.latitude == null || location.longitude == null) {
        state.forecastError = "hub location not set"
        log.warn("Cannot fetch forecast: hub location is not set")
        return
    }
    def params = [
        uri: FORECAST_URI,
        path: "/v1/forecast",
        query: [
            latitude: location.latitude.toString(),
            longitude: location.longitude.toString(),
            hourly: "temperature_2m",
            timeformat: "unixtime",
            timezone: "UTC",
            past_days: "1",
            forecast_days: "3",
            temperature_unit: (scale() == "F" ? "fahrenheit" : "celsius")
        ],
        timeout: 30
    ]
    logDebug("Fetching forecast")
    asynchttpGet("handleForecast", params, [unit: scale()])
}

def handleForecast(resp, data) {
    if (resp.hasError()) {
        forecastFailed(resp.getErrorMessage() ?: "HTTP ${resp.getStatus()}")
        return
    }
    def json
    try {
        json = resp.getJson()
    } catch (e) {
        forecastFailed("unreadable response")
        return
    }
    def times = json?.hourly?.time
    def temps = json?.hourly?.temperature_2m
    if (!(times instanceof List) || !(temps instanceof List) || times.size() != temps.size()) {
        forecastFailed("unexpected response")
        return
    }

    def byHour = [:]
    times.eachWithIndex { t, i ->
        if (temps[i] != null) byHour[t.toString()] = temps[i]
    }
    state.forecast = byHour
    state.forecastUnit = data?.unit ?: scale()
    state.forecastFetchedAt = now()
    state.forecastError = null
    logDebug("Forecast updated: ${byHour.size()} hours")
    applySchedule()
}

def forecastFailed(String reason) {
    state.forecastError = reason
    log.warn("Forecast unavailable (${reason}); using fixed times if no recent forecast")
}

// 24 hourly temperatures for the local day, or null if the forecast is
// off, stale or incomplete.
List forecastForDay(int dayOffset) {
    if (!forecastEnabled()) return null
    if (!state.forecast || state.forecastFetchedAt == null) return null
    if (now() - state.forecastFetchedAt > FORECAST_MAX_AGE_MS) return null
    if (state.forecastUnit != scale()) return null

    def temps = (0..23).collect { h -> state.forecast[hourEpoch(dayOffset, h).toString()] }
    if (temps.any { it == null }) return null
    return temps.collect { it as BigDecimal }
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

def setStatus(String text) {
    if (state.status != text) {
        logDebug("Status: ${text}")
        state.status = text
    }
}

String statusHtml() {
    def lines = []
    lines << "<b>Active:</b> ${state.active ? 'Yes' : 'No'}"
    lines << "<b>Now:</b> ${state.status ?: '-'}"
    lines << "<b>Heater:</b> ${fmtTemp(heater.currentValue('heatingSetpoint'))}, " +
        "${modeName(heater.currentValue('Mode'))} mode"

    if (forceRunActive()) {
        lines << "<b>Force run</b> until ${fmtTime(state.boostUntil)}"
    }

    if (state.active && !isAway()) {
        lines << "<b>Next change:</b> ${nextChangeText()}"
    }

    lines << "<b>Forecast:</b> ${forecastText()}"

    def plan = buildPlan(0)
    def planLines = planSegments(plan).collect { seg ->
        def text = "${hh(seg.start)}–${hh(seg.end)} ${seg.period} ${fmtTemp(targetTemp(seg.period))}"
        def outside = seg.outdoor.findAll { it != null }
        if (outside) {
            def low = fmtTemp(outside.min())
            def high = fmtTemp(outside.max())
            text += " <small>(outside ${low == high ? low : low + ' to ' + high})</small>"
        }
        text
    }
    lines << "<b>Today (${plan[0].source}):</b><br>" + planLines.join("<br>")

    return lines.join("<br>")
}

String forecastText() {
    if (!forecastEnabled()) return "off; using fixed times"
    def temps = forecastForDay(0)
    if (!temps) {
        def reason = state.forecastError ?: "not loaded yet"
        return "unavailable (${reason}); using fixed times"
    }
    def high = temps.max()
    def highHour = temps.indexOf(high)
    return "Open-Meteo, updated ${fmtTime(state.forecastFetchedAt)}; " +
        "high ${fmtTemp(high)} at ${hh(highHour)}, low ${fmtTemp(temps.min())}"
}

String nextChangeText() {
    if (forceRunActive()) return "force run ends at ${fmtTime(state.boostUntil)}"

    def today = buildPlan(0)
    int h = currentHour()
    def current = today[h].period
    for (int i = h + 1; i < 24; i++) {
        if (today[i].period != current) {
            return "${hh(i)} ${today[i].period} ${fmtTemp(targetTemp(today[i].period))}"
        }
    }
    def tomorrow = buildPlan(1)
    for (int i = 0; i < 24; i++) {
        if (tomorrow[i].period != current) {
            return "tomorrow ${hh(i)} ${tomorrow[i].period} ${fmtTemp(targetTemp(tomorrow[i].period))}"
        }
    }
    return "none in the next day"
}

List planSegments(List plan) {
    def segments = []
    plan.each { e ->
        if (segments && segments[-1].period == e.period) {
            segments[-1].end = e.hour + 1
            segments[-1].outdoor << e.outdoor
        } else {
            segments << [start: e.hour, end: e.hour + 1, period: e.period, outdoor: [e.outdoor]]
        }
    }
    return segments
}

// ---------------------------------------------------------------------------
// Child switches (for dashboards, voice assistants and rules)
// ---------------------------------------------------------------------------

String activeDni() { "icomm-hpwh-${app.id}-active" }
String forceRunDni() { "icomm-hpwh-${app.id}-forcerun" }
def activeSwitch() { getChildDevice(activeDni()) }
def forceRunSwitch() { getChildDevice(forceRunDni()) }

def createChildSwitches() {
    def base = app.label ?: app.name
    try {
        if (!activeSwitch()) {
            addChildDevice("hubitat", "Virtual Switch", activeDni(),
                [name: "${base} Active", label: "${base} Active", isComponent: false])
        }
        if (!forceRunSwitch()) {
            addChildDevice("hubitat", "Virtual Switch", forceRunDni(),
                [name: "${base} Force Run", label: "${base} Force Run", isComponent: false])
        }
    } catch (e) {
        log.error("Could not create switches: ${e}")
    }
}

def syncSwitches() {
    state.active ? switchOn(activeSwitch()) : switchOff(activeSwitch())
    forceRunActive() ? switchOn(forceRunSwitch()) : switchOff(forceRunSwitch())
}

def switchOn(dev) {
    if (dev && dev.currentValue("switch") != "on") dev.on()
}

def switchOff(dev) {
    if (dev && dev.currentValue("switch") != "off") dev.off()
}

// ---------------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------------

String scale() { location.temperatureScale ?: "F" }

def defaultFor(String key) { (scale() == "C" ? DEFAULTS_C : DEFAULTS_F)[key] }

String tempRange() { scale() == "C" ? "35..70" : "95..158" }

def settingTemp(String name, String key) {
    def v = settings[name]
    return (v != null ? v : defaultFor(key)) as BigDecimal
}

def boostTemp() { settingTemp("boostTemp", "boost") }
def normalTemp() { settingTemp("normalTemp", "normal") }
def coldTemp() { settingTemp("coldTemp", "cold") }
def coldThreshold() { settingTemp("coldThreshold", "threshold") }

def targetTemp(String period) {
    def t = normalTemp()
    if (period == "Boost" || period == "Force run") t = boostTemp()
    if (period == "Cold") t = coldTemp()
    return clampTemp(t)
}

// Keep within what the heater accepts, on a value it can store exactly
def clampTemp(t) {
    def max = heater?.currentValue("Maximum Temperature")
    def min = fromF(API_MIN_F)
    if (max != null && t > max) t = max
    if (t < min) t = min
    return fromF(toF(t))
}

int hourSetting(String name, int dflt) {
    def v = settings[name]
    return v != null ? (v.toString() as Integer) : dflt
}

int boostEarliestHour() { hourSetting("boostEarliest", 13) }
int boostLatestHour() { hourSetting("boostLatest", 19) }
int boostLengthHours() { hourSetting("boostLength", 4) }

def forceRunHours() {
    def v = settings.forceRunHours
    return (v != null ? v : 2) as BigDecimal
}

Boolean forecastEnabled() { settings.useForecast != false }

Boolean isAway() {
    def modes = settings.awayModes
    if (modes == null) modes = ["Away"]
    if (!(modes instanceof Collection)) modes = [modes]
    return modes.contains(location.mode)
}

List hourOptions() {
    (0..23).collect { [(it.toString()): hh(it)] }
}

// ---------------------------------------------------------------------------
// Time and unit helpers
// ---------------------------------------------------------------------------

TimeZone tz() { location.timeZone ?: TimeZone.getDefault() }

int currentHour() {
    def cal = Calendar.getInstance(tz())
    cal.setTimeInMillis(now())
    return cal.get(Calendar.HOUR_OF_DAY)
}

// Epoch seconds for the start of an hour of a local day
long hourEpoch(int dayOffset, int hour) {
    def cal = Calendar.getInstance(tz())
    cal.setTimeInMillis(now())
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_MONTH, dayOffset)
    cal.set(Calendar.HOUR_OF_DAY, hour)
    return (long) (cal.getTimeInMillis() / 1000)
}

String hh(int h) { String.format("%02d:00", h % 24) }

String fmtTime(ms) { new Date((long) ms).format("HH:mm", tz()) }

String fmtHours(h) {
    def n = h as BigDecimal
    return (n.stripTrailingZeros().toPlainString()) + " h"
}

String fmtTemp(v) {
    if (v == null) return "?"
    def n = (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).stripTrailingZeros()
    return "${n.toPlainString()}°${scale()}"
}

String modeName(mode) {
    switch (mode) {
        case "HYBRID": return "Hybrid"
        case "HEAT_PUMP": return "Heat Pump"
        case "ELECTRIC": return "Electric"
        case "VACATION": return "Vacation"
        default: return mode ?: "unknown"
    }
}

// The iCOMM API works in whole °F; the hub may show °C.
long toF(v) {
    def d = (v as BigDecimal).doubleValue()
    return scale() == "C" ? Math.round(d * 9 / 5 + 32) : Math.round(d)
}

def fromF(f) {
    def d = (f as BigDecimal).doubleValue()
    if (scale() == "C") {
        return (Math.round((d - 32) * 5 / 9 * 2) / 2.0) as BigDecimal
    }
    return f as BigDecimal
}

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

def logInfo(String msg) { log.info(msg) }

def logDebug(String msg) {
    if (settings.debugLogs) log.debug(msg)
}
