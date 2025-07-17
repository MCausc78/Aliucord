/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins

import android.content.Context
import android.net.Uri
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.*
import com.aliucord.utils.lazyField
import com.discord.api.auth.OAuthScope
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreMessageReplies
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.lazy.memberlist.ChannelMemberList
import com.discord.utilities.lazy.memberlist.MemberListRow
import com.discord.views.OAuthPermissionViews
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.linecorp.apng.decoder.Apng
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach

@Suppress("PrivatePropertyName")
internal class DiscordFixes : CorePlugin(Manifest("DiscordFixes")) {
    private val f_memberListGroups by lazyField<ChannelMemberList>("groups")

    override val isHidden = true
    override val isRequired = true

    override fun load(context: Context) {
        fixAuthorizedApps()
        fixGifPreviews()
        fixMemberList()
        fixPrivateChannelListScrolling()
        fixShowingReplyMentions()
        fixStickerCrash()
    }

    private fun fixAuthorizedApps() {
        Patcher.addPatch(
            OAuthPermissionViews::class.java.getMethod(
                "a",
                TextView::class.java,
                OAuthScope::class.java
            ),
            Hook {
                if (!it.hasThrowable()) return@Hook

                val exc = it.throwable
                if (exc is OAuthPermissionViews.InvalidScopeException) {
                    val scope = exc.a()
                    (it.args[0] as TextView).text = scope
                    it.throwable = null
                }
            }
        )
    }

    private fun fixGifPreviews() {
        patcher.after<EmbedResourceUtils>("getPreviewUrls", String::class.java, Int::class.java, Int::class.java, Boolean::class.java) {
            // it.args[3] is a boolean that indicates
            // if the gif should be animated (for example no autoplay setting)
            if (!(it.args[3] as Boolean)) return@after
            @Suppress("UNCHECKED_CAST")
            val result = (it.result as List<String>).toMutableList()

            val uri = Uri.parse(result[0])
            if (uri.path?.endsWith(".gif") == true) {
                val newUri = uri.buildUpon().encodedQuery("format=gif").build()
                result[0] = newUri.toString()

                it.result = result
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixMemberList() {
        patcher.after<ChannelMemberList>("setGroups", List::class.java, Function1::class.java) {
            val rows = this.rows
            val groupsMap = f_memberListGroups[this] as Map<String, MemberListRow>
            this.groupIndices.forEach { (idx, id) -> rows[idx] = groupsMap[id] }
        }
    }

    private fun fixPrivateChannelListScrolling() {
        var unpatch: Runnable? = null

        unpatch = patcher.after<WidgetChannelsList>("configureUI", WidgetChannelListModel::class.java)
        { (_, model: WidgetChannelListModel) ->
            if (!model.isGuildSelected && model.items.size > 1) {
                val manager = WidgetChannelsList.`access$getBinding$p`(this).c.layoutManager!! as LinearLayoutManager
                if (manager.findFirstVisibleItemPosition() != 0) {
                    manager.scrollToPosition(0)

                    unpatch?.run()
                }
            }
        }
    }

    private fun fixShowingReplyMentions() {
        val user = User::class.java
        val guildMember = GuildMember::class.java
        val messageEntry = MessageEntry::class.java
        val messageItem = WidgetChatListAdapterItemMessage::class.java
        val configureReplyAvatar = messageItem.getDeclaredMethod("configureReplyAvatar", user,
            guildMember).apply { isAccessible = true }
        val configureReplyName = messageItem.getDeclaredMethod("configureReplyName", String::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val getAuthorTextColor = messageItem.getDeclaredMethod("getAuthorTextColor", guildMember)
            .apply { isAccessible = true }
        val replyHolder = messageItem.getDeclaredField("replyHolder").apply { isAccessible = true }
        val replyLinkItem = messageItem.getDeclaredField("replyLinkItem").apply { isAccessible = true }

        patcher.patch(messageItem.getDeclaredMethod("configureReplyPreview", messageEntry), PreHook {
            if (replyHolder[it.thisObject] == null || replyLinkItem[it.thisObject] == null) return@PreHook

            val messageEntry = it.args[0] as MessageEntry
            val replyData = messageEntry.replyData
            if (replyData == null || replyData.messageState !is StoreMessageReplies.MessageState.Loaded) return@PreHook

            val refEntry = replyData.messageEntry
            val refAuthor = CoreUser(refEntry.message.author)
            val refAuthorMember = refEntry.author
            configureReplyAvatar(it.thisObject, refAuthor, refAuthorMember)

            val refAuthorId = refAuthor.id
            configureReplyName(
                it.thisObject,
                refEntry.nickOrUsernames[refAuthorId] ?: refAuthor.username,
                getAuthorTextColor(it.thisObject, refAuthorMember),
                messageEntry.message.mentions.any { u -> u.id == refAuthorId }
            )
        })

        // configureReplyAuthor was mostly reimplemented in our patch in configureReplyPreview,
        // however it is also used for interactions, so we prevent it from calling only when interactionAuthor is null
        patcher.patch(messageItem.getDeclaredMethod("configureReplyAuthor", user, guildMember, messageEntry), PreHook {
            if ((it.args[2] as MessageEntry).interactionAuthor == null) it.result = null
        })

    }

    private fun fixStickerCrash() {
        patcher.before<Apng>(
            Integer::class.javaPrimitiveType!!,
            Integer::class.javaPrimitiveType!!,
            Integer::class.javaPrimitiveType!!,
            Integer::class.javaPrimitiveType!!,
            IntArray::class.java,
            Integer::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
        ) { param ->
            val durations = param.args[4] as IntArray
            for ((index, duration) in durations.withIndex())
                if (duration <= 10)
                    durations[index] = 100
        }
    }

    override fun start(context: Context) {}
    override fun stop(context: Context) {}
}
