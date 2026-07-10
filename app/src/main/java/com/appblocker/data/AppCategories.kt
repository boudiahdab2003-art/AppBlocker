package com.appblocker.data

import android.content.pm.ApplicationInfo
import com.appblocker.data.AppCategory.CREATIVITY
import com.appblocker.data.AppCategory.EDUCATION
import com.appblocker.data.AppCategory.ENTERTAINMENT
import com.appblocker.data.AppCategory.GAMES
import com.appblocker.data.AppCategory.HEALTH_FITNESS
import com.appblocker.data.AppCategory.NEWS_BOOKS
import com.appblocker.data.AppCategory.PRODUCTIVITY
import com.appblocker.data.AppCategory.SHOPPING_FOOD
import com.appblocker.data.AppCategory.SOCIAL
import com.appblocker.data.AppCategory.TRAVEL
import com.appblocker.data.AppCategory.UTILITIES

/**
 * App categories, AppBlock-style. Declaration order = display order in the pickers.
 * [color] is a raw ARGB long (this file stays Compose-free — the service uses it too);
 * [weight] is the "how distracting" score feeding the picker's smart order.
 */
enum class AppCategory(val label: String, val color: Long, val weight: Int) {
    SOCIAL("Social media", 0xFFEC4899, 100),
    ENTERTAINMENT("Entertainment", 0xFFEF4444, 100),
    GAMES("Games", 0xFFF59E0B, 70),
    NEWS_BOOKS("News & Books", 0xFF8B5CF6, 40),
    SHOPPING_FOOD("Shopping & Food", 0xFF06B6D4, 40),
    CREATIVITY("Creativity", 0xFFF97316, 20),
    TRAVEL("Travel", 0xFF14B8A6, 10),
    UTILITIES("Utilities", 0xFF64748B, 10),
    EDUCATION("Education", 0xFF6366F1, 0),
    HEALTH_FITNESS("Health & Fitness", 0xFF22C55E, 0),
    PRODUCTIVITY("Productivity", 0xFF3B82F6, 0),
    OTHER("Other", 0xFF94A3B8, 20),
}

object AppCategories {

    /** Category by package from the baked map only (Insights/UsageTracker path). */
    fun categoryOf(pkg: String): AppCategory = MAP[pkg] ?: AppCategory.OTHER

    fun weightOf(pkg: String): Int = categoryOf(pkg).weight

    /**
     * Full resolution for the app picker: baked AI map -> the developer-declared
     * [ApplicationInfo.category] -> Other.
     */
    fun resolve(pkg: String, sysCategory: Int): AppCategory =
        MAP[pkg] ?: fromSystem(sysCategory) ?: AppCategory.OTHER

    /**
     * Never-throwing enum parse. Old persisted names (usage-cache slices written before the
     * 12-category expansion) map onto their successors instead of crashing valueOf.
     */
    fun parse(name: String?): AppCategory = when (name) {
        "VIDEO" -> ENTERTAINMENT
        "CHAT" -> SOCIAL
        "PRODUCTIVE" -> PRODUCTIVITY
        else -> AppCategory.entries.firstOrNull { it.name == name } ?: AppCategory.OTHER
    }

    private fun fromSystem(category: Int): AppCategory? = when (category) {
        ApplicationInfo.CATEGORY_GAME -> GAMES
        ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> ENTERTAINMENT
        ApplicationInfo.CATEGORY_IMAGE -> CREATIVITY
        ApplicationInfo.CATEGORY_NEWS -> NEWS_BOOKS
        ApplicationInfo.CATEGORY_MAPS -> TRAVEL
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
        else -> null
    }

    // Package -> category. Built with Gemini 3.1 Pro (2026-07-11): global top apps +
    // Germany-popular (where Abdallah lives) + Arabic staples. Wrong guesses were told to be
    // skipped, so unknown apps simply fall through to the system category, then Other.
    private val MAP: Map<String, AppCategory> = mapOf(
// SOCIAL
    "com.instagram.android" to SOCIAL,
    "com.zhiliaoapp.musically" to SOCIAL,
    "com.snapchat.android" to SOCIAL,
    "com.facebook.katana" to SOCIAL,
    "com.twitter.android" to SOCIAL,
    "com.reddit.frontpage" to SOCIAL,
    "com.pinterest" to SOCIAL,
    "com.linkedin.android" to SOCIAL,
    "org.telegram.messenger" to SOCIAL,
    "com.whatsapp" to SOCIAL,
    "org.thoughtcrime.securesms" to SOCIAL,
    "com.discord" to SOCIAL,
    "com.bereal.bereal" to SOCIAL,
    "com.instagram.barcelona" to SOCIAL,
    "com.imo.android.imoim" to SOCIAL,
    "im.thebot.messenger" to SOCIAL,
    "com.viber.voip" to SOCIAL,
    "com.tencent.mm" to SOCIAL,
    "com.facebook.orca" to SOCIAL,
    "com.facebook.lite" to SOCIAL,
    "com.vkontakte.android" to SOCIAL,
    "jp.naver.line.android" to SOCIAL,
    "com.tinder" to SOCIAL,
    "com.bumble.app" to SOCIAL,
    "com.kakao.talk" to SOCIAL,
    "kik.android" to SOCIAL,

// ENTERTAINMENT
    "com.google.android.youtube" to ENTERTAINMENT,
    "com.netflix.mediaclient" to ENTERTAINMENT,
    "com.disney.disneyplus" to ENTERTAINMENT,
    "com.amazon.avod.thirdpartyclient" to ENTERTAINMENT,
    "tv.twitch.android.app" to ENTERTAINMENT,
    "com.spotify.music" to ENTERTAINMENT,
    "com.soundcloud.android" to ENTERTAINMENT,
    "net.mbc.shahid" to ENTERTAINMENT,
    "com.anghami" to ENTERTAINMENT,
    "com.osn.web.player" to ENTERTAINMENT,
    "de.prosiebensat1digital.joyn" to ENTERTAINMENT,
    "de.rtli.tvnow" to ENTERTAINMENT,
    "de.swr.avp.ard" to ENTERTAINMENT,
    "com.zdf.android.mediathek" to ENTERTAINMENT,
    "com.dazn" to ENTERTAINMENT,
    "de.sky.bw" to ENTERTAINMENT,
    "com.apple.android.music" to ENTERTAINMENT,
    "com.google.android.apps.youtube.music" to ENTERTAINMENT,
    "com.shazam.android" to ENTERTAINMENT,
    "com.zhiliaoapp.musically.go" to ENTERTAINMENT,
    "com.crunchyroll.crunchyroid" to ENTERTAINMENT,
    "org.videolan.vlc" to ENTERTAINMENT,
    "com.plexapp.android" to ENTERTAINMENT,
    "tunein.player" to ENTERTAINMENT,
    "deezer.android.app" to ENTERTAINMENT,

// GAMES
    "com.supercell.clashofclans" to GAMES,
    "com.king.candycrushsaga" to GAMES,
    "com.roblox.client" to GAMES,
    "com.supercell.brawlstars" to GAMES,
    "com.tencent.ig" to GAMES,
    "com.dts.freefireth" to GAMES,
    "com.miHoYo.GenshinImpact" to GAMES,
    "jp.konami.pesam" to GAMES,
    "com.ea.gp.fifamobile" to GAMES,
    "com.kiloo.subwaysurf" to GAMES,
    "com.miniclip.eightballpool" to GAMES,
    "com.ludo.king" to GAMES,
    "com.supercell.clashroyale" to GAMES,
    "com.activision.callofduty.shooter" to GAMES,
    "com.nianticlabs.pokemongo" to GAMES,
    "com.mojang.minecraftpe" to GAMES,
    "com.innersloth.spacemafia" to GAMES,
    "com.moonactive.coinmaster" to GAMES,
    "com.supercell.hayday" to GAMES,
    "com.dreamgames.royalmatch" to GAMES,
    "com.king.candycrushsodasaga" to GAMES,
    "com.mobile.legends" to GAMES,
    "com.gameloft.android.ANMP.GloftA9HM" to GAMES,
    "com.playrix.township" to GAMES,
    "com.playrix.homescapes" to GAMES,

// NEWS_BOOKS
    "de.axelspringer.yana.zeropage" to NEWS_BOOKS,
    "de.spiegel.android.app.spon" to NEWS_BOOKS,
    "de.tagesschau" to NEWS_BOOKS,
    "net.aljazeera.arabic" to NEWS_BOOKS,
    "com.amazon.kindle" to NEWS_BOOKS,
    "com.audible.application" to NEWS_BOOKS,
    "wp.wattpad" to NEWS_BOOKS,
    "com.google.android.apps.magazines" to NEWS_BOOKS,
    "de.cellular.focus" to NEWS_BOOKS,
    "de.lineas.lit.ntv.android" to NEWS_BOOKS,
    "com.netbiscuits.kicker" to NEWS_BOOKS,
    "net.alarabiya.app" to NEWS_BOOKS,
    "bbc.mobile.news.ww" to NEWS_BOOKS,
    "com.cnn.mobile.android.phone" to NEWS_BOOKS,
    "com.nytimes.android" to NEWS_BOOKS,
    "com.goodreads" to NEWS_BOOKS,
    "com.ideashower.readitlater.pro" to NEWS_BOOKS,
    "flipboard.app" to NEWS_BOOKS,
    "com.medium.reader" to NEWS_BOOKS,
    "de.sueddeutsche" to NEWS_BOOKS,
    "de.faz.faznet" to NEWS_BOOKS,
    "de.welt.n24d" to NEWS_BOOKS,

// SHOPPING_FOOD
    "com.amazon.mShop.android.shopping" to SHOPPING_FOOD,
    "com.ebay.mobile" to SHOPPING_FOOD,
    "com.ebay.kleinanzeigen" to SHOPPING_FOOD,
    "fr.vinted" to SHOPPING_FOOD,
    "com.einnovation.temu" to SHOPPING_FOOD,
    "com.zzkko" to SHOPPING_FOOD,
    "com.alibaba.aliexpresshd" to SHOPPING_FOOD,
    "de.zalando.mobile" to SHOPPING_FOOD,
    "de.otto.shoppingapp" to SHOPPING_FOOD,
    "de.mediamarkt.android.app" to SHOPPING_FOOD,
    "de.check24.check24" to SHOPPING_FOOD,
    "com.yopeso.lieferando" to SHOPPING_FOOD,
    "com.wolt.android" to SHOPPING_FOOD,
    "com.ubercab.eats" to SHOPPING_FOOD,
    "com.app.tgtg" to SHOPPING_FOOD,
    "de.rewe.android.app" to SHOPPING_FOOD,
    "com.lidl.eci.lidlplus" to SHOPPING_FOOD,
    "de.kaufland.kauflandapp" to SHOPPING_FOOD,
    "de.aldisued.app" to SHOPPING_FOOD,
    "de.edeka.smartshopper" to SHOPPING_FOOD,
    "de.dm.dmapp" to SHOPPING_FOOD,
    "de.rossmann.app" to SHOPPING_FOOD,
    "de.payback.client.android" to SHOPPING_FOOD,
    "de.flaschenpost.app" to SHOPPING_FOOD,
    "com.picnic.android" to SHOPPING_FOOD,
    "com.talabat" to SHOPPING_FOOD,
    "com.ingka.ikea.app" to SHOPPING_FOOD,
    "de.saturn.android.app" to SHOPPING_FOOD,
    "com.asos.app" to SHOPPING_FOOD,
    "com.hm.goe" to SHOPPING_FOOD,
    "com.inditex.zara" to SHOPPING_FOOD,
    "com.nike.omega" to SHOPPING_FOOD,
    "com.aboutyou.mobile.app" to SHOPPING_FOOD,
    "de.tchibo.mobil" to SHOPPING_FOOD,

// CREATIVITY
    "com.canva.editor" to CREATIVITY,
    "com.lemon.lvoverseas" to CREATIVITY,
    "com.picsart.studio" to CREATIVITY,
    "com.adobe.lrmobile" to CREATIVITY,
    "com.vsco.cam" to CREATIVITY,
    "us.pixomatic.pixomatic" to CREATIVITY,
    "com.niksoftware.snapseed" to CREATIVITY,
    "com.camerasideas.instashot" to CREATIVITY,
    "com.adsk.sketchbook" to CREATIVITY,
    "com.nexstreaming.app.kinemasterfree" to CREATIVITY,
    "com.frontrow.vlog" to CREATIVITY,
    "com.photoroom.app" to CREATIVITY,
    "com.lightricks.facetune.free" to CREATIVITY,
    "com.bigwinepot.nwdn.international" to CREATIVITY,
    "com.adobe.spark.post" to CREATIVITY,
    "com.mt.mtxx.mtxx" to CREATIVITY,
    "com.gopro.smarty" to CREATIVITY,

// TRAVEL
    "com.google.android.apps.maps" to TRAVEL,
    "de.hafas.android.db" to TRAVEL,
    "de.flixbus.app" to TRAVEL,
    "de.bvg.ticket" to TRAVEL,
    "com.ubercab" to TRAVEL,
    "ee.mtakso.client" to TRAVEL,
    "com.booking" to TRAVEL,
    "com.airbnb.android" to TRAVEL,
    "com.ryanair.cheapflights" to TRAVEL,
    "com.lufthansa.android.lufthansa" to TRAVEL,
    "net.skyscanner.android.main" to TRAVEL,
    "com.waze" to TRAVEL,
    "com.tier.app" to TRAVEL,
    "com.limebike" to TRAVEL,
    "io.voiapp.voi" to TRAVEL,
    "com.miles.app" to TRAVEL,
    "com.car2go" to TRAVEL,
    "com.tripadvisor.tripadvisor" to TRAVEL,
    "com.careem.acma" to TRAVEL,
    "com.google.earth" to TRAVEL,
    "com.agoda.mobile.consumer" to TRAVEL,
    "com.expedia.bookings" to TRAVEL,
    "com.easyjet.mobile" to TRAVEL,
    "com.eurowings.mobile" to TRAVEL,

// UTILITIES
    "de.dhl.paket" to UTILITIES,
    "de.deutschepost.postmobil" to UTILITIES,
    "com.paypal.android.p2pmobile" to UTILITIES,
    "de.number26.android" to UTILITIES,
    "com.starfinanz.smob.android.sfinanzstatus" to UTILITIES,
    "de.commerzbank.cebo" to UTILITIES,
    "de.ing.diba.mbanca" to UTILITIES,
    "com.revolut.netcore" to UTILITIES,
    "com.traderepublic.app" to UTILITIES,
    "com.transferwise.android" to UTILITIES,
    "com.myklarnamobile" to UTILITIES,
    "com.google.android.apps.nbu.files" to UTILITIES,
    "com.nordvpn.android" to UTILITIES,
    "com.macropinch.swan" to UTILITIES,
    "com.teslacoilsw.launcher" to UTILITIES,
    "com.dkb.banking" to UTILITIES,
    "de.postbank.finanzassistent" to UTILITIES,
    "de.fiduciagad.banking.vr" to UTILITIES,
    "com.scalable.capital.android" to UTILITIES,
    "com.piriform.ccleaner" to UTILITIES,
    "ru.zdevs.zarchiver" to UTILITIES,
    "com.weather.Weather" to UTILITIES,
    "com.x8bit.bitwarden" to UTILITIES,
    "com.agilebits.onepassword" to UTILITIES,
    "com.alrajhibank.sma" to UTILITIES,
    "com.expressvpn.vpn" to UTILITIES,
    "com.bitsmedia.android.muslimpro" to UTILITIES,
    "de.bund.bbk.nina" to UTILITIES,

// EDUCATION
    "com.duolingo" to EDUCATION,
    "com.ichi2.anki" to EDUCATION,
    "com.google.android.apps.classroom" to EDUCATION,
    "org.khanacademy.android" to EDUCATION,
    "no.mobitroll.kahoot.android" to EDUCATION,
    "com.babbel.mobile.android.en" to EDUCATION,
    "com.quizlet.quizletandroid" to EDUCATION,
    "com.microblink.photomath" to EDUCATION,
    "co.brainly" to EDUCATION,
    "org.coursera.android" to EDUCATION,
    "com.udemy.android" to EDUCATION,
    "com.busuu.android.enc" to EDUCATION,
    "com.google.android.apps.translate" to EDUCATION,
    "com.deepl.mobiletranslator" to EDUCATION,
    "com.memrise.android.memrisecompanion" to EDUCATION,
    "air.com.rosettastone.mobile.CoursePlayer" to EDUCATION,
    "com.ted.android" to EDUCATION,
    "com.sololearn" to EDUCATION,

// HEALTH_FITNESS
    "com.strava" to HEALTH_FITNESS,
    "com.freeletics.lite" to HEALTH_FITNESS,
    "com.calm.android" to HEALTH_FITNESS,
    "com.getsomeheadspace.android" to HEALTH_FITNESS,
    "com.myfitnesspal.android" to HEALTH_FITNESS,
    "com.sec.android.app.shealth" to HEALTH_FITNESS,
    "com.google.android.apps.fitness" to HEALTH_FITNESS,
    "org.iggymedia.periodtracker" to HEALTH_FITNESS,
    "io.yuka.android" to HEALTH_FITNESS,
    "fr.doctolib.www" to HEALTH_FITNESS,
    "de.tk.tkapp" to HEALTH_FITNESS,
    "de.aok.meinleben" to HEALTH_FITNESS,
    "de.barmer.app" to HEALTH_FITNESS,
    "com.garmin.android.apps.connectmobile" to HEALTH_FITNESS,
    "de.komoot.android" to HEALTH_FITNESS,
    "com.fitbit.FitbitMobile" to HEALTH_FITNESS,
    "com.alltrails.alltrails" to HEALTH_FITNESS,
    "com.lexware.zendo" to HEALTH_FITNESS,
    "com.clue.android" to HEALTH_FITNESS,
    "com.nike.plusgps" to HEALTH_FITNESS,
    "com.runtastic.android" to HEALTH_FITNESS,
    "com.yazio.android" to HEALTH_FITNESS,

// PRODUCTIVITY
    "com.google.android.gm" to PRODUCTIVITY,
    "com.microsoft.office.outlook" to PRODUCTIVITY,
    "com.android.chrome" to PRODUCTIVITY,
    "org.mozilla.firefox" to PRODUCTIVITY,
    "com.google.android.apps.docs" to PRODUCTIVITY,
    "com.dropbox.android" to PRODUCTIVITY,
    "notion.id" to PRODUCTIVITY,
    "com.Slack" to PRODUCTIVITY,
    "com.microsoft.teams" to PRODUCTIVITY,
    "us.zoom.videomeetings" to PRODUCTIVITY,
    "com.google.android.apps.docs.editors.docs" to PRODUCTIVITY,
    "com.google.android.apps.docs.editors.sheets" to PRODUCTIVITY,
    "com.microsoft.office.word" to PRODUCTIVITY,
    "com.microsoft.office.excel" to PRODUCTIVITY,
    "com.evernote" to PRODUCTIVITY,
    "com.todoist" to PRODUCTIVITY,
    "com.trello" to PRODUCTIVITY,
    "com.adobe.reader" to PRODUCTIVITY,
    "com.intsig.camscanner" to PRODUCTIVITY,
    "de.gmx.mobile.android.mail" to PRODUCTIVITY,
    "de.web.mobile.android.mail" to PRODUCTIVITY,
    "com.samsung.android.app.notes" to PRODUCTIVITY,
    "com.brave.browser" to PRODUCTIVITY,
    "com.opera.browser" to PRODUCTIVITY,
    "com.microsoft.emmx" to PRODUCTIVITY,
    "com.google.android.calendar" to PRODUCTIVITY,
    "com.anydo" to PRODUCTIVITY,
    "com.ticktick.task" to PRODUCTIVITY,

// Kept from the original map (system/Google apps Gemini skipped by design)
    "com.google.android.play.games" to GAMES,
    "com.google.android.apps.messaging" to SOCIAL,
    "com.android.mms" to SOCIAL,
    "com.google.android.keep" to PRODUCTIVITY,
    "com.android.vending" to UTILITIES,
    )
}
