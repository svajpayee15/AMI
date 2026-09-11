package com.example.ami.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveAppsTest {

    @Test
    fun `known password managers and wallets are refused`() {
        assertTrue(SensitiveApps.isSensitive("com.x8bit.bitwarden"))
        assertTrue(SensitiveApps.isSensitive("com.google.android.apps.walletnfcrel"))
        assertTrue(SensitiveApps.isSensitive("com.phonepe.app"))
    }

    @Test
    fun `banking keywords anywhere in the package are refused`() {
        assertTrue(SensitiveApps.isSensitive("com.chase.sig.android.bank"))
        assertTrue(SensitiveApps.isSensitive("com.sbi.upi"))
        assertTrue(SensitiveApps.isSensitive("uk.co.example.netbanking"))
        assertTrue(SensitiveApps.isSensitive("com.example.myvault"))
    }

    @Test
    fun `a payments segment is refused but a lookalike word is not`() {
        assertTrue(SensitiveApps.isSensitive("com.example.pay"))
        assertTrue(SensitiveApps.isSensitive("com.example.payments.app"))
        // "wallpaper" and "paypal-like" substrings must not trip the segment rule.
        assertFalse(SensitiveApps.isSensitive("com.example.wallpapers"))
        assertFalse(SensitiveApps.isSensitive("com.example.paycheckstub.reader"))
    }

    @Test
    fun `ordinary apps are allowed`() {
        assertFalse(SensitiveApps.isSensitive("com.whatsapp"))
        assertFalse(SensitiveApps.isSensitive("com.android.settings"))
        assertFalse(SensitiveApps.isSensitive("com.google.android.youtube"))
        assertFalse(SensitiveApps.isSensitive("com.google.android.dialer"))
    }

    @Test
    fun `an unknown foreground package is not treated as sensitive`() {
        assertFalse(SensitiveApps.isSensitive(null))
        assertFalse(SensitiveApps.isSensitive("   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(SensitiveApps.isSensitive("COM.EXAMPLE.BANK"))
    }
}
