package io.github.macmarrum.freeplane

import groovy.transform.SourceURI
import org.junit.jupiter.api.Test

import java.nio.file.Paths

import static io.github.macmarrum.freeplane.MapObfuscatorUtils._obfuscateUriExceptLastSegmentsAndHash
import static io.github.macmarrum.freeplane.MapObfuscatorUtils.xHtml

class MapObfuscatorUtilsTest {

    static @SourceURI URI sourceUri
    static me = Paths.get(sourceUri)

    @Test
    void _obfuscateUriExceptLastSegmentsAndHash_0() {
        def input = 'file:/%20/deab%20c/def.txt'
        def expected = 'file:/%20/xxxx%20x/xxx.xxx'
        def actual = _obfuscateUriExceptLastSegmentsAndHash(input, 0)
        assert expected == actual
    }

    @Test
    void _obfuscateUriExceptLastSegmentsAndHash_1() {
        def input = 'file:/%20/deab%20c/def.txt'
        def expected = 'file:/%20/xxxx%20x/def.txt'
        def actual = _obfuscateUriExceptLastSegmentsAndHash(input, 1)
        assert expected == actual
    }

    @Test
    void xHtml() {
        def input = '''<html>
  <head>
    
  </head>
  <body>
    <p>
      A <b>B</b>&nbsp;<i>C</i>&nbsp;D
    </p>
  </body>
</html>'''
        def expected = '''<html>
  <head>
    
  </head>
  <body>
    <p>
      x <b>x</b>&nbsp;<i>x</i>&nbsp;x
    </p>
  </body>
</html>'''
        def actual = xHtml(input)
        assert expected == actual
    }

    @Test
    void obfuscateStyleNames() {
        def input = me.parent.resolve('obfuscateStyleNames_input.txt').toFile()
        def expected = me.parent.resolve('obfuscateStyleNames_expected.txt').getText('UTF-8')
        def actual = MapObfuscatorUtils.getTextWithRenamedStyles(input)
        assert expected == actual
    }

    @Test
    void xHref() {
        Opt.mo_obfuscate_links = true
        Opt.mo_preserve_last_segment_in_links = true
        def input = '<a href="http://google.com">google.com</a><img src="https://yahoo.com/some_image.png">'
        def expected = '<a href="http://xxxxxx.xxx">google.com</a><img src="https://xxxxx.xxx/some_image.png">'
        def actual = MapObfuscatorUtils.xHrefIfEnabled(input)
        assert expected == actual
    }

    @Test
    void splitExtension() {
        def input = 'A.file with $om#.ext'
        def expected = ['A.file with $om#', 'ext']
        def actual = MapObfuscatorUtils.splitExtension(input)
        assert expected == actual
    }
}