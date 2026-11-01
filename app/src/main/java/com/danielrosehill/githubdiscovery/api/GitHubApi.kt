package com.danielrosehill.githubdiscovery.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Repo(
    val id: String,
    val nameWithOwner: String,
    val url: String,
    val description: String?,
    val stars: Int,
    val forks: Int,
    val language: String?,
    val languageColor: String?,
    val createdAt: String,
    val pushedAt: String?,
    val license: String?,
    val archived: Boolean,
    val topics: List<String>,
    val starred: Boolean,
)

data class SearchPage(val total: Int, val repos: List<Repo>, val endCursor: String?, val hasNext: Boolean)

data class StarList(val id: String, val name: String, val isPrivate: Boolean, val itemIds: Set<String>)

data class Topic(val name: String, val displayName: String?, val description: String?, val featured: Boolean, val curated: Boolean)

class GitHubApi(private val token: String) {

    suspend fun viewerLogin(): String =
        Http.graphql(token, "query{ viewer{ login } }", JSONObject())
            .getJSONObject("viewer").getString("login")

    suspend fun search(query: String, after: String?, first: Int = 50): SearchPage {
        val vars = JSONObject().put("q", query).put("first", first).put("after", after ?: JSONObject.NULL)
        val s = Http.graphql(token, SEARCH, vars).getJSONObject("search")
        val nodes = s.getJSONArray("nodes")
        val repos = (0 until nodes.length()).mapNotNull { parseRepo(nodes.getJSONObject(it)) }
        val pi = s.getJSONObject("pageInfo")
        return SearchPage(s.getInt("repositoryCount"), repos, pi.str("endCursor"), pi.getBoolean("hasNextPage"))
    }

    suspend fun setStar(repoId: String, star: Boolean) {
        val m = if (star) "addStar" else "removeStar"
        Http.graphql(
            token,
            "mutation(\$id:ID!){ $m(input:{starrableId:\$id}){ starrable{ viewerHasStarred } } }",
            JSONObject().put("id", repoId),
        )
    }

    /** All of the viewer's Star Lists with the IDs of the repos in each (GitHub has no per-repo membership field). */
    suspend fun lists(): List<StarList> {
        val out = mutableListOf<StarList>()
        var after: String? = null
        do {
            val l = Http.graphql(token, LISTS, JSONObject().put("after", after ?: JSONObject.NULL))
                .getJSONObject("viewer").getJSONObject("lists")
            val nodes = l.getJSONArray("nodes")
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                val items = n.getJSONObject("items")
                val ids = idsOf(items.getJSONArray("nodes")).toMutableSet()
                var ipi = items.getJSONObject("pageInfo")
                while (ipi.getBoolean("hasNextPage")) {
                    val more = Http.graphql(
                        token, LIST_ITEMS,
                        JSONObject().put("id", n.getString("id")).put("after", ipi.getString("endCursor")),
                    ).getJSONObject("node").getJSONObject("items")
                    ids += idsOf(more.getJSONArray("nodes"))
                    ipi = more.getJSONObject("pageInfo")
                }
                out += StarList(n.getString("id"), n.getString("name"), n.optBoolean("isPrivate"), ids)
            }
            val pi = l.getJSONObject("pageInfo")
            after = if (pi.getBoolean("hasNextPage")) pi.getString("endCursor") else null
        } while (after != null)
        return out
    }

    /** Sets the complete set of lists [repoId] belongs to. */
    suspend fun setLists(repoId: String, listIds: Collection<String>) {
        Http.graphql(
            token,
            "mutation(\$i:ID!,\$l:[ID!]!){ updateUserListsForItem(input:{itemId:\$i, listIds:\$l}){ lists{ id } } }",
            JSONObject().put("i", repoId).put("l", JSONArray(listIds.toList())),
        )
    }

    suspend fun createList(name: String, isPrivate: Boolean): StarList {
        val l = Http.graphql(
            token,
            "mutation(\$n:String!,\$p:Boolean){ createUserList(input:{name:\$n, isPrivate:\$p}){ list{ id name isPrivate } } }",
            JSONObject().put("n", name).put("p", isPrivate),
        ).getJSONObject("createUserList").getJSONObject("list")
        return StarList(l.getString("id"), l.getString("name"), l.optBoolean("isPrivate"), emptySet())
    }

    suspend fun searchTopics(q: String): List<Topic> {
        val j = Http.get(
            "https://api.github.com/search/topics?per_page=50&q=" + URLEncoder.encode(q, "UTF-8"),
            token,
        )
        val items = j.getJSONArray("items")
        return (0 until items.length()).map {
            val t = items.getJSONObject(it)
            Topic(
                t.getString("name"), t.str("display_name"),
                t.str("short_description") ?: t.str("description"),
                t.optBoolean("featured"), t.optBoolean("curated"),
            )
        }
    }

    private fun idsOf(a: JSONArray) =
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("id") }

    private fun parseRepo(n: JSONObject): Repo? {
        val id = n.str("id") ?: return null
        val topicNodes = n.optJSONObject("repositoryTopics")?.optJSONArray("nodes")
        val topics = if (topicNodes == null) emptyList() else
            (0 until topicNodes.length()).map { topicNodes.getJSONObject(it).getJSONObject("topic").getString("name") }
        val lang = n.optJSONObject("primaryLanguage")
        return Repo(
            id = id,
            nameWithOwner = n.getString("nameWithOwner"),
            url = n.getString("url"),
            description = n.str("description")?.takeIf { it.isNotBlank() },
            stars = n.optInt("stargazerCount"),
            forks = n.optInt("forkCount"),
            language = lang?.str("name"),
            languageColor = lang?.str("color"),
            createdAt = n.getString("createdAt"),
            pushedAt = n.str("pushedAt"),
            license = n.optJSONObject("licenseInfo")?.str("spdxId")?.takeIf { it != "NOASSERTION" },
            archived = n.optBoolean("isArchived"),
            topics = topics,
            starred = n.optBoolean("viewerHasStarred"),
        )
    }

    private companion object {
        val SEARCH = """
            query(${'$'}q:String!, ${'$'}first:Int!, ${'$'}after:String){
              search(query:${'$'}q, type:REPOSITORY, first:${'$'}first, after:${'$'}after){
                repositoryCount
                pageInfo{ hasNextPage endCursor }
                nodes{ ... on Repository{
                  id nameWithOwner url description stargazerCount forkCount isArchived
                  createdAt pushedAt viewerHasStarred
                  primaryLanguage{ name color }
                  licenseInfo{ spdxId }
                  repositoryTopics(first:8){ nodes{ topic{ name } } }
                } }
              }
            }
        """.trimIndent()

        val LISTS = """
            query(${'$'}after:String){ viewer{ lists(first:50, after:${'$'}after){
              pageInfo{ hasNextPage endCursor }
              nodes{ id name isPrivate items(first:100){ pageInfo{ hasNextPage endCursor } nodes{ ... on Repository{ id } } } }
            } } }
        """.trimIndent()

        val LIST_ITEMS = """
            query(${'$'}id:ID!, ${'$'}after:String){ node(id:${'$'}id){ ... on UserList{
              items(first:100, after:${'$'}after){ pageInfo{ hasNextPage endCursor } nodes{ ... on Repository{ id } } }
            } } }
        """.trimIndent()
    }
}
