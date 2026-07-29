package com.slate.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile identity and discovery.
 *
 * Every binder call here is wrapped and degrades to "nothing" rather than throwing. A launcher
 * that crashes because UserManager was momentarily unavailable is worse than one that briefly
 * shows no work apps, and "nothing" is also the input the reconcile guards treat as
 * "cannot vouch" rather than "everything was uninstalled".
 */
object WorkProfiles {

    /** Sentinel for "main profile, or unresolvable" - ConcurrentHashMap forbids null values. */
    private const val NO_SERIAL = Long.MIN_VALUE

    /**
     * Constant for the process lifetime and NOT a binder call - it is derived from our own uid.
     * Comparing against it means the common case (a notification from the profile Slate runs in)
     * costs nothing and never touches UserManager.
     */
    private val selfUser: UserHandle = Process.myUserHandle()

    private val serialCache = ConcurrentHashMap<UserHandle, Long>()

    /**
     * Preference-space serial for [user], or null when the app belongs to the profile Slate runs
     * in. Null is what keeps [AppKey.of] returning a bare package name, so every string written
     * by a build that predates work-profile support round-trips byte-identically.
     *
     * Returns null on failure too. A launcher must not crash or drop a notification because
     * UserManager was momentarily unavailable, and null degrades to exactly today's behaviour.
     */
    fun serialFor(context: Context, user: UserHandle?): Long? {
        if (user == null || user == selfUser) return null

        serialCache[user]?.let { return it.takeIf { s -> s != NO_SERIAL } }

        val resolved = runCatching {
            (context.getSystemService(Context.USER_SERVICE) as UserManager)
                .getSerialNumberForUser(user)
        }.getOrNull()
            // getSerialNumberForUser yields -1 for a handle it does not recognise. Encoding
            // "pkg@-1" would invent an identity that can never match a real app, so treat it
            // as unresolvable and fall back to the bare package name.
            ?.takeIf { it != -1L }

        serialCache[user] = resolved ?: NO_SERIAL
        return resolved
    }

    /** Drops the handle-to-serial cache. Bounded by profile count, but cleared on disconnect. */
    fun clearCache() {
        serialCache.clear()
    }

    /**
     * Every profile other than Slate's own that should be surfaced, ordered by ascending serial.
     *
     * The ordering is load-bearing: [WorkProfile.label] is assigned by position, so a stable
     * sort is what stops a second profile appearing and silently renaming the first one's
     * marker. Serials are monotonic and never recycled, so ascending serial is also creation
     * order.
     *
     * Returns empty on any failure. Callers must treat empty as "could not determine", never as
     * "there are none" - see FolderStore.ReconcileScope.
     */
    fun profiles(context: Context): List<WorkProfile> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return emptyList()

        // getProfiles() INCLUDES the calling user. Without this filter every personal app
        // would enumerate twice.
        val others = runCatching { launcherApps.profiles }.getOrNull()
            ?.filter { it != selfUser }
            ?: return emptyList()

        val resolved = others.mapNotNull { handle ->
            val serial = runCatching { userManager.getSerialNumberForUser(handle) }
                .getOrNull()
                ?.takeIf { it != -1L }
                ?: return@mapNotNull null
            if (!isManagedProfile(launcherApps, handle)) return@mapNotNull null
            handle to serial
        }.sortedBy { it.second }

        return resolved.mapIndexed { index, (handle, serial) ->
            WorkProfile(
                handle = handle,
                serial = serial,
                label = if (index == 0) "Work" else "Work ${index + 1}",
                quiet = runCatching { userManager.isQuietModeEnabled(handle) }.getOrDefault(false)
            )
        }
    }

    /** Whether this device has any work profile at all. Gates the Settings rows. */
    fun hasAny(context: Context): Boolean = profiles(context).isNotEmpty()

    /**
     * Resolves a stored serial back to a live handle, or null if the system no longer knows it.
     *
     * Null is a POSITIVE signal that the profile is gone, which is the only thing that lets
     * reconcile ever prune a work key. Distinguish it carefully from "we could not ask": on
     * failure this returns null too, which is why the caller must have already established that
     * UserManager is reachable.
     */
    fun handleForSerial(context: Context, serial: Long): UserHandle? {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return null
        return runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull()
    }

    /**
     * Below API 35 no public API identifies an arbitrary UserHandle as managed, so every
     * non-self profile is treated as a work profile. The exposure is clone profiles on
     * Android 14 and 15, which would be grouped under a folder named "Work"; the mitigation is
     * the design itself - one rename or one delete, and it never recurs.
     *
     * getUserBadgedLabel inequality is deliberately NOT used as a probe: it is undocumented as
     * a classifier, unverified across OEMs and locales, and is an accessibility affordance.
     */
    private fun isManagedProfile(launcherApps: LauncherApps, handle: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
        return runCatching {
            launcherApps.getLauncherUserInfo(handle)?.userType ==
                UserManager.USER_TYPE_PROFILE_MANAGED
        }.getOrDefault(true)
    }
}
