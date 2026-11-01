package com.danielrosehill.githubdiscovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielrosehill.githubdiscovery.api.DateRange
import com.danielrosehill.githubdiscovery.api.Filters
import com.danielrosehill.githubdiscovery.api.Preset
import com.danielrosehill.githubdiscovery.api.Repo
import com.danielrosehill.githubdiscovery.api.Sort
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    var keywords by remember(vm.filters) { mutableStateOf(vm.filters.keywords) }
    var showFilters by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.activeQuery, vm.results.isEmpty()) { if (vm.results.isEmpty()) listState.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, vm.results.size) { if (nearEnd && vm.results.isNotEmpty()) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search repos") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (keywords.isNotEmpty()) IconButton(onClick = { keywords = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch(vm.filters.copy(keywords = keywords)) }),
            )
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (activeFilterCount(vm.filters) > 0) Badge { Text("${activeFilterCount(vm.filters)}") } }) {
                    Icon(Icons.Filled.Tune, "Advanced filters")
                }
            }
            IconButton(onClick = { showSave = true }) { Icon(Icons.Outlined.BookmarkAdd, "Save search") }
        }

        // Presets: trending surrogates
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Preset.entries.forEach { p ->
                val selected = vm.sort == p.sort && vm.filters.created == p.created
                FilterChip(selected, { vm.runSearch(vm.filters.copy(keywords = keywords, created = p.created), p.sort) }, { Text(p.label) })
            }
        }
        // Sort
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort", style = MaterialTheme.typography.labelMedium)
            Sort.entries.forEach { s ->
                FilterChip(vm.sort == s, { vm.runSearch(vm.filters.copy(keywords = keywords), s) }, { Text(s.label) })
            }
        }
        // Active topic chips (tap to remove)
        val topicList = vm.filters.topicList()
        if (topicList.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                topicList.forEach { t ->
                    InputChip(
                        selected = true,
                        onClick = { vm.runSearch(vm.filters.copy(topics = (topicList - t).joinToString(" "))) },
                        label = { Text("#$t") },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
            }
        }
        StatusLine(vm)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.results, key = { it.id }) { repo ->
                RepoCard(repo, vm)
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        vm.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                        vm.searchError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(vm.searchError!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.runSearch() }) { Text("Retry") }
                        }
                        vm.endReached && vm.results.isEmpty() -> Text("No repositories match.")
                        vm.endReached && vm.results.size >= 1000 ->
                            Text("GitHub stops at 1,000 results per search. Narrow the filters, or use Newest, which has no limit.",
                                style = MaterialTheme.typography.bodySmall)
                        vm.endReached -> Text("End of results", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showFilters) FilterSheet(vm.filters.copy(keywords = keywords), onDismiss = { showFilters = false }) {
        showFilters = false
        vm.runSearch(it)
    }
    if (showSave) SaveDialog(onDismiss = { showSave = false }) { name ->
        showSave = false
        vm.runSearch(vm.filters.copy(keywords = keywords))
        vm.saveCurrent(name)
    }
}

private fun activeFilterCount(f: Filters): Int = listOf(
    f.inName || f.inDescription || f.inReadme || f.inTopics, f.topics.isNotBlank(), f.language.isNotBlank(),
    f.owner.isNotBlank(), f.minStars.isNotBlank() || f.maxStars.isNotBlank(), f.minForks.isNotBlank(),
    f.created != DateRange.ANY, f.pushed != DateRange.ANY, f.license.isNotBlank(),
    f.goodFirstIssues.isNotBlank(), !f.hideArchived, f.includeForks, f.extra.isNotBlank(),
).count { it }

@Composable
private fun StatusLine(vm: AppViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val count = when {
            vm.sort == Sort.NEWEST && vm.newestReachedDate != null ->
                "${vm.results.size} shown · back to ${vm.newestReachedDate}"
            vm.total != null -> "%,d repositories".format(vm.total) + if (vm.total!! > 1000) " (first 1,000 browsable)" else ""
            else -> ""
        }
        if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                vm.activeQuery, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RepoCard(repo: Repo, vm: AppViewModel) {
    val ctx = LocalContext.current
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { openUrl(ctx, repo.url) },
    ) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    val (owner, name) = repo.nameWithOwner.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    Text(owner, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { vm.openLists(repo) }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to list") }
                IconButton(onClick = { vm.toggleStar(repo) }) {
                    if (repo.starred) Icon(Icons.Filled.Star, "Unstar", tint = Color(0xFFE3B341))
                    else Icon(Icons.Outlined.StarBorder, "Star")
                }
            }
            repo.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp))
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Meta("★ %,d".format(repo.stars))
                Meta("⑂ %,d".format(repo.forks))
                repo.language?.let { lang ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val c = repo.languageColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                        if (c != null) Box(Modifier.size(9.dp).background(c, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Meta(lang)
                    }
                }
                Meta("created " + shortDate(repo.createdAt))
                repo.pushedAt?.let { Meta("pushed " + ago(it)) }
                repo.license?.let { Meta(it) }
                if (repo.archived) Meta("archived", MaterialTheme.colorScheme.error)
            }
            if (repo.topics.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repo.topics.forEach { t ->
                        SuggestionChip(onClick = { vm.searchTopic(t) }, label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun shortDate(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(dateFmt) }.getOrDefault(iso.take(10))

private fun ago(iso: String): String = runCatching {
    val d = Duration.between(Instant.parse(iso), Instant.now())
    when {
        d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        d.toDays() < 30 -> "${d.toDays()}d ago"
        d.toDays() < 365 -> "${d.toDays() / 30}mo ago"
        else -> "${d.toDays() / 365}y ago"
    }
}.getOrDefault(iso.take(10))

// ---------------- Advanced filters ----------------

private val LANGS = listOf("Python", "TypeScript", "JavaScript", "Go", "Rust", "Kotlin", "Java", "C++", "Swift", "Jupyter Notebook", "Shell")
private val LICENSES = listOf("mit", "apache-2.0", "gpl-3.0", "agpl-3.0", "bsd-3-clause", "mpl-2.0")

@Composable
private fun FilterSheet(initial: Filters, onDismiss: () -> Unit, onApply: (Filters) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Advanced search", style = MaterialTheme.typography.titleLarge)
            Field("Keywords", f.keywords) { f = f.copy(keywords = it) }
            Label("Match keywords in")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f.inName, { f = f.copy(inName = !f.inName) }, { Text("Name") })
                FilterChip(f.inDescription, { f = f.copy(inDescription = !f.inDescription) }, { Text("Description") })
                FilterChip(f.inReadme, { f = f.copy(inReadme = !f.inReadme) }, { Text("README") })
                FilterChip(f.inTopics, { f = f.copy(inTopics = !f.inTopics) }, { Text("Topics") })
            }
            Field("Topics (space-separated, all must match)", f.topics) { f = f.copy(topics = it) }
            Field("Language", f.language) { f = f.copy(language = it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LANGS.forEach { l -> FilterChip(f.language.equals(l, true), { f = f.copy(language = if (f.language.equals(l, true)) "" else l) }, { Text(l) }) }
            }
            Field("Owner (user or org)", f.owner) { f = f.copy(owner = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Min stars", f.minStars, Modifier.weight(1f), number = true) { f = f.copy(minStars = it) }
                Field("Max stars", f.maxStars, Modifier.weight(1f), number = true) { f = f.copy(maxStars = it) }
                Field("Min forks", f.minForks, Modifier.weight(1f), number = true) { f = f.copy(minForks = it) }
            }
            Label("Created within")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DateRange.entries.forEach { r -> FilterChip(f.created == r, { f = f.copy(created = r) }, { Text(r.label) }) }
            }
            Label("Last pushed within")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DateRange.entries.forEach { r -> FilterChip(f.pushed == r, { f = f.copy(pushed = r) }, { Text(r.label) }) }
            }
            Field("License", f.license) { f = f.copy(license = it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LICENSES.forEach { l -> FilterChip(f.license == l, { f = f.copy(license = if (f.license == l) "" else l) }, { Text(l) }) }
            }
            Field("Min good-first-issues", f.goodFirstIssues, number = true) { f = f.copy(goodFirstIssues = it) }
            SwitchRow("Hide archived repos", f.hideArchived) { f = f.copy(hideArchived = it) }
            SwitchRow("Include forks", f.includeForks) { f = f.copy(includeForks = it) }
            Field("Extra qualifiers (any GitHub search syntax)", f.extra) { f = f.copy(extra = it) }
            Text("e.g. size:<5000  followers:>100  mirror:false  is:template  NOT tutorial",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(f.build(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { f = Filters() }, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = { onApply(f) }, modifier = Modifier.weight(1f)) { Text("Search") }
            }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this search") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
