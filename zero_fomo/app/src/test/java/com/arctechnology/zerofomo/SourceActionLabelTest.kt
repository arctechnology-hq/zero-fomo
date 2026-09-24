package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.ui.detail.sourceActionLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceActionLabelTest {

    @Test fun `ticket sellers get tickets`() {
        assertEquals("Get Tickets", sourceActionLabel("https://www.ticketmaster.com/event/0D006"))
        assertEquals("Get Tickets", sourceActionLabel("https://seatgeek.com/x-tickets/123"))
        assertEquals("Get Tickets", sourceActionLabel("https://www.eventbrite.com/e/abc-tickets-1"))
        assertEquals("Get Tickets", sourceActionLabel("https://tickets.venue.bs/junkanoo"))
    }

    @Test fun `social posts read as posts`() {
        assertEquals("View post", sourceActionLabel("https://www.facebook.com/events/1234"))
        assertEquals("View post", sourceActionLabel("https://www.reddit.com/r/bahamas/comments/x/"))
        assertEquals("View post", sourceActionLabel("https://t.me/nassauevents/55"))
        assertEquals("View post", sourceActionLabel("https://www.instagram.com/p/abc/"))
    }

    @Test fun `everything else is more info`() {
        assertEquals("More info", sourceActionLabel("https://www.bahamas.com/events/regatta"))
        assertEquals("More info", sourceActionLabel("https://tribune242.com/news/2026/09/24/party"))
    }

    @Test fun `garbage never crashes`() {
        assertEquals("More info", sourceActionLabel(""))
        assertEquals("More info", sourceActionLabel("not a url at all"))
        assertEquals("More info", sourceActionLabel("http://[::1"))
    }
}
