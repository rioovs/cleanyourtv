package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The camera address has to be on the local network: the app has no business talking to the internet. */
public class CctvLocalHostTest {

    @Test
    public void privateIpv4IsLocal() {
        assertTrue(CctvPanel.isLocalHost("192.168.1.50"));
        assertTrue(CctvPanel.isLocalHost("10.0.0.7"));
        assertTrue(CctvPanel.isLocalHost("172.16.5.9"));
        assertTrue(CctvPanel.isLocalHost("172.31.255.254"));
        assertTrue(CctvPanel.isLocalHost("169.254.1.1"));
    }

    @Test
    public void publicAddressIsNotLocal() {
        assertFalse(CctvPanel.isLocalHost("8.8.8.8"));
        assertFalse(CctvPanel.isLocalHost("172.32.0.1"));
        assertFalse(CctvPanel.isLocalHost("11.0.0.1"));
        assertFalse(CctvPanel.isLocalHost("192.169.0.1"));
        assertFalse(CctvPanel.isLocalHost("example.com"));
    }

    @Test
    public void rubbishIsNotLocal() {
        assertFalse(CctvPanel.isLocalHost(null));
        assertFalse(CctvPanel.isLocalHost(""));
        assertFalse(CctvPanel.isLocalHost("192.168.1"));
        assertFalse(CctvPanel.isLocalHost("192.168.1.999"));
        assertFalse(CctvPanel.isLocalHost("192.168.1.50.5"));
    }

    @Test
    public void localhostIsLocal() {
        assertTrue(CctvPanel.isLocalHost("localhost"));
        assertTrue(CctvPanel.isLocalHost("::1"));
    }
}
