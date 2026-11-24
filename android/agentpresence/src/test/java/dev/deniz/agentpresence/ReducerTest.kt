package dev.deniz.agentpresence

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ReducerTest {
    private fun ev(json: String) = PresenceEvent.parse(json).single()

    @Before fun turkish() = Locale.setDefault(Locale.forLanguageTag("tr-TR"))

    private fun Session.texts() = directives.map { (if (it.positive) "+" else "-") + it.text }

    private fun run(vararg json: String): Pair<Session?, Reducer.Result> {
        var s: Session? = null
        var last: Reducer.Result? = null
        var t = 1000L
        for (j in json) {
            last = Reducer.apply(s, ev(j), t++)
            s = last.session
        }
        return s to last!!
    }

    private val start = """{"v":1,"id":"a","type":"start","app":"Spotify","title":"Liste",
        "steps":["Aç","Bul","Ekle","Kontrol"],"dont":["Klavye ve fareye dokunma"],"do":["Bitince haber vereceğim"]}"""

    @Test fun startAnnouncesAndBeginsAtStepOne() {
        val (s, r) = run(start)
        assertEquals(Cue.ANNOUNCE, r.cue)
        assertEquals(Phase.RUNNING, s!!.phase)
        assertEquals(1, s.step)
        assertEquals(4, s.total)
        assertEquals(0, s.finished)
        assertTrue(s.hasDirectives)
        assertEquals("Claude", s.agent)
    }

    @Test fun stepWithoutNumberGoesToNextAndFlashes() {
        val (s, r) = run(start, """{"id":"a","type":"step"}""", """{"id":"a","type":"step","detail":"3/10"}""")
        assertEquals(Cue.FLASH, r.cue)
        assertEquals(3, s!!.step)
        assertEquals(2, s.finished)
        assertEquals("3/10", s.detail)
    }

    @Test fun stepIsClampedToThePlan() {
        val (s, _) = run(start, """{"id":"a","type":"step","step":9}""")
        assertEquals(4, s!!.step)
    }

    @Test fun missingFieldsKeepOldValues() {
        val (s, _) = run(start, """{"id":"a","type":"detail","detail":"x"}""")
        assertEquals("Spotify", s!!.app)
        assertEquals(listOf("Klavye ve fareye dokunma"), s.dont)
        assertEquals("x", s.detail)
    }

    @Test fun attentionIsStickyAndBuzzes() {
        val (s, r) = run(start, """{"id":"a","type":"attention","message":"Onay ver"}""")
        assertEquals(Cue.STICKY, r.cue)
        assertEquals(Buzz.ATTENTION, r.buzz)
        assertEquals(Phase.WAITING, s!!.phase)
        assertEquals("Onay ver", s.message)
        assertFalse("the owner must act now: the don't is set aside", s.hasDirectives)
        assertTrue("but kept for when the agent resumes", s.dont.isNotEmpty())
    }

    @Test fun resumeAndStepLeaveWaiting() {
        val (s, _) = run(start, """{"id":"a","type":"attention"}""", """{"id":"a","type":"resume"}""")
        assertEquals(Phase.RUNNING, s!!.phase)
        val (s2, _) = run(start, """{"id":"a","type":"attention"}""", """{"id":"a","type":"step"}""")
        assertEquals(Phase.RUNNING, s2!!.phase)
    }

    @Test fun doneFinishesEveryStepAndClearsDont() {
        val (s, r) = run(start, """{"id":"a","type":"done","message":"Tamam"}""")
        assertEquals(Cue.ANNOUNCE, r.cue)
        assertEquals(Buzz.DONE, r.buzz)
        assertEquals(Phase.DONE, s!!.phase)
        assertEquals(4, s.finished)
        assertTrue(s.dont.isEmpty())
        assertFalse(s.hasDirectives)
        assertTrue(s.doList.isEmpty())
    }

    @Test fun doneKeepsTheDoListItCarries() {
        val (s, _) = run(start, """{"id":"a","type":"done","do":["Spotify'ı kapatabilirsin"]}""")
        assertEquals(listOf("Spotify'ı kapatabilirsin"), s!!.doList)
    }

    @Test fun failClearsDont() {
        val (s, r) = run(start, """{"id":"a","type":"fail","message":"Uygulama açılmadı"}""")
        assertEquals(Buzz.FAILED, r.buzz)
        assertEquals(Phase.FAILED, s!!.phase)
        assertTrue(s.dont.isEmpty())
        assertTrue(s.doList.isEmpty())
    }

    @Test fun endRemoves() {
        val (s, r) = run(start, """{"id":"a","type":"end"}""")
        assertNull(s)
        assertEquals(Cue.REMOVE, r.cue)
    }

    @Test fun directiveAnnouncesOnlyWhenNewAndNotEmpty() {
        assertEquals(Cue.NONE, run(start, """{"id":"a","type":"directive","dont":["Klavye ve fareye dokunma"]}""").second.cue)
        assertEquals(Cue.ANNOUNCE, run(start, """{"id":"a","type":"directive","dont":["Ekranı kapatma"]}""").second.cue)
        val (s, r) = run(start, """{"id":"a","type":"directive","dont":[]}""")
        assertEquals(Cue.NONE, r.cue)
        assertTrue(s!!.directives.none { !it.positive })
    }

    @Test fun eventForUnknownSessionCreatesIt() {
        val (s, _) = run("""{"id":"b","type":"step","step":2,"steps":["x","y","z"]}""")
        assertEquals(2, s!!.step)
        assertEquals(Phase.RUNNING, s.phase)
    }

    @Test fun sessionWithoutStepsIsFine() {
        val (s, _) = run("""{"id":"c","type":"start","title":"Bakıyor"}""", """{"id":"c","type":"step"}""")
        assertEquals(0, s!!.total)
        assertEquals(2, s.step)
        assertEquals(0, s.finished)
    }

    @Test fun startReplacesAnOldSession() {
        val (s, _) = run(start, """{"id":"a","type":"done"}""", """{"id":"a","type":"start","title":"Yeni"}""")
        assertEquals(Phase.RUNNING, s!!.phase)
        assertEquals("Yeni", s.title)
        assertTrue(s.steps.isEmpty())
        assertNull(s.app)
    }

    @Test fun parseArrayAndLenientLists() {
        val list = PresenceEvent.parse("""[{"id":"a","type":"start","dont":"Dokunma"},{"id":"a","type":"step"}]""")
        assertEquals(2, list.size)
        assertEquals(listOf("Dokunma"), list[0].dont)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTypeIsRejected() {
        PresenceEvent.parse("""{"id":"a","type":"explode"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingIdIsRejected() {
        PresenceEvent.parse("""{"type":"start"}""")
    }

    @Test fun stateJsonHasWhatTheAgentReads() {
        val (s, _) = run(start, """{"id":"a","type":"step"}""")
        val o = JSONObject(Session.stateOf(listOf(s!!.copy(stopRequested = true))))
        assertEquals(1, o.getInt("v"))
        val one = o.getJSONArray("sessions").getJSONObject(0)
        assertEquals("a", one.getString("id"))
        assertEquals("running", one.getString("phase"))
        assertEquals(2, one.getInt("step"))
        assertEquals(4, one.getInt("total"))
        assertTrue(one.getBoolean("stopRequested"))
    }

    @Test fun sessionRoundTripsThroughJson() {
        val (s, _) = run(start, """{"id":"a","type":"attention","message":"m"}""")
        assertEquals(s, Session.fromJson(s!!.toJson()))
    }

    @Test fun directivesOnlyOnTheDrivenDevice() {
        val (mac, _) = run("""{"id":"m","type":"start","device":"mac","steps":[{"title":"Yaz","uses":["keyboard"]}]}""")
        assertTrue("the Mac's own warning", mac!!.hasDirectivesOn("mac"))
        assertFalse("a mirror on the phone shows progress only", mac.hasDirectivesOn("phone"))
        val (phone, _) = run("""{"id":"p","type":"start","device":"phone","steps":[{"title":"Dokun","uses":["touch"]}]}""")
        assertTrue(phone!!.hasDirectivesOn("phone"))
        assertFalse(phone.hasDirectivesOn("mac"))
    }

    // ---------------------------------------------------------------- v1.1: steps with uses, computed directives

    private val plan = """{"id":"p","type":"start","app":"Spotify","steps":[
        "Aç",
        {"title":"Listeyi bul","uses":["mouse"]},
        {"title":"Şarkıları ekle","uses":["keyboard"],"dont":["Spotify'ı kapatma"]},
        "Kontrol et"]}"""

    @Test fun aLaterStepThatUsesTheMouseWarnsFromTheStart() {
        val (s, _) = run(plan)
        assertEquals(1, s!!.step)
        assertEquals(listOf("-Klavyeye ve trackpad'e dokunma", "-Spotify'ı kapatma"), s.texts())
    }

    @Test fun aFinishedStepTakesItsWarningAway() {
        val (s3, _) = run(plan, """{"id":"p","type":"step","step":3}""")
        assertEquals("only the keyboard step is still ahead", listOf("-Klavyeye dokunma", "-Spotify'ı kapatma"), s3!!.texts())
        val (s4, _) = run(plan, """{"id":"p","type":"step","step":4}""")
        assertTrue("every step that drove an input is finished", s4!!.directives.isEmpty())
        assertFalse(s4.hasDirectives)
    }

    @Test fun plainStringStepsMakeNoDirective() {
        val (s, _) = run("""{"id":"q","type":"start","steps":["Aç","Bul","Kapat"]}""")
        assertTrue(s!!.directives.isEmpty())
        assertEquals(listOf("Aç", "Bul", "Kapat"), s.steps.map { it.title })
    }

    @Test fun touchMakesItsOwnLine() {
        val (s, _) = run("""{"id":"t","type":"start","steps":[{"title":"Dokun","uses":["touch","mouse"]}]}""")
        assertEquals(listOf("-Trackpad'e dokunma", "-Ekrana dokunma"), s!!.texts())
    }

    @Test fun sessionDontAndDoJoinTheComputedOnes() {
        val (s, _) = run("""{"id":"d","type":"start","steps":[{"title":"Yaz","uses":["keyboard"]}],
            "dont":["Ekranı kilitleme"],"do":["Şarjı tak"]}""")
        assertEquals(listOf("-Klavyeye dokunma", "-Ekranı kilitleme", "+Şarjı tak"), s!!.texts())
    }

    @Test fun noDirectiveWhileWaitingOrOver() {
        assertTrue(run(plan, """{"id":"p","type":"attention"}""").first!!.directives.isEmpty())
        assertTrue(run(plan, """{"id":"p","type":"done"}""").first!!.directives.isEmpty())
        assertTrue(run(plan, """{"id":"p","type":"fail"}""").first!!.directives.isEmpty())
        assertEquals(2, run(plan, """{"id":"p","type":"attention"}""", """{"id":"p","type":"resume"}""").first!!.directives.size)
    }

    @Test fun unknownInputsAndEmptyTitlesAreDropped() {
        val (s, _) = run("""{"id":"u","type":"start","steps":[{"title":"A","uses":["Keyboard","telepathy"]},{"uses":["mouse"]},""]}""")
        assertEquals(1, s!!.total)
        assertEquals(setOf("keyboard"), s.steps[0].uses)
    }

    @Test fun stepsWithUsesRoundTripThroughJson() {
        val (s, _) = run(plan)
        assertEquals(s, Session.fromJson(s!!.toJson()))
        val again = PresenceEvent.parse(PresenceEvent(id = "x", type = "start", steps = s.steps).toJson().toString()).single()
        assertEquals(s.steps, again.steps)
    }

    // ---------------------------------------------------------------- v1.2: one warning is said once

    @Test fun aTypedKeyboardOrMouseDontIsDroppedWhenTheWarningIsGenerated() {
        val (s, _) = run("""{"id":"k","type":"start","steps":[{"title":"Yaz","uses":["keyboard","mouse"],
            "dont":["Klavyeye yazma"]}],"dont":["Fareyi oynatma","İMLECİ KIPIRDATMA","Don't touch the Mouse","Trackpad'i kullanma"]}""")
        assertEquals(listOf("-Klavyeye ve trackpad'e dokunma"), s!!.texts())
    }

    @Test fun aTypedScreenDontIsDroppedWhenTheTouchWarningIsGenerated() {
        val (s, _) = run("""{"id":"t","type":"start","steps":[{"title":"Kaydır","uses":["touch"],"dont":["Ekranı kilitleme"]}],
            "dont":["TELEFONU bırakma","Don't use the phone","Kabloya dokunma"]}""")
        assertEquals("only real repeats go; \"Kabloya dokunma\" stays", listOf("-Ekrana dokunma", "-Kabloya dokunma"), s!!.texts())
    }

    @Test fun anUnrelatedDontStaysNextToAGeneratedWarning() {
        val (s, _) = run("""{"id":"u","type":"start","steps":[{"title":"Ekle","uses":["keyboard","touch"]}],
            "dont":["Spotify'ı kapatma"]}""")
        assertEquals(listOf("-Klavyeye dokunma", "-Ekrana dokunma", "-Spotify'ı kapatma"), s!!.texts())
    }

    @Test fun withoutAGeneratedWarningNothingIsDropped() {
        val (s, _) = run("""{"id":"n","type":"start","steps":["Aç"],"dont":["Klavyeye dokunma","Ekranı kapatma"]}""")
        assertEquals(listOf("-Klavyeye dokunma", "-Ekranı kapatma"), s!!.texts())
    }

    @Test fun mirror() {
        val (s, _) = run("""{"id":"m","type":"start","device":"mac"}""")
        assertTrue(s!!.mirroredOn("phone"))
        assertFalse(s.mirroredOn("mac"))
    }
}
