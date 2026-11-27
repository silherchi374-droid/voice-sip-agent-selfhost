package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Feature 6: looks up an incoming caller's number in the Infinix contacts so the SIP From
 * display name shows the contact name in Linphone. Needs READ_CONTACTS at runtime; without the
 * permission (or no match) it returns null and the number is shown instead — never a crash.
 * The lookup normalizes both sides to digits so "+216 29 154 710" matches a contact stored as
 * "29 154 710".
 */
class ContactLookup(private val context: Context) {
    fun nameFor(number: String?): String? {
        val digits = number?.filter { it.isDigit() }.orEmpty()
        if (digits.length < 7) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }
}
