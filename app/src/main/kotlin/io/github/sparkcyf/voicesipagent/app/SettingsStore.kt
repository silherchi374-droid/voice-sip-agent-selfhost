package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(
    /** Master switch; call cleanup and default-dialer restoration may finish later. */
    val takeover: Boolean = true,
    /** Retain the role on switch-off instead of returning to the saved dialer. */
    val keepDefaultDialer: Boolean = false,
    /** Mute local call output while bridged. Telecom manages microphone mute independently. */
    val muteEarpiece: Boolean = true,
    val server: String = "",
    val port: Int = 5060,
    /** udp | tcp | tls; the built-in stack only supports udp. */
    val sipTransport: String = "udp",
    /** off | optional | mandatory SDES-SRTP (pjsua2 over TLS only). */
    val srtp: String = "optional",
    /** pjsip info-level logging (development); errors/warnings are always logged. */
    val sipDebugLog: Boolean = false,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val localSipPort: Int = 5062,
    /** Server mode: lease (seconds) granted to the softphone; longer survives background Doze. */
    val registrarLeaseSec: Int = 600,
    /**
     * Server mode: password the softphone must present to REGISTER (SIP digest auth). Empty = no
     * authentication (anyone who reaches the port can register and dial out) — set one before
     * exposing the gateway to the internet. Sealed on disk like the upstream SIP password.
     */
    val registrarPassword: String = "",
    /** SIP extension / DID that receives cellular inbound calls; blank = the SIP username (trunk DID). */
    val inboundTarget: String = "",
    /** Comma-separated; empty = any number may be dialed out. */
    val allowedPrefixes: String = "",
    val busyPolicy: BusyPolicy = BusyPolicy.REJECT,
    val audioMethod: Int = AudioMethod.AUTO,
    val callAccess: Int = CallAccess.AUTO,
    /** Which SIP implementation backs the PBX side. */
    val sipStack: SipStack = SipStack.BUILTIN,
    /**
     * Device-in-itself mode: this phone hosts the SIP registrar. A softphone (e.g. baresip
     * in the field) registers directly to it; no external PBX/server is involved. When on,
     * [server]/[username]/[password] are not required.
     */
    val serverMode: Boolean = true,
    /** UI language: "ru", "en" or "ar". Pure UI preference, no SIP restart. */
    val uiLanguage: String = "ar",
    /** Outbound SIM for calls without X-VSA-SIMSLOT; see [DefaultSim] (SYSTEM = Android default). */
    val defaultSimSlot: Int = DefaultSim.SYSTEM,
    /** SIM slots whose incoming calls stay with the phone app; empty = take over all slots. */
    val inboundExcludedSlots: Set<Int> = emptySet(),
    /**
     * Email notifications (missed calls, SMS/OTP, IP changes). The master switch and each type
     * are OFF by default; the gateway works fully without them. Mail goes only to the configured
     * recipient (the user's own email) and never contains a password or secret.
     */
    val emailNotifyEnabled: Boolean = false,
    val emailMissedCalls: Boolean = false,
    val emailSms: Boolean = false,
    val emailIpChange: Boolean = false,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    /** "starttls" (port 587) or "ssl" (port 465). */
    val smtpSecurity: String = "starttls",
    val smtpUser: String = "",
    /** App password for the SMTP account (e.g. a Google app password). Sealed on disk. */
    val smtpPassword: String = "",
    /** Where notifications go; blank = the sender address itself. */
    val emailRecipient: String = "",
    // ---- Side-menu features (12 new, 2026-11-27) ----
    /** Feature 1: allow SMS commands from the iPhone (SIP MESSAGE "SMS <number> <text>"). */
    val smsCommandsEnabled: Boolean = true,
    /** Feature 2: allow USSD commands from the iPhone ("USSD [code]"); default code *124#. */
    val ussdCommandsEnabled: Boolean = true,
    val ussdDefaultCode: String = "*124#",
    /** Feature 3: email once when the battery drops below 20% (re-arm above 25% / on charger). */
    val lowBatteryAlertEnabled: Boolean = false,
    /** Feature 4: incoming calls from these numbers are rejected, never ring the iPhone. */
    val blockedNumbers: Set<String> = emptySet(),
    /** Feature 5: night window (minutes from midnight); incoming do not ring inside it. */
    val nightModeEnabled: Boolean = false,
    val nightStartMin: Int = 22 * 60,
    val nightEndMin: Int = 7 * 60,
    /** Feature 6: show the Infinix contact name for the caller in Linphone. */
    val callerNameEnabled: Boolean = true,
    /** Feature 7: auto-reply SMS to missed calls (costs credit). Default OFF. */
    val autoReplyEnabled: Boolean = false,
    val autoReplyText: String = "مشغول حاليًا، سأعاود الاتصال بك.",
    /** Feature 8: allow remote gateway restart from the iPhone ("RESTART"). */
    val remoteRestartEnabled: Boolean = true,
    /** Feature 9: daily USSD balance check; email once a day when below the threshold. */
    val lowBalanceAlertEnabled: Boolean = false,
    val lowBalanceThresholdTnd: Double = 5.0,
    /** Feature 10: turn the Wi-Fi hotspot back on if it switches off. */
    val hotspotAutoRestartEnabled: Boolean = false,
    /** Feature 11: allow the find-my-phone alarm from the iPhone ("FIND" / "FIND STOP"). */
    val findPhoneEnabled: Boolean = true,
    /** Feature 12: reject incoming calls with no caller ID. Default ON. */
    val blockHiddenNumbers: Boolean = true,
    /**
     * Feature 13: forward incoming GSM calls to this external SIP URI instead of the local
     * phone (e.g. sip:name@sip.linphone.org, so Linphone's servers can push the iPhone).
     * Empty = off. A failed forward falls back to the local phone.
     */
    val externalForwardTarget: String = "",
) {
    val sipConfigured: Boolean get() =
        serverMode || (server.isNotBlank() && username.isNotBlank() && password.isNotEmpty())

    override fun toString() =
        "GatewaySettings(takeover=$takeover muteEarpiece=$muteEarpiece stack=$sipStack serverMode=$serverMode server=$server:$port/$sipTransport srtp=$srtp user=$username target=$inboundTarget email=$emailNotifyEnabled)"
}

enum class SipStack(val id: String) {
    /** The hand-written Kotlin stack in :sip. */
    BUILTIN("builtin"),

    /** pjsip's pjsua2 via :sip-pjsua2. */
    PJSUA2("pjsua2"),
    ;

    companion object {
        fun of(id: String?): SipStack? = entries.firstOrNull { it.id == id }
    }
}

/** First-run UI language: Arabic is this app's language (Saif's choice), whatever the
 * system locale is. The RU/EN/عربي picker on the main screen still lets anyone switch. */
private fun systemDefaultUiLang(): String = "ar"

/** SharedPreferences, with the SIP password sealed by an Android Keystore AES-GCM key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun load(): GatewaySettings = GatewaySettings(
        takeover = prefs.getBoolean("takeover", true),
        keepDefaultDialer = prefs.getBoolean("keep_default_dialer", false),
        muteEarpiece = prefs.getBoolean("mute_earpiece", true),
        server = prefs.getString("server", "")!!,
        port = prefs.getInt("port", 5060),
        sipTransport = prefs.getString("sip_transport", "udp")!!,
        srtp = prefs.getString("srtp", "optional")!!,
        sipDebugLog = prefs.getBoolean("sip_debug_log", false),
        username = prefs.getString("username", "")!!,
        password = prefs.getString("password_sealed", null)?.let(::unseal).orEmpty(),
        domain = prefs.getString("domain", "")!!,
        localSipPort = prefs.getInt("local_sip_port", 5062),
        registrarLeaseSec = prefs.getInt("registrar_lease_sec", 600).takeIf { it in 30..86400 } ?: 600,
        registrarPassword = prefs.getString("registrar_password_sealed", null)?.let(::unseal).orEmpty(),
        inboundTarget = prefs.getString("inbound_target", "")!!,
        allowedPrefixes = prefs.getString("allowed_prefixes", "")!!,
        busyPolicy = runCatching { BusyPolicy.valueOf(prefs.getString("busy_policy", "REJECT")!!) }.getOrDefault(BusyPolicy.REJECT),
        audioMethod = prefs.getInt("audio_method", AudioMethod.AUTO).takeIf { it in 0..2 } ?: AudioMethod.AUTO,
        callAccess = prefs.getInt("call_access", CallAccess.AUTO).takeIf { it in 0..2 } ?: CallAccess.AUTO,
        sipStack = SipStack.of(prefs.getString("sip_stack", null)) ?: SipStack.BUILTIN,
        serverMode = prefs.getBoolean("server_mode", true),
        // First run (key absent): default to Arabic — this app's language. Once the user
        // picks a language it is saved and always wins.
        uiLanguage = prefs.getString("ui_lang", null) ?: systemDefaultUiLang(),
        defaultSimSlot = prefs.getInt("default_sim_slot", DefaultSim.SYSTEM).takeIf(DefaultSim::isValid) ?: DefaultSim.SYSTEM,
        inboundExcludedSlots = prefs.getString("inbound_excluded_slots", "")!!.split(',')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { slot -> slot >= 0 } }.toSet(),
        emailNotifyEnabled = prefs.getBoolean("email_notify_enabled", false),
        emailMissedCalls = prefs.getBoolean("email_missed_calls", false),
        emailSms = prefs.getBoolean("email_sms", false),
        emailIpChange = prefs.getBoolean("email_ip_change", false),
        smtpHost = prefs.getString("smtp_host", "smtp.gmail.com")!!,
        smtpPort = prefs.getInt("smtp_port", 587).takeIf { it in 1..65535 } ?: 587,
        smtpSecurity = prefs.getString("smtp_security", "starttls")!!.takeIf { it == "ssl" } ?: "starttls",
        smtpUser = prefs.getString("smtp_user", "")!!,
        smtpPassword = prefs.getString("smtp_password_sealed", null)?.let(::unseal).orEmpty(),
        emailRecipient = prefs.getString("email_recipient", "")!!,
        smsCommandsEnabled = prefs.getBoolean("sms_commands_enabled", true),
        ussdCommandsEnabled = prefs.getBoolean("ussd_commands_enabled", true),
        ussdDefaultCode = prefs.getString("ussd_default_code", "*124#")!!.takeIf { it.isNotBlank() } ?: "*124#",
        lowBatteryAlertEnabled = prefs.getBoolean("low_battery_alert_enabled", false),
        blockedNumbers = prefs.getString("blocked_numbers", "")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        nightModeEnabled = prefs.getBoolean("night_mode_enabled", false),
        nightStartMin = prefs.getInt("night_start_min", 22 * 60).mod(24 * 60),
        nightEndMin = prefs.getInt("night_end_min", 7 * 60).mod(24 * 60),
        callerNameEnabled = prefs.getBoolean("caller_name_enabled", true),
        autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", false),
        autoReplyText = prefs.getString("auto_reply_text", "مشغول حاليًا، سأعاود الاتصال بك.")!!,
        remoteRestartEnabled = prefs.getBoolean("remote_restart_enabled", true),
        lowBalanceAlertEnabled = prefs.getBoolean("low_balance_alert_enabled", false),
        lowBalanceThresholdTnd = prefs.getString("low_balance_threshold_tnd", "5.0")!!.toDoubleOrNull()?.takeIf { it >= 0 } ?: 5.0,
        hotspotAutoRestartEnabled = prefs.getBoolean("hotspot_auto_restart_enabled", false),
        findPhoneEnabled = prefs.getBoolean("find_phone_enabled", true),
        blockHiddenNumbers = prefs.getBoolean("block_hidden_numbers", true),
        externalForwardTarget = prefs.getString("external_forward_target", "")!!,
    )

    fun save(s: GatewaySettings) {
        prefs.edit()
            .putBoolean("takeover", s.takeover)
            .putBoolean("keep_default_dialer", s.keepDefaultDialer)
            .putBoolean("mute_earpiece", s.muteEarpiece)
            .putString("server", s.server.trim())
            .putInt("port", s.port)
            .putString("sip_transport", s.sipTransport.trim().lowercase())
            .putString("srtp", s.srtp.trim().lowercase())
            .putBoolean("sip_debug_log", s.sipDebugLog)
            .putString("username", s.username.trim())
            .putString("password_sealed", seal(s.password))
            .putString("domain", s.domain.trim())
            .putInt("local_sip_port", s.localSipPort)
            .putInt("registrar_lease_sec", s.registrarLeaseSec)
            .putString("registrar_password_sealed", seal(s.registrarPassword))
            .putString("inbound_target", s.inboundTarget.trim())
            .putString("allowed_prefixes", s.allowedPrefixes.trim())
            .putString("busy_policy", s.busyPolicy.name)
            .putInt("audio_method", s.audioMethod)
            .putInt("call_access", s.callAccess)
            .putString("sip_stack", s.sipStack.id)
            .putBoolean("server_mode", s.serverMode)
            .putString("ui_lang", s.uiLanguage)
            .putInt("default_sim_slot", s.defaultSimSlot)
            .putString("inbound_excluded_slots", s.inboundExcludedSlots.sorted().joinToString(","))
            .putBoolean("email_notify_enabled", s.emailNotifyEnabled)
            .putBoolean("email_missed_calls", s.emailMissedCalls)
            .putBoolean("email_sms", s.emailSms)
            .putBoolean("email_ip_change", s.emailIpChange)
            .putString("smtp_host", s.smtpHost.trim())
            .putInt("smtp_port", s.smtpPort)
            .putString("smtp_security", s.smtpSecurity.trim().lowercase())
            .putString("smtp_user", s.smtpUser.trim())
            .putString("smtp_password_sealed", seal(s.smtpPassword))
            .putString("email_recipient", s.emailRecipient.trim())
            .putBoolean("sms_commands_enabled", s.smsCommandsEnabled)
            .putBoolean("ussd_commands_enabled", s.ussdCommandsEnabled)
            .putString("ussd_default_code", s.ussdDefaultCode.trim())
            .putBoolean("low_battery_alert_enabled", s.lowBatteryAlertEnabled)
            .putString("blocked_numbers", s.blockedNumbers.joinToString(","))
            .putBoolean("night_mode_enabled", s.nightModeEnabled)
            .putInt("night_start_min", s.nightStartMin)
            .putInt("night_end_min", s.nightEndMin)
            .putBoolean("caller_name_enabled", s.callerNameEnabled)
            .putBoolean("auto_reply_enabled", s.autoReplyEnabled)
            .putString("auto_reply_text", s.autoReplyText)
            .putBoolean("remote_restart_enabled", s.remoteRestartEnabled)
            .putBoolean("low_balance_alert_enabled", s.lowBalanceAlertEnabled)
            .putString("low_balance_threshold_tnd", s.lowBalanceThresholdTnd.toString())
            .putBoolean("hotspot_auto_restart_enabled", s.hotspotAutoRestartEnabled)
            .putBoolean("find_phone_enabled", s.findPhoneEnabled)
            .putBoolean("block_hidden_numbers", s.blockHiddenNumbers)
            .putString("external_forward_target", s.externalForwardTarget.trim())
            .apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        AppLog.w("stored SIP password unreadable; re-enter it", e)
        null
    }

    companion object {
        private const val KEY_ALIAS = "sip-password"
    }
}
