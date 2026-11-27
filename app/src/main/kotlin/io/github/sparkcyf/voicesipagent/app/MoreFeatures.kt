package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The side menu (navigation drawer): all 12 new features live here, each with its own screen.
 * The main screen stays exactly as it is — nothing from this file appears on it.
 *
 * Command protocol (also in HANDOFF.md): from Linphone, send a chat message to the gateway:
 *   SMS <number> <text>   USSD [code]   RESTART   FIND [STOP]
 * Commands are accepted only from the registered phone (digest auth + registered-sender check
 * in SipUserAgent); the toggles below only enable/disable each feature.
 */
enum class MoreFeature {
    SMS, USSD, LOW_BATTERY, BLOCKLIST, NIGHT_MODE, CALLER_NAME,
    AUTO_REPLY, REMOTE_RESTART, LOW_BALANCE, HOTSPOT, FIND_PHONE, BLOCK_HIDDEN,
}

fun moreFeatureTitle(f: MoreFeature): String = when (f) {
    MoreFeature.SMS -> t("SMS с iPhone", "SMS from iPhone", "إرسال رسائل من الآيفون")
    MoreFeature.USSD -> t("USSD: баланс", "USSD balance check", "فحص الرصيد USSD")
    MoreFeature.LOW_BATTERY -> t("Низкий заряд", "Low-battery alert", "تنبيه البطارية المنخفضة")
    MoreFeature.BLOCKLIST -> t("Чёрный список", "Blocklist", "قائمة الحظر")
    MoreFeature.NIGHT_MODE -> t("Ночной режим", "Night mode", "الوضع الليلي")
    MoreFeature.CALLER_NAME -> t("Имя звонящего", "Caller name", "اسم المتصل")
    MoreFeature.AUTO_REPLY -> t("Автоответ SMS", "Auto-reply SMS", "الرد التلقائي")
    MoreFeature.REMOTE_RESTART -> t("Перезапуск удалённо", "Remote restart", "إعادة التشغيل عن بعد")
    MoreFeature.LOW_BALANCE -> t("Низкий баланс", "Low-balance alert", "تنبيه الرصيد المنخفض")
    MoreFeature.HOTSPOT -> t("Перезапуск точки доступа", "Hotspot auto-restart", "إعادة تشغيل نقطة الاتصال")
    MoreFeature.FIND_PHONE -> t("Найти Infinix", "Find my Infinix", "أين جهاز Infinix")
    MoreFeature.BLOCK_HIDDEN -> t("Скрытые номера", "Block hidden numbers", "حظر الأرقام المخفية")
}

@Composable
fun MoreFeatureScreen(feature: MoreFeature, rt: GatewayRuntime, modifier: Modifier,
    onBack: () -> Unit, onRequestPermissions: () -> Unit) {
    val cur by rt.settingsFlow.collectAsState()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.titleLarge)
            }
            Text(moreFeatureTitle(feature), style = MaterialTheme.typography.headlineSmall)
        }
        when (feature) {
            MoreFeature.SMS -> SmsFeature(rt, cur, onRequestPermissions)
            MoreFeature.USSD -> UssdFeature(rt, cur)
            MoreFeature.LOW_BATTERY -> LowBatteryFeature(rt, cur)
            MoreFeature.BLOCKLIST -> BlocklistFeature(rt, cur)
            MoreFeature.NIGHT_MODE -> NightModeFeature(rt, cur)
            MoreFeature.CALLER_NAME -> CallerNameFeature(rt, cur, onRequestPermissions)
            MoreFeature.AUTO_REPLY -> AutoReplyFeature(rt, cur, onRequestPermissions)
            MoreFeature.REMOTE_RESTART -> RemoteRestartFeature(rt, cur)
            MoreFeature.LOW_BALANCE -> LowBalanceFeature(rt, cur)
            MoreFeature.HOTSPOT -> HotspotFeature(rt, cur)
            MoreFeature.FIND_PHONE -> FindPhoneFeature(rt, cur)
            MoreFeature.BLOCK_HIDDEN -> BlockHiddenFeature(rt, cur)
        }
    }
}

@Composable
private fun PermissionButton(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        OutlinedButton(onClick = onRequestPermissions) {
            Text(t("Разрешить SMS и контакты", "Grant SMS and contacts permission", "السماح بالرسائل وجهات الاتصال"))
        }
    }
}

@Composable
private fun SmsFeature(rt: GatewayRuntime, cur: GatewaySettings, onRequestPermissions: () -> Unit) {
    Section(moreFeatureTitle(MoreFeature.SMS)) {
        Toggle(t("Команды SMS с iPhone", "SMS commands from iPhone", "أوامر الرسائل من الآيفون"),
            cur.smsCommandsEnabled, { rt.applySettings(cur.copy(smsCommandsEnabled = it)) }) {
            t("В Linphone отправь сообщение шлюзу: SMS <номер> <текст>. Шлюз отправит SMS через SIM (по умолчанию). Ответ «отправлено» значит, что радиомодуль принял сообщение, а не что оно доставлено.",
                "In Linphone, message the gateway: SMS <number> <text>. The gateway sends the SMS via the SIM (default SIM). A \"sent\" reply means the radio accepted it, not that it was delivered.",
                "في Linphone أرسل رسالة للجهاز: SMS <رقم> <نص>. يرسل الجهاز الرسالة عبر الشريحة (الافتراضية). رد «تم الإرسال» يعني أن الراديو قبلها، لا أنها وصلت.")
        }
        PermissionButton(onRequestPermissions)
    }
}

@Composable
private fun UssdFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    var code by remember(cur) { mutableStateOf(cur.ussdDefaultCode) }
    Section(moreFeatureTitle(MoreFeature.USSD)) {
        Toggle(t("Команды USSD с iPhone", "USSD commands from iPhone", "أوامر USSD من الآيفون"),
            cur.ussdCommandsEnabled, { rt.applySettings(cur.copy(ussdCommandsEnabled = it)) }) {
            t("В Linphone отправь: USSD [код]. Без кода — код по умолчанию ниже. Ответ с результатом приходит сообщением. USSD требует роль звонилки по умолчанию (или CALL_PHONE), иначе придёт ошибка.",
                "In Linphone, send: USSD [code]. No code = the default below. The result comes back as a message. USSD needs the default-dialer role (or CALL_PHONE), otherwise you get an error.",
                "في Linphone أرسل: USSD [رمز]. بدون رمز يُستخدم الافتراضي أدناه. تأتي النتيجة برسالة. يحتاج USSD دور تطبيق الهاتف الافتراضي، وإلا تأتي رسالة خطأ.")
        }
        Field(t("Код по умолчанию", "Default code", "الرمز الافتراضي"), code) { code = it }
        Button(onClick = { rt.applySettings(cur.copy(ussdDefaultCode = code.ifBlank { "*124#" })) }) {
            Text(t("Сохранить", "Save", "حفظ"))
        }
    }
}

@Composable
private fun LowBatteryFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    Section(moreFeatureTitle(MoreFeature.LOW_BATTERY)) {
        Toggle(t("Письмо при низком заряде", "Email on low battery", "بريد عند انخفاض البطارية"),
            cur.lowBatteryAlertEnabled, { rt.applySettings(cur.copy(lowBatteryAlertEnabled = it)) }) {
            t("Одно письмо «[VSA Gateway] Low battery: <pct>%», когда заряд падает ниже 20%. Повторно — только после зарядки выше 25% или подключения зарядника. Нужна настроенная почта (раздел Email).",
                "One email \"[VSA Gateway] Low battery: <pct>%\" when the battery drops below 20%. Again only after charging above 25% or plugging in. Needs email configured (Email section).",
                "رسالة واحدة «[VSA Gateway] Low battery: <pct>%» عند نزول البطارية تحت 20%. تتكرر فقط بعد الشحن فوق 25% أو توصيل الشاحن. تحتاج بريدًا مضبوطًا (قسم البريد).")
        }
    }
}

@Composable
private fun BlocklistFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    var text by remember(cur) { mutableStateOf(cur.blockedNumbers.joinToString(", ")) }
    Section(moreFeatureTitle(MoreFeature.BLOCKLIST)) {
        Text(t("Звонки с этих номеров не звонят на iPhone: шлюз отклоняет их, записывает как пропущенные и шлёт письмо (если включено). Номера через запятую, например: +21629154710, 97771750.",
            "Calls from these numbers never ring the iPhone: the gateway rejects them, logs them as missed and emails them (if enabled). Comma-separated, e.g.: +21629154710, 97771750.",
            "المكالمات من هذه الأرقام لا ترن على الآيفون: يرفضها الجهاز ويسجلها فائتة ويرسل بريدًا (إن كان مفعّلًا). أرقام مفصولة بفاصلة، مثال: +21629154710, 97771750."),
            style = MaterialTheme.typography.bodySmall)
        Field(t("Номера через запятую", "Numbers, comma-separated", "الأرقام مفصولة بفاصلة"), text) { text = it }
        Button(onClick = {
            rt.applySettings(cur.copy(blockedNumbers = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
        }) { Text(t("Сохранить", "Save", "حفظ")) }
    }
}

@Composable
private fun NightModeFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    var start by remember(cur) { mutableStateOf(formatTimeMin(cur.nightStartMin)) }
    var end by remember(cur) { mutableStateOf(formatTimeMin(cur.nightEndMin)) }
    Section(moreFeatureTitle(MoreFeature.NIGHT_MODE)) {
        Toggle(t("Ночной режим", "Night mode", "الوضع الليلي"),
            cur.nightModeEnabled, { rt.applySettings(cur.copy(nightModeEnabled = it)) }) {
            t("В это окно входящие не звонят на iPhone: записываются как пропущенные и шлют письмо (если включено). Вне окна — как обычно. Формат времени ЧЧ:ММ, окно может переходить через полночь (22:00–07:00).",
                "During this window incoming calls do not ring the iPhone: they are logged as missed and emailed (if enabled). Outside the window — normal. Time format HH:MM; the window may cross midnight (22:00–07:00).",
                "في هذه الفترة لا ترن المكالمات الواردة على الآيفون: تُسجل فائتة وتُرسل بالبريد (إن كان مفعّلًا). خارج الفترة — عادي. صيغة الوقت HH:MM، ويمكن أن تعبر منتصف الليل (22:00–07:00).")
        }
        Field(t("Начало (ЧЧ:ММ)", "Start (HH:MM)", "البداية (HH:MM)"), start, KeyboardType.Number) { start = it }
        Field(t("Конец (ЧЧ:ММ)", "End (HH:MM)", "النهاية (HH:MM)"), end, KeyboardType.Number) { end = it }
        Button(onClick = {
            val s = parseTimeMin(start)
            val e = parseTimeMin(end)
            if (s != null && e != null) rt.applySettings(cur.copy(nightStartMin = s, nightEndMin = e))
        }) { Text(t("Сохранить", "Save", "حفظ")) }
    }
}

@Composable
private fun CallerNameFeature(rt: GatewayRuntime, cur: GatewaySettings, onRequestPermissions: () -> Unit) {
    Section(moreFeatureTitle(MoreFeature.CALLER_NAME)) {
        Toggle(t("Имя из контактов Infinix", "Name from Infinix contacts", "الاسم من جهات اتصال Infinix"),
            cur.callerNameEnabled, { rt.applySettings(cur.copy(callerNameEnabled = it)) }) {
            t("Шлюз ищет номер звонящего в контактах этого телефона и показывает имя в Linphone вместо номера. Нет совпадения — показывается номер. Нужно разрешение на контакты.",
                "The gateway looks the caller's number up in this phone's contacts and shows the name in Linphone instead of the number. No match — the number is shown. Needs contacts permission.",
                "يبحث الجهاز عن رقم المتصل في جهات اتصال هذا الهاتف ويعرض الاسم في Linphone بدل الرقم. لا تطابق — يُعرض الرقم. يحتاج إذن جهات الاتصال.")
        }
        PermissionButton(onRequestPermissions)
    }
}

@Composable
private fun AutoReplyFeature(rt: GatewayRuntime, cur: GatewaySettings, onRequestPermissions: () -> Unit) {
    var text by remember(cur) { mutableStateOf(cur.autoReplyText) }
    Section(moreFeatureTitle(MoreFeature.AUTO_REPLY)) {
        Toggle(t("Автоответ на пропущенные", "Auto-reply to missed calls", "الرد التلقائي على الفائتة"),
            cur.autoReplyEnabled, { rt.applySettings(cur.copy(autoReplyEnabled = it)) }) {
            t("Когда звонок пропущен (в т.ч. отклонён чёрным списком или ночным режимом), шлюз сам шлёт звонящему SMS с текстом ниже. Выкл по умолчанию: каждое SMS стоит денег.",
                "When a call is missed (including calls rejected by the blocklist or night mode), the gateway texts the caller the message below. Off by default: every SMS costs credit.",
                "عند فوات مكالمة (بما فيها المرفوضة من الحظر أو الوضع الليلي)، يرسل الجهاز رسالة نصية للمتصل بالنص أدناه. متوقف افتراضيًا: كل رسالة تكلف رصيدًا.")
        }
        Field(t("Текст ответа", "Reply text", "نص الرد"), text) { text = it }
        Button(onClick = { rt.applySettings(cur.copy(autoReplyText = text)) }) {
            Text(t("Сохранить", "Save", "حفظ"))
        }
        PermissionButton(onRequestPermissions)
    }
}

@Composable
private fun RemoteRestartFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    Section(moreFeatureTitle(MoreFeature.REMOTE_RESTART)) {
        Toggle(t("Перезапуск с iPhone", "Remote restart from iPhone", "إعادة التشغيل من الآيفون"),
            cur.remoteRestartEnabled, { rt.applySettings(cur.copy(remoteRestartEnabled = it)) }) {
            t("В Linphone отправь шлюзу: RESTART. Шлюз перезапускает SIP-стек (служба не останавливается полностью). Подтверждение «Gateway restarted.» приходит, когда iPhone перерегистрируется. Во время звонка перезапуск откладывается до его конца.",
                "In Linphone, message the gateway: RESTART. The gateway restarts its SIP stack (the service itself keeps running). Confirmation \"Gateway restarted.\" arrives once the iPhone re-registers. During a call the restart waits until the call ends.",
                "في Linphone أرسل للجهاز: RESTART. يعيد الجهاز تشغيل SIP (الخدمة نفسها تستمر). يأتي تأكيد «Gateway restarted.» عند إعادة تسجيل الآيفون. أثناء مكالمة يتأجل حتى تنتهي.")
        }
    }
}

@Composable
private fun LowBalanceFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    var threshold by remember(cur) { mutableStateOf(cur.lowBalanceThresholdTnd.toString()) }
    Section(moreFeatureTitle(MoreFeature.LOW_BALANCE)) {
        Toggle(t("Письмо при низком балансе", "Email on low balance", "بريد عند انخفاض الرصيد"),
            cur.lowBalanceAlertEnabled, { rt.applySettings(cur.copy(lowBalanceAlertEnabled = it)) }) {
            t("Раз в день шлюз сам проверяет баланс по USSD (код по умолчанию) и шлёт одно письмо «[VSA Gateway] Low balance: <сумма>», если баланс ниже порога. Разбор ответа — best-effort: если сумму распознать не удалось, письмо не шлётся. Нужна настроенная почта.",
                "Once a day the gateway checks the balance via USSD (default code) and sends one email \"[VSA Gateway] Low balance: <amount>\" when the balance is below the threshold. Parsing is best-effort: if the amount cannot be read, no email is sent. Needs email configured.",
                "مرة في اليوم يفحص الجهاز الرصيد عبر USSD (الرمز الافتراضي) ويرسل رسالة واحدة «[VSA Gateway] Low balance: <المبلغ>» إذا كان الرصيد تحت الحد. القراءة تقريبية: إذا تعذر فهم المبلغ لا تُرسل رسالة. يحتاج بريدًا مضبوطًا.")
        }
        Field(t("Порог (TND)", "Threshold (TND)", "الحد (دينار)"), threshold, KeyboardType.Number) { threshold = it }
        Button(onClick = {
            threshold.toDoubleOrNull()?.takeIf { it >= 0 }?.let {
                rt.applySettings(cur.copy(lowBalanceThresholdTnd = it))
            }
        }) { Text(t("Сохранить", "Save", "حفظ")) }
    }
}

@Composable
private fun HotspotFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    Section(moreFeatureTitle(MoreFeature.HOTSPOT)) {
        Toggle(t("Возвращать точку доступа", "Restart hotspot if it turns off", "إعادة تشغيل نقطة الاتصال عند إيقافها"),
            cur.hotspotAutoRestartEnabled, { rt.applySettings(cur.copy(hotspotAutoRestartEnabled = it)) }) {
            t("Если точка доступа Wi-Fi выключится, шлюз включит её обратно сам (через Shizuku). Без Shizuku это сделать нельзя: у Android 13+ нет открытого API для включения точки доступа с сохранёнными настройками. Перезапуск — не чаще раза в минуту.",
                "If the Wi-Fi hotspot switches off, the gateway turns it back on itself (via Shizuku). Without Shizuku this cannot be done: Android 13+ has no public API to start tethering with the saved settings. At most one restart per minute.",
                "إذا أُوقفت نقطة اتصال Wi-Fi، يعيدها الجهاز بنفسه (عبر Shizuku). بدون Shizuku لا يمكن: Android 13+ لا يوفر واجهة عامة لتشغيل نقطة الاتصال بالإعدادات المحفوظة. إعادة تشغيل واحدة في الدقيقة كحد أقصى.")
        }
    }
}

@Composable
private fun FindPhoneFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    Section(moreFeatureTitle(MoreFeature.FIND_PHONE)) {
        Toggle(t("Найти Infinix с iPhone", "Find my Infinix from iPhone", "إيجاد Infinix من الآيفون"),
            cur.findPhoneEnabled, { rt.applySettings(cur.copy(findPhoneEnabled = it)) }) {
            t("В Linphone отправь шлюзу: FIND — Infinix зазвонит на максимальной громкости до 30 секунд. Остановить: FIND STOP. Громкость будильника возвращается как была.",
                "In Linphone, message the gateway: FIND — the Infinix rings at full volume for up to 30 seconds. Stop it with: FIND STOP. The alarm volume is restored afterwards.",
                "في Linphone أرسل للجهاز: FIND — يرن Infinix بأعلى صوت حتى 30 ثانية. للإيقاف: FIND STOP. يعود مستوى صوت المنبه كما كان.")
        }
    }
}

@Composable
private fun BlockHiddenFeature(rt: GatewayRuntime, cur: GatewaySettings) {
    Section(moreFeatureTitle(MoreFeature.BLOCK_HIDDEN)) {
        Toggle(t("Отклонять скрытые номера", "Reject hidden numbers", "رفض الأرقام المخفية"),
            cur.blockHiddenNumbers, { rt.applySettings(cur.copy(blockHiddenNumbers = it)) }) {
            t("Включено по умолчанию. Входящие без определителя номера отклоняются, записываются как пропущенные и шлют письмо (если включено).",
                "On by default. Incoming calls with no caller ID are rejected, logged as missed and emailed (if enabled).",
                "مفعّل افتراضيًا. المكالمات الواردة بدون رقم تُرفض وتُسجل فائتة وتُرسل بالبريد (إن كان مفعّلًا).")
        }
    }
}

private fun parseTimeMin(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private fun formatTimeMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
