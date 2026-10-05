package fr.junade.taipo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lot 22 : non-régression de [KeyboardView] sur appareil (rendu et gestes), après la refonte graphique.
 *
 * La vue est mesurée et dessinée hors fenêtre, à largeur fixe ([WIDTH_PX]) ; les gestes sont des [MotionEvent] envoyés
 * à `dispatchTouchEvent`. Ce qui est couvert : toutes les dispositions se dessinent sans erreur, les zones tactiles
 * couvrent chaque touche sans chevauchement, un appui sur chaque touche la saisit, les couleurs de la charte sont
 * bien celles dessinées, glissements espace et retour arrière, appui long (accents), annulation d'un geste.
 *
 * Non exécutés depuis l'environnement de développement (ni appareil ni compilateur) : `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardViewRegressionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val density: Float get() = context.resources.displayMetrics.density

    private lateinit var view: KeyboardView
    private val typed = mutableListOf<Key>()
    private val cursorSteps = mutableListOf<Int>()
    private val deleteUpdates = mutableListOf<Int>()
    private var deleteReleases = 0
    private var deleteCancels = 0

    @Before
    fun setUp() {
        onMain {
            view = KeyboardView(context)
            view.setOnKeyListener { typed += it }
            view.setOnCursorMoveListener { cursorSteps += it }
            view.setOnDeleteSwipeListener(object : KeyboardView.OnDeleteSwipeListener {
                override fun onDeleteSwipeUpdate(words: Int) {
                    deleteUpdates += words
                }

                override fun onDeleteSwipeRelease() {
                    deleteReleases++
                }

                override fun onDeleteSwipeCancel() {
                    deleteCancels++
                }
            })
        }
        show(Keyboards.letters)
    }

    // --- Outils -----------------------------------------------------------------------------------------------

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Affiche [layout], mesure et positionne la vue (décalée de [TOP_PX] : la bulle d'appui long a de la place au-dessus). */
    private fun show(layout: KeyboardLayout) = onMain {
        view.layout = layout
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, TOP_PX, view.measuredWidth, TOP_PX + view.measuredHeight)
    }

    private fun slots(): List<KeySlot> {
        var result = emptyList<KeySlot>()
        onMain { result = view.keySlots() }
        return result
    }

    private fun slot(id: String): KeySlot = slots().first { it.key.id == id }

    private fun event(action: Int, x: Float, y: Float, downTime: Long): MotionEvent =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

    private fun send(action: Int, x: Float, y: Float, downTime: Long) = onMain {
        val ev = event(action, x, y, downTime)
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun tap(slot: KeySlot) {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, slot.bounds.exactCenterX(), slot.bounds.exactCenterY(), t)
        send(MotionEvent.ACTION_UP, slot.bounds.exactCenterX(), slot.bounds.exactCenterY(), t)
    }

    /** Glissement horizontal de [dxDp] depuis le centre de [slot], par pas de 4 dp, sans relâcher ; renvoie l'heure d'appui. */
    private fun dragFrom(slot: KeySlot, dxDp: Float): Long {
        val t = SystemClock.uptimeMillis()
        val x0 = slot.bounds.exactCenterX()
        val y = slot.bounds.exactCenterY()
        send(MotionEvent.ACTION_DOWN, x0, y, t)
        val steps = (kotlin.math.abs(dxDp) / 4f).toInt().coerceAtLeast(1)
        for (i in 1..steps) send(MotionEvent.ACTION_MOVE, x0 + dxDp * density * i / steps, y, t)
        return t
    }

    private fun drawToBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        onMain { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private val allLayouts: List<Pair<String, KeyboardLayout>>
        get() = buildList {
            for (language in KeyboardLanguage.entries) {
                for (numberRow in listOf(false, true)) {
                    add("lettres/$language/numberRow=$numberRow" to Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow))
                }
            }
            add("symboles" to Keyboards.layoutOf(LayoutId.SYMBOLS))
            for (field in FieldType.entries) add("champ/$field" to Keyboards.layoutOf(LayoutId.LETTERS, fieldType = field))
        }

    // --- Rendu ------------------------------------------------------------------------------------------------

    @Test
    fun toutesLesDispositionsSeDessinentSansErreurEtLaissentUneTrace() {
        for ((name, layout) in allLayouts) {
            show(layout)
            for ((shifted, caps) in listOf(false to false, true to false, true to true)) {
                onMain {
                    view.isShifted = shifted
                    view.isCapsLock = caps
                }
                val bitmap = drawToBitmap()
                val painted = (0 until bitmap.width step 7).any { x -> (0 until bitmap.height step 7).any { y -> bitmap.getPixel(x, y) != 0 } }
                assertTrue("$name (maj=$shifted, verrou=$caps) : rien n'a ete dessine", painted)
            }
        }
        onMain {
            view.isShifted = false
            view.isCapsLock = false
        }
    }

    @Test
    fun lesTouchesSontDessineesAvecLesCouleursDeLaCharte() {
        val bitmap = drawToBitmap()

        // La couleur doit apparaitre quelque part dans la zone de la touche (le libelle, l'icone et les coins arrondis
        // empechent de viser un pixel precis). Palette de KeyboardTheme : la meme que celle dessinee par la vue.
        fun assertPainted(id: String, colorRes: Int, what: String) {
            val expected = KeyboardTheme.color(context, colorRes)
            val r = slot(id).bounds
            val pixels = IntArray(r.width() * r.height())
            bitmap.getPixels(pixels, 0, r.width(), r.left, r.top, r.width(), r.height())
            assertTrue("$what : couleur #${Integer.toHexString(expected)} absente de la touche $id", pixels.any { it == expected })
        }
        assertPainted("letter_a", R.color.key_functional, "face d'une lettre (grise)")
        assertPainted("letter_a", R.color.key_functional_shadow, "ombre d'une lettre (grise)")
        assertPainted("space", R.color.key_functional, "face de l'espace (grise)")
        assertPainted("comma", R.color.key_normal, "face de la virgule (violet-gris)")
        assertPainted("comma", R.color.key_normal_shadow, "ombre de la virgule (violet-gris)")
        assertPainted("emoji", R.color.key_normal, "face de la touche emoji (violet-gris)")
        assertPainted("shift", R.color.key_normal, "face de Maj (inchangee)")
        assertPainted("backspace", R.color.key_normal, "face de retour arriere (inchangee)")
        assertPainted("toggle", R.color.key_normal, "face de 123 (inchangee)")
        assertPainted("enter", R.color.key_enter, "face de la touche Entree")
        assertPainted("enter", R.color.key_enter_shadow, "ombre de la touche Entree")
    }

    @Test
    fun uneTouchePresseeChangeDAspectEtRevientAuRelachement() {
        val a = slot("letter_a")
        val before = drawToBitmap()
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, a.bounds.exactCenterX(), a.bounds.exactCenterY(), t)
        val pressed = drawToBitmap()
        send(MotionEvent.ACTION_CANCEL, a.bounds.exactCenterX(), a.bounds.exactCenterY(), t)
        Thread.sleep(SETTLE_MS) // laisse finir une eventuelle animation de relachement
        instrumentation.waitForIdleSync()
        val after = drawToBitmap()
        assertTrue("la touche pressee doit se voir", !before.sameAs(pressed))
        assertTrue("retour a l'etat normal apres l'annulation", before.sameAs(after))
        assertTrue("une annulation ne saisit rien", typed.isEmpty())
    }

    // --- Zones tactiles ---------------------------------------------------------------------------------------

    @Test
    fun lesZonesTactilesCouvrentChaqueToucheDansLaVueSansChevauchement() {
        for ((name, layout) in allLayouts) {
            show(layout)
            val slots = slots()
            assertEquals("$name : une zone par touche", layout.rows.sumOf { it.size }, slots.size)
            for (s in slots) {
                assertTrue("$name : ${s.key.id} hors de la vue", s.bounds.left >= 0 && s.bounds.right <= view.width && s.bounds.top >= 0 && s.bounds.bottom <= view.height)
                assertTrue("$name : ${s.key.id} sans surface", s.bounds.width() > 0 && s.bounds.height() > 0)
            }
            slots.groupBy { it.bounds.top }.values.forEach { row ->
                row.sortedBy { it.bounds.left }.zipWithNext { a, b ->
                    assertTrue("$name : ${a.key.id} chevauche ${b.key.id}", a.bounds.right <= b.bounds.left + 1)
                }
            }
        }
    }

    // --- Saisie -----------------------------------------------------------------------------------------------

    @Test
    fun unAppuiCourtSurChaqueToucheLaSaisit() {
        for ((name, layout) in allLayouts) {
            show(layout)
            for (s in slots()) {
                typed.clear()
                tap(s)
                assertEquals("$name : appui sur ${s.key.id}", listOf(s.key), typed.toList())
            }
        }
    }

    @Test
    fun unAppuiEnDehorsDesToucheNeSaisitRien() {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 5f, view.height - 1f, t)
        send(MotionEvent.ACTION_UP, 5f, view.height - 1f, t)
        assertTrue("la marge basse est inerte", typed.isEmpty())
    }

    @Test
    fun changerDeToucheEnGlissantSaisitLaToucheSousLeDoigtAuRelachement() {
        val a = slot("letter_a")
        val z = slot("letter_z")
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, a.bounds.exactCenterX(), a.bounds.exactCenterY(), t)
        send(MotionEvent.ACTION_MOVE, z.bounds.exactCenterX(), z.bounds.exactCenterY(), t)
        send(MotionEvent.ACTION_UP, z.bounds.exactCenterX(), z.bounds.exactCenterY(), t)
        // Le doigt a change de touche : c'est la touche sous le doigt au relachement qui est saisie, jamais l'ancienne.
        assertEquals(listOf(z.key), typed.toList())
    }

    // --- Gestes -----------------------------------------------------------------------------------------------

    @Test
    fun glisserSurLaBarreEspaceDeplaceLeCurseurSansSaisirDEspace() {
        val space = slot("space")
        val t = dragFrom(space, 16f + 12f * 3 + 2f)
        send(MotionEvent.ACTION_UP, space.bounds.exactCenterX() + 80f * density, space.bounds.exactCenterY(), t)
        assertTrue("curseur deplace vers la droite", cursorSteps.sum() > 0)
        assertTrue("aucun espace saisi apres un glissement", typed.none { it.action == KeyAction.Space })

        cursorSteps.clear()
        val t2 = dragFrom(space, -(16f + 12f * 3 + 2f))
        send(MotionEvent.ACTION_UP, space.bounds.exactCenterX() - 80f * density, space.bounds.exactCenterY(), t2)
        assertTrue("curseur deplace vers la gauche", cursorSteps.sum() < 0)
    }

    @Test
    fun glisserVersLaGaucheSurRetourArriereSurligneLesMotsPuisLesSupprimeAuRelachement() {
        val backspace = slot("backspace")
        val t = dragFrom(backspace, -(24f + 40f + 4f))
        assertTrue("des mots sont surlignes", deleteUpdates.isNotEmpty() && deleteUpdates.last() >= 1)
        assertTrue("rien n'est supprime avant le relachement", typed.none { it.action == KeyAction.Backspace })
        send(MotionEvent.ACTION_UP, backspace.bounds.exactCenterX(), backspace.bounds.exactCenterY(), t)
        assertEquals("suppression au relachement", 1, deleteReleases)
        assertTrue("pas de caractere efface en plus", typed.none { it.action == KeyAction.Backspace })
    }

    @Test
    fun annulerLeGlissementSurRetourArriereRetireLeSurlignageSansRienSupprimer() {
        val backspace = slot("backspace")
        val t = dragFrom(backspace, -(24f + 4f))
        send(MotionEvent.ACTION_CANCEL, backspace.bounds.exactCenterX(), backspace.bounds.exactCenterY(), t)
        assertEquals(1, deleteCancels)
        assertEquals(0, deleteReleases)
        assertTrue(typed.isEmpty())
    }

    @Test
    fun unAppuiLongSurELaisseChoisirUnAccentEtLeRelachementSansBougerSaisitLeChoixParDefaut() {
        val e = slot("letter_e")
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, e.bounds.exactCenterX(), e.bounds.exactCenterY(), t)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS)
        instrumentation.waitForIdleSync()
        send(MotionEvent.ACTION_UP, e.bounds.exactCenterX(), e.bounds.exactCenterY(), t)
        val symbol = (typed.singleOrNull()?.action as? KeyAction.TypeChar)?.char
        assertEquals("choix par defaut de la bulle du e", e.key.defaultPopupChar, symbol)
    }

    @Test
    fun unAppuiLongSurLePointOuvreLaBulleDeSymbolesEtSonAnnulationNeSaisitRien() {
        val period = slot("period")
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, period.bounds.exactCenterX(), period.bounds.exactCenterY(), t)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS)
        instrumentation.waitForIdleSync()
        send(MotionEvent.ACTION_CANCEL, period.bounds.exactCenterX(), period.bounds.exactCenterY(), t)
        assertTrue("annuler ferme la bulle sans rien saisir", typed.isEmpty())
    }

    @Test
    fun deuxDoigtsSaisissentLesDeuxTouchesDansLOrdre() {
        val a = slot("letter_a")
        val z = slot("letter_z")
        val t = SystemClock.uptimeMillis()
        onMain {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = a.bounds.exactCenterX(); y = a.bounds.exactCenterY() })
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.dispatchTouchEvent(down)
            down.recycle()

            val props2 = arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
            )
            val coords2 = arrayOf(
                MotionEvent.PointerCoords().apply { x = a.bounds.exactCenterX(); y = a.bounds.exactCenterY() },
                MotionEvent.PointerCoords().apply { x = z.bounds.exactCenterX(); y = z.bounds.exactCenterY() },
            )
            val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val second = MotionEvent.obtain(t, t + 1, action, 2, props2, coords2, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.dispatchTouchEvent(second)
            second.recycle()
        }
        onMain {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = z.bounds.exactCenterX(); y = z.bounds.exactCenterY() })
            val up = MotionEvent.obtain(t, t + 2, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.dispatchTouchEvent(up)
            up.recycle()
        }
        assertEquals("la premiere touche n'est pas perdue", listOf(a.key, z.key), typed.toList())
    }

    private companion object {
        const val WIDTH_PX = 1080
        const val TOP_PX = 400
        const val LONG_PRESS_MARGIN_MS = 200L
        const val SETTLE_MS = 300L
    }
}
