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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.outlined.Update
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

    // Keep the current page filled; GitHub pages are 50, ours are PAGE_SIZE, so fetch on demand.
    LaunchedEffect(vm.page, vm.results.size, vm.loading, vm.endReached) { if (!vm.loading) vm.ensurePageLoaded() }
    LaunchedEffect(vm.page, vm.activeQuery) { listState.scrollToItem(0) }

    val from = vm.page * PAGE_SIZE
    val pageItems = if (from < vm.results.size) vm.results.subList(from, minOf(from + PAGE_SIZE, vm.results.size)).toList() else emptyList()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = keywords,
                onValueChange = { keywords = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search repositories") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (keywords.isNotEmpty()) IconButton(onClick = { keywords = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
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

        // Presets (trending surrogates) | sort, on one scrolling row
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Preset.entries.forEach { p ->
                val selected = vm.sort == p.sort && vm.filters.created == p.created
                FilterChip(selected, { vm.runSearch(vm.filters.copy(keywords = keywords, created = p.created), p.sort) }, { Text(p.label) },
                    leadingIcon = if (selected) {{ Icon(Icons.Filled.LocalFireDepartment, null, Modifier.size(16.dp)) }} else null)
            }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            Icon(Icons.AutoMirrored.Filled.Sort, "Sort", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
            items(pageItems, key = { it.id }) { repo -> RepoCard(repo, vm) }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        vm.searchError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(vm.searchError!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.runSearch() }) { Text("Retry") }
                        }
                        pageItems.size < PAGE_SIZE && vm.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                        vm.endReached && vm.results.isEmpty() -> Text("No repositories match.")
                        vm.endReached && vm.results.size >= 1000 && vm.page == (vm.pageCount ?: 0) - 1 ->
                            Text("GitHub stops at 1,000 results per search. Narrow the filters, or use Newest, which has no limit.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (vm.results.isNotEmpty()) PagerBar(vm)
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

/** Bottom pager: ‹  1 … 4 [5] 6 … 50  › . With no known total (Newest) the last number is "…". */
@Composable
private fun PagerBar(vm: AppViewModel) {
    val count = vm.pageCount
    val cur = vm.page
    val hasNext = count?.let { cur < it - 1 } ?: true
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { vm.goToPage(cur - 1) }, enabled = cur > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val last = count?.minus(1)
                val shown = sortedSetOf(0, cur).apply {
                    if (cur > 0) add(cur - 1)
                    if (last == null || cur + 1 <= last) add(cur + 1)
                    last?.let { add(it) }
                }.filter { it >= 0 && (last == null || it <= last) }
                var prev = -1
                shown.forEach { p ->
                    if (p - prev > 1) Ellipsis()
                    PageNumber(p, p == cur) { vm.goToPage(p) }
                    prev = p
                }
                if (last == null) Ellipsis()
            }
            IconButton(onClick = { vm.goToPage(cur + 1) }, enabled = hasNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
        }
    }
}

@Composable
private fun Ellipsis() = Text("…", Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PageNumber(p: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.padding(horizontal = 2.dp).defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text("${p + 1}", color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

private fun activeFilterCount(f: Filters): Int = listOf(
    f.inName || f.inDescription || f.inReadme || f.inTopics, f.topics.isNotBlank(), f.language.isNotBlank(),
    f.owner.isNotBlank(), f.minStars.isNotBlank() || f.maxStars.isNotBlank(), f.minForks.isNotBlank(),
    f.created != DateRange.ANY, f.pushed != DateRange.ANY, f.license.isNotBlank(),
    f.goodFirstIssues.isNotBlank(), !f.hideArchived, f.includeForks, f.extra.isNotBlank(),
).count { it }

@Composable
private fun StatusLine(vm: AppViewModel) {
    var showQuery by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val from = vm.page * PAGE_SIZE + 1
            val to = minOf((vm.page + 1) * PAGE_SIZE, vm.results.size)
            val range = if (vm.results.size >= from) "$from–$to" else ""
            val count = when {
                vm.sort == Sort.NEWEST && vm.newestReachedDate != null -> "$range · back to ${shortDate(vm.newestReachedDate.toString() + "T00:00:00Z")}"
                vm.total != null -> "$range of %,d".format(vm.total) + if (vm.total!! > 1000) " · first 1,000 browsable" else ""
                else -> ""
            }
            Text(count, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.activeQuery.isNotBlank()) Text(
                if (showQuery) "Hide query" else "Query",
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { showQuery = !showQuery }.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showQuery) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                vm.activeQuery, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RepoCard(repo: Repo, vm: AppViewModel) {
    val ctx = LocalContext.current
    val (owner, name) = repo.nameWithOwner.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = { openUrl(ctx, repo.url) },
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OwnerAvatar(owner)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(owner, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { vm.openLists(repo) }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to list") }
                IconButton(onClick = { vm.toggleStar(repo) }) {
                    if (repo.starred) Icon(Icons.Filled.Star, "Unstar", tint = Color(0xFFE3B341))
                    else Icon(Icons.Outlined.StarBorder, "Star")
                }
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, end = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Stat(Icons.Outlined.StarBorder, compact(repo.stars))
                Stat(Icons.Filled.CallSplit, compact(repo.forks))
                repo.language?.let { lang ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val c = repo.languageColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                        Box(Modifier.size(10.dp).background(c ?: MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Meta(lang)
                    }
                }
                repo.pushedAt?.let { Stat(Icons.Outlined.Update, ago(it)) }
                if (repo.archived) Meta("archived", MaterialTheme.colorScheme.error)
            }
            Text(
                listOfNotNull("created " + shortDate(repo.createdAt), repo.license).joinToString("  ·  "),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
            if (repo.topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(Modifier.padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repo.topics.take(5).forEach { t -> TopicPill(t) { vm.searchTopic(t) } }
                    if (repo.topics.size > 5) Meta("+${repo.topics.size - 5}")
                }
            }
        }
    }
}

@Composable
private fun OwnerAvatar(owner: String) {
    val hue = ((owner.hashCode() % 360) + 360) % 360
    val bg = Color.hsv(hue.toFloat(), 0.45f, 0.75f)
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(owner.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun TopicPill(t: String, onClick: () -> Unit) {
    Text(
        t,
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Meta(text)
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0).replace(".0k", "k")
    else -> "$n"
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
