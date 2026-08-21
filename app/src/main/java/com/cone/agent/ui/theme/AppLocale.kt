package com.cone.agent.ui.theme

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Applies the user-selected UI [languageTag] ("zh" / "en" / "ja" / …) to the whole Compose tree so
 * every `stringResource(...)` resolves against the matching `values-<lang>/`. Switching is instant
 * (just a recomposition) — no Activity recreation, no AppCompat.
 *
 * CRITICAL — why a [ContextWrapper] around the Activity, and NOT a bare `createConfigurationContext()`:
 *  - `stringResource` resolves via `LocalContext.current.resources`, so to localize the UI we must
 *    provide a [LocalContext] whose `getResources()` is the localized one.
 *  - But Hilt's `hiltViewModel()` resolves the Activity via `LocalContext.current.findActivity()`,
 *    which walks the `ContextWrapper.baseContext` chain looking for an [android.app.Activity].
 *  - A bare `context.createConfigurationContext(config)` returns a `ContextImpl` whose chain does NOT
 *    lead back to the Activity → `findActivity()` returns null → Hilt's HiltViewModelFactory cannot
 *    cast it to ComponentActivity and the app crashes immediately on launch.
 *  - Wrapping the Activity itself (so `baseContext` == the Activity) keeps that chain intact while
 *    still returning localized resources. This is the whole point — do not "simplify" it back to
 *    providing `createConfigurationContext(...)` directly as the LocalContext.
 */
@Composable
fun AppLocale(languageTag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val locale = remember(languageTag) { Locale.forLanguageTag(languageTag) }

    val localizedContext = remember(base, baseConfig, locale) {
        val config = Configuration(baseConfig).apply { setLocale(locale) }
        val localizedResources = base.createConfigurationContext(config).resources
        // Wrap the *Activity* (base) so Context.findActivity() still resolves it for Hilt /
        // ViewModelStoreOwner, while resources/getString() resolve against the selected locale.
        object : ContextWrapper(base) {
            override fun getResources(): Resources = localizedResources
        }
    }
    val config = remember(baseConfig, locale) {
        Configuration(baseConfig).apply { setLocale(locale) }
    }

    // Keep the process default locale in sync too — affects TTS, the speech recognizer and any
    // locale-aware formatting outside the Compose tree.
    remember(locale) { Locale.setDefault(locale); locale }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides config,
        content = content,
    )
}
