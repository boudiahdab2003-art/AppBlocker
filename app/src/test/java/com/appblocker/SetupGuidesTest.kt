package com.appblocker

import com.appblocker.data.SetupGuide
import com.appblocker.data.SetupGuides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup pictures — the mapping only, since a JVM test cannot look at an image.
 *
 * What it can protect is the part that fails silently: a ring that has drifted outside its picture
 * (drawn off-screen, so the user is shown an un-annotated screenshot and told to tap "the ringed
 * row"), a caption that went missing (the picture then carries the whole instruction, and a screen
 * reader carries none of it), and a brand quietly losing its guide.
 */
class SetupGuidesTest {

    private val brands = listOf("Xiaomi", "Samsung", "Huawei", "Oppo", "Vivo", "", "Nothing Phone")
    private val illustrated = listOf("accessibility", "overlay")

    @Test
    fun `every brand gets pictures for every illustrated permission`() {
        for (brand in brands) {
            for (key in illustrated) {
                assertNotNull(
                    "$brand/$key has no guide — a brand with no set of its own must fall back to " +
                        "the stock one rather than showing nothing",
                    SetupGuides.forPermission(key, brand),
                )
            }
        }
    }

    /** A permission we have never photographed must say so rather than showing another one's. */
    @Test
    fun `a permission with no pictures returns null`() {
        assertNull(SetupGuides.forPermission("battery", "Samsung"))
        assertNull(SetupGuides.forPermission("notifications", ""))
    }

    @Test
    fun `every ring sits inside its picture`() {
        forEveryShot { brand, key, guide ->
            guide.shots.forEachIndexed { i, shot ->
                val r = shot.ring
                val where = "$brand/$key shot $i"
                assertTrue("$where: left..right outside 0..1", r.left >= 0f && r.right <= 1f)
                assertTrue("$where: top..bottom outside 0..1", r.top >= 0f && r.bottom <= 1f)
                assertTrue("$where: zero or negative width", r.right > r.left)
                assertTrue("$where: zero or negative height", r.bottom > r.top)
            }
        }
    }

    /**
     * **The caption has to stand alone.** It is what a screen reader announces and what remains if
     * the image fails to load, so a picture carrying the instruction by itself is a step nobody
     * can follow.
     */
    @Test
    fun `every shot has a caption and every guide says where it was photographed`() {
        forEveryShot { brand, key, guide ->
            assertTrue("$brand/$key: blank takenOn", guide.takenOn.isNotBlank())
            guide.shots.forEachIndexed { i, shot ->
                assertTrue("$brand/$key shot $i: blank caption", shot.caption.isNotBlank())
                assertTrue(
                    "$brand/$key shot $i: caption too short to be an instruction",
                    shot.caption.length >= 12,
                )
            }
        }
    }

    @Test
    fun `every shot points at a real drawable`() {
        forEveryShot { brand, key, guide ->
            guide.shots.forEachIndexed { i, shot ->
                assertTrue("$brand/$key shot $i: no image", shot.image != 0)
            }
        }
    }

    /**
     * Until a brand is photographed it shares the stock set, and that is only honest because the
     * "yours may look different" line goes with it. If a brand ever gets its own pictures, this
     * says nothing about them — it only pins that the fallback is a real, complete fallback.
     */
    @Test
    fun `an unphotographed brand gets the same guide as stock Android`() {
        val stock = SetupGuides.forPermission("accessibility", "")!!
        val samsung = SetupGuides.forPermission("accessibility", "Samsung")!!
        if (samsung === stock) {
            assertEquals(stock.shots.size, samsung.shots.size)
            assertTrue(
                "a shared guide must admit it was taken on another phone",
                samsung.takenOn.contains("may look", ignoreCase = true),
            )
        }
    }

    private fun forEveryShot(check: (String, String, SetupGuide) -> Unit) {
        for (brand in brands) {
            for (key in illustrated) {
                SetupGuides.forPermission(key, brand)?.let { check(brand, key, it) }
            }
        }
    }
}
