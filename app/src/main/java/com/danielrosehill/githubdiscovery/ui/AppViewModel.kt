package com.danielrosehill.githubdiscovery.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielrosehill.githubdiscovery.api.ApiException
import com.danielrosehill.githubdiscovery.api.DeviceFlow
import com.danielrosehill.githubdiscovery.api.Filters
import com.danielrosehill.githubdiscovery.api.GitHubApi
import com.danielrosehill.githubdiscovery.api.Preset
import com.danielrosehill.githubdiscovery.api.Repo
import com.danielrosehill.githubdiscovery.api.SavedSearch
import com.danielrosehill.githubdiscovery.api.Sort
import com.danielrosehill.githubdiscovery.api.StarList
import com.danielrosehill.githubdiscovery.api.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDate

enum class Tab { DISCOVER, TOPICS, SAVED }

sealed interface AuthState {
    data object SignedOut : AuthState
    data object Starting : AuthState
    data class AwaitingApproval(val userCode: String, val verificationUri: String) : AuthState
    data class SignedIn(val login: String) : AuthState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("github_discovery", Context.MODE_PRIVATE)
    private var api: GitHubApi? = prefs.getString(KEY_TOKEN, null)?.let { GitHubApi(it) }

    var auth by mutableStateOf<AuthState>(
        if (api != null) AuthState.SignedIn(prefs.getString(KEY_LOGIN, "") ?: "") else AuthState.SignedOut,
    )
        private set
    var authError by mutableStateOf<String?>(null)
        private set
    private var authJob: Job? = null

    var tab by mutableStateOf(Tab.DISCOVER)
    var message by mutableStateOf<String?>(null)

    // ---- Discover ----
    var filters by mutableStateOf(Filters())
        private set
    var sort by mutableStateOf(Sort.STARS)
        private set
    val results = mutableStateListOf<Repo>()
    var total by mutableStateOf<Int?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var newestReachedDate by mutableStateOf<LocalDate?>(null)
        private set
    /** The exact query string sent to GitHub (shown so it can be copied to desktop search). */
    var activeQuery by mutableStateOf("")
        private set

    private var generation = 0
    private var cursor: String? = null
    private val seen = HashSet<String>()
    private var newest: NewestCursor? = null

    private class NewestCursor(var windowEnd: LocalDate, var size: Long, val floor: LocalDate, var cursor: String? = null)

    // ---- Lists ----
    var lists by mutableStateOf<List<StarList>?>(null)
        private set
    var listsLoading by mutableStateOf(false)
        private set
    var listTarget by mutableStateOf<Repo?>(null)
        private set

    // ---- Topics ----
    var topicQuery by mutableStateOf("")
    var topics by mutableStateOf<List<Topic>>(emptyList())
        private set
    var topicsLoading by mutableStateOf(false)
        private set
    var topicsError by mutableStateOf<String?>(null)
        private set

    // ---- Saved ----
    val saved = mutableStateListOf<SavedSearch>()

    init {
        prefs.getString(KEY_SAVED, null)?.let { s ->
            runCatching {
                val a = JSONArray(s)
                for (i in 0 until a.length()) saved += SavedSearch.fromJson(a.getJSONObject(i))
            }
        }
        if (api != null) {
            runSearch(Filters(created = Preset.HOT_WEEK.created), Preset.HOT_WEEK.sort)
            loadTopics("is:featured")
        }
    }

    // ================= Auth =================
    fun startSignIn() {
        authJob?.cancel()
        authError = null
        auth = AuthState.Starting
        authJob = viewModelScope.launch {
            try {
                val code = DeviceFlow.start()
                auth = AuthState.AwaitingApproval(code.userCode, code.verificationUri)
                val token = DeviceFlow.awaitToken(code)
                val client = GitHubApi(token)
                val login = client.viewerLogin()
                prefs.edit().putString(KEY_TOKEN, token).putString(KEY_LOGIN, login).apply()
                api = client
                auth = AuthState.SignedIn(login)
                runSearch(Filters(created = Preset.HOT_WEEK.created), Preset.HOT_WEEK.sort)
                loadTopics("is:featured")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                authError = e.message ?: "Sign-in failed"
                auth = AuthState.SignedOut
            }
        }
    }

    fun cancelSignIn() {
        authJob?.cancel()
        auth = AuthState.SignedOut
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
        api = null
        generation++
        results.clear()
        lists = null
        auth = AuthState.SignedOut
    }

    private fun handle(e: Exception): String {
        if (e is ApiException && e.unauthorized) signOut()
        return e.message ?: e.toString()
    }

    // ================= Search =================
    fun runSearch(f: Filters = filters, s: Sort = sort) {
        filters = f
        sort = s
        generation++
        results.clear()
        seen.clear()
        cursor = null
        total = null
        endReached = false
        searchError = null
        newestReachedDate = null
        loading = false
        newest = if (s == Sort.NEWEST) {
            val today = LocalDate.now()
            NewestCursor(windowEnd = today, size = 1, floor = f.created.since(today) ?: LocalDate.of(2008, 1, 1))
        } else null
        activeQuery = if (s == Sort.NEWEST) f.build() + "  (newest first)" else listOfNotNull(f.build(), s.qualifier).joinToString(" ").trim()
        loadMore()
    }


    fun searchTopic(topic: String) {
        tab = Tab.DISCOVER
        runSearch(Filters(topics = topic), if (sort == Sort.BEST) Sort.STARS else sort)
    }

    fun loadMore() {
        val client = api ?: return
        if (loading || endReached) return
        val gen = generation
        loading = true
        viewModelScope.launch {
            try {
                if (sort == Sort.NEWEST) loadNewest(client, gen) else {
                    val q = listOfNotNull(filters.build(), sort.qualifier).joinToString(" ").trim()
                    if (q.isBlank()) {
                        searchError = "Add a keyword, topic or filter to search."
                        endReached = true
                        return@launch
                    }
                    val page = client.search(q, cursor)
                    if (gen != generation) return@launch
                    total = page.total
                    results.addAll(page.repos.filter { seen.add(it.id) })
                    cursor = page.endCursor
                    // GitHub search stops at 1,000 results per query.
                    endReached = !page.hasNext || results.size >= 1000
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) searchError = handle(e)
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    /**
     * "Newest" emulation: GitHub search can't sort by creation date, so walk backwards through
     * `created:START..END` windows. Each window is small enough that sorting it client-side by
     * createdAt gives a true newest-first feed. Window size adapts: empty/sparse windows double,
     * crowded ones halve. Unlike sorted search this has no 1,000-result ceiling.
     */
    private suspend fun loadNewest(client: GitHubApi, gen: Int) {
        val st = newest ?: return
        val base = filters.build(includeCreated = false)
        var requests = 0
        while (requests < 20) {
            if (st.windowEnd < st.floor) {
                endReached = true
                return
            }
            val start = maxOf(st.windowEnd.minusDays(st.size - 1), st.floor)
            val q = "$base created:$start..${st.windowEnd}".trim()
            val page = client.search(q, st.cursor)
            requests++
            if (gen != generation) return
            val fresh = page.repos.sortedByDescending { it.createdAt }.filter { seen.add(it.id) }
            results.addAll(fresh)
            newestReachedDate = start
            if (page.hasNext) {
                st.cursor = page.endCursor
            } else {
                st.cursor = null
                st.windowEnd = start.minusDays(1)
                st.size = when {
                    page.total < 15 -> (st.size * 2).coerceAtMost(64)
                    page.total > 200 -> (st.size / 2).coerceAtLeast(1)
                    else -> st.size
                }
            }
            if (fresh.isNotEmpty()) return
        }
    }

    private fun updateRepo(id: String, f: (Repo) -> Repo) {
        val i = results.indexOfFirst { it.id == id }
        if (i >= 0) results[i] = f(results[i])
        listTarget?.let { if (it.id == id) listTarget = f(it) }
    }

    fun toggleStar(repo: Repo) {
        val client = api ?: return
        val target = !repo.starred
        updateRepo(repo.id) { it.copy(starred = target, stars = it.stars + if (target) 1 else -1) }
        viewModelScope.launch {
            try {
                client.setStar(repo.id, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateRepo(repo.id) { it.copy(starred = !target, stars = it.stars + if (target) -1 else 1) }
                message = handle(e)
            }
        }
    }

    // ================= Lists =================
    fun openLists(repo: Repo) {
        listTarget = repo
        refreshLists()
    }

    fun closeLists() {
        listTarget = null
    }

    fun refreshLists() {
        val client = api ?: return
        listsLoading = true
        viewModelScope.launch {
            try {
                lists = client.lists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = handle(e)
            } finally {
                listsLoading = false
            }
        }
    }

    fun createList(name: String, isPrivate: Boolean) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                val l = client.createList(name.trim(), isPrivate)
                lists = (lists ?: emptyList()) + l
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = handle(e)
            }
        }
    }

    fun saveLists(repo: Repo, selected: Set<String>) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                client.setLists(repo.id, selected)
                lists = lists?.map { l ->
                    l.copy(itemIds = if (l.id in selected) l.itemIds + repo.id else l.itemIds - repo.id)
                }
                // Lists on GitHub hold starred repos; make sure it is starred.
                if (selected.isNotEmpty() && !repo.starred) {
                    client.setStar(repo.id, true)
                    updateRepo(repo.id) { it.copy(starred = true, stars = it.stars + 1) }
                }
                message = if (selected.isEmpty()) "Removed from all lists" else
                    "Saved to ${selected.size} list${if (selected.size == 1) "" else "s"}"
                listTarget = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = handle(e)
            }
        }
    }

    // ================= Topics =================
    fun loadTopics(q: String = topicQuery) {
        val client = api ?: return
        val query = q.trim().ifEmpty { "is:featured" }
        topicsLoading = true
        topicsError = null
        viewModelScope.launch {
            try {
                topics = client.searchTopics(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                topicsError = handle(e)
            } finally {
                topicsLoading = false
            }
        }
    }

    // ================= Saved =================
    fun saveCurrent(name: String) {
        saved.removeAll { it.name == name }
        saved.add(0, SavedSearch(name, filters, sort))
        persistSaved()
        message = "Saved \"$name\""
    }

    fun deleteSaved(s: SavedSearch) {
        saved.remove(s)
        persistSaved()
    }

    fun runSaved(s: SavedSearch) {
        tab = Tab.DISCOVER
        runSearch(s.filters, s.sort)
    }

    private fun persistSaved() {
        val a = JSONArray()
        saved.forEach { a.put(it.toJson()) }
        prefs.edit().putString(KEY_SAVED, a.toString()).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
        const val KEY_SAVED = "saved_searches"
    }
}
