package app.mate.gleo

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/** Everything Mate reads from the car, in one place. */
data class CarState(
    val gear: String = "P",
    val restricted: Boolean = false,      // CarUxRestrictions: driver must not read/touch
    val brake: Boolean = false,
    val speedKmh: Float? = null,          // PERF_VEHICLE_SPEED (android.car); null until the first reading
    val socPct: Int? = null,
    val rangeM: Float? = null,            // RANGE_REMAINING from the Vehicle SDK
    val waypoint: String? = null,         // charging stop added through NaviHelper
    val tripMinutes: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val destination: String? = null,
    val remainSec: Int? = null,
    val remainM: Int? = null,
    val arrivedAt: String? = null,
) {
    /** Moving = vehicle speed above 0. Until a speed reading exists, the platform's driving restriction stands in. */
    val moving get() = speedKmh?.let { it > 0.5f } ?: restricted
}

/** Live status of each Pleos SDK so the demo screen can prove what is connected. */
data class SdkStatus(
    val vehicle: String = "…",
    val navi: String = "…",
    val location: String = "…",
    val stt: String = "…",
    val tts: String = "…",
    val uxr: String = "…",
    val speed: String = "…",
)

class PleosBridge(private val ctx: Context) {
    val car = MutableStateFlow(CarState())
    val sdk = MutableStateFlow(SdkStatus())
    val heard = MutableStateFlow("")          // latest STT text
    val speaking = MutableStateFlow(false)
    val listening = MutableStateFlow(false)   // STT recognition in progress (orb LISTENING state)
    val level = MutableStateFlow(0.0)         // mic / voice level for the orb

    var onUtterance: ((String) -> Unit)? = null
    var onGear: ((String) -> Unit)? = null
    var onMoving: ((Boolean) -> Unit)? = null
    var onArrived: ((String) -> Unit)? = null
    var onNearDestination: (() -> Unit)? = null
    var onSoc: ((Int) -> Unit)? = null

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var location: FusedLocation? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()

    fun start() {
        runCatching { startVehicle() }.onFailure { mark { copy(vehicle = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startNavi() }.onFailure { mark { copy(navi = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startLocation() }.onFailure { mark { copy(location = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startStt() }.onFailure { mark { copy(stt = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startTts() }.onFailure { mark { copy(tts = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startUxRestrictions() }.onFailure { mark { copy(uxr = "ERR ${it.javaClass.simpleName}") } }
        runCatching { startSpeed() }.onFailure { mark { copy(speed = "ERR ${it.javaClass.simpleName}") } }
    }

    private fun mark(f: SdkStatus.() -> SdkStatus) { sdk.value = sdk.value.f() }
    private fun setCar(f: CarState.() -> CarState) { car.value = car.value.f() }

    // ---------- Vehicle SDK: gear, brake, battery, trip time ----------
    private fun startVehicle() {
        val v = Vehicle(ctx).also { vehicle = it }
        v.initialize()
        val gear = v.getGear()
        gear.getCurrentGearSelectionState({ g -> applyGear(g) }, { e -> Log.w(TAG, "gear get", e) })
        gear.registerGearSelection(object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) { lastPushTs = System.currentTimeMillis(); applyGear(p0) }
            override fun onFailed(p0: Exception) { mark { copy(vehicle = "gear ERR") } }
        })
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) = setCar { copy(brake = p0 == true) }
            override fun onFailed(p0: Exception) {}
        })
        v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(p0: Int?) = applySoc(p0)
            override fun onFailed(p0: Exception) {}
        })
        v.getOdometer().registerRangeRemainingListener(object : RangeRemainingListener {
            override fun onRangeRemainingChanged(p0: Float?) {
                if (p0 == null) return
                setCar { copy(rangeM = p0) }
                Log.i(TAG, "RANGE ${(p0 / 1000).toInt()} km")
                car.value.socPct?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { onSoc?.invoke(s) } }
            }
            override fun onFailed(p0: Exception) {}
        })
        val battery = v.getEvBattery()
        battery.getEvBatteryStateOfCharge({ applySoc(it) }, {})
        v.getOdometer().registerTripCurrentDrivingTime(object : DrivingTimeListener {
            override fun onDrivingTimeUpdated(p0: Int?) = setCar { copy(tripMinutes = p0) }
            override fun onFailed(p0: Exception) {}
        })
        mark { copy(vehicle = "OK") }
        // The emulator's VHAL does not always push gear callbacks; poll as a safety net (1 Hz, cheap IPC).
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                runCatching { gear.getCurrentGearSelectionState({ g -> h.post { if (pollN++ % 10 == 0) Log.i(TAG, "GEAR poll=$g ui=${car.value.gear}"); if (System.currentTimeMillis() - lastPushTs > 2500) applyGear(g) } }, { e -> Log.w(TAG, "GEAR poll fail ${e.message}") }) }
                if (pollN % 3 == 0) runCatching { v.getEvBattery().getEvBatteryStateOfCharge({ s -> h.post { applySoc(s) } }, {}) }
                h.postDelayed(this, 1000)
            }
        })
    }

    private fun applySoc(p0: Int?) {
        p0 ?: return
        val changed = p0 != car.value.socPct
        setCar { copy(socPct = p0) }
        if (changed) { Log.i(TAG, "SOC $p0%"); android.os.Handler(android.os.Looper.getMainLooper()).post { onSoc?.invoke(p0) } }
    }

    @Volatile private var lastPushTs = 0L
    private var pollN = 0

    private fun applyGear(g: VehicleGear?) {
        val s = when (g) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            null, VehicleGear.GEAR_UNKNOWN -> car.value.gear
            else -> "D"
        }
        if (s != car.value.gear) Log.i(TAG, "GEAR $g -> $s")
        setCar { copy(gear = s) }
        mark { copy(vehicle = "OK · gear $s") }
        evalDriving()
    }

    /** Driving = platform says distraction-optimised UX is required, or the gear is out of P. */
    private var wasDriving: Boolean? = null
    private fun evalDriving() {
        val c = car.value
        val now = c.restricted || c.gear == "D" || c.gear == "R"
        if (now != wasDriving) {
            val first = wasDriving == null
            wasDriving = now
            Log.i(TAG, "DRIVE state -> ${if (now) "DRIVING" else "PARKED"} (uxr=${c.restricted}, gear=${c.gear})")
            if (!first) onGear?.invoke(if (now) "D" else "P")
        }
    }

    // ---------- CarUxRestrictions: the platform's own "driver is driving" signal ----------
    private fun startUxRestrictions() {
        val c = Car.createCar(ctx) ?: run { mark { copy(uxr = "no car") }; return }
        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        fun apply(r: CarUxRestrictions?) {
            val on = r?.isRequiresDistractionOptimization == true
            Log.i(TAG, "UXR requiresDO=$on active=${r?.activeRestrictions}")
            setCar { copy(restricted = on) }
            mark { copy(uxr = if (on) "OK · restricted" else "OK · free") }
            evalDriving(); evalMoving()
        }
        apply(m.currentCarUxRestrictions)
        m.registerListener { apply(it) }
    }

    // ---------- Vehicle speed (android.car VHAL property): moving vs. stopped ----------
    private var speedStarted = false
    fun startSpeed() {
        if (speedStarted) return
        if (ctx.checkSelfPermission(PERM_SPEED) != android.content.pm.PackageManager.PERMISSION_GRANTED) { mark { copy(speed = "no permission") }; Log.w(TAG, "SPEED permission not granted -> using CarUxRestrictions"); return }
        val c = Car.createCar(ctx) ?: run { mark { copy(speed = "no car") }; return }
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
        speedStarted = true
        runCatching { applySpeed(m.getFloatProperty(android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)) }
        m.registerCallback(object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: android.car.hardware.CarPropertyValue<*>) { (v.value as? Float)?.let { applySpeed(it) } }
            override fun onErrorEvent(propId: Int, zone: Int) {}
        }, android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, android.car.hardware.property.CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    private fun applySpeed(ms: Float) {
        val kmh = Math.abs(ms) * 3.6f
        val prev = car.value.speedKmh
        if (prev == null || Math.abs(prev - kmh) >= 1f || (kmh == 0f) != (prev == 0f)) Log.i(TAG, "SPEED ${"%.0f".format(kmh)} km/h")
        setCar { copy(speedKmh = kmh) }
        mark { copy(speed = "OK · ${kmh.toInt()} km/h") }
        evalMoving()
    }

    private var wasMoving: Boolean? = null
    private fun evalMoving() {
        val now = car.value.moving
        if (now == wasMoving) return
        val first = wasMoving == null
        wasMoving = now
        Log.i(TAG, "MOVE -> ${if (now) "MOVING" else "STOPPED"} (speed=${car.value.speedKmh}, uxr=${car.value.restricted}, gear=${car.value.gear})")
        if (!first || now) android.os.Handler(android.os.Looper.getMainLooper()).post { onMoving?.invoke(now) }
    }

    // ---------- NaviHelper SDK: destination, ETA, arrival ----------
    private fun startNavi() {
        val n = NaviHelper(ctx).also { navi = it }
        n.initialize(onConnected = {
            n.addListener(naviListener)
            n.getDestinationInfo()
            mark { copy(navi = "OK") }
        }, onFailed = { mark { copy(navi = "connect fail") } })
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(TAG, "NAVI dest ${destinationInfo.name} remain=${destinationInfo.remainTime}s ${destinationInfo.remainDistance}m")
            if (destinationInfo.name.isBlank()) return
            if (destinationInfo.remainDistance > 0) { tripFromNavi = true; setCar { copy(destination = destinationInfo.name, remainSec = destinationInfo.remainTime, remainM = destinationInfo.remainDistance) } }
            mark { copy(navi = "OK · ${destinationInfo.name}") }
        }
        override fun onDestinationChanged() { naviAsync { getDestinationInfo() } }
        override fun onRoutingCompleted() { naviAsync { getDestinationInfo() } }
        override fun onTripInfo(tripInfo: TripInfo) {
            Log.i(TAG, "NAVI trip eta=${tripInfo.eta} dist=${tripInfo.distance}")
            if (tripInfo.distance > 0) tripFromNavi = true
            if (tripInfo.distance > 0) setCar { copy(remainSec = tripInfo.eta, remainM = tripInfo.distance) }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            setCar { copy(arrivedAt = destinationArrivedInfo.name, remainSec = 0, remainM = 0) }
            onArrived?.invoke(destinationArrivedInfo.name)
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            // log only: a synchronous navi query from this callback blocked the UI thread while the engine re-routed
            Log.i(TAG, "NAVI waypoint ${waypointChangedInfo.cmdType} ${waypointChangedInfo.index}")
        }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "NAVI error $errorCode"); mark { copy(navi = "ERR $errorCode") } }
    }

    private val naviThread = android.os.HandlerThread("navi-io").apply { start() }
    private val naviIo = android.os.Handler(naviThread.looper)
    /** NaviHelper requests can block while the engine re-routes; never make them on the UI thread. */
    private fun naviAsync(f: NaviHelper.() -> Unit) { naviIo.post { runCatching { navi?.f() }.onFailure { Log.w(TAG, "navi", it) } } }

    fun setDestination(name: String, lat: Double, lon: Double, address: String) {
        Log.i(TAG, "DEST $name $lat,$lon")
        setCar { copy(destination = name, arrivedAt = null) }
        runCatching {
            navi?.requestRoute(RouteInfo(latitude = lat, longitude = lon, displayLatitude = null, displayLongitude = null,
                poiName = name, poiId = "", address = address, poiSubId = "0"))
            navi?.requestTrip(TripRequest(lat, lon, "", name, "0"))
        }.onFailure { Log.w(TAG, "route", it) }
        // the navi engine answers asynchronously; re-ask until ETA arrives
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        listOf(2500L, 6000L, 12000L).forEach { d -> h.postDelayed({
            if (car.value.remainM == null) naviAsync { requestTrip(TripRequest(lat, lon, "", name, "0")); getDestinationInfo() }
        }, d) }
        watchArrival(lat, lon)
    }

    /** Proactive charging stop: a real NaviHelper waypoint on the active route. */
    fun addChargingStop(name: String, lat: Double, lon: Double, address: String) {
        Log.i(TAG, "WAYPOINT add $name $lat,$lon")
        setCar { copy(waypoint = name) }
        naviAsync { addWaypoint(RequestWaypointInfo(lat, lon, name, WaypointIndex.FIRST, "", address, "0", "")) }
    }

    // ---------- FusedLocation SDK: where the car is + 300 m geofence on the destination ----------
    private fun startLocation() {
        val l = FusedLocation(ctx).also { location = it }
        if (!l.isSupported()) { mark { copy(location = "unsupported") }; return }
        l.initialize()
        l.registerFusedLocationCallback(object : FusedLocationListener {
            override fun onChanged(p0: LocationData?) { p0 ?: return; setCar { copy(lat = p0.latitude, lon = p0.longitude) }; estimateIfNoTrip() }
            override fun onError(p0: Exception) { mark { copy(location = "ERR") } }
        })
        mark { copy(location = "OK") }
    }

    private var dest: Pair<Double, Double>? = null
    @Volatile private var tripFromNavi = false

    /** If the navi engine has not answered with TripInfo yet, estimate from FusedLocation (road factor 1.3, 32 km/h city). */
    private fun estimateIfNoTrip() {
        if (tripFromNavi) return
        val (dl, dn) = dest ?: return
        val c = car.value; val la = c.lat ?: return; val lo = c.lon ?: return
        val r = FloatArray(1); android.location.Location.distanceBetween(la, lo, dl, dn, r)
        if (r[0] > 150_000f) return   // emulator GPS not near the route (default fix) -> keep NaviHelper's values
        val road = (r[0] * 1.3).toInt()
        setCar { copy(remainM = road, remainSec = (road / (32_000.0 / 3600)).toInt()) }
    }

    private fun watchArrival(lat: Double, lon: Double) {
        dest = lat to lon; estimateIfNoTrip()
        runCatching {
            location?.addProximityAlert(ProximityAlertRequest(lat, lon, 300f, setOf(ProximityTransition.ENTER)),
                object : ProximityAlertListener {
                    override fun onRegistered(p0: String) { mark { copy(location = "OK · geofence 300m") } }
                    override fun onTransition(p0: ProximityAlertEvent) { Log.i(TAG, "GEOFENCE ENTER"); android.os.Handler(android.os.Looper.getMainLooper()).post { onNearDestination?.invoke() } }
                    override fun onError(p0: Exception) {}
                })
        }
    }

    // ---------- SpeechToText SDK: the only way the driver talks to Mate while moving ----------
    private fun startStt() {
        val s = SpeechToText(ctx, SttMode.ON_DEVICE).also { stt = it }
        s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() {
                s.addListener(sttListener)
                s.setLocale(Locale.KOREA)
                mark { copy(stt = "OK · on-device ko-KR") }
            }
            override fun onFailure(p0: SttError) { mark { copy(stt = "init $p0") } }
        })
    }

    private val sttListener = object : ResultListener {
        override fun onUpdated(p0: String, p1: Boolean) {
            heard.value = p0
            if (p1) { listening.value = false; level.value = 0.0; Log.i(TAG, "HEARD: $p0") }
            if (p1 && p0.isNotBlank()) onUtterance?.invoke(p0)
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) {}
        override fun onStartedRecognition() { listening.value = true; mark { copy(stt = "OK · listening") } }
        override fun onEndedRecognition() { listening.value = false; mark { copy(stt = "OK · on-device ko-KR") }; level.value = 0.0 }
        override fun onError(p0: SttError) { listening.value = false; mark { copy(stt = "ERR $p0") } }
        override fun onReady() {}
        override fun onUpdatedRms(p0: Double) { level.value = p0 }
    }

    fun listen() { runCatching { stt?.request() } }

    /** Feed recorded 16 kHz mono PCM (ON_DEVICE mode) — used for repeatable demo runs in the emulator. */
    fun listenPcm(pcm: ByteArray) {
        Log.i(TAG, "PCM start ${pcm.size / 32000.0}s")
        Thread {
            runCatching {
                listening.value = true
                pcm.asList().chunked(3200).forEach { c ->
                    val b = c.toByteArray()
                    // drive the orb from the PCM itself too (RMS of the chunk, dB-ish 0..12) in case the SDK does not report RMS for sendAudio
                    var acc = 0.0; var i = 0
                    while (i + 1 < b.size) { val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toDouble(); acc += v * v; i += 2 }
                    val rms = Math.sqrt(acc / (b.size / 2).coerceAtLeast(1))
                    level.value = (20 * Math.log10(rms.coerceAtLeast(1.0)) - 30).coerceIn(0.0, 12.0) / 1.0
                    stt?.sendAudio(b); Thread.sleep(100)
                }
                stt?.completeAudioSend()
                level.value = 0.0
            }
        }.start()
    }

    // ---------- TextToSpeech SDK ----------
    private fun startTts() {
        val t = TextToSpeech(ctx, TtsMode.ON_DEVICE).also { tts = it }
        t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
            override fun onSuccess() {
                t.addEventListener(ttsListener)
                t.setLocale(Locale.KOREA)
                ttsReady = true
                mark { copy(tts = "OK · on-device ko-KR") }
                while (pendingSpeech.isNotEmpty()) t.speak(pendingSpeech.removeFirst(), true)
            }
            override fun onFailure(p0: TtsError) { mark { copy(tts = "init $p0") } }
        })
    }

    private val ttsListener = object : EventListener {
        override fun onStart() { ttsStarted = true; speaking.value = true }
        override fun onReady() {}
        override fun onDone() { speaking.value = false; level.value = 0.0 }
        override fun onError(p0: TtsError) { speaking.value = false; mark { copy(tts = "ERR $p0") } }
        override fun onStop() { speaking.value = false }
        override fun onUpdatedRms(p0: Double) { level.value = p0 }
    }

    @Volatile private var ttsStarted = false
    private var sayId = 0

    fun say(text: String) {
        Log.i(TAG, "SAY: $text")
        // orb SPEAKING state: from the TTS onStart/onDone callbacks; if the platform gives none
        // (e.g. an emulator without an audio device), fall back to an estimate from the text length.
        speaking.value = true
        val id = ++sayId
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (id == sayId && !ttsStarted) speaking.value = false
        }, 900L + text.length * 150L)
        if (ttsReady) runCatching { tts?.speak(text, true) } else pendingSpeech.addLast(text)
    }

    companion object { const val TAG = "MATE"; const val PERM_SPEED = "android.car.permission.CAR_SPEED" }
}
