package com.arctechnology.zerofomo.ui.detail

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

fun sourceActionLabel(url: String): String {
    val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
        ?: return "More info"
    return when {
        TICKET_HOSTS.any { host.contains(it) } -> "Get Tickets"
        POST_HOSTS.any { host == it || host.endsWith(".$it") } -> "View post"
        else -> "More info"
    }
}
