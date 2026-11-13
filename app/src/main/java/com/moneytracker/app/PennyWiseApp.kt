package com.moneytracker.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import com.moneytracker.app.navigation.AppLock
import com.moneytracker.app.navigation.Home
import com.moneytracker.app.navigation.OnBoarding
import com.moneytracker.app.navigation.PennyWiseNavHost
import com.moneytracker.app.ui.theme.PennyWiseTheme
import com.moneytracker.app.ui.viewmodel.AppLockViewModel
import com.moneytracker.app.ui.viewmodel.ThemeViewModel
import com.moneytracker.app.widget.WidgetRefresher

@Composable
fun PennyWiseApp(
    themeViewModel: ThemeViewModel? = null,
    appLockViewModel: AppLockViewModel? = null,
    editTransactionId: Long? = null,
    openAddTransaction: com.moneytracker.app.navigation.AddTransaction? = null,
    onEditComplete: (() -> Unit)? = null,
    onAddTransactionShortcutHandled: (() -> Unit)? = null,
    sharedText: String? = null,
    onSharedTextHandled: (() -> Unit)? = null,
    widgetDestination: Any? = null,
    onWidgetDestinationHandled: (() -> Unit)? = null
) {
    val owner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current ?: error("No ViewModelStoreOwner")
    val actualThemeViewModel: ThemeViewModel = themeViewModel ?: hiltViewModel(viewModelStoreOwner = owner, key = null)
    val actualAppLockViewModel: AppLockViewModel = appLockViewModel ?: hiltViewModel(viewModelStoreOwner = owner, key = null)

    val themeUiState by actualThemeViewModel.themeUiState.collectAsStateWithLifecycle()
    val appLockUiState by actualAppLockViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val darkTheme = themeUiState.isDarkTheme ?: isSystemInDarkTheme()

    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe lifecycle events and refresh lock state when app resumes from background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Leaving the app starts the timeout clock: measured from the unlock it would
            // lock someone who had been using the app for longer than the timeout.
            if (event == Lifecycle.Event.ON_STOP) actualAppLockViewModel.onAppLeft()
            if (event == Lifecycle.Event.ON_RESUME &&
                (context.applicationContext as? com.moneytracker.app.PennyWiseApplication)?.consumeBackgrounded() != false
            ) {
                // App came to foreground - check if it should be locked
                actualAppLockViewModel.refreshLockState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Wait for preferences to load before deciding start destination
    if (themeUiState.isLoading) {
        return
    }

    // Migrate existing users: if they already have SMS permission from the old flow,
    // auto-complete onboarding so they aren't forced through it on update
    LaunchedEffect(themeUiState.hasCompletedOnboarding) {
        if (!themeUiState.hasCompletedOnboarding) {
            val hasSmsPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasSmsPermission) {
                actualThemeViewModel.markOnboardingCompleted()
            }
        }
    }

    // Set correct start destination based on loaded preferences
    val startDestination: Any = remember(themeUiState.hasCompletedOnboarding) {
        if (themeUiState.hasCompletedOnboarding) Home else OnBoarding
    }

    // Observe lock state changes and navigate to lock screen if needed
    // But don't navigate when user is actively in Settings configuring app lock
    val showLockScreen = {
        navController.navigate(AppLock) {
            // Don't add to back stack, force lock screen
            popUpTo(navController.graph.startDestinationId) { inclusive = false }
            launchSingleTop = true
        }
    }
    LaunchedEffect(appLockUiState.isLocked, appLockUiState.isLockEnabled) {
        if (appLockUiState.isLocked && appLockUiState.isLockEnabled) {
            val currentRoute = navController.currentDestination?.route
            // Don't navigate if already on lock screen or in Settings (user is configuring)
            if (currentRoute != AppLock::class.qualifiedName &&
                currentRoute != com.moneytracker.app.navigation.Settings::class.qualifiedName) {
                showLockScreen()
            }
        }
    }
    
    // Every outside entry point — notification and Recent-widget transaction taps,
    // the Add widget/shortcut, "Share to PennyWise" and goal widget taps (#914) —
    // opens a screen with private data, so they all wait for onboarding and an
    // unlocked app (see NavigateWhenUnlocked).
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    NavigateWhenUnlocked(
        request = editTransactionId,
        onboarded = themeUiState.hasCompletedOnboarding,
        lock = appLockUiState,
        currentRoute = currentRoute,
        refreshLock = actualAppLockViewModel::refreshLockState,
        showLockScreen = showLockScreen,
    ) { transactionId ->
        navController.navigate(com.moneytracker.app.navigation.TransactionDetail(transactionId)) { launchSingleTop = true }
        // Consume now, or leaving the detail screen would re-run this and reopen it.
        onEditComplete?.invoke()
    }
    NavigateWhenUnlocked(
        request = openAddTransaction,
        onboarded = themeUiState.hasCompletedOnboarding,
        lock = appLockUiState,
        currentRoute = currentRoute,
        refreshLock = actualAppLockViewModel::refreshLockState,
        showLockScreen = showLockScreen,
    ) { add ->
        navController.navigate(add) { launchSingleTop = true }
        onAddTransactionShortcutHandled?.invoke()
    }
    NavigateWhenUnlocked(
        request = sharedText,
        onboarded = themeUiState.hasCompletedOnboarding,
        lock = appLockUiState,
        currentRoute = currentRoute,
        refreshLock = actualAppLockViewModel::refreshLockState,
        showLockScreen = showLockScreen,
    ) { text ->
        navController.navigate(com.moneytracker.app.navigation.AddTransaction(sharedText = text))
        onSharedTextHandled?.invoke()
    }
    NavigateWhenUnlocked(
        request = widgetDestination,
        onboarded = themeUiState.hasCompletedOnboarding,
        lock = appLockUiState,
        currentRoute = currentRoute,
        refreshLock = actualAppLockViewModel::refreshLockState,
        showLockScreen = showLockScreen,
    ) { destination ->
        // Replace the same kind of screen only when it's on top, so a repeated "+"
        // starts New goal again; anything under it (an unsaved edit) stays.
        val top = navController.currentBackStackEntry?.destination
        navController.navigate(destination) {
            if (top != null && top.hasRoute(destination::class)) popUpTo(top.id) { inclusive = true }
        }
        onWidgetDestinationHandled?.invoke()
    }

    // Keep widgets current when app launches (covers upgrades/installs with existing widgets)
    LaunchedEffect(Unit) {
        WidgetRefresher.refreshTransactionWidgets(context.applicationContext)
    }

    PennyWiseTheme(
        darkTheme = darkTheme,
        dynamicColor = themeUiState.isDynamicColorEnabled,
        themeStyle = themeUiState.themeStyle,
        accentColor = themeUiState.accentColor,
        isAmoledMode = themeUiState.isAmoledMode,
        appFont = themeUiState.appFont,
        blurEffects = themeUiState.blurEffectsEnabled
    ) {
        PennyWiseNavHost(
            navController = navController,
            themeViewModel = actualThemeViewModel,
            startDestination = startDestination,
            onEditComplete = onEditComplete ?: {}
        )
    }
}

/**
 * Runs [navigate] for [request] once onboarding is done and the app is unlocked,
 * so the screen never shows above the lock screen or skips setup. The lock state
 * must come from a check that ran *after* the request arrived (the resume re-check
 * can still be pending), and the lock screen must have navigated away, otherwise
 * its unlock/lock navigation would pop the new screen. The effect re-runs as each
 * of these settles.
 *
 * Passing through the lock screen also counts as unlocked: it can only be left by
 * authenticating (back is blocked), while [lock] (the activity's view model, not
 * the lock screen's) can keep `isLocked` after an unlock with the "Immediately"
 * timeout. So when a fresh check says locked and the lock screen isn't showing,
 * this shows it ([showLockScreen]) rather than waiting for one that never comes.
 */
@Composable
private fun <T : Any> NavigateWhenUnlocked(
    request: T?,
    onboarded: Boolean,
    lock: com.moneytracker.app.ui.viewmodel.AppLockUiState,
    currentRoute: String?,
    refreshLock: () -> Unit,
    showLockScreen: () -> Unit,
    navigate: (T) -> Unit,
) {
    val checksAtRequest = remember(request) { lock.checks }
    var passedLockScreen by remember(request) { mutableStateOf(false) }
    // Guarantee a lock check after that baseline: on a cold start the startup
    // checks can finish before this composes (themeUiState loads first), and no
    // other check would follow, leaving the request pending forever.
    LaunchedEffect(request) {
        if (request != null) refreshLock()
    }
    LaunchedEffect(request, onboarded, lock.checks, lock.isLocked, currentRoute) {
        val pending = request ?: return@LaunchedEffect
        val onLockScreen = currentRoute == AppLock::class.qualifiedName
        if (onLockScreen) passedLockScreen = true
        if (!onboarded || lock.checks <= checksAtRequest || currentRoute == null || onLockScreen) return@LaunchedEffect
        val ready = !lock.isLocked || passedLockScreen
        if (ready) navigate(pending) else showLockScreen()
    }
}
