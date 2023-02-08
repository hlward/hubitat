/* vim: set et ts=4 sw=4: */

import groovy.transform.Field

definition(
    name: "HVACBond",
    namespace: "tochtli",
    author: "Lee Ward",
    description: "Bond a temperature sensor, virtual thermostat, and two switches into a functional HVAC system",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page name: "mainPage"
}

def mainPage() {
    dynamicPage(name: "mainPage", install: "true", uninstall: "true") {
	    section() {
            input "thermometer", "capability.temperatureMeasurement", title: "Temperature Source"
            input "thermostat", "capability.thermostat", title: "Thermostat", required: true
            input "heaterSwitch", "capability.switch", title: "Heater Switch"
            input "coolerSwitch", "capability.switch", title: "Cooler Switch"
        }
    }
}

void installed() {
    log.info("installed...")
    if (settings?.thermostat != null)
        initialize()
}

void updated() {
    log.info("updated...")
    
    unsubscribe() 
    initialize()
}

void initialize() {
    log.info("initialize...")
    def event
    
    event = [name:"thermostatOperatingState", value:thermostat.currentValue("thermostatOperatingState")]
    operatingStateHandler event
    subscribe(thermostat, "thermostatOperatingState", operatingStateHandler)
    
    def sensor = thermometer != null ? thermometer : thermostat
    if (sensor == thermometer)
        subscribe(sensor, "temperature", temperatureEventHandler)
}

void temperatureEventHandler(event) {
    Float temperature = Float.parseFloat("${event.value}")
    
    thermostat.setTemperature temperature
}

void operatingStateHandler(event) {     
    switch (event.value) {
    case "heating":
        manipulateSwitch(coolerSwitch, "off")
        manipulateSwitch(heaterSwitch, "on")
        break
    case "cooling":
        manipulateSwitch(heaterSwitch, "off")
        manipulateSwitch(coolerSwitch, "on")
        break
    default:
        manipulateSwitch(coolerSwitch, "off")
        manipulateSwitch(heaterSwitch, "off")
        break
    }
}

private manipulateSwitch(sw, value) {
    if (sw == null)
        return
    
    switch (value) {
    case "on":
        sw.on()
        break
    case "off":
        sw.off()
        break
    default:
        break
    }
}
