/* vim: set et ts=4 sw=4: */

/*
    Virtual Thermostat
    Copyright 2016 -> 2023 Hubitat Inc.  All Rights Reserved

    Copyright 2023 Lee Ward with No Rights Reserved

    Lee Ward, LICENCE:
    My changes are placed in the public domain. Any person or entity may, at
    their discretion, remove my copyright notice, my statement(s) of
    attribution, etc.

    Lee Ward, re. Warranty:
    No warranties of any kind are provided, expressed or implied.
 */

metadata {
    definition (
        name: "Virtual Thermostat",
        namespace: "tochtli",
        author: "Kevin L., Mike M., Bruce R., Lee W."
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Thermostat"

        // Commands needed to change state/attributes of virtual device.
        command "setTemperature", ["NUMBER"]
    }

    preferences {
        input( name: "hysteresis", type: "enum", title: "Thermostat hysteresis", options: ["0.1","0.25","0.5","1","2"], description: "", defaultValue: 0.5)
        input( name: "idleWait", type: "number", title: "Idle time minimum, in seconds", defaultValue: 300)
        input( name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false)
        input( name: "txtEnable", type:"bool", title: "Enable descriptionText logging", defaultValue: true)
    }
}

import groovy.json.JsonOutput
import java.time.Instant

def installed() {
    log.info "installed..."
    initialize()
}

def updated() {
    log.info "updated..."
    log.info "debug logging is: ${logEnable == true}"
    if (idleWait < 0)
        device.updateSetting("idleWait", [value: "0", type: "number"])
    unschedule()
    if (logEnable)
        runIn(1800, logsOff)
    // Recalculate the operating state.
    sendEvents ChooseNextOperatingState()
}

def initialize() {
    log.info "initializing..."

    if (state?.current != null)
        return

    // We maintain an internal copy of critical state attributes, and work from/with them, since
    // our updates are asynchronous and we often must alter multiple attributes in
    // a transaction.
    //
    // The strategy, then, is to perform changes of state and, only after, reflect those
    // changes by publication.

    // Assume a reasonable state using the following defaults...
    state.current = [:]
    state.current.mode = "off"
    state.current.temperature = convertTemperatureIfNeeded(68.0, "F", 1).toFloat()
    state.current.heatingSetpoint = state.current.temperature
    state.current.coolingSetpoint = convertTemperatureIfNeeded(75.0, "F" ,1).toFloat()

    state.operatingState = "idle"
    state.lastIdle = Instant.EPOCH.toString()

    // Publish that state.
    def text = "initial value"
    def events = []
    events << [name:"thermostatMode", value:"${state.current.mode}", descriptionText:text]
    events << [name:"temperature", value:"${state.current.temperature}", descriptionText:text]
    events << [name:"thermostatSetpoint", value:"${state.current.heatingSetpoint}", descriptionText:text]
    events << [name:"heatingSetpoint", value:"${state.current.heatingSetpoint}", descriptionText:text]
    events << [name:"coolingSetpoint", value:"${state.current.coolingSetpoint}", descriptionText:text]
    events << [name:"thermostatOperatingState", value:"${state.operatingState}", descriptionText:text]
    events << [name:"thermostatFanMode", value:"auto", descriptionText:text]
    //events << [name:"setSupportedThermostatFanModes", value:JsonOutput.toJson(["auto","circulate","on"]), descriptionText:text]
    //events << [name:"setSupportedThermostatModes", value:JsonOutput.toJson(["auto", "cool", "emergency heat", "heat", "off"]), descriptionText:text]
    sendEvents events
}

def logsOff() {
    log.info "debug logging disabled..."
    device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def setTemperature(temperature) {
    if (temperature == null) {
        log.warn "null temperature update ignored"
        return
    }

    state.current.temperature = Float.parseFloat("${temperature}")

    def events = []
    def u = "°${getTemperatureScale()}"
    events << [
        name:"temperature",
        value:"${state.current.temperature}",
        unit:"${u}",
        descriptionText:"temperature set to ${state.current.temperature}${u}"
    ]
    finalize events
}

def auto() { setThermostatMode("auto") }

def cool() { setThermostatMode("cool") }

def emergencyHeat() { setThermostatMode("emergency heat") }

def heat() { setThermostatMode("heat") }
def off() { setThermostatMode("off") }

def setThermostatMode(mode) {
    if (!(mode in ["off", "auto", "cool", "heat", "emergency heat"])) {
        log.warn "Unknown mode, ${mode}, ignored"
        return
    }
    if (mode == "emergency heat") {
        // We don't support an emergency backup heater, so...
        mode = "heat"
    }

    state.current.mode = mode

    def events = []
    events << [name:"thermostatMode", value:state.current.mode]
    finalize events
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def fanOn() { setThermostatFanMode("on") }

def setThermostatFanMode(fanMode) {
    if (!(fanMode in ["auto", "circulate", "on"])) {
        log.warn "unknown fan-mode, ${fanMode}, ignored"
        return
    }

    def events = []
    events << [name:"thermostatFanMode", value:fanMode]
    sendEvents events
}

def setCoolingSetpoint(setpoint) {
    if (setpoint == null) {
        log.warn "null cooling set-point update ignored"
        return
    }

    setpoint = Float.parseFloat("${setpoint}")
        def events = []

        Float x = setpoint - 2 * Float.parseFloat(hysteresis)
        if (state.current.heatingSetpoint > x) {
            state.current.heatingSetpoint = x
            events << [name:"heatingSetpoint", value:"${state.current.heatingSetpoint}"]
        }

    finalizeSetSetpoint(events, "coolingSetpoint", setpoint)
}

def setHeatingSetpoint(setpoint) {
    if (setpoint == null) {
        log.warn "null heating set-point update ignored"
        return
    }

    setpoint = Float.parseFloat("${setpoint}")
        def events = []

        Float x = setpoint + 2 * Float.parseFloat(hysteresis)
        if (state.current.coolingSetpoint < x) {
            state.current.coolingSetpoint = x
            events << [name:"coolingSetpoint", value:"${state.current.coolingSetpoint}"]
        }

    finalizeSetSetpoint(events, "heatingSetpoint", setpoint)
}

private finalizeSetSetpoint(events, String k, Float v) {
    state.current[k] = v

    events << [name:"thermostatSetpoint", value:"${v}"]
    events << [name:k, value:"${v}"]
    finalize events
}

def setSchedule(schedule) {
    if (schedule == null) {
        log.warn "null schedule update ignored"
        return
    }

    def events = []
    events << [name:"schedule", value:"${schedule}"]
    sendEvents events
}

def parse(String description) {
    log.err "$description"
}

private logDebug(msg) {
    if (settings.logEnable) log.debug "${msg}"
}

// Choose an operating state and send all events; The given events list is value-result.
private finalize(events) {
    events.addAll ChooseNextOperatingState()
    sendEvents events
}

// Send the given events in order.
private sendEvents(events) {
    for (event in events) {
        // Capture or create descriptive text.
        def descriptionText = event?.descriptionText ?: "${event.name} set to ${event.value}"
        logDebug descriptionText

        // Strip descriptive text from this event.
        Map e = event.findAll {it -> it.key != "descriptionText"}
        if (settings.txtEnable) {
            // Add/Restore descriptive text to this event.
            e.descriptionText = descriptionText
        }

        sendEvent e
    }
}

// Perform actions based on the current state and return a list of events reflecting those actions.
private ChooseNextOperatingState() {   
    def h = Float.parseFloat(hysteresis)
    def nxtOperatingState = "idle"
    switch (state.current.mode) {
        case "auto":
            if (state.current.coolingSetpoint <= state.current.heatingSetpoint) {
                // Out of order event delivery? Race? Doesn't matter. Nothing we can really do about it. Hope
                // another set-point-set comes along to make them consistent soon!
                log.err "inconsistent set-points -- unable to determine new state"
                    return []
            }

            if (state.operatingState == "idle")
                h = 0

            if (state.current.temperature < state.current.heatingSetpoint + h)
                nxtOperatingState = "heating"
            else if (state.current.temperature > state.current.coolingSetpoint - h)
                nxtOperatingState = "cooling"
            break
        case "cool":
            if (state.operatingState == "cooling")
                h = -h
            if (state.current.temperature >= state.current.coolingSetpoint + h)
                nxtOperatingState = "cooling"
            break
        case "heat":
            if (state.operatingState != "heating")
            h = -h
            if (state.current.temperature <= state.current.heatingSetpoint + h)
                nxtOperatingState = "heating"
            break
        default: // off
            break
    }

    // Avoid short-cycling by forcing a transition through the idle state, if needed.
    if (nxtOperatingState != state.operatingState && !(state.operatingState == "idle" || nxtOperatingState == "idle"))
        toOperatingState("idle") // Internal state change; Discard the events, then.

    return toOperatingState(nxtOperatingState)
}

// Perform actions needed to achieve the given, desired, operating state and return a list of
// events reflecting those actions.
private toOperatingState(String nxtOperatingState) {
    def ttxt = "${state.operatingState} -> ${nxtOperatingState}"
    logDebug ttxt

    if (nxtOperatingState == state.operatingState)
        return []

    def events = []
    if (nxtOperatingState == "idle") {
        // Transitioning into the "idle" state.
        state.lastIdle = Instant.now().toString()
    } else if (state.operatingState == "idle") {
        // Transitioning away from "idle" but must honor the required wait-time.
        Instant timeout = Instant.parse(state.lastIdle).plusSeconds(idleWait)
        Instant now = Instant.now()
        if (now.isBefore(timeout)) {
            // Start/Restart the idle timeout wait handler.
            long nSec = timeout.getEpochSecond() - now.getEpochSecond() + 1
            logDebug "delay ${ttxt}, idle wait; Retry in ${nSec}"
            runIn(nSec, idleTimeoutHandler)
            def Map m = ["heating":"pending heat", "cooling":"pending cool"]
            events << [name:"thermostatOperatingState", value:m[nxtOperatingState]]
            return events
        }
    }
    state.operatingState = nxtOperatingState

    events << [name:"thermostatOperatingState", value:state.operatingState]
    return events
}

def idleTimeoutHandler() {
    logDebug "idle timeout!"
    sendEvents ChooseNextOperatingState()
}
