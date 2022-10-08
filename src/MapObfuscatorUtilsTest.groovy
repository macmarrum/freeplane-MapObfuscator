import org.junit.jupiter.api.Test

import static MapObfuscatorUtils._obfuscateUriExceptLastSegmentsAndHash

class MapObfuscatorUtilsTest {

    @Test
    void _obfuscateUriExceptLastSegmentsAndHash_0() {
        def input = 'file:/%20/deab%20c/def.txt'
        def expected = 'file:/%20/xxxx%20x/xxx.xxx'.toURI()
        def actual = _obfuscateUriExceptLastSegmentsAndHash(input, 0)
        assert expected == actual
    }

    @Test
    void _obfuscateUriExceptLastSegmentsAndHash_1() {
        def input = 'file:/%20/deab%20c/def.txt'
        def expected = 'file:/%20/xxxx%20x/def.txt'.toURI()
        def actual = _obfuscateUriExceptLastSegmentsAndHash(input, 1)
        assert expected == actual
    }

}