package com.gathin.tvhome;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppKeyMapsTest {

    @Test
    public void learnableRejectsNavAndVolume() {
        assertFalse(AppKeyMaps.learnable(KeyEvent.KEYCODE_DPAD_CENTER, 0));
        assertFalse(AppKeyMaps.learnable(KeyEvent.KEYCODE_BACK, 0));
        assertFalse(AppKeyMaps.learnable(KeyEvent.KEYCODE_HOME, 0));
        assertFalse(AppKeyMaps.learnable(KeyEvent.KEYCODE_VOLUME_UP, 0));
        assertFalse(AppKeyMaps.learnable(KeyEvent.KEYCODE_UNKNOWN, 0));
    }

    @Test
    public void learnableAcceptsVendorAndUnknownWithScan() {
        assertTrue(AppKeyMaps.learnable(4001, 365));
        assertTrue(AppKeyMaps.learnable(4065, 245));
        assertTrue(AppKeyMaps.learnable(KeyEvent.KEYCODE_UNKNOWN, 490));
        assertTrue(AppKeyMaps.learnable(KeyEvent.KEYCODE_NOTIFICATION, 233));
    }

    @Test
    public void applyIsNoOpWithoutContext() {
        Map<Integer, String> keys = new HashMap<>();
        keys.put(4001, "com.google.android.tv");
        AppKeyMaps.apply(null, keys, null);
        assertEquals("com.google.android.tv", keys.get(4001));
    }
}
