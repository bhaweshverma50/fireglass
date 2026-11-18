package dev.bhawesh.tvhome;

import android.os.SystemClock;

import java.util.Locale;

/**
 * System dialogs own the screen. Fireglass brings itself back when Amazon's home surfaces (the Home
 * button), covers Amazon's pages with a loading screen, and lifts its task forward; any of that over
 * "Allow USB debugging?", an install prompt or a permission dialog hides it, or throws it behind us
 * where nobody can press Allow. So whenever one of these opens, every bounce, cover and screensaver
 * stands down until it is gone.
 *
 * An explicit list, not a guess from window shape: packages that only ever show system prompts, and
 * for Amazon's own packages (which also hold the normal home, search and settings) just the
 * activity names that are prompts.
 */
final class SystemUi {
    private SystemUi() { }

    /** Every activity of these is a system prompt or panel. */
    private static final String[] PACKAGES = {
            "android",                                  // chooser, system alert dialogs
            "com.android.systemui",                     // UsbDebuggingActivity, USB access, recents
            "com.android.packageinstaller",             // install, uninstall, unknown sources (Fire OS 5)
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",         // runtime permissions (Fire OS 7)
            "com.google.android.permissioncontroller",
            "com.android.settings",                     // stock settings dialogs, if present
            "com.amazon.dcp",                           // device registration, account sign-in
            "com.amazon.tv.oobe",                       // setup / account prompts
    };

    /** In any com.amazon.* package: activity names (case-insensitive fragments) that are prompts. */
    private static final String[] AMAZON_PROMPTS = {
            "usbdebug", "adbdebug", "debuggingconfirm", "usbaccess", "unknownsource", "installunknown",
            "permissiondialog", "grantpermission", "signin", "sign_in", "registration", "accountpicker",
            "authoriz", "confirmcredential", "confirmpin", "parentalcontrol", "pinentry",
    };

    /** Actions Android resolves to system prompts, when a START line carries no usable component. */
    private static final String[] ACTIONS = {
            "android.intent.action.INSTALL_PACKAGE", "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.DELETE", "android.content.pm.action.REQUEST_PERMISSIONS",
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES", "android.hardware.usb.action.USB_ACCESSORY_ATTACHED",
    };

    private static final long HOLD_MS = 75_000;
    private static volatile long until;
    private static long lastHome;

    /** Is this activity (package, class or null) a system prompt? Class may be abbreviated (".usb.Foo"). */
    static boolean isPrompt(String pkg, String cls) {
        if (pkg == null) return false;
        // These two also own non-prompt windows (panels, popups); their prompts are activities and dialogs.
        if ((pkg.equals("android") || pkg.equals("com.android.systemui")) && cls != null
                && !cls.contains("Activity") && !cls.contains("Dialog")) return false;
        for (String p : PACKAGES) if (pkg.equals(p)) return true;
        if (pkg.startsWith("com.amazon.") && cls != null) {
            String c = cls.toLowerCase(Locale.US);
            for (String f : AMAZON_PROMPTS) if (c.contains(f)) return true;
        }
        return false;
    }

    /** True when a log line's intent action is one of the prompts (see ACTIONS). */
    static boolean isPromptAction(String line) {
        for (String a : ACTIONS) if (line.contains("act=" + a)) return true;
        return false;
    }

    /** A prompt just opened: stand down for a while (until it's dismissed or another app is used). */
    static void opened() {
        until = SystemClock.uptimeMillis() + HOLD_MS;
        lastHome = 0;
    }

    /** The prompt is gone (Fireglass is in front again on its own) or the user has gone elsewhere. */
    static void gone() {
        until = 0;
    }

    static boolean active() {
        return SystemClock.uptimeMillis() < until;
    }

    /**
     * The Home button arrived while a prompt is up. The first press is let be (the prompt may be
     * what raised Amazon's home, and Home from a prompt is the user's choice to leave it); a second,
     * separate press ends the hold and goes home as usual. Returns true when the bounce must not run.
     */
    static synchronized boolean holdHome() {
        long now = SystemClock.uptimeMillis();
        if (now >= until) return false;
        if (lastHome != 0 && now - lastHome > 2500) {
            gone();
            return false;
        }
        if (lastHome == 0) lastHome = now;
        return true;
    }
}
