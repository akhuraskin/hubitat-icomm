/*
* iCOMM Heat Pump Water Heater Scheduler
*
* Description:
* Hubitat app that sets the temperature of an iCOMM heat pump water heater
* (driver: iCOMMWaterHeater) on a fixed daily schedule with three levels:
*
*   07:00 Medium (48°C)   14:00 High (52°C)   19:00 Medium (48°C)   22:00 Low (35°C)
*
* High stores heat in the warmest part of the day, when the heat pump works
* best. Low keeps the heater idle overnight, when it would work worst. Times,
* levels and temperatures can be changed.
*
* While active, the app keeps the heater in Heat Pump only mode, so the
* electric element isn't used, and overrides any temperature set elsewhere.
* When the hub is in an Away mode, the heater is put in Vacation mode. A force
* run sets High for a couple of hours.
*
* Change Log:
* [10/20/2026] Initial release
*/

import groovy.transform.Field

@Field static final List LEVELS = ["High", "Medium", "Low"]
@Field static final Map DEFAULTS_C = [High: 52, Medium: 48, Low: 35]
@Field static final Map DEFAULTS_F = [High: 126, Medium: 118, Low: 95]
@Field static final List DEFAULT_CHANGES = [
    [minute: 7 * 60, level: "Medium"],
    [minute: 14 * 60, level: "High"],
    [minute: 19 * 60, level: "Medium"],
    [minute: 22 * 60, level: "Low"]
]
@Field static final Integer STEP_MINUTES = 30
@Field static final Integer DAY_MINUTES = 24 * 60
@Field static final Integer MAX_CHANGES = 12
@Field static final Integer VACATION_DAYS = 100
@Field static final Long VACATION_RENEW_MS = 99L * 24 * 3600 * 1000
@Field static final Long COMMAND_RETRY_MS = 4L * 60 * 1000
@Field static final Long RESTORE_TIMEOUT_MS = 60L * 60 * 1000
@Field static final Integer API_MIN_F = 95

definition(
    name: "iCOMM HPWH Scheduler",
    namespace: "evequefou",
    author: "akhuraskin",
    description: "Runs an iCOMM heat pump water heater on a daily schedule with three temperature levels",
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
            }
        }

        section("Water heater") {
            input "heater", "device.iCOMMWaterHeater", title: "iCOMM water heater", required: true
        }

        def unit = "°${scale()}"
        section("Temperatures") {
            LEVELS.each { level ->
                input "temp${level}", "decimal", title: "${level} (${unit})", required: true,
                    defaultValue: defaultTemp(level), range: tempRange()
            }
            paragraph "Values above the heater's maximum temperature are capped at the maximum. " +
                "The lowest the heater accepts is ${fmtTemp(fromF(API_MIN_F))}."
        }

        section("Schedule") {
            paragraph "Each change sets a level from that time until the next change, every day."
            sortedChangeIds().each { id ->
                def dflt = defaultChange(id)
                input "change${id}Time", "enum", title: "Time", required: false, width: 4,
                    options: timeOptions(), defaultValue: dflt ? dflt.minute.toString() : null,
                    submitOnChange: true
                input "change${id}Level", "enum", title: "Level", required: false, width: 4,
                    options: LEVELS, defaultValue: dflt?.level, submitOnChange: true
                input name: "btnRemoveChange_${id}", type: "button", title: "Remove", width: 4
            }
            if (changeIds().size() < MAX_CHANGES) {
                input name: "btnAddChange", type: "button", title: "Add time"
            }
            if (!scheduleChanges()) {
                paragraph "<span style='color:red'>No changes set. The heater will stay at Medium.</span>"
            }
        }

        section("Force run") {
            input "forceRunHours", "decimal", title: "Force run sets High for (hours)", required: true,
                defaultValue: 2, range: "0.5..12"
        }

        section("Away") {
            input "awayModes", "mode", title: "Hubitat modes that mean Away", multiple: true, required: false,
                defaultValue: ["Away"]
            paragraph "When Away, the heater is put in Vacation mode for ${VACATION_DAYS} days. " +
                "When you're back, it returns to Heat Pump only mode and the schedule."
        }

        section("Options") {
            input "debugLogs", "bool", title: "Debug logging", defaultValue: false
            label title: "App name", required: false
        }
    }
}

def appButtonHandler(String btn) {
    if (btn == "btnAddChange") {
        addChange()
        return
    }
    if (btn.startsWith("btnRemoveChange_")) {
        removeChange(btn.substring("btnRemoveChange_".length()) as Integer)
        return
    }
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

    // Every 5 minutes, including :00 and :30, so changes apply on time
    schedule("0 0/5 * ? * *", "applySchedule")

    if (forceRunActive()) {
        runIn(Math.max(1L, (long) ((state.boostUntil - now()) / 1000)), "endForceRun")
    }

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
        logInfo("Turned off; setting Medium and stopping")
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

    if (mode == "VACATION") {
        sendMode("HEAT_PUMP")
        setStatus("Leaving Vacation mode")
        return
    }
    state.restoreMode = null

    if (mode != null && mode != "HEAT_PUMP") {
        sendMode("HEAT_PUMP")
    }

    def target = currentTarget()
    applySetpoint(target.temp)
    setStatus("${target.level} ${fmtTemp(target.temp)}")
}

def handleAway(mode) {
    if (!state.awayActive) {
        logInfo("Away: putting heater in Vacation mode")
        state.awayActive = true
        state.restoreMode = null
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
        state.weSetVacation = true
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
        state.restoreMode = "HEAT_PUMP"
        logInfo("Back from Away: switching heater to Heat Pump mode")
    }
    state.weSetVacation = false
    state.vacationSentAt = null
}

// Take the heater out of Vacation if the app put it there, set Medium once,
// then leave the heater alone.
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
        setStatus("Turning off: leaving Vacation mode")
        return
    }
    state.restoreMode = null

    if (mode != "VACATION") {
        applySetpoint(targetTemp("Medium"))
    }
    state.deactivating = false
    state.deactivatingSince = null
    setStatus("Off")
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
        return [level: "Force run", temp: targetTemp("High")]
    }
    def level = levelAt(currentMinute())
    return [level: level, temp: targetTemp(level)]
}

// ---------------------------------------------------------------------------
// Schedule
// ---------------------------------------------------------------------------

// Changes in effect, sorted by time: [minute (of the day), level]
// Rows with no time or level yet are skipped.
List scheduleChanges() {
    def changes = []
    changeIds().each { id ->
        def c = changeAt(id)
        if (c.minute != null && LEVELS.contains(c.level)) changes << c
    }
    return changes.sort { a, b -> a.minute <=> b.minute ?: a.id <=> b.id }
}

// The schedule rows are kept as a list of ids in state; each id has its own
// change<id>Time and change<id>Level settings. Ids 1-4 start with the
// default schedule. Removed ids are never reused, so a new row starts empty.
List changeIds() {
    if (state.changeIds == null) {
        state.changeIds = (1..DEFAULT_CHANGES.size()).toList()
        state.nextChangeId = DEFAULT_CHANGES.size() + 1
    }
    return state.changeIds
}

Map defaultChange(id) {
    return id <= DEFAULT_CHANGES.size() ? DEFAULT_CHANGES[id - 1] : null
}

Map changeAt(id) {
    def dflt = defaultChange(id)
    def minute = settings["change${id}Time".toString()] ?: dflt?.minute
    def level = settings["change${id}Level".toString()] ?: dflt?.level
    return [id: id, minute: minute != null ? (minute.toString() as Integer) : null, level: level]
}

// Rows in time order, new (empty) rows last
List sortedChangeIds() {
    def key = { id ->
        def m = changeAt(id).minute
        return m == null ? DAY_MINUTES : m
    }
    return changeIds().sort(false) { a, b -> key(a) <=> key(b) ?: a <=> b }
}

def addChange() {
    def ids = changeIds()
    if (ids.size() >= MAX_CHANGES) return
    int id = state.nextChangeId ?: ((ids ? ids.max() : 0) + 1)
    state.changeIds = ids + [id]
    state.nextChangeId = id + 1
}

def removeChange(Integer id) {
    state.changeIds = changeIds() - [id]
    app.removeSetting("change${id}Time")
    app.removeSetting("change${id}Level")
    if (heater && app.getInstallationState() == "COMPLETE") applySchedule()
}

// Level at a time of day (minutes): the last change at or before it,
// wrapping from the previous day.
String levelAt(int minute) {
    def changes = scheduleChanges()
    if (!changes) return "Medium"
    def before = changes.findAll { it.minute <= minute }
    return (before ? before[-1] : changes[-1]).level
}

// [start, end, level] blocks covering the day, in minutes
List daySegments() {
    def segments = []
    for (int m = 0; m < DAY_MINUTES; m += STEP_MINUTES) {
        def level = levelAt(m)
        if (segments && segments[-1].level == level) {
            segments[-1].end = m + STEP_MINUTES
        } else {
            segments << [start: m, end: m + STEP_MINUTES, level: level]
        }
    }
    return segments
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
    } else if (state.active && !isAway()) {
        lines << "<b>Next change:</b> ${nextChangeText()}"
    }

    def blocks = daySegments().collect { seg ->
        "${hm(seg.start)}–${hm(seg.end)} ${seg.level} ${fmtTemp(targetTemp(seg.level))}"
    }
    lines << "<b>Daily schedule:</b><br>" + blocks.join("<br>")

    return lines.join("<br>")
}

String nextChangeText() {
    int nowMin = currentMinute()
    def current = levelAt(nowMin)
    int slot = nowMin - nowMin % STEP_MINUTES
    for (int m = slot + STEP_MINUTES; m < slot + DAY_MINUTES; m += STEP_MINUTES) {
        def level = levelAt(m % DAY_MINUTES)
        if (level != current) {
            return "${hm(m)} ${level} ${fmtTemp(targetTemp(level))}"
        }
    }
    return "none (same level all day)"
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

def defaultTemp(String level) { (scale() == "C" ? DEFAULTS_C : DEFAULTS_F)[level] }

String tempRange() { scale() == "C" ? "35..70" : "95..158" }

def levelTemp(String level) {
    def v = settings["temp${level}".toString()]
    return (v != null ? v : defaultTemp(level)) as BigDecimal
}

def targetTemp(String level) {
    return clampTemp(levelTemp(level == "Force run" ? "High" : level))
}

// Keep within what the heater accepts, on a value it can store exactly
def clampTemp(t) {
    def max = heater?.currentValue("Maximum Temperature")
    def min = fromF(API_MIN_F)
    if (max != null && t > max) t = max
    if (t < min) t = min
    return fromF(toF(t))
}

def forceRunHours() {
    def v = settings.forceRunHours
    return (v != null ? v : 2) as BigDecimal
}

Boolean isAway() {
    def modes = settings.awayModes
    if (modes == null) modes = ["Away"]
    if (!(modes instanceof Collection)) modes = [modes]
    return modes.contains(location.mode)
}

// Values are minutes since midnight
List timeOptions() {
    (0..<(DAY_MINUTES / STEP_MINUTES)).collect { i ->
        def m = i * STEP_MINUTES
        [(m.toString()): hm(m)]
    }
}

// ---------------------------------------------------------------------------
// Time and unit helpers
// ---------------------------------------------------------------------------

TimeZone tz() { location.timeZone ?: TimeZone.getDefault() }

// Minutes since local midnight
int currentMinute() {
    def cal = Calendar.getInstance(tz())
    cal.setTimeInMillis(now())
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

String hm(int minutes) {
    int m = minutes % DAY_MINUTES
    return String.format("%02d:%02d", m.intdiv(60), m % 60)
}

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
