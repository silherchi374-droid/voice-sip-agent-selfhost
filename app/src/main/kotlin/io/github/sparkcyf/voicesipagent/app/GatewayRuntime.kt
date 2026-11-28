package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.BatteryManager
import android.os.Bundle
import android.os.Build
import android.telecom.TelecomManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.Manifest
import io.github.sparkcyf.voicesipagent.gateway.BridgeSession
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.CallPolicy
import io.github.sparkcyf.voicesipagent.gateway.EchoEndpoint
import io.github.sparkcyf.voicesipagent.gateway.GatewayConfig
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.CallAccessRequirements
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.sip.SipAccount
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.sip.SipLog
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.telephony.AndroidSmsTransport
import io.github.sparkcyf.voicesipagent.telephony.AndroidUssdTransport
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.telephony.SmsSender
import io.github.sparkcyf.voicesipagent.telephony.SystemSimSlots
import io.github.sparkcyf.voicesipagent.telephony.UssdBalanceParser
import io.github.sparkcyf.voicesipagent.telephony.UssdRunner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor

data class HealthState(
    val shizuku: ShizukuConnector.State = ShizukuConnector.State.SHIZUKU_NOT_RUNNING,
    val shizukuDetail: String = "",
    /** Null until the privileged service has reported. */
    val privileged: Map<String, Boolean>? = null,
    val privilegedDetail: String = "",
    val sip: EndpointStatus = EndpointStatus(false, "not configured"),
    val takeover: Boolean = true,
    /** Takeover was switched off during a call; it takes effect when that call ends. */
    val takeoverOffPending: Boolean = false,
    val takeoverOnPending: Boolean = false,
    val inCallServiceBound: Boolean = false,
    val loopbackTest: Boolean = false,
    val session: String? = null,
    val lastSetupLog: String = "",
    val holdsDialer: Boolean = false,
    val dialerRoleAvailable: Boolean = false,
    val effectiveCallAccess: Int = CallAccess.AUTO,
    val callAccessReady: Boolean = false,
    val audioReady: Boolean = false,
    val cellularReady: Boolean = false,
    val readinessDetail: String = "Checking gateway setup",
    val calls: CallSnapshot = CallSnapshot(),
    val settingsPending: Boolean = false,
    val dialerChangePending: Boolean = false,
    val dialerChangeRunning: Boolean = false,
    val dialerChangeFailed: Boolean = false,
    val dialerChangeAcquire: Boolean = false,
) {
    val privilegedReady: Boolean
        get() = callAccessReady && audioReady && shizuku == ShizukuConnector.State.CONNECTED

    /** Takeover needs persistence even before setup; a retained dialer role needs a reminder. */
    val needsForegroundService: Boolean
        get() = takeover || holdsDialer || session != null || calls.owned > 0
}

/**
 * Owns every long-lived component and wires them together:
 * Shizuku -> privileged setup -> cellular endpoint readiness; settings -> SIP UA; both -> CallBridge.
 * The bridge's B side is the SIP UA normally, or [EchoEndpoint] in loopback-test mode.
 * Switching takeover off withdraws companion access and SIP, then restores the previous dialer
 * unless the user chose to retain the role. Role-qualified recovery components stay enabled.
 */
class GatewayRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val worker = newWorker()
    val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settingsFlow: StateFlow<GatewaySettings> = _settings
    val settings: GatewaySettings get() = _settings.value

    // Service entry points can run before the worker initializes. Use the actual saved switch and
    // role immediately so opening the app or receiving BOOT_COMPLETED cannot flash an idle notification.
    private val _health = MutableStateFlow(run {
        val holds = holdsDialer()
        HealthState(takeover = settings.takeover, holdsDialer = holds,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds))
    })
    val health: StateFlow<HealthState> = _health
    private val _dialerMessage = MutableStateFlow("")
    val dialerMessage: StateFlow<String> = _dialerMessage

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    // Built-in Kotlin SIP stack is G.711 (narrowband) only.
    private fun audioFormat() = PcmFormat.NARROWBAND

    val cellular = CellularEndpoint(app,
        PrivilegedAudioOpener(shizuku, { settings.audioMethod }, { settings.muteEarpiece }, ::audioFormat),
        logger = { AppLog.i(it) },
        // Read live at each call, so the SIM settings apply without a restart.
        defaultSlot = { settings.defaultSimSlot },
        inboundExcluded = { settings.inboundExcludedSlots },
    )

    /** Call log + missed-call queue, persisted locally on the phone (no cloud). */
    private val recordsStore: KeyValueStore =
        SharedPreferencesKeyValueStore(app.getSharedPreferences("call_records", Context.MODE_PRIVATE))
    private val missedCallStore = MissedCallStore(recordsStore)
    private val callLogStore = CallLogStore(recordsStore)
    private val _callLog = MutableStateFlow(callLogStore.load())
    val callLog: StateFlow<List<CallLogEntry>> = _callLog
    /** Gateway addresses for the remote-access UI; refreshed on start and network changes. */
    private val _localAddresses = MutableStateFlow(LocalAddresses.list())
    val localAddresses: StateFlow<List<LocalAddress>> = _localAddresses
    /** Test-email result for the UI ("ok" or "failed: …"); null = no test sent yet. */
    private val _emailTestResult = MutableStateFlow<String?>(null)
    val emailTestResult: StateFlow<String?> = _emailTestResult
    private val emailNotifier = EmailNotifier()
    /** Session ids already recorded, so an ended session is logged exactly once. */
    private val recordedSessions = HashSet<Int>()
    /** Last mobile IP an IP-change email was sent for; null until the first observation. */
    private var lastNotifiedIp: String? = recordsStore.getString("last_notified_ip")
    // ---- Side-menu features (12 new, 2026-11-27) ----
    private val simSlots = SystemSimSlots(app)
    private val smsSender = SmsSender(simSlots, AndroidSmsTransport(app))
    private val ussdRunner = UssdRunner(AndroidUssdTransport(app))
    private val contactLookup = ContactLookup(app)
    private val findPhoneAlarm = FindPhoneAlarm(app)
    private val hotspotWatch = HotspotWatch(app, { shizuku.service }, { settings.hotspotAutoRestartEnabled })
    /** Low-battery alert state: true once the <20% email went out; re-arms above 25% / on charger. */
    private var lowBatteryAlerted = false
    /** Day (yyyy-MM-dd) of the last automatic balance check; at most one USSD check per day. */
    private var lastBalanceCheckDay: String? = recordsStore.getString("low_balance_check_day")
    /** Day (yyyy-MM-dd) a low-balance email was sent; at most one alert per day. */
    private var lowBalanceAlertDay: String? = recordsStore.getString("low_balance_alert_day")
    /** A RESTART command arrived; confirm it once the phone re-registers after the restart. */
    private var restartConfirmPending: Boolean = recordsStore.getString("restart_confirm_pending") == "1"
    /** Built-in SIP user agent. */
    private var sip: CallEndpoint? = null
    private var bridge: CallBridge? = null
    private var echo: EchoEndpoint? = null
    private var started = false
    private var pendingSettings: GatewaySettings? = null
    private var restartPending = false
    private var loopbackPending: Boolean? = null
    private var setupPending = false
    private var foregroundRequested: Boolean? = null
    private var serviceToken: Any? = null
    private var foregroundRunning = false
    private val gatewayEnabled get() = foregroundRunning && _health.value.takeover
    private var pendingDialerChange: DialerChange? = null
    private var dialerRetry: ScheduledFuture<*>? = null
    private var healthCheck: ScheduledFuture<*>? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val dialerHandoffStore = DialerHandoffStore(app)
    private val dialerHandoff = DialerHandoff(app.packageName, dialerHandoffStore, object : DialerHandoff.Backend {
        private fun service() = shizuku.service ?: error("Shizuku not connected. Use system dialer selection.")
        override fun state() = DialerRoleResult.fromBundle(service().dialerState()).let {
            it.state ?: error(it.message.ifEmpty { "Could not read the default dialer." })
        }
        override fun acquire(expectedHolder: String) = DialerRoleResult.fromBundle(service().becomeDefaultDialer(expectedHolder))
        override fun restore(previousHolder: String) = DialerRoleResult.fromBundle(service().restoreDefaultDialer(previousHolder))
    })
    private val takeoverControl = TakeoverControl(object : TakeoverControl.Host {
        override val enabled get() = _health.value.takeover
        override val active get() = hasActiveWork()
        override val needsDialer get() = _health.value.effectiveCallAccess == CallAccess.DIALER
        override val holdsDialer get() = _health.value.holdsDialer
        override val keepDialer get() = settings.keepDefaultDialer
        override fun saveEnabled(on: Boolean) {
            if (settings.takeover != on) {
                settingsStore.save(settings.copy(takeover = on).also { _settings.value = it })
            }
        }
        override fun cancelDialerChange() = clearDialerChange()
        override fun prepareDialerChange(acquire: Boolean) = prepareDialerChangeRequest(acquire)
        override fun showPending(on: Boolean?) {
            _health.update { it.copy(takeoverOffPending = on == false, takeoverOnPending = on == true) }
        }
        override fun applyEnabled() = applyTakeover()
    })

    private val sipLog = object : SipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }
    fun start() = worker.execute {
        if (started) return@execute
        started = true
        // Watchdog (feature #4): restart the gateway service if the system or the OEM killed
        // it. WorkManager persists this across reboots; a no-op while the gateway is off.
        runCatching { GatewayWatchdogWorker.schedule(app) }
            .onFailure { AppLog.w("watchdog schedule failed", it) }
        cellular.onCallsChanged = { snapshot ->
            _health.update { it.copy(calls = snapshot, inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
            worker.execute {
                syncForegroundService()
                if (!cellular.hasCalls && _health.value.session == null) applyDeferredChanges()
            }
        }
        AppLog.i("runtime starting ($settings)")
        shizuku.addListener { state, svc ->
            val detail = shizuku.detail
            worker.execute {
                if (shizuku.state != state || shizuku.service !== svc) return@execute
                _health.update { it.copy(shizuku = state, shizukuDetail = detail) }
                if (state == ShizukuConnector.State.CONNECTED && svc != null) {
                    if (pendingDialerChange?.acquire == true) attemptDialerChange()
                    runSetup()
                } else {
                    updatePrivileged(null, detail)
                }
            }
        }
        configureCallAccess()
        _health.update { it.copy(takeover = settings.takeover) }
        updateReadiness()
        if (!_health.value.takeover) shizuku.stop()
    }

    /** Track ownership before promotion so destruction also cancels a failed start. */
    internal fun onServiceCreated(service: Any) = worker.execute {
        serviceToken = service
        foregroundRunning = false
    }

    /** Called only after startForeground succeeds. All gateway work stays off the main thread. */
    internal fun onForegroundStarted(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        foregroundRunning = true
        foregroundRequested = true
        startGateway()
        updateReadiness()
    }

    internal fun onForegroundStopped(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        serviceToken = null
        foregroundRunning = false
        // Destruction releases resources, never changes roles or immediately restarts the service.
        // An explicit launch or a later switch-on can request it again.
        foregroundRequested = _health.value.needsForegroundService
        if (pendingDialerChange != null) clearDialerChange()
        stopGateway()
        configureCallAccess()
        updateReadiness()
    }

    private fun startGateway() {
        if (!gatewayEnabled || healthCheck != null) return
        configureCallAccess()
        startBackgroundTasks()
        startSip()
        rebuildBridge()
    }

    /** Stop producers before their dependencies; retain the helper for an explicit role handoff. */
    private fun stopGateway() {
        stopBackgroundTasks()
        runCatching { stopBridge() }.onFailure { AppLog.w("bridge cleanup failed", it) }
        runCatching { stopSip(releaseLibrary = true) }.onFailure { AppLog.w("SIP cleanup failed", it) }
        shizuku.service?.let { svc ->
            runCatching { svc.setupCallAccess(app.packageName, false, _health.value.effectiveCallAccess) }
                .onFailure { AppLog.w("companion cleanup failed", it) }
        }
        if (pendingDialerChange == null) runCatching { shizuku.stop() }
            .onFailure { AppLog.w("Shizuku cleanup failed", it) }
        _health.update { it.copy(privileged = null, sip = EndpointStatus(false,
            if (it.takeover) "gateway stopped" else "takeover off")) }
    }

    private fun startBackgroundTasks() {
        if (healthCheck != null) return
        worker.allowCoreThreadTimeOut(false)
        shizuku.start()
        watchNetwork()
        hotspotWatch.start()
        // Telecom or Shizuku restarts can disrupt call access. Recheck health and repair companion
        // setup while idle if access is unavailable; a missing override alone does not trigger setup.
        healthCheck = worker.scheduleWithFixedDelay(::periodicCheck, 60, 60, TimeUnit.SECONDS)
    }

    private fun stopBackgroundTasks() {
        healthCheck?.cancel(false)
        healthCheck = null
        dialerRetry?.cancel(false)
        dialerRetry = null
        hotspotWatch.stop()
        val callback = networkCallback
        networkCallback = null
        if (callback != null) runCatching {
            app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }.onFailure { AppLog.w("network callback cleanup failed", it) }
        setupPending = false
        worker.allowCoreThreadTimeOut(true)
    }

    // ------------------------------------------------------------ privileged setup / health

    fun runSetup() = worker.execute {
        if (!gatewayEnabled) { setupPending = false; return@execute }
        if (hasActiveWork()) {
            setupPending = true
            _health.update { it.copy(settingsPending = true) }
            return@execute
        }
        setupPending = false
        val svc = shizuku.service ?: return@execute updatePrivileged(null, "Shizuku not connected")
        try {
            refreshRoleState()
            val b = svc.setupCallAccess(app.packageName, _health.value.takeover, _health.value.effectiveCallAccess)
            _health.update { it.copy(lastSetupLog = b.getString("setup_log").orEmpty()) }
            applyHealthBundle(b)
        } catch (e: Exception) {
            AppLog.w("privileged setup failed", e)
            updatePrivileged(null, "setup failed: ${e.message}")
        }
    }

    private fun periodicCheck() {
        if (healthCheck == null) return
        refreshRoleState()
        val svc = shizuku.service ?: return updateReadiness()
        try {
            val b = svc.health(app.packageName)
            applyHealthBundle(b)
            if (_health.value.takeover && _health.value.effectiveCallAccess == CallAccess.COMPANION &&
                !_health.value.callAccessReady && !hasActiveWork()) {
                AppLog.i("health degraded; re-running setup")
                runSetup()
            }
        } catch (e: Exception) {
            AppLog.w("health check failed", e)
        }
        _health.update { it.copy(inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
        // Side-menu features 3 and 9: low-battery email (once per drop) and the daily
        // automatic balance check. Both fail silently when email is not configured.
        checkBattery()
        checkBalanceDaily()
    }

    private fun applyHealthBundle(b: Bundle) {
        val map = HealthKeys.BOOLEAN_KEYS.associateWith { b.getBoolean(it) }
        updatePrivileged(map, b.getString(HealthKeys.AUDIO_DETAIL).orEmpty())
    }

    private fun updatePrivileged(map: Map<String, Boolean>?, detail: String) {
        _health.update { it.copy(privileged = map, privilegedDetail = detail) }
        updateReadiness()
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    private fun holdsDialer() = app.getSystemService(TelecomManager::class.java).defaultDialerPackage == app.packageName

    private fun refreshRoleState() {
        val holds = holdsDialer()
        val available = app.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_DIALER)
        _health.update { it.copy(holdsDialer = holds,
            dialerRoleAvailable = available,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds)) }
    }

    private fun configureCallAccess() {
        refreshRoleState()
        cellular.setPreferredDialerService(_health.value.holdsDialer)
        if (!cellular.hasCalls) CellularEndpoint.setCompanionComponentEnabled(app,
            gatewayEnabled && _health.value.effectiveCallAccess == CallAccess.COMPANION && Build.VERSION.SDK_INT >= 31)
        // The dialer activity/UI service remain enabled, including when takeover is off.
    }

    fun refreshCallAccess() = worker.execute {
        configureCallAccess()
        updateReadiness()
        // Changing companion registration can change binding. Defer app-controlled setup while
        // Telecom has any call, including calls outside the bridge.
        if (gatewayEnabled) {
            if (!hasActiveWork()) runSetup() else setupPending = true
        }
    }

    @Suppress("DEPRECATION")
    private fun componentEnabled(type: Class<*>, service: Boolean): Boolean = runCatching {
        val component = ComponentName(app, type)
        val pm = app.packageManager
        val info = if (service) pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            else pm.getActivityInfo(component, 0)
        val setting = pm.getComponentEnabledSetting(component)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        enabled && info.applicationInfo.enabled && (!service ||
            (info as android.content.pm.ServiceInfo).permission == Manifest.permission.BIND_INCALL_SERVICE)
    }.getOrDefault(false)

    private fun updateReadiness() {
        refreshRoleState()
        val h = _health.value
        val p = h.privileged
        val requirements = CallAccessRequirements(h.effectiveCallAccess, Build.VERSION.SDK_INT,
            h.holdsDialer, h.dialerRoleAvailable,
            componentEnabled(GatewayDialerActivity::class.java, false) && componentEnabled(GatewayDialerService::class.java, true),
            componentEnabled(GatewayInCallService::class.java, true),
            p?.get(HealthKeys.MANAGE_ONGOING_CALLS) == true, p?.get(HealthKeys.COMPANION_OVERRIDE) == true,
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).all {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            })
        val access = requirements.ready
        val audio = when (settings.audioMethod) {
            AudioMethod.OFFICIAL -> p?.get(HealthKeys.REDIRECT_AUDIO) == true
            AudioMethod.LEGACY -> p?.get(HealthKeys.DIRECT_AUDIO) == true
            else -> p?.get(HealthKeys.DIRECT_AUDIO) == true || p?.get(HealthKeys.REDIRECT_AUDIO) == true
        }
        val detail = when {
            h.dialerChangeRunning -> "Changing the default dialer"
            h.takeoverOnPending -> "Enabling gateway after current calls end"
            h.takeoverOffPending -> "Turning gateway off after current calls end"
            h.dialerChangePending -> if (h.dialerChangeAcquire)
                "Waiting to become the default dialer" else "Waiting to restore the previous dialer"
            !h.takeover -> if (h.holdsDialer) "Gateway off; this app still holds the dialer role" else "Gateway off"
            !gatewayEnabled -> "Gateway service not running"
            !access -> requirements.problem!!
            h.shizuku != ShizukuConnector.State.CONNECTED -> "Shizuku not connected"
            !audio -> "Selected call audio unavailable: ${h.privilegedDetail}"
            !h.loopbackTest && !h.sip.ready -> "SIP unavailable: ${h.sip.detail}"
            else -> "Gateway ready"
        }
        val ready = !h.dialerChangeRunning && gatewayEnabled && access && audio && h.shizuku == ShizukuConnector.State.CONNECTED &&
            (h.loopbackTest || h.sip.ready)
        _health.update { it.copy(callAccessReady = access, audioReady = audio, cellularReady = ready, readinessDetail = detail) }
        cellular.setReadiness(ready, detail)
        syncForegroundService()
    }

    private fun syncForegroundService() {
        val h = _health.value
        if (foregroundRequested == h.needsForegroundService) return
        if (GatewayService.sync(app, h)) foregroundRequested = h.needsForegroundService
    }

    private fun hasActiveWork() = cellular.hasCalls || bridge?.session != null || _health.value.session != null

    fun showDialerMessage(message: String) { _dialerMessage.value = message }

    /** Called only from an explicit, unlocked UI action. */
    fun changeDefaultDialer(acquire: Boolean) = worker.execute {
        clearDialerChange()
        prepareDialerChangeRequest(acquire)
        attemptDialerChange()
    }

    private fun clearDialerChange() {
        dialerRetry?.cancel(false)
        dialerRetry = null
        pendingDialerChange = null
        _health.update { it.copy(dialerChangePending = false, dialerChangeFailed = false, dialerChangeAcquire = false) }
        _dialerMessage.value = ""
        if (!_health.value.takeover) shizuku.stop()
    }

    private fun prepareDialerChangeRequest(acquire: Boolean) {
        _health.update { it.copy(dialerChangeAcquire = acquire) }
        try {
            val prepared = if (acquire) {
                // Null can mean an empty role; the helper rechecks the holder before any change.
                val holder = app.getSystemService(TelecomManager::class.java).defaultDialerPackage.orEmpty()
                dialerHandoff.prepareAcquisition(PreviousDialer(android.os.Process.myUid() / 100_000, holder))
            } else dialerHandoff.prepare(false)
            pendingDialerChange = prepared.change
            prepared.result?.let(::finishDialerChange)
        } catch (e: Exception) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Could not prepare the dialer change: ${e.message}. Use system default-app settings."))
        }
    }

    private fun finishDialerChange(result: DialerRoleResult) {
        clearDialerChange()
        _health.update { it.copy(dialerChangeFailed = result.status == DialerRoleResult.FAILED) }
        _dialerMessage.value = result.message
        AppLog.i("dialer change: ${result.status} ${result.message}")
        refreshCallAccess()
    }

    private fun attemptDialerChange() {
        val change = pendingDialerChange ?: return
        dialerRetry?.cancel(false)
        dialerRetry = null
        if (change.acquire && (!_health.value.takeover || shizuku.service == null)) {
            _health.update { it.copy(dialerChangePending = true) }
            _dialerMessage.value = "Waiting for Shizuku. You can cancel the dialer change."
            updateReadiness()
            return
        }
        if (hasActiveWork()) { deferDialerChange(); return }
        takeoverControl.applyIfIdle()
        _health.update { it.copy(dialerChangeRunning = true, dialerChangePending = false) }
        val result = try {
            updateReadiness()
            dialerHandoff.execute(change)
        } catch (e: Exception) {
            DialerRoleResult(DialerRoleResult.FAILED, "Dialer change failed: ${e.message}. Use system default-app settings.")
        } finally {
            _health.update { it.copy(dialerChangeRunning = false) }
        }
        if (result.deferred) deferDialerChange() else finishDialerChange(result)
        updateReadiness()
    }

    private fun deferDialerChange() {
        if (!_health.value.takeover) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Gateway off. Choose your phone app in system Settings after current calls end."))
            return
        }
        _health.update { it.copy(dialerChangePending = true) }
        _dialerMessage.value = "Waiting for current calls to end. You can cancel the dialer change."
        // Before acquisition our InCallService may not see the other dialer's calls. The helper
        // queries Telecom, including self-managed calls, on each retry and before each command.
        dialerRetry = worker.schedule({ dialerRetry = null; attemptDialerChange() }, 1, TimeUnit.SECONDS)
    }

    fun cancelDialerChange() = worker.execute {
        val wasPending = pendingDialerChange != null
        clearDialerChange()
        if (wasPending) _dialerMessage.value = "Dialer change cancelled."
    }

    /** Public role-dialog fallback still saves a return destination before Android can kill us. */
    fun rememberDialerForSystemRequest(): Boolean {
        val previous = app.getSystemService(TelecomManager::class.java).defaultDialerPackage
            ?: return false.also { showDialerMessage("Could not read the previous dialer. Use system default-app settings.") }
        if (previous == app.packageName) return true
        val saved = dialerHandoffStore.save(PreviousDialer(android.os.Process.myUid() / 100_000, previous))
        if (!saved) showDialerMessage("Could not save the previous dialer; no role change was attempted.")
        return saved
    }

    private fun sipStatus(endpoint: CallEndpoint, status: EndpointStatus) = worker.execute {
        if (sip !== endpoint) return@execute
        _health.update { it.copy(sip = status) }
        updateReadiness()
    }

    // ------------------------------------------------------------ SIP

    private fun startSip() {
        if (!gatewayEnabled) return
        stopSip()
        val s = settings
        if (!s.takeover) {
            _health.update { it.copy(sip = EndpointStatus(false, "takeover off")) }
            return
        }
        if (!s.sipConfigured) {
            _health.update { it.copy(sip = EndpointStatus(false, "not configured")) }
            return
        }
        val transport = SipTransport.of(s.sipTransport)
        val srtp = SrtpPolicy.of(s.srtp)
        if (srtp == null) {
            AppLog.w("unknown SRTP policy '${s.srtp}'")
            _health.update { it.copy(sip = EndpointStatus(false, "unknown srtp ${s.srtp}")) }
            return
        }
        if (transport == null || transport != SipTransport.UDP) {
            AppLog.w("SIP transport '${s.sipTransport}' not supported by the built-in stack (UDP only)")
            _health.update { it.copy(sip = EndpointStatus(false, "transport ${s.sipTransport} unsupported (UDP only)")) }
            return
        }
        try {
            val ep: CallEndpoint = SipUserAgent(
                SipConfig(
                    account = SipAccount(
                        server = s.server,
                        port = s.port,
                        username = s.username,
                        password = s.password,
                        domain = s.domain.ifBlank { s.server },
                        displayName = "gateway",
                    ),
                    localPort = s.localSipPort,
                    serverMode = s.serverMode,
                    registrarLeaseSec = s.registrarLeaseSec,
                    registrarPassword = s.registrarPassword,
                ),
                sipLog,
            ).also { ua ->
                ua.addStatusListener { ep, st -> sipStatus(ep, st) }
                // The softphone (re-)registered: deliver queued missed calls as SIP MESSAGEs,
                // and confirm a pending remote RESTART once the phone is back.
                ua.onPeerRegistered = { worker.execute { flushMissedCalls(); confirmRestartIfPending() } }
                // Remote commands from the registered phone (SMS, USSD, RESTART, FIND). The UA
                // already enforced digest auth + registered-sender before this is called.
                ua.onSipMessage = { _, body -> worker.execute { handleSipCommand(body) } }
                ua.start()
            }
            sip = ep
            _health.update { it.copy(sip = ep.status) }
            AppLog.i("SIP stack: built-in${if (s.serverMode) " (server mode)" else ""}")
        } catch (e: Throwable) {
            AppLog.w("SIP start failed", e)
            _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${e.message}")) }
        }
    }

    private fun stopSip(releaseLibrary: Boolean = false) {
        val previous = sip
        sip = null
        (previous as? SipUserAgent)?.stop()
    }

    private fun refreshSipRegistration() {
        (sip as? SipUserAgent)?.refreshRegistration()
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastAddrs: String? = null
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (networkCallback !== this) return
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress.orEmpty() }
                if (addrs != lastAddrs) {
                    if (lastAddrs != null) {
                        AppLog.i("network addresses changed; re-registering")
                        worker.execute {
                            if (networkCallback === this && _health.value.takeover) refreshSipRegistration()
                            if (networkCallback === this) checkIpChange()
                        }
                    }
                    lastAddrs = addrs
                }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    // ------------------------------------------------------------ bridge

    private fun stopBridge() {
        val previous = bridge
        bridge = null
        val previousEcho = echo
        echo = null
        _health.update { it.copy(session = null) }
        try { previous?.stop() } finally { previousEcho?.close() }
    }

    private fun rebuildBridge() {
        stopBridge()
        if (!gatewayEnabled) return
        val s = settings
        if (!s.takeover) {
            bridge = null
            AppLog.i("bridge idle: takeover off")
            return
        }
        val loopback = _health.value.loopbackTest
        val sideB: CallEndpoint? = if (loopback) EchoEndpoint(format = audioFormat()).also { echo = it } else sip
        if (sideB == null) {
            bridge = null
            AppLog.i("bridge idle: SIP not configured")
            return
        }
        // In server mode the SIP side dials whichever phone is registered, so the target only has
        // to be non-empty; "phone" is a readable placeholder when no DID/username is configured.
        val inboundTarget = s.inboundTarget.ifBlank { s.username.ifBlank { if (s.serverMode) "phone" else "" } }
        val b = CallBridge(
            sideA = cellular,
            sideB = sideB,
            routeAtoB = { inboundTarget },
            routeBtoA = { leg -> leg.target?.takeIf { it != s.username } },
            config = GatewayConfig(
                cellularInboundTarget = inboundTarget,
                busyPolicy = s.busyPolicy,
                allowedDialPrefixes = s.allowedPrefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            dialPolicyForA = { n -> if (cellular.isEmergencyNumber(n)) HangupCause.FORBIDDEN else null },
            log = { AppLog.i("bridge: $it") },
            // Side-menu features 4, 5, 12: screen inbound cellular calls before dialing the
            // phone; a vetoed call is recorded as missed (call log + email + auto-reply).
            inboundVeto = { leg -> if (leg.kind == "cellular") inboundVetoFor(leg.remote.number) else null },
            onInboundVetoed = { leg, cause -> worker.execute { recordVetoedCall(leg.remote.number, cause) } },
            // Side-menu feature 6: show the Infinix contact name in Linphone.
            callerDisplayName = { number ->
                if (settings.callerNameEnabled &&
                    app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                ) contactLookup.nameFor(number) else null
            },
            // Feature 13: forward incoming GSM calls to the configured external SIP URI
            // (e.g. the user's Linphone account) so Linphone's servers can push the iPhone.
            // A failed forward falls back to the local phone; every attempt is logged.
            externalForwardForA = { leg ->
                if (leg.kind == "cellular") settings.externalForwardTarget.trim().takeIf { it.isNotEmpty() } else null
            },
            onExternalForward = { target, event ->
                AppLog.i("external forward ${redactNumber(target)}: $event")
            },
        )
        b.addListener { session ->
            val description = describe(session)
            worker.execute {
                if (bridge !== b) return@execute
                _health.update { it.copy(session = description) }
                syncForegroundService()
                if (session.endedAtMs != null) {
                    recordEndedSession(session)
                    if (!cellular.hasCalls) applyDeferredChanges()
                }
            }
        }
        bridge = b
        b.start()
        AppLog.i("bridge ready: cellular <-> ${sideB.name}")
    }

    private fun describe(s: BridgeSession): String? {
        if (s.endedAtMs != null) return null
        val out = s.outbound
        val via = when {
            s.externalForwarded && s.fellBackToLocal -> " (forward failed, local)"
            s.externalForwarded -> " (via ${s.externalTarget})"
            else -> ""
        }
        return "${s.origin}: ${redactNumber(s.inbound.remote.number)} ${s.inbound.state} -> ${out?.kind} ${out?.state ?: LegState.NEW}$via"
    }

    // ------------------------------------------------------------ external controls

    fun applySettings(new: GatewaySettings) = worker.execute {
        require(new.audioMethod in 0..2 && new.callAccess in 0..2) { "unknown platform setting" }
        if (hasActiveWork()) {
            pendingSettings = new
            _health.update { it.copy(settingsPending = true) }
            AppLog.i("settings will apply after current calls end")
            return@execute
        }
        val old = settings
        settingsStore.save(new)
        _settings.value = new
        AppLog.i("settings saved ($new)")
        val platformOnly = old.copy(audioMethod = new.audioMethod, callAccess = new.callAccess,
            muteEarpiece = new.muteEarpiece, sipDebugLog = new.sipDebugLog,
            keepDefaultDialer = new.keepDefaultDialer) == new
        if (!platformOnly) { startSip(); rebuildBridge() }
        configureCallAccess()
        runSetup()
        updateReadiness()
    }

    /** pjsip info-level logging; takes effect immediately, persisted for the next start. */
    fun setSipDebugLog(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(sipDebugLog = on).also { _settings.value = it })
        AppLog.i("SIP debug log ${if (on) "on" else "off"}")
    }

    /** Read at each call's audio setup, so no restart is needed. */
    fun setMuteEarpiece(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(muteEarpiece = on).also { _settings.value = it })
        AppLog.i("earpiece mute ${if (on) "on" else "off"} (from next call)")
    }

    /** UI-only preference; never touches SIP or the bridge. */
    fun setUiLanguage(lang: String) = worker.execute {
        if (lang == settings.uiLanguage) return@execute
        settingsStore.save(settings.copy(uiLanguage = lang).also { _settings.value = it })
    }

    /** Changes the next switch-off, without touching the current call or any queued handoff. */
    fun setKeepDefaultDialer(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(keepDefaultDialer = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(keepDefaultDialer = on)
    }

    /** The master switch. Switching off mid-call waits for that call to end. */
    fun setTakeover(on: Boolean) = worker.execute {
        refreshRoleState()
        takeoverControl.request(on)
        attemptDialerChange()
        updateReadiness()
    }

    private fun applyTakeover() {
        val on = settings.takeover
        _health.update { it.copy(takeover = on, takeoverOffPending = false, takeoverOnPending = false) }
        configureCallAccess()
        if (on) startGateway() else stopGateway()
        // Withdraws or restores the Telecom companion override.
        runSetup()
        updateReadiness()
        AppLog.i(if (on) "call takeover ON" else "call takeover OFF")
    }

    fun setLoopbackTest(on: Boolean) = worker.execute {
        if (hasActiveWork()) { loopbackPending = on; _health.update { it.copy(settingsPending = true) }; return@execute }
        if (_health.value.loopbackTest == on) return@execute
        _health.update { it.copy(loopbackTest = on) }
        AppLog.i("loopback test ${if (on) "ON: inbound cellular calls are answered and echoed" else "OFF"}")
        rebuildBridge()
        updateReadiness()
    }

    fun restartSip() = worker.execute {
        if (hasActiveWork()) { restartPending = true; _health.update { it.copy(settingsPending = true) }; return@execute }
        startSip()
        rebuildBridge()
        updateReadiness()
    }

    /** Ends the active bridged call, if any (notification Hang up action). */
    fun hangupActiveCall() = worker.execute {
        bridge?.hangupCurrent()
        AppLog.i("hanging up the active call from the notification")
    }

    /** Outbound SIM for calls without X-VSA-SIMSLOT. Applies immediately, no restart. */
    fun setDefaultSimSlot(slot: Int) = worker.execute {
        if (slot == settings.defaultSimSlot) return@execute
        settingsStore.save(settings.copy(defaultSimSlot = slot).also { _settings.value = it })
        AppLog.i("default SIM slot set to $slot")
    }

    /** Per-SIM incoming takeover. Applies immediately, no restart. */
    fun setSimInbound(slot: Int, takeOver: Boolean) = worker.execute {
        val excluded = settings.inboundExcludedSlots.toMutableSet()
        if (takeOver) excluded.remove(slot) else excluded.add(slot)
        if (excluded == settings.inboundExcludedSlots) return@execute
        settingsStore.save(settings.copy(inboundExcludedSlots = excluded).also { _settings.value = it })
        AppLog.i("SIM slot $slot incoming takeover ${if (takeOver) "on" else "off"}")
    }

    /** Sends a test email with the current SMTP settings; the result lands in [emailTestResult]. */
    fun sendTestEmail() = worker.execute {
        val config = EmailNotifier.configFrom(settings)
        if (config == null) {
            _emailTestResult.value = "not configured"
            return@execute
        }
        emailNotifier.send(config, EmailSubjects.TEST, EmailBodies.TEST_BODY) { ok, error ->
            _emailTestResult.value = if (ok) "ok" else "failed: ${error ?: "unknown error"}"
        }
    }

    /** Refreshes the remote-access address list (called on start and network changes). */
    fun refreshLocalAddresses() {
        _localAddresses.value = LocalAddresses.list()
    }

    /**
     * Records an ended bridged call in the call log. An unanswered incoming GSM call is a
     * missed call: it is queued for SIP MESSAGE delivery on re-register and emailed if the
     * user enabled missed-call emails. Runs on the worker thread; each session once.
     */
    private fun recordEndedSession(s: BridgeSession) {
        val endedAt = s.endedAtMs ?: return
        if (!recordedSessions.add(s.id)) return
        val answered = s.connectedAtMs != null
        val durationSec = if (answered) (endedAt - s.connectedAtMs!!) / 1000 else null
        if (s.origin == "cellular") {
            val inbound = s.inbound
            _callLog.value = callLogStore.add(CallLogEntry(
                direction = CallDirection.INCOMING,
                number = inbound.remote.number,
                timeMs = endedAt,
                slot = inbound.line,
                durationSec = durationSec,
                outcome = if (answered) CallOutcome.ANSWERED else CallOutcome.MISSED,
            ))
            if (!answered) {
                val missed = MissedCall(inbound.remote.number, endedAt, inbound.line)
                missedCallStore.add(missed)
                notifyMissedCall(missed)
                maybeAutoReply(missed)
            }
        } else {
            val out = s.outbound
            _callLog.value = callLogStore.add(CallLogEntry(
                direction = CallDirection.OUTGOING,
                number = out?.remote?.number ?: s.inbound.remote.number,
                timeMs = endedAt,
                slot = out?.line ?: s.inbound.line,
                durationSec = durationSec,
                outcome = if (answered) CallOutcome.ANSWERED else CallOutcome.NO_ANSWER,
            ))
        }
    }

    private fun notifyMissedCall(missed: MissedCall) {
        val s = settings
        if (!s.emailNotifyEnabled || !s.emailMissedCalls) return
        val config = EmailNotifier.configFrom(s) ?: return
        emailNotifier.send(
            config,
            EmailSubjects.missedCall(missed.number),
            EmailBodies.missedCall(missed.number, missed.timeMs, missed.slot),
        )
    }

    // ------------------------------------------------------------ side-menu features

    /**
     * Call screening for inbound cellular calls (features 4, 5, 12). Returns the hangup cause
     * when the call must be rejected, null when it may ring the phone. Order matters: a hidden
     * number is rejected even before the blocklist is consulted.
     */
    private fun inboundVetoFor(number: String?): HangupCause? {
        val s = settings
        if (s.blockHiddenNumbers && CallPolicy.isHidden(number)) return HangupCause.FORBIDDEN
        if (CallPolicy.isBlocked(number, s.blockedNumbers)) return HangupCause.FORBIDDEN
        if (s.nightModeEnabled) {
            val now = Calendar.getInstance()
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (CallPolicy.isNightNow(nowMin, s.nightStartMin, s.nightEndMin)) return HangupCause.FORBIDDEN
        }
        return null
    }

    /** A vetoed call never created a bridge session, so it is recorded as missed here. */
    private fun recordVetoedCall(number: String?, cause: HangupCause) {
        val now = System.currentTimeMillis()
        _callLog.value = callLogStore.add(CallLogEntry(
            direction = CallDirection.INCOMING,
            number = number,
            timeMs = now,
            slot = null,
            durationSec = null,
            outcome = CallOutcome.MISSED,
        ))
        val missed = MissedCall(number, now, null)
        missedCallStore.add(missed)
        notifyMissedCall(missed)
        maybeAutoReply(missed)
        AppLog.i("incoming call from ${redactNumber(number)} rejected by call policy ($cause)")
    }

    /** Feature 7: text a missed caller the configured message (costs credit; default OFF). */
    private fun maybeAutoReply(missed: MissedCall) {
        val s = settings
        if (!s.autoReplyEnabled) return
        val number = missed.number ?: return
        if (s.autoReplyText.isBlank()) return
        val slot = missed.slot ?: s.defaultSimSlot.takeIf { it >= 0 }
        when (val r = smsSender.send(number, s.autoReplyText, slot = slot)) {
            is SmsSender.Result.Sent -> AppLog.i("auto-reply SMS sent to ${redactNumber(number)}")
            is SmsSender.Result.Failed -> AppLog.w("auto-reply SMS failed: ${r.reason}")
        }
    }

    /**
     * Handles one remote command from the registered phone (features 1, 2, 8, 11). Runs on the
     * worker thread. Every outcome is answered back to the phone as a SIP MESSAGE so the user
     * sees what happened; a reply that finds no phone registered is only logged.
     */
    private fun handleSipCommand(body: String) {
        val ua = sip as? SipUserAgent ?: return
        fun reply(text: String) {
            if (!ua.sendMessage("gateway", text)) AppLog.w("command reply not delivered: no phone registered")
        }
        when (val cmd = SipCommandParser.parse(body)) {
            null -> reply(SipCommandParser.USAGE)
            is SipCommand.SendSms -> {
                if (!settings.smsCommandsEnabled) {
                    reply("SMS commands are off (side menu → SMS from iPhone).")
                    return
                }
                when (val r = smsSender.send(cmd.destination, cmd.text)) {
                    is SmsSender.Result.Sent -> reply("SMS sent to ${cmd.destination}.")
                    is SmsSender.Result.Failed -> reply("SMS failed: ${r.reason}")
                }
            }
            is SipCommand.RunUssd -> {
                if (!settings.ussdCommandsEnabled) {
                    reply("USSD commands are off (side menu → USSD balance check).")
                    return
                }
                val code = cmd.code ?: settings.ussdDefaultCode
                ussdRunner.run(code) { result ->
                    worker.execute {
                        when (result) {
                            is UssdRunner.Result.Success -> reply("USSD $code:\n${result.response}")
                            is UssdRunner.Result.Failed -> reply("USSD $code failed: ${result.reason}")
                        }
                    }
                }
            }
            is SipCommand.Restart -> {
                if (!settings.remoteRestartEnabled) {
                    reply("Remote restart is off (side menu → Remote restart).")
                    return
                }
                restartConfirmPending = true
                recordsStore.putString("restart_confirm_pending", "1")
                AppLog.i("remote RESTART command: restarting SIP stack")
                restartSip()
            }
            is SipCommand.FindPhone -> {
                if (!settings.findPhoneEnabled) {
                    reply("Find-my-phone is off (side menu → Find my Infinix).")
                    return
                }
                if (cmd.stop) {
                    findPhoneAlarm.stop()
                    reply("Alarm stopped.")
                } else {
                    findPhoneAlarm.start()
                    reply("Ringing the Infinix at full volume (30 s max). Send FIND STOP to silence it.")
                }
            }
        }
    }

    /** Confirms a remote RESTART once the phone re-registers after the restart. */
    private fun confirmRestartIfPending() {
        if (!restartConfirmPending) return
        val ua = sip as? SipUserAgent ?: return
        if (ua.sendMessage("gateway", "Gateway restarted.")) {
            restartConfirmPending = false
            recordsStore.putString("restart_confirm_pending", "")
        } else {
            AppLog.w("restart confirmation not delivered; will retry on the next registration")
        }
    }

    /** Feature 3: email once when the battery drops below 20%; re-arm above 25% / on charger. */
    private fun checkBattery() {
        val s = settings
        if (!s.lowBatteryAlertEnabled) return
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (pct < 20 && !lowBatteryAlerted) {
            lowBatteryAlerted = true
            val config = EmailNotifier.configFrom(s)
            if (config == null) {
                AppLog.w("low-battery alert skipped: email not configured")
                return
            }
            emailNotifier.send(
                config,
                EmailSubjects.lowBattery(pct),
                EmailBodies.lowBattery(pct, System.currentTimeMillis(), charging),
            )
            AppLog.i("low-battery email sent ($pct%)")
        } else if (pct > 25 || charging) {
            lowBatteryAlerted = false
        }
    }

    /**
     * Feature 9: automatic daily balance check via USSD. At most one check per day; at most
     * one low-balance email per day. An unparseable reply is logged and never alerts.
     */
    private fun checkBalanceDaily() {
        val s = settings
        if (!s.lowBalanceAlertEnabled) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastBalanceCheckDay == today) return
        lastBalanceCheckDay = today
        recordsStore.putString("low_balance_check_day", today)
        ussdRunner.run(s.ussdDefaultCode) { result ->
            worker.execute {
                when (result) {
                    is UssdRunner.Result.Success -> {
                        val amount = UssdBalanceParser.parseAmountTnd(result.response)
                        if (amount == null) {
                            AppLog.w("daily balance check: could not parse USSD reply: ${result.response.take(120)}")
                            return@execute
                        }
                        val threshold = settings.lowBalanceThresholdTnd
                        if (amount < threshold && lowBalanceAlertDay != today) {
                            lowBalanceAlertDay = today
                            recordsStore.putString("low_balance_alert_day", today)
                            val config = EmailNotifier.configFrom(settings)
                            if (config == null) {
                                AppLog.w("low-balance alert skipped: email not configured")
                                return@execute
                            }
                            val amountText = "%.3f".format(Locale.US, amount).trimEnd('0').trimEnd('.')
                            emailNotifier.send(
                                config,
                                EmailSubjects.lowBalance(amountText),
                                EmailBodies.lowBalance(amount, System.currentTimeMillis()),
                            )
                            AppLog.i("low-balance email sent ($amount TND)")
                        }
                    }
                    is UssdRunner.Result.Failed -> AppLog.w("daily balance check failed: ${result.reason}")
                }
            }
        }
    }

    /** Stops the find-my-phone alarm (notification action / FIND STOP). */
    fun stopFindPhoneAlarm() = worker.execute { findPhoneAlarm.stop() }

    /**
     * Delivers queued missed calls to the freshly registered softphone as SIP MESSAGEs
     * (the same path as SMS forwarding). Delivered entries leave the queue; the rest stay
     * for the next registration.
     */
    private fun flushMissedCalls() {
        val queued = missedCallStore.load()
        if (queued.isEmpty()) return
        val ua = sip as? SipUserAgent ?: return
        var delivered = 0
        for (m in queued) {
            val text = "Missed call from ${m.number ?: "unknown number"} at ${EmailBodies.formatTime(m.timeMs)}" +
                (m.slot?.let { " (SIM ${it + 1})" } ?: "")
            if (ua.sendMessage("gateway", text)) delivered++ else break
        }
        if (delivered > 0) missedCallStore.drop(delivered)
        AppLog.i("missed calls delivered to the phone: $delivered/${queued.size}")
    }

    /**
     * The gateway's primary address changed while running (mobile data dropped and came back
     * with a new CGNAT address). Emails the new address once per change so the user can
     * update Linphone; the first observation only sets the baseline and sends nothing.
     */
    private fun checkIpChange() {
        val addresses = LocalAddresses.list()
        _localAddresses.value = addresses
        val current = LocalAddresses.primary(addresses) ?: return
        val previous = lastNotifiedIp
        lastNotifiedIp = current
        recordsStore.putString("last_notified_ip", current)
        if (previous == null || previous == current) return
        val s = settings
        if (!s.emailNotifyEnabled || !s.emailIpChange) return
        val config = EmailNotifier.configFrom(s) ?: return
        emailNotifier.send(
            config,
            EmailSubjects.newIp(current),
            EmailBodies.newIp(current, addresses),
        )
        AppLog.i("gateway IP changed $previous -> $current; email sent")
    }

    private fun applyDeferredChanges() {
        if (hasActiveWork()) return
        takeoverControl.applyIfIdle()
        if (pendingDialerChange != null) attemptDialerChange()
        val queued = pendingSettings
        pendingSettings = null
        val restart = restartPending
        restartPending = false
        val loopback = loopbackPending
        loopbackPending = null
        _health.update { it.copy(settingsPending = false) }
        if (queued != null) applySettings(queued.copy(takeover = settings.takeover))
        if (loopback != null) setLoopbackTest(loopback)
        if (restart) restartSip()
        configureCallAccess()
        updateReadiness()
        if (setupPending || queued != null || _health.value.privileged == null ||
            (_health.value.effectiveCallAccess == CallAccess.COMPANION && !_health.value.callAccessReady)) runSetup()
    }

    fun statusReport(): String {
        val h = _health.value
        return buildString {
            appendLine("takeover=${h.takeover} pendingOff=${h.takeoverOffPending} pendingOn=${h.takeoverOnPending} " +
                "keepDefaultDialer=${settings.keepDefaultDialer} muteEarpiece=${settings.muteEarpiece}")
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready} stack=builtin " +
                "transport=${settings.sipTransport} srtp=${settings.srtp} debugLog=${settings.sipDebugLog}")
            appendLine("cellular=${cellular.status.detail} inCallServiceBound=${CellularEndpoint.isInCallServiceBound} " +
                "service=${CellularEndpoint.boundServiceDescription ?: "none"}")
            appendLine("sdk=${Build.VERSION.SDK_INT} callAccess=${settings.callAccess} effective=${h.effectiveCallAccess} " +
                "defaultDialer=${h.holdsDialer} audioMethod=${settings.audioMethod} audioReady=${h.audioReady}")
            appendLine("dialerChangePending=${h.dialerChangePending} running=${h.dialerChangeRunning} ${_dialerMessage.value}")
            appendLine("calls=${h.calls.summary} settingsPending=${h.settingsPending}")
            appendLine("loopback=${h.loopbackTest} session=${h.session}")
            shizuku.service?.let { appendLine("tap=${runCatching { it.audioStats() }.getOrDefault("?")}") }
        }
    }

    /** Forward an incoming cellular SMS to the registered phone (server mode) as a SIP MESSAGE. */
    fun forwardSms(from: String?, text: String, slot: Int? = null) = worker.execute {
        val s = sip
        if (s is SipUserAgent && s.sendMessage(from, text)) {
            AppLog.i("SMS forwarded to the registered phone")
        } else {
            AppLog.w("SMS not forwarded: no phone registered")
        }
        // SMS -> email (OTP codes): the SIP MESSAGE does not reliably show in Linphone on iOS.
        val st = settings
        if (st.emailNotifyEnabled && st.emailSms) {
            EmailNotifier.configFrom(st)?.let { config ->
                emailNotifier.send(
                    config,
                    EmailSubjects.sms(from),
                    EmailBodies.sms(from, System.currentTimeMillis(), slot, text),
                )
            }
        }
    }

    companion object {
        @Volatile private var instance: GatewayRuntime? = null

        internal fun newWorker() = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "gateway-runtime").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(1, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) { instance ?: GatewayRuntime(context).also { instance = it } }
    }
}
