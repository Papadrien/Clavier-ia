package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Lot 22 (non-régression de la refonte graphique) : garde-fous sur les ressources, sans appareil.
 *
 * - règle du plan « ne pas transformer les éléments graphiques en PNG » : aucune image raster dans `drawable*` ;
 * - Open Sans (lot 02) : la famille déclare ses trois graisses et les fichiers de police sont là ;
 * - toute ressource référencée (XML ou Kotlin) existe : une couleur, une dimension, une icône ou un texte renommé
 *   pendant la refonte ferait sinon planter l'application à l'exécution (`Resources.NotFoundException`) ou casser
 *   la compilation ;
 * - la palette de nuit ne déclare aucun nom absent de la palette de jour.
 */
class RefonteResourcesRegressionTest {

    private val types = listOf("color", "dimen", "drawable", "font", "string")

    private fun mainDir(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory } ?: fail("Dossier src/main introuvable")

    private val res: File get() = File(mainDir(), "res")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun xmlFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()

    private fun valueFiles(prefix: String = "values"): List<File> =
        res.listFiles { f -> f.isDirectory && (f.name == prefix || f.name.startsWith("$prefix-")) }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }

    /** Noms de ressources déclarés, par type. */
    private fun definedNames(): Map<String, Set<String>> {
        val defined = types.associateWith { mutableSetOf<String>() }
        for (file in valueFiles()) {
            val children = parse(file).childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                val name = node.getAttribute("name").ifEmpty { continue }
                val type = if (node.tagName == "item") node.getAttribute("type") else node.tagName
                defined[type]?.add(name)
            }
        }
        // Ressources déclarées par leur nom de fichier : drawables, polices, listes de couleurs.
        res.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val type = when {
                dir.name == "drawable" || dir.name.startsWith("drawable-") -> "drawable"
                dir.name == "font" -> "font"
                dir.name == "color" -> "color"
                else -> null
            }
            if (type != null) dir.listFiles().orEmpty().filter { it.isFile }.forEach { defined.getValue(type).add(it.nameWithoutExtension) }
        }
        return defined
    }

    @Test
    fun `aucune image raster dans les drawables`() {
        val raster = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        // Exception documentée (lot UX 1) : le logo de marque est une illustration raster (dégradés et textures des
        // touches) fournie par le design, non vectorisable sans perte ; c'est la seule image autorisée.
        val allowed = setOf("taipo_logo_horizontal")
        val found = res.listFiles { f -> f.isDirectory && f.name.startsWith("drawable") }.orEmpty()
            .flatMap {
                it.walkTopDown()
                    .filter { f -> f.isFile && f.extension.lowercase() in raster && f.nameWithoutExtension !in allowed }
                    .toList()
            }
        assertTrue(found.isEmpty(), "Images raster interdites par le plan de refonte : ${found.map { it.name }}")
    }

    @Test
    fun `open sans declare ses trois graisses et les fichiers existent`() {
        val family = File(res, "font/open_sans.xml")
        assertTrue(family.isFile, "res/font/open_sans.xml introuvable")
        val fonts = parse(family).getElementsByTagName("font")
        val weights = (0 until fonts.length).associate {
            val font = fonts.item(it) as Element
            font.getAttribute("android:fontWeight") to font.getAttribute("android:font")
        }
        assertEquals(setOf("400", "500", "700"), weights.keys, "graisses declarees")
        for (ref in weights.values) {
            val name = ref.removePrefix("@font/")
            val file = File(res, "font/$name.ttf")
            assertTrue(file.isFile && file.length() > 0, "police absente ou vide : $name.ttf")
        }
    }

    @Test
    fun `toute ressource referencee dans les fichiers xml existe`() {
        val defined = definedNames()
        val reference = Regex("@(${types.joinToString("|")})/([A-Za-z0-9_.]+)")
        val missing = mutableListOf<String>()
        val files = xmlFiles(res) + File(mainDir(), "AndroidManifest.xml")
        for (file in files.filter { it.isFile }) {
            for (match in reference.findAll(file.readText())) {
                val (type, name) = match.destructured
                if (name !in defined.getValue(type)) missing += "${file.name} -> @$type/$name"
            }
        }
        assertTrue(missing.isEmpty(), "References XML sans ressource : $missing")
    }

    @Test
    fun `toute ressource referencee dans le code kotlin existe`() {
        val defined = definedNames()
        // `android.R.xxx` (ressources du systeme) est exclu ; `fr.junade.taipo.R.xxx` et `R.xxx` sont gardes.
        val reference = Regex("(?<!android\\.)(?<!\\w)R\\.(${types.joinToString("|")})\\.(\\w+)")
        val missing = mutableListOf<String>()
        val sources = File(mainDir(), "java").walkTopDown().filter { it.isFile && it.extension == "kt" }
        for (file in sources) {
            for (match in reference.findAll(file.readText())) {
                val (type, name) = match.destructured
                if (name !in defined.getValue(type)) missing += "${file.name} -> R.$type.$name"
            }
        }
        assertTrue(missing.isEmpty(), "References Kotlin sans ressource : $missing")
    }

    @Test
    fun `la palette de nuit ne declare aucun nom absent de la palette de jour`() {
        fun names(file: File): Set<String> {
            val colors = parse(file).getElementsByTagName("color")
            return (0 until colors.length).map { (colors.item(it) as Element).getAttribute("name") }.toSet()
        }
        val day = names(File(res, "values/colors.xml"))
        val night = names(File(res, "values-night/colors.xml"))
        assertTrue((night - day).isEmpty(), "Couleurs de nuit sans couleur de jour : ${night - day}")
    }

    @Test
    fun `les dimensions sont des valeurs ou des alias valides`() {
        val valid = Regex("^(@dimen/\\w+|-?(\\d+\\.?\\d*|\\.\\d+)(dp|sp|px|dip|pt|mm|in))$")
        val dimens = parse(File(res, "values/dimens.xml")).getElementsByTagName("dimen")
        assertTrue(dimens.length > 0, "dimens.xml ne declare aucune dimension")
        val invalid = (0 until dimens.length).map { dimens.item(it) as Element }
            .filter { !valid.matches(it.textContent.trim()) }
            .map { "${it.getAttribute("name")}=${it.textContent.trim()}" }
        assertTrue(invalid.isEmpty(), "Dimensions invalides : $invalid")
    }
}
