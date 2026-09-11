package com.example.ami.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverContactsTest {

    @Test
    fun `round-trips the chain in order`() {
        val contacts = listOf(
            CaregiverContact("Daughter", "daughter@example.com"),
            CaregiverContact("Son", "son@example.com")
        )
        assertEquals(contacts, CaregiverContacts.decode(CaregiverContacts.encode(contacts)))
    }

    /** Upgrading from the single-address version must not drop the only contact. */
    @Test
    fun `falls back to the legacy single address`() {
        val decoded = CaregiverContacts.decode("", legacySingleEmail = "old@example.com")
        assertEquals(listOf(CaregiverContact("", "old@example.com")), decoded)
    }

    @Test
    fun `a stored chain wins over the legacy address`() {
        val stored = CaregiverContacts.encode(listOf(CaregiverContact("New", "new@example.com")))
        val decoded = CaregiverContacts.decode(stored, legacySingleEmail = "old@example.com")
        assertEquals(listOf("new@example.com"), decoded.map { it.email })
    }

    @Test
    fun `corrupt json degrades to no contacts rather than throwing`() {
        assertTrue(CaregiverContacts.decode("{not json").isEmpty())
        assertTrue(CaregiverContacts.decode("").isEmpty())
    }

    @Test
    fun `invalid and duplicate addresses are dropped`() {
        val raw = CaregiverContacts.encode(
            listOf(
                CaregiverContact("Good", "good@example.com"),
                CaregiverContact("Broken", "not-an-email"),
                CaregiverContact("Dupe", "GOOD@example.com")
            )
        )
        assertEquals(listOf("good@example.com"), CaregiverContacts.decode(raw).map { it.email })
    }

    @Test
    fun `validates addresses the same way the server does`() {
        assertTrue(CaregiverContacts.isValidEmail("a@b.co"))
        assertFalse(CaregiverContacts.isValidEmail("a@b"))
        assertFalse(CaregiverContacts.isValidEmail("a b@c.com"))
        assertFalse(CaregiverContacts.isValidEmail(""))
    }

    @Test
    fun `falls back to the address when no name was given`() {
        assertEquals("x@example.com", CaregiverContact("", "x@example.com").displayName)
        assertEquals("Mum", CaregiverContact("Mum", "x@example.com").displayName)
    }
}
