package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : DialerRoleActivity() {
    /** SMS + contacts runtime permissions for the side-menu features (SMS, caller name, auto-reply). */
    private val permissionTick = mutableStateOf(0)
    private val featurePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionTick.value++
        }

    private fun requestFeaturePermissions() {
        featurePermissionLauncher.launch(
            arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                // Side menu (navigation drawer): all 12 new features live here; the main
                // screen stays exactly as it is.
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var selectedFeature by remember { mutableStateOf<MoreFeature?>(null) }
                // Bumps when the SMS/contacts permission dialog returns, so the drawer
                // screens re-read the granted state.
                val permissionVersion by permissionTick
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text(t("Доп. функции", "More features", "ميزات إضافية"),
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                            MoreFeature.entries.forEach { f ->
                                NavigationDrawerItem(
                                    label = { Text(moreFeatureTitle(f)) },
                                    selected = selectedFeature == f,
                                    onClick = {
                                        selectedFeature = f
                                        scope.launch { drawerState.close() }
                                    },
                                )
                            }
                        }
                    },
                ) {
                    Scaffold { pad ->
                        if (selectedFeature == null) {
                            Screen(runtime, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                                ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message,
                                onMenuClick = { scope.launch { drawerState.open() } })
                        } else {
                            key(permissionVersion) {
                                MoreFeatureScreen(selectedFeature!!, runtime, Modifier.padding(pad),
                                    onBack = { selectedFeature = null },
                                    onRequestPermissions = ::requestFeaturePermissions)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String,
    onMenuClick: () -> Unit) {
    val h by rt.health.collectAsState()
    val log by AppLog.tail.collectAsState()
    val cur by rt.settingsFlow.collectAsState()
    val context = LocalContext.current
    currentUiLang = UiLang.of(cur.uiLanguage)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Text glyph (no material-icons dependency): the app already uses ● ○ ★ ⓘ.
                IconButton(onClick = onMenuClick) {
                    Text("☰", style = MaterialTheme.typography.titleLarge)
                }
                Text("GSMtoSIP", style = MaterialTheme.typography.headlineSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lang = UiLang.of(cur.uiLanguage)
                TextButton(onClick = { rt.setUiLanguage("ru") }, enabled = lang != UiLang.RU) { Text("РУ") }
                TextButton(onClick = { rt.setUiLanguage("en") }, enabled = lang != UiLang.EN) { Text("EN") }
                TextButton(onClick = { rt.setUiLanguage("ar") }, enabled = lang != UiLang.AR) { Text("عربي") }
                InfoButton("language")
            }
        }
        Section(t("Перехват", "Takeover", "تحويل المكالمات"), info = "takeover") {
            Toggle(t("Перехватывать звонки", "Take over calls", "تحويل المكالمات"), cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> t("Выключится после текущего звонка", "Turning off after the current call", "سيتوقف بعد المكالمة الحالية")
                    h.takeoverOnPending -> t("Включится после завершения звонков", "Turning on after current calls end", "سيعمل بعد انتهاء المكالمات الحالية")
                    h.dialerChangeRunning -> t("Меняется звонилка по умолчанию", "Changing the default dialer", "جارٍ تغيير تطبيق الهاتف الافتراضي")
                    h.dialerChangePending -> if (h.dialerChangeAcquire) t("Ожидание роли звонилки по умолчанию", "Waiting to become the default dialer", "بانتظار أن يصبح تطبيق الهاتف الافتراضي") else t("Ожидание возврата прежней звонилки", "Waiting to restore your previous dialer", "بانتظار استعادة تطبيق الهاتف السابق")
                    cur.takeover -> if (h.cellularReady) t("Звонки переводятся на софтфон", "Calls are bridged to the PBX", "تتحول المكالمات إلى الهاتف الثاني") else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> t("Шлюз выключен; роль звонилки сохранена", "Gateway off; keeping the default-dialer role", "الجهاز متوقف؛ دور تطبيق الهاتف محفوظ")
                    h.holdsDialer -> t("Шлюз выключен; нужно вернуть звонилку", "Gateway off; dialer restoration needs attention", "الجهاز متوقف؛ يجب استعادة تطبيق الهاتف")
                    else -> t("Шлюз выключен; звонки у вашей звонилки", "Gateway off; calls stay with your dialer", "الجهاز متوقف؛ المكالمات تبقى في تطبيق الهاتف")
                }
            }
            if (h.effectiveCallAccess == CallAccess.DIALER) {
                Text(t("Включение запросит роль звонилки по умолчанию. Выключение вернёт прежнюю, если не включено сохранение роли в Advanced.",
                        "Turning on requests the default-dialer role. Turning off restores your previous dialer unless you keep the role in Advanced settings."),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (h.settingsPending) Text(t("Настройки применятся после завершения звонков", "Settings will apply after current calls end"))
        }
        Section(t("Состояние", "Health", "الحالة")) {
            val p = h.privileged
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED, "${h.shizuku} ${h.shizukuDetail}")
            Check(t("Доступ к звонкам", "Call access", "الوصول إلى المكالمات"), h.callAccessReady,
                if (h.effectiveCallAccess == CallAccess.DIALER) t("Звонилка по умолчанию", "Default dialer", "تطبيق الهاتف الافتراضي") else t("Рядом со звонилкой", "Alongside your dialer", "بجانب تطبيق الهاتف"))
            Check(t("Доступ к звуку вызова", "Call audio access", "الوصول إلى صوت المكالمة"), h.audioReady)
            Check(t("Исключён из экономии батареи", "Doze allowlisted", "مستثنى من توفير البطارية"), p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check(t("Регистрация SIP", "SIP registration", "تسجيل SIP"), h.sip.ready, h.sip.detail)
            Text(h.readinessDetail, style = MaterialTheme.typography.bodySmall)
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                Text(t("Режим звонилки по умолчанию мостит звонки. Для ручного набора верни обычную звонилку.",
                    "Default-dialer mode bridges calls through the PBX. For manual calling, switch back to your usual dialer."))
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text(t("Включить перехват", "Enable takeover")) }
                }
                if (!h.dialerRoleAvailable) Text(t("Выбор звонилки по умолчанию недоступен на этом устройстве.", "Default dialer selection is unavailable on this device."))
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.dialerChangeFailed || (h.holdsDialer && !cur.takeover && !cur.keepDefaultDialer)) {
                if (h.holdsDialer) OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text(t("Отменить смену звонилки", "Cancel dialer change")) }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }) { Text(t("Выдать Shizuku", "Grant Shizuku")) }
                }
                OutlinedButton(onClick = { rt.runSetup() }) { Text(t("Перенастроить", "Re-run setup")) }
                OutlinedButton(onClick = { rt.restartSip() }) { Text(t("Перерегистрация", "Re-register")) }
            }
            if (p?.get(HealthKeys.DOZE_ALLOWLISTED) != true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { requestBatteryOptOut(context) }) {
                        Text(t("Выключить экономию батареи", "Exclude from battery optimization", "إيقاف توفير البطارية"))
                    }
                    InfoButton("background")
                }
                Text(t("Шлюз должен работать с закрытым приложением — без этого Android/Infinix его «убьёт». Подробнее в ⓘ.",
                    "The gateway must keep running with the app closed — without this, Android/Infinix will kill it. Details in ⓘ.",
                    "يجب أن يعمل الجهاز والتطبيق مغلق — بدون هذا سيوقفه Android/Infinix. التفاصيل في ⓘ."),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Section(t("Звонок", "Call", "المكالمة")) {
            Text(h.calls.summary)
            Text(h.session ?: t("Нет активной сессии", "No active session", "لا توجد مكالمة نشطة"))
        }
        SettingsSection(rt)
        SimSection(rt)
        EmailSection(rt)
        RemoteAccessSection(rt)
        CallLogSection(rt)
        Section(t("Журнал", "Log", "السجل")) {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
        var advanced by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) t("Скрыть доп. настройки", "Hide advanced settings", "إخفاء الإعدادات المتقدمة") else t("Дополнительные настройки", "Advanced settings", "إعدادات متقدمة")) }
        if (advanced) {
            Section(t("Интеграция с системой", "Platform integration")) {
                Choice(t("Доступ к звонкам", "Call access"), listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), cur.callAccess,
                    { when (it) { CallAccess.AUTO -> t("Авто", "Automatic"); CallAccess.COMPANION -> t("Рядом", "Alongside dialer"); else -> t("Звонилка", "Default dialer") } },
                    info = "call_access") {
                    rt.applySettings(cur.copy(callAccess = it))
                }
                Toggle(t("Сохранять роль звонилки при выключении", "Keep default-dialer role when gateway is off"), cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, info = "keep_dialer") {
                    t("Применится при следующем выключении перехвата. Прежнюю звонилку можно вернуть кнопкой ниже.",
                        "Applies the next time takeover is switched off. Restore manually below when needed.")
                }
                if (h.holdsDialer) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(t("Вернуть прежнюю звонилку", "Restore previous dialer")) }
                } else if (h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = !h.dialerChangeRunning) { Text(t("Стать звонилкой по умолчанию", "Become default dialer")) }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(t("Выбрать звонилку в настройках", "Choose dialer in Settings")) }
                Choice(t("Способ захвата звука", "Cellular audio"), listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), cur.audioMethod,
                    { when (it) { AudioMethod.AUTO -> t("Авто", "Automatic"); AudioMethod.LEGACY -> t("Прямой тракт", "Direct telephony"); else -> t("Переадресация", "Call redirection") } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }, info = "audio_method") {
                    rt.applySettings(cur.copy(audioMethod = it))
                }
                Text(t("★ Если звонок соединился, но никого не слышно — переключай именно этот параметр (подробно в ⓘ).",
                        "★ If a call connects but nobody is heard, switch this very setting (details in ⓘ)."),
                    style = MaterialTheme.typography.bodySmall)
                Toggle(t("Глушить динамик шлюза", "Mute local call output"), cur.muteEarpiece, { rt.setMuteEarpiece(it) }, info = "mute_earpiece") {
                    val backendAvailable = when (cur.audioMethod) {
                        AudioMethod.LEGACY -> h.privileged?.get(HealthKeys.DIRECT_AUDIO) == true
                        AudioMethod.OFFICIAL -> h.privileged?.get(HealthKeys.REDIRECT_AUDIO) == true
                        else -> true
                    }
                    if (!cur.muteEarpiece)
                        t("Оставляет звук вызова в динамике; микрофон глушится. С следующего звонка.",
                            "Keeps normal call output for local listening; microphone stays muted. Applies from next call.")
                    else if (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) == true && backendAvailable)
                        t("Глушит звук вызова, если поддерживается; с следующего звонка", "Mutes call output when supported; applies from next call")
                    else t("Недоступно с текущей платформой или методом звука", "Unavailable with the current platform or audio method")
                }
                Toggle(t("Эхо-тест (зацикливание входящих)", "Loopback test (echo inbound calls)"), h.loopbackTest, { rt.setLoopbackTest(it) }, info = "loopback") { t("Возвращает входящие эхом вместо соединения с софтфоном", "Echoes incoming calls instead of connecting the PBX") }
                Toggle(t("Подробный журнал", "Debug log"), cur.sipDebugLog, { rt.setSipDebugLog(it) }, info = "debug_log") { t("Подробные логи, номера маскируются", "Info-level logs with masked numbers") }
            }
            Section(t("Диагностика", "Diagnostics")) {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: t("Ожидание помощника", "Waiting for helper"))
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text(t("Счётчики звука не доказывают доставку. Проверяй обе стороны на слух.",
                    "Audio access and frame counters do not verify remote delivery. Verify both directions on this device."))
            }
        }
        FaqSection()
        DonateFooter()
    }
}

/**
 * Optional, voluntary donation link (DonationAlerts). The app stays free (GPL-2.0-or-later);
 * this changes nothing.
 */
private const val DONATE_DA_URL = "https://www.donationalerts.com/r/hinducoder"

@Composable
private fun DonateFooter() {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            t("♡ Поддержать разработку (добровольно)", "♡ Support development (optional)"),
            modifier = Modifier.clickable { runCatching { uri.openUri(DONATE_DA_URL) } },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
private fun FaqSection() {
    var open by remember { mutableStateOf(false) }
    Section(t("Справка и частые вопросы", "Help & FAQ", "المساعدة والأسئلة الشائعة")) {
        OutlinedButton(onClick = { open = !open }) {
            Text(if (open) t("Скрыть", "Hide", "إخفاء") else t("Показать справку / рекомендации клиентов", "Show help / recommended clients", "إظهار المساعدة / التطبيقات المقترحة"))
        }
        if (open) {
            val lang = currentUiLang
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                faqEntries(lang).forEach { (q, a) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(q, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(a, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime) {
    // Re-seed the form whenever settings change elsewhere (e.g. adb provisioning).
    val cur by rt.settingsFlow.collectAsState()
    var serverMode by remember(cur) { mutableStateOf(cur.serverMode) }
    var localPort by remember(cur) { mutableStateOf(cur.localSipPort.toString()) }
    var server by remember(cur) { mutableStateOf(cur.server) }
    var port by remember(cur) { mutableStateOf(cur.port.toString()) }
    var user by remember(cur) { mutableStateOf(cur.username) }
    var password by remember(cur) { mutableStateOf(cur.password) }
    var target by remember(cur) { mutableStateOf(cur.inboundTarget) }
    var prefixes by remember(cur) { mutableStateOf(cur.allowedPrefixes) }
    var domain by remember(cur) { mutableStateOf(cur.domain) }
    var stack by remember(cur) { mutableStateOf(cur.sipStack) }
    var transport by remember(cur) { mutableStateOf(SipTransport.of(cur.sipTransport) ?: SipTransport.UDP) }
    var srtp by remember(cur) { mutableStateOf(SrtpPolicy.of(cur.srtp) ?: SrtpPolicy.OPTIONAL) }
    var busy by remember(cur) { mutableStateOf(cur.busyPolicy) }
    var lease by remember(cur) { mutableStateOf(cur.registrarLeaseSec.toString()) }
    var regPassword by remember(cur) { mutableStateOf(cur.registrarPassword) }
    val builtin = stack == SipStack.BUILTIN
    Section(t("Настройки", "Settings", "الإعدادات")) {
        // Core mode: this phone is its own SIP server, or it registers to an external PBX.
        Toggle(t("Режим «устройство в себе» (встроенный SIP-сервер)", "Device-in-itself mode (built-in SIP server)", "وضع «الجهاز في نفسه» (خادم SIP مدمج)"), serverMode, { serverMode = it }, info = "server_mode") {
            if (serverMode) t("Этот телефон сам — SIP-сервер; софтфон (baresip) регистрируется прямо на него. Внешний сервер не нужен, поля PBX ниже необязательны.",
                "This phone hosts the SIP registrar; your softphone (e.g. baresip) registers straight to it. No external PBX is needed, so the PBX fields below are optional.")
            else t("Выкл: шлюз регистрируется на внешнюю SIP-АТС. Заполни PBX host, user и password ниже.",
                "Off: this gateway registers to an external PBX / SIP server. Fill in PBX host, user and password below.")
        }
        Field(t("Порт регистратора", "Local SIP port (registrar listens here)"), localPort, KeyboardType.Number, info = "local_port") { localPort = it }
        if (serverMode) Text(t("Укажи в софтфоне  <IP этого телефона>:$localPort  по UDP.", "Point your softphone at  <this phone's IP>:$localPort  over UDP."),
            style = MaterialTheme.typography.bodySmall)
        Field(t("Аренда регистрации (сек)", "Registration lease (sec)"), lease, KeyboardType.Number, info = "lease") { lease = it }
        if (serverMode) {
            OutlinedTextField(
                value = regPassword, onValueChange = { regPassword = it },
                label = { Text(t("Пароль для софтфона (защита регистратора)", "Softphone password (registrar auth)")) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(t("Пусто = без пароля, регистрироваться может кто угодно. Задай пароль перед выходом в интернет и введи тот же пароль в софтфоне.",
                "Empty = no password, anyone may register. Set one before exposing the gateway to the internet, and enter the same password in your softphone."),
                style = MaterialTheme.typography.bodySmall)
        }
        Choice(t("SIP-стек", "SIP stack"), SipStack.entries, stack, { it.id }, info = "sip_stack") {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) transport = SipTransport.UDP
        }
        Choice(t("Транспорт", "Transport"), SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }, info = "transport") {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice(t("SRTP (только TLS)", "SRTP (TLS only)"), SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }, info = "srtp") {
            srtp = it
        }
        LabelWithInfo(if (serverMode) t("Внешняя АТС (необязательно в режиме «устройство в себе»)", "External PBX (optional in device-in-itself mode)") else t("Внешняя АТС", "External PBX"),
            "pbx", style = MaterialTheme.typography.bodySmall)
        Field(t("Адрес АТС (PBX host)", "PBX host"), server) { server = it }
        Field(t("Порт АТС", "PBX port"), port, KeyboardType.Number) { port = it }
        Field(t("SIP-домен (пусто = адрес АТС)", "SIP domain (empty = PBX host)"), domain) { domain = it }
        Field(t("Логин / extension", "Extension / user"), user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text(t("Пароль", "Password")) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field(t("Куда переводить входящие (пусто = логин)", "Cellular inbound -> SIP target (blank = username)"), target, info = "inbound_target") { target = it }
        Field(t("Префиксы для исходящих (через запятую, пусто = любой)", "Allowed dial-out prefixes (comma separated, empty = any)"), prefixes, info = "prefixes") { prefixes = it }
        Choice(t("Второй звонок при занятости", "Second call while busy"), BusyPolicy.entries, busy,
            { when (it) { BusyPolicy.REJECT -> t("Отклонять", "Reject"); BusyPolicy.IGNORE -> t("Отдать звонилке", "Leave to phone") } }, info = "busy") {
            busy = it
        }
        Button(onClick = {
            rt.applySettings(
                cur.copy(
                    serverMode = serverMode,
                    localSipPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: cur.localSipPort,
                    registrarLeaseSec = lease.toIntOrNull()?.takeIf { it in 30..86400 } ?: cur.registrarLeaseSec,
                    registrarPassword = regPassword,
                    server = server, port = port.toIntOrNull() ?: transport.defaultPort, username = user, password = password,
                    inboundTarget = target, allowedPrefixes = prefixes, domain = domain,
                    sipStack = stack, sipTransport = transport.id, srtp = srtp.id, busyPolicy = busy,
                ),
            )
        }) { Text(t("Сохранить и применить", "Save & apply")) }
    }
}

/** Asks Android to exclude the app from battery optimization (Doze). */
private fun requestBatteryOptOut(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        })
    }.onFailure { AppLog.w("could not open battery-optimization request", it) }
}

/** Per-SIM incoming takeover + the default SIM for outgoing calls (feature: dual-SIM). */
@Composable
private fun SimSection(rt: GatewayRuntime) {
    val cur by rt.settingsFlow.collectAsState()
    var slots by remember { mutableStateOf(rt.cellular.sims.active()) }
    Section(t("SIM-карты", "SIM cards", "شرائح SIM"), info = "sim") {
        if (slots.isEmpty()) {
            Text(t("SIM не найдены — проверь разрешение на чтение состояния телефона.",
                "No SIMs found — check the phone-state permission.",
                "لم يتم العثور على شريحة — تحقق من إذن قراءة حالة الهاتف."),
                style = MaterialTheme.typography.bodySmall)
        }
        slots.forEach { sim ->
            val takeOver = sim.slot !in cur.inboundExcludedSlots
            val carrier = sim.carrier.ifBlank { "SIM ${sim.slot + 1}" }
            Toggle(
                t("SIM ${sim.slot + 1} ($carrier): перехватывать входящие",
                    "SIM ${sim.slot + 1} ($carrier): take over incoming",
                    "الشريحة ${sim.slot + 1} ($carrier): تحويل الوارد"),
                takeOver, { rt.setSimInbound(sim.slot, it) },
            ) {
                if (takeOver) t("Входящие с этой SIM идут на софтфон",
                    "Incoming calls on this SIM go to the softphone",
                    "المكالمات الواردة على هذه الشريحة تتحول إلى الهاتف الثاني")
                else t("Входящие с этой SIM остаются в обычной звонилке",
                    "Incoming calls on this SIM stay with the phone app",
                    "المكالمات الواردة على هذه الشريحة تبقى في تطبيق الهاتف")
            }
        }
        val options = listOf(DefaultSim.SYSTEM) + slots.map { it.slot } + listOf(DefaultSim.REFUSE)
        Choice(t("SIM для исходящих без выбора", "Default SIM for outgoing calls", "الشريحة الافتراضية للمكالمات الصادرة"),
            options, cur.defaultSimSlot,
            { when (it) {
                DefaultSim.SYSTEM -> t("Как в системе", "Android default", "افتراضي النظام")
                DefaultSim.REFUSE -> t("Запретить", "Refuse", "رفض")
                else -> "SIM ${it + 1}"
            } }) {
            rt.setDefaultSimSlot(it)
        }
        OutlinedButton(onClick = { slots = rt.cellular.sims.active() }) {
            Text(t("Обновить список SIM", "Refresh SIM list", "تحديث قائمة الشرائح"))
        }
    }
}

/** Optional email notifications: missed calls, SMS/OTP, IP changes (features #5, #6a, #6c). */
@Composable
private fun EmailSection(rt: GatewayRuntime) {
    val cur by rt.settingsFlow.collectAsState()
    var host by remember(cur) { mutableStateOf(cur.smtpHost) }
    var port by remember(cur) { mutableStateOf(cur.smtpPort.toString()) }
    var security by remember(cur) { mutableStateOf(cur.smtpSecurity) }
    var user by remember(cur) { mutableStateOf(cur.smtpUser) }
    var password by remember(cur) { mutableStateOf(cur.smtpPassword) }
    var recipient by remember(cur) { mutableStateOf(cur.emailRecipient) }
    val testResult by rt.emailTestResult.collectAsState()
    Section(t("Уведомления по email", "Email notifications", "الإشعارات بالبريد"), info = "email") {
        Toggle(t("Уведомления по email", "Email notifications", "إشعارات البريد الإلكتروني"),
            cur.emailNotifyEnabled, { rt.applySettings(cur.copy(emailNotifyEnabled = it)) }) {
            t("Пропущенные звонки, SMS и смена IP — на твою почту. Выкл по умолчанию.",
                "Missed calls, SMS and IP changes to your inbox. Off by default.",
                "المكالمات الفائتة والرسائل وتغيير IP إلى بريدك. متوقفة افتراضيًا.")
        }
        if (cur.emailNotifyEnabled) {
            Toggle(t("Пропущенные звонки", "Missed calls", "المكالمات الفائتة"),
                cur.emailMissedCalls, { rt.applySettings(cur.copy(emailMissedCalls = it)) }) {
                t("Одно письмо на пропущенный звонок: номер, время, SIM.",
                    "One email per missed call: number, time, SIM.",
                    "رسالة واحدة لكل مكالمة فائتة: الرقم والوقت والشريحة.")
            }
            Toggle(t("SMS (в т.ч. коды OTP)", "SMS (incl. OTP codes)", "الرسائل (بما فيها رموز OTP)"),
                cur.emailSms, { rt.applySettings(cur.copy(emailSms = it)) }) {
                t("Каждое входящее SMS — письмом на твою почту.",
                    "Every incoming SMS as an email to your inbox.",
                    "كل رسالة واردة تُرسل بالبريد إلى بريدك.")
            }
            Toggle(t("Смена IP шлюза", "Gateway IP changes", "تغيير IP الجهاز"),
                cur.emailIpChange, { rt.applySettings(cur.copy(emailIpChange = it)) }) {
                t("Письмо с новым адресом, когда мобильный IP меняется.",
                    "An email with the new address when the mobile IP changes.",
                    "رسالة بالعنوان الجديد عند تغيير IP بيانات الهاتف.")
            }
            Field(t("SMTP host", "SMTP host", "خادم SMTP"), host) { host = it }
            Field(t("SMTP порт", "SMTP port", "منفذ SMTP"), port, KeyboardType.Number) { port = it }
            Choice(t("Защита", "Security", "الحماية"), listOf("starttls", "ssl"), security,
                { if (it == "ssl") "SSL (465)" else "STARTTLS (587)" }) {
                security = it
                // Follow the matching default port when it was still the other one's default.
                if (it == "ssl" && port == "587") port = "465"
                if (it == "starttls" && port == "465") port = "587"
            }
            Field(t("SMTP user (твой Gmail)", "SMTP user (your Gmail)", "مستخدم SMTP (بريد Gmail)"), user) { user = it }
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(t("Пароль приложения Google", "Google app password", "كلمة مرور تطبيق Google")) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(t("НЕ пароль от Gmail, а отдельный 16-значный «пароль приложения» (см. ⓘ выше).",
                "NOT your Gmail password — a separate 16-character app password (see ⓘ above).",
                "ليست كلمة مرور Gmail، بل «كلمة مرور تطبيق» منفصلة من 16 حرفًا (انظر ⓘ أعلاه)."),
                style = MaterialTheme.typography.bodySmall)
            Field(t("Куда слать (пусто = себе)", "Recipient (empty = yourself)", "المستلم (فارغ = لنفسك)"), recipient) { recipient = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    rt.applySettings(cur.copy(
                        smtpHost = host, smtpPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: cur.smtpPort,
                        smtpSecurity = security, smtpUser = user, smtpPassword = password, emailRecipient = recipient,
                    ))
                }) { Text(t("Сохранить", "Save", "حفظ")) }
                OutlinedButton(onClick = { rt.sendTestEmail() }) {
                    Text(t("Тестовое письмо", "Send test email", "إرسال بريد تجريبي"))
                }
            }
            if (testResult != null) {
                Text(
                    if (testResult == "ok") t("Тестовое письмо отправлено — проверь почту.",
                        "Test email sent — check your inbox.",
                        "تم إرسال البريد التجريبي — تحقق من بريدك.")
                    else t("Ошибка: $testResult", "Failed: $testResult", "فشل: $testResult"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Every usable address of the gateway phone, with copy buttons (feature: remote access). */
@Composable
private fun RemoteAccessSection(rt: GatewayRuntime) {
    val addrs by rt.localAddresses.collectAsState()
    val clipboard = LocalClipboardManager.current
    Section(t("Удалённый доступ", "Remote access", "الوصول عن بُعد"), info = "remote_access") {
        if (addrs.isEmpty()) {
            Text(t("Адресов не найдено — проверь мобильные данные.",
                "No addresses found — check mobile data.",
                "لم يتم العثور على عناوين — تحقق من بيانات الهاتف."),
                style = MaterialTheme.typography.bodySmall)
        } else {
            if (LocalAddresses.vpnConnected(addrs)) {
                Text(t("VPN подключён — вдали используй VPN-адрес ниже.",
                    "VPN connected — use the VPN address below when far away.",
                    "VPN متصل — استخدم عنوان VPN أدناه عند البُعد."),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text(t("VPN не подключён — вдали шлюз недоступен без Tailscale/ZeroTier (или Zadarma).",
                    "No VPN connected — far away the gateway is unreachable without Tailscale/ZeroTier (or Zadarma).",
                    "لا يوجد VPN متصل — بعيدًا لا يمكن الوصول إلى الجهاز بدون Tailscale/ZeroTier (أو Zadarma)."),
                    style = MaterialTheme.typography.bodySmall)
            }
            addrs.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label)
                        Text(a.address, fontFamily = FontFamily.Monospace)
                        Text(when (a.kind) {
                            AddressKind.MOBILE -> t("Меняется при переподключении данных; рядом не нужен.",
                                "Changes on reconnect; not needed nearby.",
                                "يتغير عند إعادة الاتصال؛ غير مطلوب بالقرب.")
                            AddressKind.WIFI -> t("Рядом: введи этот адрес в Linphone, когда iPhone на хот-споте.",
                                "Nearby: enter this in Linphone when the iPhone is on the hotspot.",
                                "بالقرب: أدخل هذا العنوان في Linphone عندما يكون الآيفون على نقطة الاتصال.")
                            AddressKind.VPN_TAILSCALE, AddressKind.VPN_ZEROTIER -> t("Вдали: введи этот адрес в Linphone (VPN — на обоих телефонах).",
                                "Far away: enter this in Linphone (VPN on both phones).",
                                "بعيدًا: أدخل هذا العنوان في Linphone (VPN على الهاتفين).")
                            AddressKind.OTHER -> t("Введи этот адрес в Linphone.",
                                "Enter this address in Linphone.",
                                "أدخل هذا العنوان في Linphone.")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(a.address)) }) {
                        Text(t("Копировать", "Copy", "نسخ"))
                    }
                }
            }
        }
        OutlinedButton(onClick = { rt.refreshLocalAddresses() }) {
            Text(t("Обновить адреса", "Refresh addresses", "تحديث العناوين"))
        }
    }
}

/** In-app call log: incoming, outgoing, missed (feature: call log). */
@Composable
private fun CallLogSection(rt: GatewayRuntime) {
    val log by rt.callLog.collectAsState()
    val timeFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Section(t("Журнал звонков", "Call log", "سجل المكالمات"), info = "call_log") {
        if (log.isEmpty()) {
            Text(t("Пока пусто", "No calls yet", "لا توجد مكالمات بعد"),
                style = MaterialTheme.typography.bodySmall)
        } else {
            log.take(30).forEach { e ->
                Column {
                    Text(
                        "${when (e.direction) {
                            CallDirection.INCOMING -> t("Входящий", "Incoming", "وارد")
                            CallDirection.OUTGOING -> t("Исходящий", "Outgoing", "صادر")
                        }} · ${when (e.outcome) {
                            CallOutcome.ANSWERED -> t("отвечен", "answered", "تم الرد")
                            CallOutcome.MISSED -> t("пропущен", "missed", "فائت")
                            CallOutcome.NO_ANSWER -> t("без ответа", "no answer", "بلا رد")
                        }} — ${e.number ?: t("неизвестный номер", "unknown number", "رقم مجهول")}",
                    )
                    Text(
                        "${timeFormat.format(Date(e.timeMs))} · SIM ${e.slot?.plus(1) ?: "?"}${e.durationSec?.let { " · ${it}s" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    info: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelWithInfo(label, info, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, info: String? = null, detail: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LabelWithInfo(label, info)
            Text(detail(), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, info: String? = null, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(info)
    }
}

@Composable
internal fun Section(title: String, info: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelWithInfo(title, info, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
