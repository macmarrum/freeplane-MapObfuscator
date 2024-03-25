package io.github.macmarrum.freeplane

import groovy.transform.SourceURI
import org.junit.jupiter.api.Test

import java.nio.file.Paths

class MapObfuscatorUtilsTest {

    static @SourceURI
    URI sourceUri
    static me = Paths.get(sourceUri)

    @Test
    void x() {
        def input = $/
            `1234567890-=
            ~!@#$%^&*()_+
            qwertyuiop[]\
            QWERTYUIOP{}|
            ≠²³¢€½§·«»–
            ¡¿£¼‰∧≈¾±°—
            ;':" ,./<>?
            ä ö ü ß
            ąćęłńóśźż
            ĄĆĘŁŃÓŚŹŻ
            /$.stripIndent()
        def expected = $/
            `xxxxxxxxxx-=
            ~!@#$%^&*()_+
            xxxxxxxxxx[]\
            xxxxxxxxxx{}|
            xxxxxxxxxxx
            xxxxxxxxxxx
            ;':" ,./<>?
            x x x x
            xxxxxxxxx
            xxxxxxxxx
            /$.stripIndent()
        def actual = MapObfuscatorUtils.x(input)
        assert expected == actual
    }

    @Test
    void xUri() {
        def input = '//duckduckgo.com:8080/bangs?a=1&b=2'
        def expected = '//xxxxxxxxxx.xxx:xxxx/xxxxx?x=x&x=x'
        def actual = MapObfuscatorUtils.xUri(input)
        assert expected == actual
    }

    @Test
    void _obfuscateSspExceptLastSegments_0() {
        def input = '//duckduckgo.com'
        def expected = '//xxxxxxxxxx.xxx'
        def actual = MapObfuscatorUtils._obfuscateSspExceptLastSegments(input, 0)
        assert expected == actual
    }

    @Test
    void _obfuscateSspExceptLastSegments_1() {
        def input = '//duckduckgo.com/bangs'
        def expected = '//xxxxxxxxxx.xxx/bangs'
        def actual = MapObfuscatorUtils._obfuscateSspExceptLastSegments(input, 1)
        assert expected == actual
    }

    @Test
    void _obfuscateSspExceptLastSegments_optionB_0() {
        def input = '/%20/deab%20c/def.txt'
        def expected = '/%20/xxxx%20x/xxx.xxx'
        def actual = MapObfuscatorUtils._obfuscateSspExceptLastSegments(input, 0)
        assert expected == actual
    }

    @Test
    void _obfuscateSspExceptLastSegments_optionB_1() {
        def input = '/%20/deab%20c/def.txt'
        def expected = '/%20/xxxx%20x/def.txt'
        def actual = MapObfuscatorUtils._obfuscateSspExceptLastSegments(input, 1)
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
        def actual = MapObfuscatorUtils.xHtml(input)
        assert expected == actual
    }

    @Test
    void obfuscateStyleNamesAndOrTemplatePaths_both() {
        Opt.mo_obfuscate_style_names = true
        Opt.mo_obfuscate_template_paths = true
        Opt.mo_preserve_last_segment_in_links = true
        def input = me.parent.resolve('obfuscateStyleNamesAndOrTemplatePaths_input.txt').toFile()
        def expected = me.parent.resolve('obfuscateStyleNamesAndOrTemplatePaths_expected.txt').getText('UTF-8')
        def actual = MapObfuscatorUtils.getTextWithRenamedStylesAndOrTemplatePaths(input)
        // me.parent.resolve('obfuscateStyleNames_actual.txt').setText(actual, 'UTF-8')
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