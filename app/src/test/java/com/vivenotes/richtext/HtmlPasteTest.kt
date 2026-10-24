package com.vivenotes.richtext

import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Clipboard HTML as browsers and editors write it — Chrome's inline computed styles, Google Docs'
 * spans, GitHub's task lists, Firefox's fragment comments — pasted as the blocks it looks like.
 */
class HtmlPasteTest {
    private val caret = setOf(Mark.FontFamily("sans-serif"), Mark.FontSize(24))

    private fun shape(blocks: List<Block>) = blocks.map { Triple(it.type, it.indent, it.text) }

    private fun List<Block>.run(text: String) = flatMap { it.runs }.single { it.text == text }

    /** Chrome copying part of a Wikipedia article: computed styles on every element. */
    @Test
    fun aWebPageKeepsItsHeadingsParagraphsAndLists() {
        val html = """<meta charset='utf-8'><h2 style="color: rgb(32, 33, 34); font-family: 'Linux Libertine', Georgia, Times, serif; font-size: 28.8px; font-weight: normal; margin: 0.25em 0px;"><span class="mw-headline" id="Definition">Definition</span><span class="mw-editsection" style="display: none;">edit</span></h2><p style="color: rgb(32, 33, 34); font-family: sans-serif; font-size: 14px; margin: 0.5em 0px;">In <a href="https://en.wikipedia.org/wiki/Mathematics" title="Mathematics" style="color: rgb(51, 102, 204); text-decoration: none;">mathematics</a>, the <b>derivative</b> shows the
            <i>rate of change</i>.</p><ul style="margin: 0.3em 0px 0px 1.6em; list-style-type: disc;"><li style="margin-bottom: 0.1em;">first</li><li>second<ul><li>nested</li></ul></li></ul><ol><li>one</li><li>two</li></ol>"""

        val blocks = pastedHtmlBlocks(html, caret)!!

        // Wikipedia's own margins are a few pixels: no blank lines, except before the list without a style.
        assertEquals(listOf(
            Triple(BlockType.Heading2, 0, "Definition"),
            Triple(BlockType.Paragraph, 0, "In mathematics, the derivative shows the rate of change."),
            Triple(BlockType.Bullet, 0, "first"),
            Triple(BlockType.Bullet, 0, "second"),
            Triple(BlockType.Bullet, 1, "nested"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Numbered, 0, "one"),
            Triple(BlockType.Numbered, 0, "two"),
        ), shape(blocks))
        assertEquals("a heading is bold by being one, and the page's grey goes", caret, blocks.run("Definition").marks)
        assertEquals(caret + Mark.Link("https://en.wikipedia.org/wiki/Mathematics"), blocks.run("mathematics").marks)
        assertEquals(caret + Mark.Bold, blocks.run("derivative").marks)
        assertEquals(caret + Mark.Italic, blocks.run("rate of change").marks)
    }

    /** Google Docs: one `<b style="font-weight:normal">` round it all, `margin:0` paragraphs, flat lists. */
    @Test
    fun googleDocsKeepsItsStylesListLevelsAndChecklists() {
        val span = "font-family:Arial,sans-serif;color:#000000;background-color:transparent;font-style:normal;" +
            "font-variant:normal;text-decoration:none;vertical-align:baseline;white-space:pre;white-space:pre-wrap;"
        val html = """<meta charset="utf-8"><b style="font-weight:normal;" id="docs-internal-guid-6b1f2c3d-7fff-1e2a-b3c4-5d6e7f809a1b"><h1 dir="ltr" style="line-height:1.38;margin-top:20pt;margin-bottom:6pt;"><span style="font-size:20pt;${span}font-weight:400;">Plan</span></h1><p dir="ltr" style="line-height:1.38;margin-top:0pt;margin-bottom:0pt;"><span style="font-size:11pt;${span}font-weight:700;">Bold</span><span style="font-size:11pt;${span}font-weight:400;">  and </span><span style="font-size:11pt;font-family:Arial,sans-serif;color:#ff0000;font-weight:400;font-style:italic;white-space:pre-wrap;">red italic</span></p><br><ul style="margin-top:0;margin-bottom:0;padding-inline-start:48px;"><li dir="ltr" style="list-style-type:disc;font-size:11pt;" aria-level="1"><p dir="ltr" style="line-height:1.38;margin-top:0pt;margin-bottom:0pt;" role="presentation"><span style="font-size:11pt;${span}font-weight:400;">Item</span></p></li><li dir="ltr" style="list-style-type:circle;font-size:11pt;" aria-level="2"><p dir="ltr" style="line-height:1.38;margin-top:0pt;margin-bottom:0pt;" role="presentation"><span style="font-size:11pt;${span}font-weight:400;">Sub item</span></p></li></ul><ul style="margin-top:0;margin-bottom:0;"><li dir="ltr" role="checkbox" aria-checked="true" style="list-style-type:none;" aria-level="1"><p dir="ltr" style="margin-top:0pt;margin-bottom:0pt;" role="presentation"><span style="font-size:11pt;font-weight:400;text-decoration:line-through;white-space:pre-wrap;">Done</span></p></li><li dir="ltr" role="checkbox" aria-checked="false" aria-level="1"><p dir="ltr" style="margin-top:0pt;margin-bottom:0pt;" role="presentation"><span style="font-weight:400;">Open</span></p></li></ul></b>"""

        val blocks = pastedHtmlBlocks(html, caret)!!

        assertEquals(listOf(
            Triple(BlockType.Heading1, 0, "Plan"),
            Triple(BlockType.Paragraph, 0, "Bold  and red italic"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Bullet, 0, "Item"),
            Triple(BlockType.Bullet, 1, "Sub item"),
            Triple(BlockType.Todo, 0, "Done"),
            Triple(BlockType.Todo, 0, "Open"),
        ), shape(blocks))
        assertEquals(listOf(true, false), blocks.filter { it.type == BlockType.Todo }.map { it.checked })
        assertEquals(caret + Mark.Bold, blocks.run("Bold").marks)
        assertEquals("Docs' font-weight:normal wrapper is not bold", caret, blocks.run("  and ").marks)
        assertEquals(caret + setOf(Mark.Italic, Mark.TextColor(0xFFFF0000.toInt())), blocks.run("red italic").marks)
        assertEquals(caret + Mark.Strikethrough, blocks.run("Done").marks)
    }

    /** GitHub's rendered Markdown task list, copied from Chrome or Firefox. */
    @Test
    fun checkboxesMakeToDosTickedAsTheyWere() {
        val html = """<ul class="contains-task-list"><li class="task-list-item enabled"><input type="checkbox" class="task-list-item-checkbox" disabled="" checked=""> Write tests</li>
            <li class="task-list-item enabled"><input type="checkbox" class="task-list-item-checkbox" disabled=""> Ship it</li></ul>"""

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(listOf(Triple(BlockType.Todo, 0, "Write tests"), Triple(BlockType.Todo, 0, "Ship it")), shape(blocks))
        assertEquals(listOf(true, false), blocks.map { it.checked })
    }

    @Test
    fun codeKeepsItsLinesSpacesAndMonospace() {
        val html = "<p>Run:</p><pre style=\"background-color: rgb(246, 248, 250);\"><code class=\"language-kotlin\">\nfun main() {\n" +
            "    println(&quot;hi&quot;)\n}\n</code></pre><p>Inline <code>val x = 1</code> code.</p>"

        val blocks = pastedHtmlBlocks(html, caret)!!

        assertEquals(listOf(
            Triple(BlockType.Paragraph, 0, "Run:"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Code, 0, "fun main() {"),
            Triple(BlockType.Code, 0, "    println(\"hi\")"),
            Triple(BlockType.Code, 0, "}"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "Inline val x = 1 code."),
        ), shape(blocks))
        val monospace = setOf(Mark.FontFamily("monospace"), Mark.FontSize(24))
        assertEquals("the page's pale grey background goes too", monospace, blocks.run("    println(\"hi\")").marks)
        assertEquals(monospace, blocks.run("val x = 1").marks)
    }

    /** Firefox wraps what it copies in fragment comments; Windows' "HTML Format" can keep its header. */
    @Test
    fun fragmentMarkersHeadersEntitiesAndBreaksAreReadAsTheBrowserShowsThem() {
        val html = "Version:0.9\r\nStartHTML:0000000105\r\nEndHTML:0000000400\r\n<html><body>\n<!--StartFragment-->" +
            "<blockquote><p>To be&nbsp;or not to be &mdash; that&#8217;s the question &amp more &#x1F600;<br>&mdash; Hamlet</p></blockquote>" +
            "<p style=\"text-align: center\">Centred</p><div align=\"right\">Right</div><!--EndFragment-->\n</body>\n</html>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(listOf(
            Triple(BlockType.Quote, 0, "To be or not to be — that’s the question & more 😀"),
            Triple(BlockType.Quote, 0, "— Hamlet"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "Centred"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "Right"),
        ), shape(blocks))
        assertEquals(listOf(Align.Start, Align.Start, Align.Start, Align.Center, Align.Start, Align.End), blocks.map { it.align })
    }

    /** A quote's stripe runs through the space between its paragraphs, and not above its first. */
    @Test
    fun blankLinesBelongToAQuoteOnlyInsideIt() {
        val html = "<h3>Notes</h3><blockquote><p>first</p><p>second</p></blockquote><p>after</p>"

        assertEquals(listOf(
            Triple(BlockType.Heading3, 0, "Notes"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Quote, 0, "first"),
            Triple(BlockType.Quote, 0, ""),
            Triple(BlockType.Quote, 0, "second"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "after"),
        ), shape(pastedHtmlBlocks(html)!!))
    }

    @Test
    fun inlineMarksNestAndAStyleOverridesItsTag() {
        val html = "<p><b>bold <i>both</i></b> <s>gone</s> <u>under</u> x<sub>2</sub> e<sup>x</sup> " +
            "<strong style=\"font-weight: 400\">plain</strong> <span style=\"vertical-align: super\">up</span></p>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals("bold both gone under x2 ex plain up", blocks.single().text)
        assertEquals(setOf(Mark.Bold), blocks.run("bold ").marks)
        assertEquals(setOf(Mark.Bold, Mark.Italic), blocks.run("both").marks)
        assertEquals(setOf(Mark.Strikethrough), blocks.run("gone").marks)
        assertEquals(setOf(Mark.Underline), blocks.run("under").marks)
        assertEquals(setOf(Mark.Subscript), blocks.run("2").marks)
        assertEquals(setOf(Mark.Superscript), blocks.run("x").marks)
        assertEquals(setOf(Mark.Superscript), blocks.run("up").marks)
        assertTrue(blocks.flatMap { it.runs }.none { it.text.contains("plain") && Mark.Bold in it.marks })
    }

    /** Colours that are colours stay; the greys, blacks and whites of a page's text and background go. */
    @Test
    fun onlyRealColoursAndHighlightsAreKept() {
        val html = "<p><span style=\"color: rgb(220, 38, 38)\">red</span> <span style=\"color:#333\">grey</span> " +
            "<mark>marked</mark> <span style=\"background-color: rgb(255, 255, 255)\">white</span> " +
            "<span style=\"background: rgb(255, 240, 0) none repeat scroll 0% 0%\">yellow</span> " +
            "<span style=\"color: rgb(220, 38, 38)\">dark <span style=\"color: rgb(0, 0, 0)\">black</span></span> " +
            "<font color=\"blue\">font</font> <span style=\"color: hsl(120, 100%, 25%)\">green</span></p>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(setOf(Mark.TextColor(0xFFDC2626.toInt())), blocks.run("red").marks)
        assertEquals(emptySet<Mark>(), blocks.run(" grey ").marks)
        assertEquals(setOf(Mark.Highlight(0x66FFEB3B)), blocks.run("marked").marks)
        assertTrue(blocks.flatMap { it.runs }.none { it.text.contains("white") && it.marks.isNotEmpty() })
        assertEquals(setOf(Mark.Highlight(0x66FFF000)), blocks.run("yellow").marks)
        assertEquals(setOf(Mark.TextColor(0xFFDC2626.toInt())), blocks.run("dark ").marks)
        assertTrue("black inside red goes back to the page's ink",
            blocks.flatMap { it.runs }.single { it.text.startsWith("black") }.marks.none { it is Mark.TextColor })
        assertEquals(setOf(Mark.TextColor(0xFF0000FF.toInt())), blocks.run("font").marks)
        assertEquals(setOf(Mark.TextColor(0xFF008000.toInt())), blocks.run("green").marks)
    }

    @Test
    fun linksAreKeptOnlyWhenTheyLeadSomewhere() {
        val html = "<p><a href=\"https://example.com/a\">absolute</a> <a href=\"/wiki/Page\">relative</a> " +
            "<a href=\"javascript:alert(1)\">script</a> <a href=\"#top\">anchor</a> <a href=\"//cdn.example.org/x\">cdn</a> " +
            "<a href=\"mailto:someone@example.com\">mail</a></p>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(setOf(Mark.Link("https://example.com/a")), blocks.run("absolute").marks)
        assertEquals(setOf(Mark.Link("https://cdn.example.org/x")), blocks.run("cdn").marks)
        assertEquals(listOf("absolute", "cdn"), blocks.flatMap { it.runs }.filter { run -> run.marks.any { it is Mark.Link } }.map { it.text })

        val based = pastedHtmlBlocks("<base href=\"https://en.wikipedia.org/wiki/Derivative\"><a href=\"/wiki/Limit\">limit</a> " +
            "<a href=\"Slope\">slope</a>")!!
        assertEquals(setOf(Mark.Link("https://en.wikipedia.org/wiki/Limit")), based.run("limit").marks)
        assertEquals(setOf(Mark.Link("https://en.wikipedia.org/wiki/Slope")), based.run("slope").marks)
        val host = pastedHtmlBlocks("<base href=\"https://example.com\"><a href=\"page.html\">page</a>")!!
        assertEquals(setOf(Mark.Link("https://example.com/page.html")), host.run("page").marks)
    }

    @Test
    fun tablesBecomeRowsOfTabSeparatedCells() {
        val html = "<table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody><tr><td><p>x</p></td><td>1</td></tr>" +
            "<tr><td>y<td>2</table><p>after</p>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(listOf("Name\tValue", "x\t1", "y\t2", "", "after"), blocks.map { it.text })
    }

    @Test
    fun itemsCloseThemselvesAndAnItemsLaterParagraphsAreItsOwn() {
        val html = "<ul><li>one<li>two</ul><p>a<p>b<ul><li><p>First para</p><p>Second para</p><ul><li>deeper</li></ul></li></ul>"

        val blocks = pastedHtmlBlocks(html)!!

        assertEquals(listOf(
            Triple(BlockType.Bullet, 0, "one"),
            Triple(BlockType.Bullet, 0, "two"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "a"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Paragraph, 0, "b"),
            Triple(BlockType.Paragraph, 0, ""),
            Triple(BlockType.Bullet, 0, "First para"),
            Triple(BlockType.Paragraph, 1, "Second para"),
            Triple(BlockType.Bullet, 1, "deeper"),
        ), shape(blocks))
    }

    @Test
    fun scriptsStylesAndHiddenTextNeverArrive() {
        val html = "<html><head><title>Tab title</title><style>p { color: red; }</style></head><body>" +
            "<script>document.write('<p>no</p>')</script><p>shown<span style=\"display:none\">hidden</span>" +
            "<span aria-hidden=\"true\">icon</span><span hidden>gone</span></p><noscript>enable JS</noscript>" +
            "<svg><text>chart</text></svg><button>Copy</button></body></html>"

        assertEquals(listOf("shown"), pastedHtmlBlocks(html)!!.map { it.text })
    }

    /** Wikipedia draws formulas as pictures with LaTeX for alternative text; KaTeX keeps it in MathML. */
    @Test
    fun formulasArriveAsTheirLatex() {
        val wikipedia = "<p>The slope <span class=\"mwe-math-element\"><span class=\"mwe-math-mathml-inline\" style=\"display: none;\">" +
            "<math><semantics><mrow><mi>m</mi></mrow><annotation encoding=\"application/x-tex\">{\\displaystyle m}</annotation>" +
            "</semantics></math></span><img src=\"https://wikimedia.org/api/rest_v1/media/math/render/svg/0a07d98b\" " +
            "alt=\"{\\displaystyle m}\"></span> is constant.</p>"
        assertEquals("The slope \${\\displaystyle m}\$ is constant.", pastedHtmlBlocks(wikipedia)!!.single().text)

        val katex = "<p>so <span class=\"katex\"><span class=\"katex-mathml\"><math xmlns=\"http://www.w3.org/1998/Math/MathML\">" +
            "<semantics><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow><annotation encoding=\"application/x-tex\">x^2</annotation>" +
            "</semantics></math></span><span class=\"katex-html\" aria-hidden=\"true\"><span class=\"mord mathnormal\">x</span>" +
            "<span class=\"msupsub\">2</span></span></span> grows</p>"
        assertEquals("so \$x^2\$ grows", pastedHtmlBlocks(katex)!!.single().text)
    }

    @Test
    fun whitespaceCollapsesAsOnThePage() {
        val html = "\n  <div>\n    <span>  Hello \n   world  </span>\n  </div>\n  <div><span>next</span>   <span>words</span></div>\n"

        assertEquals(listOf("Hello world", "next words"), pastedHtmlBlocks(html)!!.map { it.text })
    }

    @Test
    fun breaksMakeLinesAndATrailingBreakEndsNone() {
        val html = "<div>a<br>b<br><br>c<br></div><div>d</div><p>e<br></p><p>f</p>"

        assertEquals(listOf("a", "b", "", "c", "d", "", "e", "", "f"), pastedHtmlBlocks(html)!!.map { it.text })
    }

    @Test
    fun nothingToPasteIsNull() {
        assertNull(pastedHtmlBlocks(""))
        assertNull(pastedHtmlBlocks("<html><body>  <p> </p><br><img src=\"x.png\"></body></html>"))
        assertNull(pastedHtmlBlocks("<style>p{}</style>"))
    }

    @Test
    fun brokenMarkupStillReadsAsText() {
        val html = "<p>a < b and c > d <b>unclosed <i>tags</p><p class=x>next &unknown; &amp</p>"

        assertEquals(listOf("a < b and c > d unclosed tags", "", "next &unknown; &"), pastedHtmlBlocks(html)!!.map { it.text })
    }

    @Test
    fun colourSyntaxIsReadAsCssWritesIt() {
        assertEquals(0xFFFF0000.toInt(), cssColor("#f00"))
        assertEquals(0x80FF0000.toInt(), cssColor("#ff000080"))
        assertEquals(0xFF336699.toInt(), cssColor("rgb(51, 102, 153)"))
        assertEquals(0x80336699.toInt(), cssColor("rgba(51, 102, 153, 0.5)"))
        assertEquals(0xFF336699.toInt(), cssColor("rgb(51 102 153)"))
        assertEquals(0xFF0000FF.toInt(), cssColor("Blue"))
        assertEquals(0, cssColor("transparent"))
        assertNull(cssColor("var(--fg)"))
        assertNull(cssColor("#12"))
        assertEquals("a&b <c> \"d\" 'e' é \u00A0", decodeEntities("a&amp;b &lt;c&gt; &quot;d&quot; &#39;e&#x27; &eacute; &nbsp;"))
    }
}
