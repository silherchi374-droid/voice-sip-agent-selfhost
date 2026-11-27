package io.github.sparkcyf.voicesipagent.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI language. Pure presentation; stored as "ru"/"en"/"ar" in settings. */
enum class UiLang { RU, EN, AR;
    companion object {
        fun of(s: String?) = when (s) {
            "en" -> EN
            "ar" -> AR
            else -> RU
        }
    }
}

/**
 * Current UI language. A plain top-level value (not a CompositionLocal) so that [t] can be called
 * from ordinary lambdas too (e.g. a Choice's option-name lambda or a Toggle's detail lambda), not
 * only from @Composable scope. [Screen] keeps it in sync with the saved setting on every
 * recomposition, and a language change re-emits settings → recomposes → updates this before the
 * subtree reads it.
 */
@Volatile
var currentUiLang: UiLang = UiLang.RU

/**
 * Pick the string for the current UI language. Safe to call anywhere. Arabic falls back to the
 * English text unless an Arabic variant is given.
 */
fun t(ru: String, en: String, ar: String = en): String = when (currentUiLang) {
    UiLang.RU -> ru
    UiLang.EN -> en
    UiLang.AR -> ar
}

/**
 * A small "ⓘ" affordance next to a label. Tapping it opens a dialog that explains, in the current
 * language, what the setting is, why it matters and (where relevant) where to get what it needs.
 * [key] selects the text from [helpText]; unknown keys render nothing.
 */
@Composable
fun InfoButton(key: String) {
    val lang = currentUiLang
    val entry = helpText(key, lang) ?: return
    var open by remember { mutableStateOf(false) }
    Text(
        "  ⓘ",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
        modifier = Modifier.clickable { open = true }.padding(2.dp),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(when (lang) { UiLang.RU -> "Понятно"; UiLang.EN -> "Got it"; UiLang.AR -> "فهمت" }) } },
            title = { Text(entry.first, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.second, fontSize = 14.sp, lineHeight = 20.sp)
                }
            },
        )
    }
}

/** A label line that carries an inline info button. */
@Composable
fun LabelWithInfo(label: String, infoKey: String?, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style)
        if (infoKey != null) InfoButton(infoKey)
    }
}

/**
 * Help / info text per key, returned as (title, body) already localized.
 * Keep bodies self-contained and practical: what it is, why, and where to get anything it needs.
 */
fun helpText(key: String, lang: UiLang): Pair<String, String>? {
    fun p(titleRu: String, titleEn: String, bodyRu: String, bodyEn: String,
          titleAr: String = titleEn, bodyAr: String = bodyEn): Pair<String, String> = when (lang) {
        UiLang.RU -> titleRu to bodyRu
        UiLang.EN -> titleEn to bodyEn
        UiLang.AR -> titleAr to bodyAr
    }
    return when (key) {
        "takeover" -> p(
            "Перехват звонков", "Take over calls",
            "Главный выключатель. Включён — входящие сотовые звонки на SIM этого телефона перехватываются и переводятся на твой софтфон по интернету. Выключен — звонки звонят как обычно на этом телефоне.",
            "Master switch. On: incoming cellular calls on this phone's SIM are intercepted and bridged to your softphone over the internet. Off: calls ring normally on this phone.",
        )
        "server_mode" -> p(
            "Режим «устройство в себе»", "Device-in-itself mode",
            "Включён (рекомендуется): этот телефон сам работает SIP-сервером. Твой софтфон (например baresip) регистрируется прямо на него — внешний сервер/АТС не нужны.\n\nВыключен: шлюз сам подключается к внешней SIP-АТС — тогда заполни поля PBX host / user / password ниже.",
            "On (recommended): this phone is its own SIP server. Your softphone (e.g. baresip) registers straight to it — no external server/PBX needed.\n\nOff: the gateway registers to an external SIP PBX — then fill in PBX host / user / password below.",
        )
        "local_port" -> p(
            "Порт регистратора", "Local SIP port",
            "UDP-порт, на котором этот телефон ждёт регистрацию софтфона. Именно его указываешь в софтфоне как адрес: «<IP телефона>:<порт>», транспорт UDP. По умолчанию 5062.",
            "The UDP port this phone listens on for the softphone's registration. Enter it in the softphone as «<phone IP>:<port>», transport UDP. Default 5062.",
        )
        "lease" -> p(
            "Аренда регистрации", "Registration lease",
            "Сколько секунд держать регистрацию софтфона. Софтфон обновляет её примерно раз в половину этого времени. Больше значение — реже обновления, устойчивее к «засыпанию» софтфона в фоне. Адрес удерживается ещё примерно вдвое дольше, прежде чем шлюз решит, что телефон пропал. По умолчанию 600 с.\n\nЕсли регистрация у тебя «протухает» — увеличь это значение (например, 1200) и убери ограничения батареи у софтфона.",
            "How many seconds to keep the softphone's registration. The softphone refreshes it about every half of this. Larger = fewer refreshes, more tolerant of the softphone sleeping in the background. The contact is held for about twice this before the gateway gives up. Default 600 s.\n\nIf your registration keeps expiring, raise this (e.g. 1200) and lift battery limits on the softphone.",
        )
        "sip_stack" -> p(
            "SIP-стек", "SIP stack",
            "builtin — встроенный лёгкий стек (только UDP и кодек G.711, узкополосный звук), ничего доустанавливать не нужно. pjsua2 — нативный стек (TCP/TLS, кодек Opus), в этой сборке не включён.",
            "builtin — the lightweight built-in stack (UDP only, G.711 codec, narrowband), nothing to install. pjsua2 — native stack (TCP/TLS, Opus), not enabled in this build.",
        )
        "transport" -> p(
            "Транспорт", "Transport",
            "Протокол для SIP. Со встроенным стеком доступен только UDP. TCP и TLS — только с pjsua2.",
            "Transport protocol for SIP. With the built-in stack only UDP is available. TCP and TLS need pjsua2.",
        )
        "srtp" -> p(
            "Шифрование медиа (SRTP)", "Media encryption (SRTP)",
            "Шифрование голоса. Работает только при транспорте TLS (pjsua2). off — выкл, optional — если поддерживается, mandatory — обязательно.",
            "Voice encryption. Works only with TLS transport (pjsua2). off, optional (if supported), mandatory.",
        )
        "pbx" -> p(
            "Внешняя АТС (PBX)", "External PBX",
            "Нужны только если «Режим устройство в себе» выключен. PBX host — адрес сервера; user/password — логин на нём; SIP domain — домен, если отличается от адреса.",
            "Only needed when Device-in-itself mode is off. PBX host — the server address; user/password — your login there; SIP domain — the domain if different from the address.",
        )
        "inbound_target" -> p(
            "Куда переводить входящие", "Inbound → SIP target",
            "SIP-адресат, которому отдаётся входящий сотовый звонок. Пусто — на твой зарегистрированный софтфон (в режиме «устройство в себе») или на имя пользователя (в режиме АТС).",
            "The SIP destination an incoming cellular call is handed to. Empty — your registered softphone (device-in-itself mode) or the username (PBX mode).",
        )
        "prefixes" -> p(
            "Префиксы для исходящих", "Dial-out prefixes",
            "Ограничение на номера, которые можно набирать через SIM (для будущих исходящих звонков). Через запятую. Пусто — любой номер.",
            "Restricts which numbers may be dialed out through the SIM (for future outbound calls). Comma-separated. Empty — any number.",
        )
        "busy" -> p(
            "Второй звонок при занятости", "Second call while busy",
            "Reject — отклонять второй входящий во время разговора. Leave to phone — оставить его обычной звонилке телефона (а SIP-стороне вернуть «занято»).",
            "Reject — refuse a second incoming call during a conversation. Leave to phone — let the phone's native dialer handle it (SIP side gets busy).",
        )
        "call_access" -> p(
            "Доступ к звонкам", "Call access",
            "Как приложение получает доступ к звонкам. Automatic — само. Alongside dialer — рядом со штатной звонилкой (роль «телефон по умолчанию» не забирается, обычно лучший вариант). Default dialer — стать телефоном по умолчанию (нужно на части прошивок и на Android 11, если «рядом» не работает).",
            "How the app gets access to calls. Automatic — chooses for you. Alongside dialer — works next to the native dialer without taking the default-dialer role (usually best). Default dialer — become the default phone app (needed on some firmwares and Android 11 if the alongside mode fails).",
        )
        "keep_dialer" -> p(
            "Сохранять роль звонилки", "Keep default-dialer role",
            "Если шлюз был «телефоном по умолчанию» — при выключении оставить роль за ним, а не возвращать прежнюю звонилку. Применяется при следующем выключении.",
            "If the gateway was the default phone app, keep that role when switching off instead of restoring the previous dialer. Applies at the next switch-off.",
        )
        "audio_method" -> p(
            "Способ захвата звука", "Cellular audio method",
            "САМЫЙ ВАЖНЫЙ параметр при проблемах со звуком — зависит от модели телефона.\n\n• Direct telephony — прямой захват тракта телефонии (рабочий на многих Qualcomm, в т.ч. Nubia).\n• Call redirection — официальная переадресация звука (Android 13+), на части устройств не работает.\n• Automatic — выбор сам, но иногда ошибается.\n\nКак проверить: включи «pjsip debug log», позвони, в журнале найди строку «tap closed … method=… backendFailures=…». Правильный метод: backendFailures ПУСТО и собеседники слышат друг друга. Если тишина или есть «record read 0» — переключи метод и позвони снова.",
            "THE most important setting for audio problems — it depends on the phone model.\n\n• Direct telephony — direct capture of the telephony path (works on many Qualcomm phones, incl. Nubia).\n• Call redirection — the official call-audio redirection (Android 13+); fails on some devices.\n• Automatic — chooses for you, but sometimes wrongly.\n\nHow to check: turn on «pjsip debug log», make a call, find the log line «tap closed … method=… backendFailures=…». The right method shows backendFailures EMPTY and both sides hear each other. If there's silence or «record read 0», switch the method and call again.",
        )
        "mute_earpiece" -> p(
            "Глушить динамик шлюза", "Mute local call output",
            "Глушить звук вызова из динамика самого телефона-шлюза, пока идёт мост (чтобы он не «фонил» рядом). Применяется со следующего звонка.",
            "Mute the call audio from the gateway phone's own speaker while bridging. Applies from the next call.",
        )
        "loopback" -> p(
            "Эхо-тест", "Loopback test",
            "Диагностика: входящие звонки не соединяются с софтфоном, а возвращаются эхом звонящему. Удобно проверить сам звуковой тракт. В обычной работе — выкл.",
            "Diagnostic: incoming calls are echoed back to the caller instead of bridging to the softphone. Handy to test the audio path. Keep off in normal use.",
        )
        "debug_log" -> p(
            "Подробный журнал", "Debug log",
            "Включает подробный журнал (номера маскируются). Нужен для диагностики звука и регистрации. В обычной работе можно выключить.",
            "Enables verbose logging (phone numbers masked). Useful for diagnosing audio and registration. Can be off in normal use.",
        )
        "language" -> p(
            "Язык интерфейса", "Interface language",
            "Язык этого приложения. Меняется сразу, перезапуск не нужен.",
            "The language of this app. Applies immediately, no restart needed.",
            "لغة الواجهة",
            "لغة هذا التطبيق. تتغير فورًا، بدون إعادة تشغيل.",
        )
        "sim" -> p(
            "SIM-карты", "SIM cards",
            "Две SIM в телефоне работают независимо.\n\n• Переключатель у каждой SIM — перехватывать ли её входящие звонки на софтфон. Выключенная SIM остаётся в обычной звонилке телефона.\n• «SIM для исходящих» — с какой SIM звонить, когда софтфон не указал SIM (заголовок X-VSA-SIMSLOT). «Как в системе» — SIM по умолчанию в Android. «Запретить» — исходящие без явного выбора SIM отклоняются.\n\nЕсли SIM поменяли местами или вставили новую — нажми «Обновить» в этом разделе.",
            "Two SIMs in the phone work independently.\n\n• Each SIM's switch controls whether its incoming calls are taken over to the softphone. A switched-off SIM stays with the phone's normal dialer.\n• “Default SIM for outgoing” is the SIM used when the softphone did not pick one (X-VSA-SIMSLOT header). “Android default” follows the system default. “Refuse” rejects outgoing calls without an explicit SIM.\n\nIf you swapped SIMs or inserted a new one, tap “Refresh” in this section.",
            "شرائح SIM",
            "الشريحتان في الهاتف تعملان بشكل مستقل.\n\n• مفتاح كل شريحة يحدد هل تُحوَّل مكالماتها الواردة إلى الهاتف الثاني. الشريحة المتوقفة تبقى في تطبيق الهاتف العادي.\n• «الشريحة الافتراضية للصادر» هي الشريحة المستعملة عندما لا يحدد الهاتف الثاني شريحة (ترويسة X-VSA-SIMSLOT). «افتراضي النظام» يتبع شريحة Android الافتراضية. «رفض» يرفض المكالمات الصادرة بدون اختيار شريحة.\n\nإذا بدّلت الشرائح أو أدخلت شريحة جديدة، اضغط «تحديث» في هذا القسم.",
        )
        "email" -> p(
            "Уведомления по email", "Email notifications",
            "Когда iPhone офлайн, шлюз сообщает тебе о пропущенных звонках, SMS (в т.ч. коды OTP) и смене IP письмом на твою почту. Письма читает и ассистент в чате Muse.\n\nКак включить (бесплатно):\n1) Включи двухфакторную защиту (2-Step Verification) в своём Google-аккаунте.\n2) В Google-аккаунте создай «Пароль приложения» (App password) для «Почты» — это НЕ пароль от Gmail, а отдельный 16-значный код.\n3) Здесь введи: SMTP host smtp.gmail.com, порт 587, STARTTLS, user — твой Gmail, password — пароль приложения, recipient — куда слать (пусто = себе же).\n4) Нажми «Отправить тестовое письмо» и проверь почту.\n\nВыключено по умолчанию; без настройки шлюз работает как раньше. Письма идут только на указанный адрес и не содержат паролей.",
            "When the iPhone is offline, the gateway tells you about missed calls, SMS (incl. OTP codes) and IP changes by email to your inbox. The assistant in the Muse chat reads those emails too.\n\nHow to enable (free):\n1) Turn on 2-Step Verification in your Google account.\n2) In your Google account create an “App password” for “Mail” — this is NOT your Gmail password, but a separate 16-character code.\n3) Enter here: SMTP host smtp.gmail.com, port 587, STARTTLS, user — your Gmail, password — the app password, recipient — where to send (empty = yourself).\n4) Tap “Send test email” and check your inbox.\n\nOff by default; without it the gateway works as before. Mail goes only to the address you set and never contains passwords.",
            "الإشعارات بالبريد الإلكتروني",
            "عندما يكون الآيفون غير متصل، يخبرك الجهاز بالمكالمات الفائتة والرسائل (بما فيها رموز OTP) وتغيير IP برسالة إلى بريدك. المساعد في محادثة Muse يقرأ هذه الرسائل أيضًا.\n\nطريقة التفعيل (مجانًا):\n1) فعّل التحقق بخطوتين (2-Step Verification) في حساب Google.\n2) في حساب Google أنشئ «كلمة مرور تطبيق» (App password) لـ«البريد» — هذه ليست كلمة مرور Gmail، بل رمز منفصل من 16 حرفًا.\n3) أدخل هنا: SMTP host‏ smtp.gmail.com، المنفذ 587، STARTTLS، user — بريد Gmail، password — كلمة مرور التطبيق، recipient — أين تُرسل (فارغ = لنفسك).\n4) اضغط «إرسال بريد تجريبي» وتحقق من بريدك.\n\nمتوقفة افتراضيًا؛ وبدونها يعمل الجهاز كما قبل. الرسائل تذهب فقط إلى العنوان الذي حددته ولا تحتوي على كلمات مرور.",
        )
        "remote_access" -> p(
            "Удалённый доступ", "Remote access",
            "Адреса, по которым софтфон может найти этот шлюз. Нажми «Копировать» и вставь адрес в Linphone (аккаунт 101, порт 5062, UDP).\n\n• Рядом (iPhone на хот-споте этого телефона): адрес «Wi-Fi / hotspot».\n• Вдали (iPhone на другом интернете): адрес VPN — Tailscale или ZeroTier. VPN должен стоять и быть включён с ОДНИМ аккаунтом на ОБОИХ телефонах (бесплатно). Без VPN удалённый iPhone не дойдёт до шлюза: мобильный IP оператора — за CGNAT, входящие соединения туда закрыты, и никакой код это не обойдёт.\n• Нет VPN: режим Zadarma (оба телефона регистрируются на публичный pbx.zadarma.com).\n\nАдрес «Mobile data» меняется при каждом переподключении мобильных данных — о смене шлюз сообщает письмом (если включены email-уведомления).",
            "Addresses the softphone can reach this gateway at. Tap “Copy” and paste the address into Linphone (account 101, port 5062, UDP).\n\n• Nearby (iPhone on this phone's hotspot): the “Wi-Fi / hotspot” address.\n• Far away (iPhone on another internet connection): a VPN address — Tailscale or ZeroTier. The VPN must be installed and signed in with the SAME account on BOTH phones (free). Without a VPN a remote iPhone cannot reach the gateway: the carrier mobile IP sits behind CGNAT, inbound connections to it are blocked, and no code can work around that.\n• No VPN: Zadarma mode (both phones register to the public pbx.zadarma.com).\n\nThe “Mobile data” address changes every time mobile data reconnects — the gateway emails the new one (if email notifications are on).",
            "الوصول عن بُعد",
            "العناوين التي يمكن للهاتف الثاني الوصول بها إلى هذا الجهاز. اضغط «نسخ» والصق العنوان في Linphone (الحساب 101، المنفذ 5062، UDP).\n\n• بالقرب (الآيفون على نقطة اتصال هذا الهاتف): عنوان «Wi-Fi / hotspot».\n• بعيدًا (الآيفون على إنترنت آخر): عنوان VPN — Tailscale أو ZeroTier. يجب تثبيت VPN وتسجيل الدخول بنفس الحساب على الهاتفين (مجانًا). بدون VPN لا يستطيع الآيفون البعيد الوصول إلى الجهاز: عنوان بيانات الهاتف خلف CGNAT، والاتصالات الواردة إليه محظورة، ولا يمكن لأي برنامج تجاوز ذلك.\n• بدون VPN: وضع Zadarma (يسجل الهاتفان على pbx.zadarma.com العام).\n\nعنوان «بيانات الهاتف» يتغير مع كل إعادة اتصال للبيانات — ويرسل الجهاز العنوان الجديد بالبريد (إذا كانت إشعارات البريد مفعّلة).",
        )
        "call_log" -> p(
            "Журнал звонков", "Call log",
            "Все звонки через шлюз: входящие, исходящие и пропущенные — номер, время, SIM и длительность. Хранится только на этом телефоне (до 200 записей), никуда не отправляется. Пропущенные звонки видны и здесь, и письмом (если включены email-уведомления), и сообщением в софтфоне при следующей регистрации.",
            "Every call through the gateway: incoming, outgoing and missed — number, time, SIM and duration. Stored only on this phone (up to 200 entries), sent nowhere. Missed calls also show up here, by email (if email notifications are on) and as a message in the softphone at the next registration.",
            "سجل المكالمات",
            "كل المكالمات عبر الجهاز: الواردة والصادرة والفائتة — الرقم والوقت والشريحة والمدة. تُحفظ فقط على هذا الهاتف (حتى 200 سجل) ولا تُرسل إلى أي مكان. المكالمات الفائتة تظهر هنا أيضًا، وبالبريد (إذا كانت إشعارات البريد مفعّلة)، وكرسالة في الهاتف الثاني عند التسجيل التالي.",
        )
        "background" -> p(
            "Работа в фоне", "Running in the background",
            "Шлюз должен жить, даже когда приложение закрыто. Android и Infinix/XOS любят «убивать» фоновые приложения — сделай три вещи один раз:\n\n1) Выключи оптимизацию батареи для ЭТОГО приложения (кнопка рядом) и для Shizuku.\n2) В настройках Infinix разреши этому приложению «Автозапуск» (Настройки → Приложения → это приложение → Автозапуск).\n3) Заблокируй приложение в списке недавних: открой «недавние», найди это приложение и нажми значок замка — тогда свайп не закроет его.\n\nПосле перезагрузки телефона Shizuku всегда нужно запускать вручную — уведомление шлюза напомнит и откроет Shizuku одним нажатием; как только Shizuku запущен, шлюз поднимается сам. А вот принудительную остановку (Force stop) не обойти никак: помогает только снова открыть приложение.",
            "The gateway must stay alive even when the app is closed. Android and Infinix/XOS like to kill background apps — do these three things once:\n\n1) Turn off battery optimization for THIS app (the button next to this) and for Shizuku.\n2) In the Infinix settings allow “Auto-start” for this app (Settings → Apps → this app → Auto-start).\n3) Lock the app in recents: open recents, find this app and tap the lock icon — then swiping it away won't close it.\n\nAfter a phone reboot Shizuku always needs a manual restart — the gateway notification reminds you and opens Shizuku with one tap; once Shizuku is running, the gateway comes up by itself. A force-stop, however, cannot be worked around at all: only reopening the app fixes it.",
            "العمل في الخلفية",
            "يجب أن يبقى الجهاز يعمل حتى عند إغلاق التطبيق. Android وInfinix/XOS يحبان «قتل» التطبيقات في الخلفية — افعل هذه الأشياء الثلاثة مرة واحدة:\n\n1) أوقف تحسين البطارية لهذا التطبيق (الزر بجانب هذا) ولـShizuku.\n2) في إعدادات Infinix اسمح «التشغيل التلقائي» (Auto-start) لهذا التطبيق (الإعدادات ← التطبيقات ← هذا التطبيق ← التشغيل التلقائي).\n3) اقفل التطبيق في قائمة الأخيرة: افتح «الأخيرة»، ابحث عن هذا التطبيق واضغط أيقونة القفل — عندها لن يغلقه السحب.\n\nبعد إعادة تشغيل الهاتف يجب تشغيل Shizuku يدويًا دائمًا — إشعار الجهاز يذكّرك ويفتح Shizuku بضغطة واحدة؛ وبمجرد تشغيل Shizuku يعود الجهاز للعمل وحده. أما الإيقاف القسري (Force stop) فلا يمكن تجاوزه إطلاقًا: الحل الوحيد هو فتح التطبيق مجددًا.",
        )
        else -> null
    }
}

/** FAQ / help content, as a list of (question, answer) pairs in the current language. */
fun faqEntries(lang: UiLang): List<Pair<String, String>> {
    val ru = lang == UiLang.RU
    fun e(qRu: String, aRu: String, qEn: String, aEn: String) = if (ru) qRu to aRu else qEn to aEn
    return listOf(
        e(
            "Что это вообще такое?",
            "Старый телефон с SIM лежит дома на зарядке и Wi-Fi. Входящие звонки и SMS на его номер он сам отдаёт через интернет на твой второй телефон (софтфон), где бы ты ни был. Ни ПК, ни внешнего сервера не нужно — SIP-сервер встроен в это приложение.",
            "What is this, exactly?",
            "An old phone with a SIM sits at home on a charger and Wi-Fi. Incoming calls and SMS to its number are bridged over the internet to your second phone (a softphone), wherever you are. No PC and no external server — the SIP server is built into this app.",
        ),
        e(
            "Какой софтфон ставить на ДАЛЬНИЙ телефон (Android)?",
            "Рекомендуем baresip — бесплатный, открытый, работает в фоне и умеет page-mode SMS. Где взять: F-Droid (пакет com.tutpro.baresip). После установки: выключи оптимизацию батареи для baresip и разреши фоновую работу/автозапуск, иначе регистрация будет слетать.\n\nНастройка аккаунта: меню ⋮ → «Профили» → поле «SIP URI или Новый аккаунт» → «sip:s23@<IP телефона-шлюза>:<порт регистратора>» → «+». Порт в адресе обязателен. В редакторе аккаунта: «Пароль аутентификации» — тот же пароль, что на шлюзе (пусто только для локальной сети), транспорт UDP, включи тумблер «Регистрация». И обязательно выдай baresip доступ к микрофону.",
            "Which softphone for the FIELD phone (Android)?",
            "We recommend baresip — free, open-source, runs in the background, and does page-mode SMS. Where to get it: F-Droid (package com.tutpro.baresip). After installing: turn off battery optimization for baresip and allow background/autostart, otherwise the registration will keep dropping.\n\nAccount setup: menu ⋮ → «Accounts» → «SIP URI or New account» field → «sip:s23@<gateway phone IP>:<registrar port>» → «+». The port in the address is required. In the account editor: «Auth password» — the same password set on the gateway (empty only for a local network), transport UDP, enable the «Registration» toggle. And do grant baresip microphone access.",
        ),
        e(
            "А на iPhone (iOS)?",
            "Честно: iOS выгружает приложения из фона, поэтому надёжный приём звонков при «своём» сервере без push-уведомлений не гарантирован — приложение должно быть открыто. Push на iOS умеют платные клиенты (Groundwire, Bria), но их push идёт через их облако, а не через наш встроенный сервер. Практичнее в роли дальнего телефона держать Android (baresip); iPhone хорошо подойдёт для исходящих и при открытом приложении.",
            "What about iPhone (iOS)?",
            "Honestly: iOS suspends backgrounded apps, so reliable incoming-call delivery with a self-hosted, push-less server is not guaranteed — the app must be in the foreground. Paid iOS clients (Groundwire, Bria) do support push, but their push goes through their cloud, not our built-in server. It's more practical to use an Android phone as the field device (baresip); an iPhone works well for outbound and while the app is open.",
        ),
        e(
            "Почему протухает регистрация SIP?",
            "Софтфон должен периодически «напоминать о себе» (re-REGISTER). Если телефон с софтфоном засыпает в фоне, напоминания прекращаются. Что сделать: 1) выключить оптимизацию батареи у софтфона и разрешить фон/автозапуск; 2) здесь увеличить «Аренду регистрации» (например до 1200 с); 3) держать телефон-шлюз живым (оптимизацию батареи у ЭТОГО приложения и Shizuku тоже выключить, особенно на ZTE/Nubia/Xiaomi). Этот шлюз держит последний адрес ещё около двух сроков аренды, так что короткие пропуски не страшны.",
            "Why does the SIP registration keep expiring?",
            "The softphone must periodically re-REGISTER. If the phone running the softphone sleeps in the background, those refreshes stop. What to do: 1) disable battery optimization for the softphone and allow background/autostart; 2) raise «Registration lease» here (e.g. to 1200 s); 3) keep the gateway phone alive (also disable battery optimization for THIS app and Shizuku, especially on ZTE/Nubia/Xiaomi). This gateway holds the last contact for about two lease periods, so brief gaps are fine.",
        ),
        e(
            "Что такое Shizuku и где его взять?",
            "Shizuku даёт приложению права уровня отладки без root — через него работает перехват звука. Для MediaTek/Xiaomi/Android 16 нужна пропатченная сборка (обычная падает). Бери её из материалов проекта (файл shizuku-patched-…apk). Shizuku нужно запускать заново после каждой перезагрузки телефона (по USB или через беспроводную отладку).",
            "What is Shizuku and where do I get it?",
            "Shizuku grants the app ADB-level permissions without root — the audio interception works through it. On MediaTek/Xiaomi/Android 16 a patched build is required (the stock one crashes). Use the one from the project materials (shizuku-patched-….apk). Shizuku must be started again after every reboot of the phone (over USB or via wireless debugging).",
        ),
        e(
            "Звонок соединился, но никого не слышно",
            "Почти всегда это «Способ захвата звука» (см. ⓘ у этого пункта в Advanced). Переключи между «Direct telephony» и «Call redirection» и позвони снова. Если не слышат именно тебя — проверь, выдан ли софтфону доступ к микрофону.",
            "The call connects but nobody is heard",
            "Almost always the «Cellular audio method» (see the ⓘ on that item under Advanced). Switch between «Direct telephony» and «Call redirection» and call again. If only you can't be heard — check that the softphone has microphone access.",
        ),
        e(
            "Софтфон виснет на «подключении»",
            "1) Оба телефона в одной сети, в роутере нет «изоляции клиентов». 2) Верные IP:порт и транспорт UDP. 3) Правильный пароль регистратора в софтфоне — тот же, что задан на шлюзе.",
            "Softphone hangs on «connecting»",
            "1) Both phones on the same network, no «client isolation» on the router. 2) Correct IP:port and UDP transport. 3) Correct registrar password in the softphone — the same one set on the gateway.",
        ),
        e(
            "Как работать не в одной Wi-Fi (в поездке)?",
            "Либо белый IP дома и проброс порта регистратора (UDP) на роутере, либо Tailscale на обоих телефонах (бесплатно, без белого IP): в софтфоне укажи Tailscale-адрес шлюза. Регистратор запоминает адрес по факту регистрации, так что роуминг он переживёт.",
            "How to use it outside one Wi-Fi (while travelling)?",
            "Either a public IP at home with the registrar port (UDP) forwarded on the router, or Tailscale on both phones (free, no public IP needed): point the softphone at the gateway's Tailscale address. The registrar learns the address from the registration, so roaming is fine.",
        ),
    )
}
