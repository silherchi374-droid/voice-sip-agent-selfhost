package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipCommandParserTest {
    @Test
    fun smsCommand() {
        assertEquals(
            SipCommand.SendSms("+21629154710", "Salam, kifek?"),
            SipCommandParser.parse("SMS +21629154710 Salam, kifek?"),
        )
        assertEquals(
            SipCommand.SendSms("29154710", "hi"),
            SipCommandParser.parse("sms 29154710 hi"),
        )
    }

    @Test
    fun smsCommandRejectsMissingParts() {
        assertNull(SipCommandParser.parse("SMS"))
        assertNull(SipCommandParser.parse("SMS +21629154710"))
        assertNull(SipCommandParser.parse("SMS   "))
    }

    @Test
    fun ussdCommand() {
        assertEquals(SipCommand.RunUssd(null), SipCommandParser.parse("USSD"))
        assertEquals(SipCommand.RunUssd("*124#"), SipCommandParser.parse("USSD *124#"))
        assertEquals(SipCommand.RunUssd("*100*1#"), SipCommandParser.parse("ussd *100*1#"))
        assertEquals(SipCommand.RunUssd("#123#"), SipCommandParser.parse("USSD #123#"))
    }

    @Test
    fun ussdCommandRejectsNonCodes() {
        assertNull(SipCommandParser.parse("USSD hello"))
        assertNull(SipCommandParser.parse("USSD 124"))
        assertNull(SipCommandParser.parse("USSD *124"))
    }

    @Test
    fun restartCommand() {
        assertEquals(SipCommand.Restart, SipCommandParser.parse("RESTART"))
        assertEquals(SipCommand.Restart, SipCommandParser.parse("restart"))
        assertNull(SipCommandParser.parse("RESTART now"))
    }

    @Test
    fun findCommand() {
        assertEquals(SipCommand.FindPhone(stop = false), SipCommandParser.parse("FIND"))
        assertEquals(SipCommand.FindPhone(stop = true), SipCommandParser.parse("FIND STOP"))
        assertEquals(SipCommand.FindPhone(stop = true), SipCommandParser.parse("find stop"))
        assertNull(SipCommandParser.parse("FIND ME"))
    }

    @Test
    fun unknownAndEmptyAreNull() {
        assertNull(SipCommandParser.parse(""))
        assertNull(SipCommandParser.parse("   "))
        assertNull(SipCommandParser.parse("CALL +21629154710"))
        assertNull(SipCommandParser.parse("hello"))
    }
}
