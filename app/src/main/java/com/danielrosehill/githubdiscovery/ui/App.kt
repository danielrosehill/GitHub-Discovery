package com.danielrosehill.githubdiscovery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

fun openUrl(ctx: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url))
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    when (val a = vm.auth) {
        is AuthState.SignedIn -> MainScaffold(vm, a.login)
        else -> SignInScreen(vm, a)
    }
}

@Composable
private fun SignInScreen(vm: AppViewModel, state: AuthState) {
    val ctx = LocalContext.current
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.TravelExplore, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("GitHub Discovery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Browse topics, dig into the long tail with full advanced search, and star repos into your Lists.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(32.dp))
            when (state) {
                is AuthState.AwaitingApproval -> {
                    Text("Enter this code on GitHub:", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(state.userCode, fontSize = 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("GitHub code", state.userCode))
                        openUrl(ctx, state.verificationUri)
                    }) { Text("Copy code and open GitHub") }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = vm::cancelSignIn) { Text("Cancel") }
                }
                AuthState.Starting -> CircularProgressIndicator()
                else -> {
                    Button(onClick = vm::startSignIn) { Text("Sign in with GitHub") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Uses GitHub's device sign-in. The app can star repos and manage your Lists; it can't edit code.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            vm.authError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel, login: String) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    BackHandler(vm.tab != Tab.DISCOVER) { vm.tab = Tab.DISCOVER }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Discovery") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.AccountCircle, "Account") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Signed in as $login") }, onClick = { menu = false }, enabled = false)
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                            onClick = { menu = false; vm.signOut() },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(vm.tab == Tab.DISCOVER, { vm.tab = Tab.DISCOVER }, { Icon(Icons.Filled.TravelExplore, null) }, label = { Text("Discover") })
                NavigationBarItem(vm.tab == Tab.TOPICS, { vm.tab = Tab.TOPICS }, { Icon(Icons.Filled.Tag, null) }, label = { Text("Topics") })
                NavigationBarItem(vm.tab == Tab.SAVED, { vm.tab = Tab.SAVED }, { Icon(Icons.Filled.Bookmarks, null) }, label = { Text("Saved") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                Tab.DISCOVER -> DiscoverScreen(vm)
                Tab.TOPICS -> TopicsScreen(vm)
                Tab.SAVED -> SavedScreen(vm)
            }
        }
    }
    vm.listTarget?.let { ListsSheet(vm, it) }
}
