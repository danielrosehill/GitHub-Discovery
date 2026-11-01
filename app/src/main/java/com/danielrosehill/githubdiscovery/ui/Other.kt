package com.danielrosehill.githubdiscovery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielrosehill.githubdiscovery.api.Repo

private val SUGGESTED = listOf(
    "ai-agents", "llm", "mcp", "rag", "data-labeling", "annotation-tool", "self-hosted",
    "speech-recognition", "text-to-speech", "computer-vision", "android", "cli", "home-assistant", "awesome-list",
)

@Composable
fun TopicsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.topicQuery, onValueChange = { vm.topicQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Find topics, e.g. annotation") },
            leadingIcon = { Icon(Icons.Filled.Tag, null) },
            trailingIcon = {
                if (vm.topicQuery.isNotEmpty()) IconButton(onClick = { vm.topicQuery = ""; vm.loadTopics("") }) {
                    Icon(Icons.Filled.Close, "Clear")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.loadTopics() }),
        )
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SUGGESTED.forEach { t -> AssistChip(onClick = { vm.searchTopic(t) }, label = { Text("#$t") }) }
        }
        Text(
            if (vm.topicQuery.isBlank()) "Featured topics" else "Topics matching \"${vm.topicQuery}\"",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        when {
            vm.topicsLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            vm.topicsError != null -> Text(vm.topicsError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(vm.topics, key = { it.name }) { t ->
                    ListItem(
                        modifier = Modifier.clickable { vm.searchTopic(t.name) },
                        headlineContent = { Text(t.displayName ?: t.name, fontWeight = FontWeight.SemiBold) },
                        overlineContent = { Text("#${t.name}" + if (t.featured) " · featured" else "") },
                        supportingContent = t.description?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SavedScreen(vm: AppViewModel) {
    if (vm.saved.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No saved searches yet. Set up a search on Discover and tap the bookmark icon.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.saved, key = { it.name }) { s ->
            ListItem(
                modifier = Modifier.clickable { vm.runSaved(s) },
                headlineContent = { Text(s.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(s.filters.build() + " · " + s.sort.label, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { IconButton(onClick = { vm.deleteSaved(s) }) { Icon(Icons.Filled.Delete, "Delete") } },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ListsSheet(vm: AppViewModel, repo: Repo) {
    val lists = vm.lists
    var selected by remember(repo.id, lists == null) {
        mutableStateOf(lists?.filter { repo.id in it.itemIds }?.map { it.id }?.toSet() ?: emptySet())
    }
    var newName by remember { mutableStateOf("") }
    var newPrivate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = vm::closeLists) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Add to lists", style = MaterialTheme.typography.titleLarge)
            Text(repo.nameWithOwner, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (lists == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (vm.listsLoading) CircularProgressIndicator() else TextButton(onClick = vm::refreshLists) { Text("Retry") }
                }
            } else {
                if (lists.isEmpty()) Text("You have no Lists yet. Create one below.")
                lists.sortedBy { it.name.lowercase() }.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (l.id in selected) selected - l.id else selected + l.id
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(l.id in selected, { selected = if (it) selected + l.id else selected - l.id })
                        Text(l.name, Modifier.weight(1f))
                        if (l.isPrivate) Icon(Icons.Filled.Lock, "Private", Modifier.size(16.dp))
                        Text("  ${l.itemIds.size}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("New list") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { newPrivate = !newPrivate }) {
                        Icon(if (newPrivate) Icons.Filled.Lock else Icons.Filled.LockOpen, if (newPrivate) "Private" else "Public")
                    }
                    IconButton(enabled = newName.isNotBlank(), onClick = { vm.createList(newName, newPrivate); newName = "" }) {
                        Icon(Icons.Filled.Add, "Create list")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.saveLists(repo, selected) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                if (!repo.starred) Text("Adding to a list also stars the repo.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
