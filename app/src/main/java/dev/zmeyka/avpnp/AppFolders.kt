package dev.zmeyka.avpnp

import android.content.Context

/**
 * Named folders of apps.
 *
 * Folders are an editing convenience, not a routing primitive: assigning a destination to a folder
 * writes that destination to every member, so fifty apps can be wired at once. Membership itself
 * changes nothing about how packets flow.
 */
object AppFolders {

    data class Folder(val id: String, val name: String, val members: List<String>)

    private const val FILE = "avpnp"
    private const val NAME_PREFIX = "folder_"
    private const val NAME_SUFFIX = "_name"
    private const val MEMBERS_SUFFIX = "_members"

    fun all(context: Context): List<Folder> =
        prefs(context).all.keys
            .filter { it.startsWith(NAME_PREFIX) && it.endsWith(NAME_SUFFIX) }
            .map { it.removePrefix(NAME_PREFIX).removeSuffix(NAME_SUFFIX) }
            .map { id -> Folder(id, name(context, id), members(context, id)) }
            .sortedBy { it.name.lowercase() }

    fun create(context: Context, name: String): String {
        val id = System.currentTimeMillis().toString()
        prefs(context).edit().putString(NAME_PREFIX + id + NAME_SUFFIX, name).apply()
        return id
    }

    fun rename(context: Context, id: String, name: String) {
        prefs(context).edit().putString(NAME_PREFIX + id + NAME_SUFFIX, name).apply()
    }

    fun delete(context: Context, id: String) {
        prefs(context).edit()
            .remove(NAME_PREFIX + id + NAME_SUFFIX)
            .remove(NAME_PREFIX + id + MEMBERS_SUFFIX)
            .apply()
    }

    fun setMembers(context: Context, id: String, members: List<String>) {
        prefs(context).edit()
            .putString(NAME_PREFIX + id + MEMBERS_SUFFIX, members.joinToString(","))
            .apply()
    }

    private fun name(context: Context, id: String): String =
        prefs(context).getString(NAME_PREFIX + id + NAME_SUFFIX, "") ?: ""

    private fun members(context: Context, id: String): List<String> =
        prefs(context).getString(NAME_PREFIX + id + MEMBERS_SUFFIX, "")
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
