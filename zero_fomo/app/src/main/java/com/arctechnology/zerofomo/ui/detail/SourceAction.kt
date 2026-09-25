package com.arctechnology.zerofomo.ui.detail

import androidx.annotation.StringRes
import com.arctechnology.zerofomo.R
import java.net.URI

/** Ticket sellers get "Get Tickets"; a Facebook / Reddit / Telegram post is
 *  "View post"; anything else (a venue site, a news article) is "More info".
 *  Community and Reddit-sourced events made a blanket "Get Tickets" wrong. */
private val TICKET_HOSTS = listOf(
    "ticketmaster", "seatgeek", "eventbrite", "ticketweb", "dice.fm", "axs.com", "etix",
    "tixr", "universe.com", "showclix", "frontgatetickets", "ticketek", "ticketpro",
    "eventnoire", "tickets",
)
private val POST_HOSTS = listOf(
    "facebook.com", "fb.com", "fb.me", "instagram.com", "reddit.com", "redd.it", "t.me",
    "telegram.me", "discord.com", "discord.gg", "x.com", "twitter.com", "tiktok.com",
    "threads.net", "whatsapp.com",
)

/** Resource id for the button label — what [DetailScreen] renders. */
@StringRes
fun sourceActionLabelRes(url: String): Int {
    val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
        ?: return R.string.action_more_info
    return when {
        TICKET_HOSTS.any { host.contains(it) } -> R.string.action_get_tickets
        POST_HOSTS.any { host == it || host.endsWith(".$it") } -> R.string.action_view_post
        else -> R.string.action_more_info
    }
}

/** English label, kept for context-less (unit test) callers; the UI reads
 *  [sourceActionLabelRes] through stringResource instead. */
fun sourceActionLabel(url: String): String = when (sourceActionLabelRes(url)) {
    R.string.action_get_tickets -> "Get Tickets"
    R.string.action_view_post -> "View post"
    else -> "More info"
}
