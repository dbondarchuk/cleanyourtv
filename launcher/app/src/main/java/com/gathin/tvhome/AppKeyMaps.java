package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.Map;

/** map_remote_button: learned app_keys from the hold-OK "Map to remote button" row. */
final class AppKeyMaps {
    private static final String PREFS = "app_key_maps";
    private static volatile String learningPackage;

    private AppKeyMaps() {
    }

    static void beginLearn(String pkg) {
        learningPackage = pkg == null || pkg.isEmpty() ? null : pkg;
    }

    static boolean isLearning() {
        return learningPackage != null;
    }

    /** While learning, saves the next learnable key-down for the waiting package. */
    static boolean consumeLearn(Context ctx, KeyEvent event) {
        String pkg = learningPackage;
        if (pkg == null) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            learningPackage = null;
            Toast.makeText(ctx, R.string.remote_map_cancelled, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!learnable(keyCode, scanCode)) {
            Toast.makeText(ctx, R.string.remote_map_not_a_button, Toast.LENGTH_SHORT).show();
            return true;
        }
        learningPackage = null;
        if (!put(ctx, keyCode, scanCode, pkg)) {
            Toast.makeText(ctx, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return true;
        }
        HomeKeyService.reloadAppKeyMaps();
        InputKeyService.reloadAppKeyMaps();
        Toast.makeText(ctx, R.string.remote_map_saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    static boolean has(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (Object value : prefs(ctx).getAll().values()) {
            if (pkg.equals(value)) return true;
        }
        return false;
    }

    static boolean clearPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        SharedPreferences prefs = prefs(ctx);
        SharedPreferences.Editor edit = prefs.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (pkg.equals(e.getValue())) {
                edit.remove(e.getKey());
                changed = true;
            }
        }
        return changed && edit.commit();
    }

    static void apply(Context ctx, Map<Integer, String> keycodes, Map<Integer, String> scancodes) {
        if (ctx == null || (keycodes == null && scancodes == null)) return;
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            String pkg = ((String) v).trim();
            if (pkg.isEmpty()) continue;
            String name = e.getKey();
            try {
                if (name.startsWith("k") && keycodes != null) {
                    int code = Integer.parseInt(name.substring(1));
                    if (code > 0) keycodes.put(code, pkg);
                } else if (name.startsWith("s") && scancodes != null) {
                    int code = Integer.parseInt(name.substring(1));
                    if (code > 0) scancodes.put(code, pkg);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    static boolean learnable(int keyCode, int scanCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_ALL_APPS:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_SOFT_SLEEP:
                return false;
            case KeyEvent.KEYCODE_UNKNOWN:
                return scanCode > 0;
            default:
                return keyCode > 0 || scanCode > 0;
        }
    }

    static boolean put(Context ctx, int keyCode, int scanCode, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) return false;
        SharedPreferences.Editor edit = prefs(ctx).edit();
        if (keyCode > 0 && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            edit.putString("k" + keyCode, pkg);
        } else if (scanCode > 0) {
            edit.putString("s" + scanCode, pkg);
        } else {
            return false;
        }
        return edit.commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
