package com.romm.androidtv.storage.android

import android.content.Context
import com.romm.androidtv.storage.AppPaths
import java.nio.file.Path

/**
 * Thin adapter: implements [AppPaths] for Android's app-private storage.
 *
 * Android has no separate XDG config/data/state split, so all durable roots map
 * to `context.filesDir`; only [cacheDir] maps to the rebuildable `context.cacheDir`.
 *
 *  - [configDir] / [dataDir] / [stateDir] → `context.filesDir.toPath()`
 *  - [cacheDir] → `context.cacheDir.toPath()`
 *
 * `File.toPath()` requires API 26+, which is satisfied by the app's `minSdk = 26`.
 *
 * The caller will later supply the [Context] at construction from the app scope.
 */
class AndroidAppPaths(context: Context) : AppPaths {
    override val configDir: Path = context.filesDir.toPath()
    override val dataDir: Path = context.filesDir.toPath()
    override val stateDir: Path = context.filesDir.toPath()
    override val cacheDir: Path = context.cacheDir.toPath()
}
