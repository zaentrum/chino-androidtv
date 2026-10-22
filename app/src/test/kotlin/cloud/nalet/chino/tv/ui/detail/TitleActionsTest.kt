package cloud.nalet.chino.tv.ui.detail

import cloud.nalet.chino.tv.data.api.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Test

/** The title page's add-to-list control says the state in its name. */
class TitleActionsTest {
    private val watchlist = Watchlist(id = "w", name = "Watchlist", isDefault = true)

    @Test
    fun `add to a list, in your watchlist, in two lists`() {
        assertEquals("Add to a list", listControlName(emptySet(), watchlist))
        assertEquals("In your Watchlist", listControlName(setOf("w"), watchlist))
        assertEquals("In 2 lists", listControlName(setOf("w", "films"), watchlist))
        assertEquals("In 1 list", listControlName(setOf("films"), watchlist))
        // The default list by its own name, as the server has it.
        assertEquals("In your To watch", listControlName(setOf("w"), watchlist.copy(name = "To watch")))
        assertEquals("In 1 list", listControlName(setOf("w"), null))
    }
}
