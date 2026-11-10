package dev.andrii.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Replies captured from the real battery. */
class BmsTest {
    private fun decode(basic: String, cells: String) =
        Bms.decode(Bms.payload(basic.hexToByteArray(), Bms.BASIC), Bms.payload(cells.hexToByteArray(), Bms.CELLS))

    @Test
    fun decodesAReading() {
        val status = decode(
            "dd030019053200001a8927100025000000000000000010430304010b77fdd477",
            "dd0400080d330d340d330d33fef777",
        )
        assertEquals(13.30, status.voltage)
        assertEquals(67, status.soc)
        assertEquals(listOf(3379, 3380, 3379, 3379), status.cellsMv)
    }

    @Test
    fun rejectsTheBogusBlockTheBatterySometimesSends() {
        // Both checksums are valid, yet it claims 2.56 V across 32 cells at 2%.
        assertFailsWith<IllegalArgumentException> {
            decode(
                "dd030017010000000d00271000002c9e0000000000001002002000fea877",
                "dd040040000001f403e803e800020b860cb40cd90d000d6b2ee00e100a8c000100250ce9ed7b0000" +
                    "00".repeat(28) + "f5f377",
            )
        }
    }
}
