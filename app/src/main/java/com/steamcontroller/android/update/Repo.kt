package com.steamcontroller.android.update

/**
 * The GitHub repository this build belongs to.
 *
 * This is a SHIELD TV fork that publishes its own releases, so the update checker and the
 * in-app GitHub link must both point here rather than upstream. Pointing them upstream
 * would offer an APK signed with a different key, which Android refuses to install over
 * this one, and would silently pull the build back to the non-SHIELD variant.
 *
 * Single source of truth — change OWNER here and both call sites follow.
 */
object Repo {
    const val OWNER = "SonicDX12"
    const val NAME = "SteamController-Android"

    const val WEB_URL = "https://github.com/$OWNER/$NAME"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"
}
