package com.rembrowser.rembrowse.model

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a web notification becomes on Android: its key, text, icon and the registry of those on show. */
class SiteNotificationsTest {

    private fun content(
        title: String? = "Hello",
        text: String? = "World",
        tag: String? = "https://chat.example.org#tag:room1",
        imageUrl: String? = null,
        source: String? = "https://chat.example.org/room/1",
        silent: Boolean = false,
        principalOrigin: String? = null,
        actions: List<SiteNotificationAction> = emptyList(),
        uniqueId: () -> String = { "u1" }
    ) = SiteNotifications.contentOf(
        title = title,
        text = text,
        tag = tag,
        imageUrl = imageUrl,
        source = source,
        silent = silent,
        principalOrigin = principalOrigin,
        actions = actions,
        uniqueId = uniqueId
    )

    private fun dataUrl(bytes: ByteArray, mime: String = "image/png") =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    // --- contentOf ---

    @Test
    fun aNotificationShowsTitleTextAndTheSitesHost() {
        val c = content(title = "  New message ", text = " Hi there  ", source = "https://chat.example.org:8443/room/1")
        assertEquals("New message", c.title)
        assertEquals("Hi there", c.text)
        assertEquals("chat.example.org:8443", c.subText)
        assertEquals("https://chat.example.org:8443", c.origin)
        assertEquals("https://chat.example.org:8443/room/1", c.sourceUrl)
    }

    @Test
    fun aBlankTitleFallsBackToTheSitesNameAndABlankBodyIsDropped() {
        listOf(null, "", "   ").forEach { title ->
            assertEquals("chat.example.org", content(title = title).title)
        }
        listOf(null, "", " \n ").forEach { text -> assertNull(content(text = text).text) }
    }

    @Test
    fun silentIsPassedThrough() {
        assertTrue(content(silent = true).silent)
        assertFalse(content(silent = false).silent)
    }

    @Test
    fun aNotificationWithoutAWebPageHasNoOriginHostOrSourceAndAnEmptyTitleStaysEmpty() {
        listOf(null, "about:blank", "file:///sdcard/a.html", "javascript:alert(1)").forEach { source ->
            val c = content(title = null, source = source)
            assertNull("origin for $source", c.origin)
            assertNull("subText for $source", c.subText)
            assertNull("sourceUrl for $source", c.sourceUrl)
            assertEquals("", c.title)
        }
    }

    // --- origin ---

    @Test
    fun theSiteShownIsTheNotificationsOrigin() {
        // A service worker's notification: its source is the worker's script
        val c = content(source = "https://chat.example.org/sw.js", principalOrigin = "https://chat.example.org")
        assertEquals("chat.example.org", c.subText)
        assertEquals("https://chat.example.org", c.origin)
    }

    @Test
    fun theOriginsAttributesAreNotPartOfTheSite() {
        listOf(
            "https://chat.example.org^userContextId=1",
            "https://chat.example.org^privateBrowsingId=1",
            "https://chat.example.org^partitionKey=%28https%2Cexample.com%29"
        ).forEach { principal ->
            val c = content(principalOrigin = principal)
            assertEquals(principal, "chat.example.org", c.subText)
            assertEquals(principal, "https://chat.example.org", c.origin)
        }
        assertEquals("https://chat.example.org:8443", SiteNotifications.siteOriginOf("https://chat.example.org:8443^userContextId=2"))
    }

    @Test
    fun withoutAWebOriginTheSiteComesFromTheSource() {
        // Gecko's origin is empty for a notification restored from an older parcel, and an
        // extension's is its moz-extension: address
        listOf(null, "", "moz-extension://0d1c8a5e-3a1c-4b8e-9f1e-2b8a0e0c1d2e").forEach { principal ->
            val c = content(source = "https://chat.example.org/room/1", principalOrigin = principal)
            assertEquals("for $principal", "chat.example.org", c.subText)
        }
        val fromAnExtension = content(source = null, principalOrigin = "moz-extension://0d1c8a5e-3a1c-4b8e-9f1e-2b8a0e0c1d2e")
        assertNull(fromAnExtension.subText)
        assertNull(fromAnExtension.origin)
    }

    @Test
    fun theOriginChangesNeitherTheKeyNorThePageATapOpens() {
        val withOrigin = content(source = "https://chat.example.org/sw.js", principalOrigin = "https://chat.example.org^userContextId=1")
        val without = content(source = "https://chat.example.org/sw.js")
        assertEquals(without.key, withOrigin.key)
        assertEquals("https://chat.example.org/sw.js", withOrigin.sourceUrl)
    }

    // --- action buttons ---

    @Test
    fun aNotificationWithoutActionsHasNoButtons() {
        assertEquals(emptyList<SiteNotificationAction>(), content().actions)
    }

    @Test
    fun theButtonsAreThePagesActionsInItsOrder() {
        val c = content(
            actions = listOf(SiteNotificationAction("reply", "  Reply "), SiteNotificationAction("archive", "Archive"))
        )
        assertEquals(listOf(SiteNotificationAction("reply", "Reply"), SiteNotificationAction("archive", "Archive")), c.actions)
    }

    @Test
    fun atMostTwoButtonsAreShownAndBlankTitlesAreDropped() {
        val c = content(
            actions = listOf(
                SiteNotificationAction("blank", "  "),
                SiteNotificationAction("empty", ""),
                SiteNotificationAction("one", "One"),
                SiteNotificationAction("two", "Two"),
                SiteNotificationAction("three", "Three")
            )
        )
        assertEquals(listOf("one", "two"), c.actions.map { it.name })
        assertEquals(2, SiteNotifications.MAX_ACTIONS)
    }

    @Test
    fun aTapNamingAShownButtonPassesItsName() {
        val shown = listOf(SiteNotificationAction("reply", "Reply"), SiteNotificationAction("archive", "Archive"))
        assertEquals("reply", SiteNotifications.clickedAction("reply", shown))
        assertEquals("archive", SiteNotifications.clickedAction("archive", shown))
    }

    @Test
    fun anUnknownActionNameIsAPlainTap() {
        val shown = listOf(SiteNotificationAction("reply", "Reply"))
        assertNull(SiteNotifications.clickedAction(null, shown))
        assertNull(SiteNotifications.clickedAction("delete-everything", shown))
        assertNull(SiteNotifications.clickedAction("Reply", shown))
        assertNull(SiteNotifications.clickedAction("reply", emptyList()))
    }

    @Test
    fun aButtonThatIsNotShownCannotBeTapped() {
        // The page's third action has no button, so a tap can't name it
        val offered = listOf(
            SiteNotificationAction("one", "One"),
            SiteNotificationAction("two", "Two"),
            SiteNotificationAction("three", "Three")
        )
        assertNull(SiteNotifications.clickedAction("three", SiteNotifications.actionsOf(offered)))
    }

    @Test
    fun theIconComesFromTheImageUrlWhenItCanBeFetched() {
        assertEquals("https://cdn.example.org/i.png", content(imageUrl = "https://cdn.example.org/i.png").iconUrl)
        assertNull(content(imageUrl = "blob:https://chat.example.org/uuid").iconUrl)
        assertNull(content(imageUrl = null).iconUrl)
    }

    // --- the key: replacing and keeping apart ---

    @Test
    fun aNewNotificationWithTheSameTagReplacesTheOneBeforeEvenFromAnotherPageOfTheSite() {
        val a = content(source = "https://chat.example.org/room/1")
        val b = content(source = "https://chat.example.org/room/2?x=1")
        assertEquals(a.key, b.key)
    }

    @Test
    fun theSameTagOnTwoSitesNeverMeets() {
        val a = content(tag = "msg", source = "https://one.example.org/")
        val b = content(tag = "msg", source = "https://two.example.org/")
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun theSameTagOnHttpAndHttpsOfOneHostDoesNotMeet() {
        assertNotEquals(
            content(tag = "msg", source = "http://chat.example.org/").key,
            content(tag = "msg", source = "https://chat.example.org/").key
        )
    }

    @Test
    fun differentTagsOfOneSiteAreDifferentNotifications() {
        assertNotEquals(content(tag = "a").key, content(tag = "b").key)
    }

    @Test
    fun aNotificationWithoutATagNeverReplacesAnother() {
        val one = content(tag = null, uniqueId = { "id-1" })
        val two = content(tag = null, uniqueId = { "id-2" })
        assertNotEquals(one.key, two.key)
        // An empty tag is no tag
        assertNotEquals(content(tag = "", uniqueId = { "id-1" }).key, content(tag = "", uniqueId = { "id-2" }).key)
    }

    @Test
    fun aTaggedNotificationDoesNotAskForAUniqueId() {
        var asked = false
        content(tag = "t", uniqueId = { asked = true; "x" })
        assertFalse(asked)
    }

    @Test
    fun keyOfIsNullWithoutATagAndNormalisesTheOrigin() {
        assertNull(SiteNotifications.keyOf("https://chat.example.org/", null))
        assertNull(SiteNotifications.keyOf("https://chat.example.org/", ""))
        assertEquals(
            SiteNotifications.keyOf("https://Chat.Example.org:443/a", "t"),
            SiteNotifications.keyOf("https://chat.example.org/b", "t")
        )
    }

    // --- iconUrlOf ---

    @Test
    fun webAndBase64DataPicturesCanBeFetchedForTheIcon() {
        assertEquals("https://a.example/i.png", SiteNotifications.iconUrlOf("https://a.example/i.png"))
        assertEquals("http://a.example/i.png", SiteNotifications.iconUrlOf("http://a.example/i.png"))
        assertEquals("https://a.example/i.png", SiteNotifications.iconUrlOf("  https://a.example/i.png \n"))
        assertEquals("HTTPS://A.example/i.png", SiteNotifications.iconUrlOf("HTTPS://A.example/i.png"))
        val data = "data:image/png;base64,AAAA"
        assertEquals(data, SiteNotifications.iconUrlOf(data))
        assertEquals("DATA:IMAGE/PNG;BASE64,AAAA", SiteNotifications.iconUrlOf("DATA:IMAGE/PNG;BASE64,AAAA"))
    }

    @Test
    fun blobJavascriptFileAndOtherAddressesAreRefusedForTheIcon() {
        listOf(
            "blob:https://a.example/uuid",
            "javascript:alert(1)",
            "file:///sdcard/i.png",
            "content://media/external/1",
            "ftp://a.example/i.png",
            "/icons/i.png",
            "//cdn.example/i.png",
            "i.png",
            "data:text/html;base64,PGI+",
            "data:image/png,not-base64",
            "data:image/png;base64",
            "javascript:void(0)//https://a.example/i.png",
            "", "   "
        ).forEach { assertNull("icon for '$it'", SiteNotifications.iconUrlOf(it)) }
        assertNull(SiteNotifications.iconUrlOf(null))
    }

    // --- dataUrlBytes ---

    @Test
    fun aDataPictureIsDecoded() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertArrayEquals(bytes, SiteNotifications.dataUrlBytes(dataUrl(bytes)))
        assertArrayEquals(bytes, SiteNotifications.dataUrlBytes("  " + dataUrl(bytes) + "  "))
        assertArrayEquals(bytes, SiteNotifications.dataUrlBytes(dataUrl(bytes, "IMAGE/PNG").replaceFirst("data:", "DATA:")))
    }

    @Test
    fun aDataPictureWrappedOverLinesIsDecoded() {
        val bytes = ByteArray(100) { it.toByte() }
        val wrapped = Base64.getMimeEncoder(16, "\n".toByteArray()).encodeToString(bytes)
        assertArrayEquals(bytes, SiteNotifications.dataUrlBytes("data:image/png;base64,$wrapped"))
    }

    @Test
    fun onlyDataAddressesAreDecodedHere() {
        assertNull(SiteNotifications.dataUrlBytes("https://a.example/i.png"))
        assertNull(SiteNotifications.dataUrlBytes("blob:https://a.example/uuid"))
        assertNull(SiteNotifications.dataUrlBytes("data:text/plain;base64,AAAA"))
        assertNull(SiteNotifications.dataUrlBytes("data:image/png,AAAA"))
    }

    @Test
    fun anEmptyOrUndecodablePayloadGivesNothing() {
        assertNull(SiteNotifications.dataUrlBytes("data:image/png;base64,"))
        assertNull(SiteNotifications.dataUrlBytes("data:image/png;base64,!!!!"))
        assertNull(SiteNotifications.dataUrlBytes("data:image/png;base64,A"))
    }

    @Test
    fun aPictureLargerThanTheLimitIsRefusedAndOneAtTheLimitIsKept() {
        val atLimit = ByteArray(30) { 7 }
        assertArrayEquals(atLimit, SiteNotifications.dataUrlBytes(dataUrl(atLimit), maxBytes = 30))
        assertNull(SiteNotifications.dataUrlBytes(dataUrl(ByteArray(31)), maxBytes = 30))
        assertNull(SiteNotifications.dataUrlBytes(dataUrl(ByteArray(1000)), maxBytes = 30))
    }

    @Test
    fun theDefaultLimitIsOneMegabyte() {
        assertEquals(1024 * 1024, SiteNotifications.MAX_ICON_BYTES)
        assertNull(SiteNotifications.dataUrlBytes(dataUrl(ByteArray(SiteNotifications.MAX_ICON_BYTES + 1))))
        assertEquals(
            SiteNotifications.MAX_ICON_BYTES,
            SiteNotifications.dataUrlBytes(dataUrl(ByteArray(SiteNotifications.MAX_ICON_BYTES) { 1 }))?.size
        )
    }

    // --- iconSampleSize ---

    @Test
    fun theSampleSizeShrinksTowardsTheTargetWithoutGoingBelowIt() {
        assertEquals(1, SiteNotifications.iconSampleSize(256, 256))
        assertEquals(1, SiteNotifications.iconSampleSize(100, 100))
        assertEquals(1, SiteNotifications.iconSampleSize(511, 511))
        assertEquals(2, SiteNotifications.iconSampleSize(512, 512))
        assertEquals(2, SiteNotifications.iconSampleSize(1000, 1000))
        assertEquals(4, SiteNotifications.iconSampleSize(1024, 1024))
        assertEquals(16, SiteNotifications.iconSampleSize(4096, 4096))
    }

    @Test
    fun theLongerSideDecidesTheSampleSize() {
        assertEquals(16, SiteNotifications.iconSampleSize(4096, 300))
        assertEquals(16, SiteNotifications.iconSampleSize(512, 4096))
        assertEquals(2, SiteNotifications.iconSampleSize(1000, 300))
        assertEquals(1, SiteNotifications.iconSampleSize(300, 40))
    }

    @Test
    fun everyAcceptablePictureDecodesToAroundTheTargetSize() {
        for (width in listOf(256, 300, 511, 512, 1000, 2047, 2048, 4096)) {
            for (height in listOf(width, width / 2, width / 8)) {
                if (!SiteNotifications.isAcceptableIconSize(width, height)) continue
                val size = SiteNotifications.iconSampleSize(width, height)
                val longer = maxOf(width, height) / size
                assertTrue("$width x $height decodes to $longer", longer in 256..511)
            }
        }
    }

    // --- isAcceptableIconSize ---

    @Test
    fun aPictureUpToTheSideAndAspectLimitsIsAccepted() {
        assertEquals(4096, SiteNotifications.MAX_ICON_SIDE_PX)
        assertEquals(8, SiteNotifications.MAX_ICON_ASPECT)
        assertTrue(SiteNotifications.isAcceptableIconSize(1, 1))
        assertTrue(SiteNotifications.isAcceptableIconSize(192, 192))
        assertTrue(SiteNotifications.isAcceptableIconSize(4096, 4096))
        assertTrue(SiteNotifications.isAcceptableIconSize(2048, 256))
        assertTrue(SiteNotifications.isAcceptableIconSize(512, 4096))
    }

    @Test
    fun aVastOrLopsidedPictureIsRefusedBeforeDecoding() {
        // The review's case: a tiny 1-bit PNG that would decode to about a gigabyte
        assertFalse(SiteNotifications.isAcceptableIconSize(1_000_000, 255))
        assertFalse(SiteNotifications.isAcceptableIconSize(4097, 4096))
        assertFalse(SiteNotifications.isAcceptableIconSize(4096, 4097))
        assertFalse(SiteNotifications.isAcceptableIconSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(SiteNotifications.isAcceptableIconSize(2049, 256))
        assertFalse(SiteNotifications.isAcceptableIconSize(4096, 1))
        assertFalse(SiteNotifications.isAcceptableIconSize(0, 100))
        assertFalse(SiteNotifications.isAcceptableIconSize(100, -1))
    }

    // --- where an icon may come from ---

    @Test
    fun anIconOnThisDeviceOrTheLocalNetworkIsRefused() {
        listOf(
            "http://localhost/i.png",
            "https://LOCALHOST./i.png",
            "http://app.localhost:3000/i.png",
            "http://127.0.0.1/i.png",
            "http://127.8.9.10:8080/i.png",
            "http://0.0.0.0/i.png",
            "http://10.1.2.3/i.png",
            "http://172.16.0.1/i.png",
            "http://172.31.255.255/i.png",
            "http://192.168.1.1/i.png",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/i.png",
            "http://100.127.255.255/i.png",
            "http://[::1]/i.png",
            "http://[::]/i.png",
            "http://[fe80::1]/i.png",
            "http://[febf::1]/i.png",
            "http://[fec0::1]/i.png",
            "http://[fd12:3456::1]/i.png",
            "http://[fc00::1]/i.png",
            "http://[::ffff:127.0.0.1]/i.png",
            "http://[::ffff:c0a8:101]/i.png",
            "http://[::127.0.0.1]/i.png",
            "http://[64:ff9b::a00:1]/i.png"
        ).forEach { assertNull("icon for $it", SiteNotifications.iconUrlOf(it)) }
    }

    @Test
    fun anAddressWrittenOtherwiseIsStillRecognised() {
        listOf(
            "http://127.1/i.png",
            "http://2130706433/i.png",
            "http://0x7f.0.0.1/i.png",
            "http://0x7f000001/i.png",
            "http://0177.0.0.1/i.png",
            "http://10.1/i.png",
            "http://192.168.257/i.png",
            "http://127.0.0.1./i.png"
        ).forEach { assertNull("icon for $it", SiteNotifications.iconUrlOf(it)) }
    }

    @Test
    fun anIconOnTheInternetIsKept() {
        listOf(
            "https://cdn.example.org/i.png",
            "http://8.8.8.8/i.png",
            "http://172.15.0.1/i.png",
            "http://172.32.0.1/i.png",
            "http://192.169.0.1/i.png",
            "http://169.255.0.1/i.png",
            "http://100.63.0.1/i.png",
            "http://100.128.0.1/i.png",
            "http://11.0.0.1/i.png",
            "http://[2001:4860:4860::8888]/i.png",
            "http://[::ffff:8.8.8.8]/i.png",
            "http://1.example/i.png",
            "http://localhost.example.org/i.png",
            "https://example.org:8443/i.png"
        ).forEach { assertEquals(it, SiteNotifications.iconUrlOf(it)) }
    }

    @Test
    fun anIconOnAPortPagesMayNotUseIsRefused() {
        listOf(21, 22, 23, 25, 110, 143, 6667, 10080).forEach { port ->
            assertNull("port $port", SiteNotifications.iconUrlOf("http://cdn.example.org:$port/i.png"))
        }
        assertEquals("http://cdn.example.org:8080/i.png", SiteNotifications.iconUrlOf("http://cdn.example.org:8080/i.png"))
    }

    @Test
    fun anUnparsableWebAddressIsRefused() {
        assertNull(SiteNotifications.iconUrlOf("http://"))
        assertNull(SiteNotifications.iconUrlOf("https://exa mple.org/i.png"))
        assertNull(SiteNotifications.iconUrlOf("http://cdn.example.org:99999/i.png"))
    }

    @Test
    fun aLocalHostNameOrAddressIsRecognisedDirectly() {
        assertTrue(SiteNotifications.isLocalHost("[::1]"))
        assertTrue(SiteNotifications.isLocalHost("Localhost"))
        assertTrue(SiteNotifications.isLocalHost(""))
        assertFalse(SiteNotifications.isLocalHost("example.org"))
        assertFalse(SiteNotifications.isLocalHost("1.2.3.4.5"))
        assertFalse(SiteNotifications.isLocalHost("256.1.1.1"))
        assertFalse(SiteNotifications.isLocalHost("[1::2::3]"))
    }

    @Test
    fun aResolvedAddressIsJudgedByItsBytes() {
        fun v4(vararg b: Int) = ByteArray(4) { b[it].toByte() }
        assertTrue(SiteNotifications.isLocalAddress(v4(127, 0, 0, 1)))
        assertTrue(SiteNotifications.isLocalAddress(v4(192, 168, 0, 10)))
        assertFalse(SiteNotifications.isLocalAddress(v4(93, 184, 216, 34)))
        val loopback6 = ByteArray(16).also { it[15] = 1 }
        assertTrue(SiteNotifications.isLocalAddress(loopback6))
        val public6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x48; it[3] = 0x60; it[15] = 0x88.toByte() }
        assertFalse(SiteNotifications.isLocalAddress(public6))
        // Neither IPv4 nor IPv6: never fetched
        assertTrue(SiteNotifications.isLocalAddress(ByteArray(5)))
        assertTrue(SiteNotifications.isLocalAddress(ByteArray(0)))
    }

    @Test
    fun theSampleSizeIsAPowerOfTwoAndOneForUnusableSizes() {
        for (side in listOf(257, 600, 777, 2049, 5000)) {
            val size = SiteNotifications.iconSampleSize(side, side)
            assertEquals(0, size and (size - 1))
            assertTrue(side / size >= SiteNotifications.ICON_TARGET_PX)
        }
        assertEquals(1, SiteNotifications.iconSampleSize(0, 500))
        assertEquals(1, SiteNotifications.iconSampleSize(500, -1))
        assertEquals(1, SiteNotifications.iconSampleSize(5000, 5000, target = 0))
        assertEquals(8, SiteNotifications.iconSampleSize(1024, 1024, target = 128))
    }

    // --- the registry ---

    private class Item(val name: String)

    private fun registry(max: Int = SiteNotificationRegistry.MAX_ENTRIES) = SiteNotificationRegistry<Item>(max)

    @Test
    fun theRegistryHoldsANotificationUnderItsTokenUntilItIsTaken() {
        val r = registry()
        val item = Item("a")
        r.put("k", item, "t1")

        assertEquals(1, r.size)
        assertTrue(r.holds("k", "t1"))
        assertFalse(r.holds("k", "other"))
        assertFalse(r.holds("missing", "t1"))
        assertEquals("k", r.keyOf(item))
        assertNull(r.keyOf(Item("a")))

        assertEquals(item, r.take("k", "t1"))
        assertEquals(0, r.size)
        assertNull(r.take("k", "t1"))
    }

    @Test
    fun aWrongOrMissingTokenTakesNothingAndLeavesTheNotification() {
        val r = registry()
        r.put("k", Item("a"), "t1")

        assertNull(r.take("k", "forged"))
        assertNull(r.take("k", null))
        assertNull(r.take(null, "t1"))
        assertNull(r.take(null, null))
        assertEquals(1, r.size)
        assertTrue(r.holds("k", "t1"))
    }

    @Test
    fun aReplacementInvalidatesTheOldTokenAndKeepsOneEntry() {
        val r = registry()
        val old = Item("old")
        val new = Item("new")
        r.put("k", old, "t1")
        r.put("k", new, "t2")

        assertEquals(1, r.size)
        assertFalse(r.holds("k", "t1"))
        assertNull(r.take("k", "t1"))
        assertNull(r.keyOf(old))
        assertEquals(new, r.take("k", "t2"))
    }

    @Test
    fun removeTakesOutByKeyWithoutAToken() {
        val r = registry()
        val item = Item("a")
        r.put("k", item, "t1")
        assertNull(r.remove("missing"))
        assertEquals(item, r.remove("k"))
        assertEquals(0, r.size)
        assertFalse(r.holds("k", "t1"))
    }

    @Test
    fun theRegistryKeepsAtMostSixtyFourAndTheOldestGoFirst() {
        assertEquals(64, SiteNotificationRegistry.MAX_ENTRIES)
        val r = registry()
        for (i in 0 until 65) r.put("k$i", Item("$i"), "t$i")

        assertEquals(64, r.size)
        assertFalse(r.holds("k0", "t0"))
        assertTrue(r.holds("k1", "t1"))
        assertTrue(r.holds("k64", "t64"))
    }

    @Test
    fun replacingAnEntryMakesItTheNewestSoItIsNotTheNextEvicted() {
        val r = registry(max = 3)
        r.put("a", Item("a"), "ta")
        r.put("b", Item("b"), "tb")
        r.put("c", Item("c"), "tc")
        r.put("a", Item("a2"), "ta2")
        r.put("d", Item("d"), "td")

        assertEquals(3, r.size)
        assertFalse(r.holds("b", "tb"))
        assertTrue(r.holds("a", "ta2"))
        assertTrue(r.holds("c", "tc"))
        assertTrue(r.holds("d", "td"))
    }

    @Test
    fun anEvictedNotificationCannotBeTakenByItsOldToken() {
        val r = registry(max = 1)
        r.put("a", Item("a"), "ta")
        r.put("b", Item("b"), "tb")
        assertNull(r.take("a", "ta"))
        assertEquals("b", r.take("b", "tb")?.name)
    }
}
