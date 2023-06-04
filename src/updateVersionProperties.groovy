import groovy.transform.SourceURI

import java.nio.file.Paths
import java.time.LocalDate

@SourceURI URI sourceUri
def me = Paths.get(sourceUri)
def versionPropertiesFile = me.parent.parent.resolve('version.properties')

def version = LocalDate.now().format('Y.MM.dd')

/** This is how the information is used:
 * org.freeplane.main.mindmapmode.HttpVersionClient#parseProperties(java.net.URL, org.freeplane.core.util.FreeplaneVersion)
 * org.freeplane.core.util.FreeplaneVersion#getVersion(java.lang.String) -- Ignores leading 'v' (e.g. "v1.2.6") and accept '.' and ' ' as separator.
 */
def template = """\
version=${version}
downloadUrl=https://github.com/macmarrum/freeplane-MapObfuscator/releases/download/v${version}/MapObfuscator-v${version}.addon.mm
freeplaneVersionFrom=1.8.0
"""
println template
versionPropertiesFile.text = template
println "saved to ${versionPropertiesFile}"
