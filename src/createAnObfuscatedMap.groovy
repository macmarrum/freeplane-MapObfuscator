// @ExecutionModes({ON_SINGLE_NODE})


import groovy.xml.XmlSlurper
import groovy.xml.XmlUtil
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.NodeChild
import groovy.xml.slurpersupport.NodeChildren
import org.freeplane.api.Node
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.view.swing.features.filepreview.ExternalResource
import org.freeplane.view.swing.features.filepreview.ViewerController

import javax.swing.*

final NAME = 'MapObfuscator'
final OBFUSCATED_PREFIX = 'obfuscated~'

c = ScriptUtils.c()
node = ScriptUtils.node()
NodeModel nodeModel = node.delegate

if (!node.mindMap.saved) {
    if (MapObfuscatorUtils.confirmSaveMap(nodeModel)) {
        def allowInteraction = true
        node.mindMap.save(allowInteraction)
    } else
        return
}

def file = node.mindMap.file
if (file.is(null))
    return

if (file.name.startsWith(OBFUSCATED_PREFIX)) {
    MapObfuscatorUtils.informAlreadyObfuscated()
    return
}

def newName = OBFUSCATED_PREFIX + file.name
def newStem = newName.replaceAll(/\.mm$/, '')
def targetFile = new File(file.parentFile, newName)

def isOkToObfuscate = true
if (targetFile.exists()) {
    isOkToObfuscate = MapObfuscatorUtils.confirmOverwrite(targetFile, nodeModel)
}
if (!isOkToObfuscate)
    return
LogUtils.info("$NAME: XmlSlurper parse ${file.name}, do replacements and save to ${targetFile.name}")
new MMFileObfuscator(file, targetFile).obfuscate()

LogUtils.info("$NAME: open and obfuscate ${targetFile.name}")
def mindMap = c.mapLoader(targetFile).withView().mindMap
mindMap.root.findAll().each { Node n ->
    MapObfuscatorUtils.obfuscateCore(n)
    MapObfuscatorUtils.obfuscateDetails(n)
    MapObfuscatorUtils.obfuscateNote(n)
    MapObfuscatorUtils.obfuscateLinks(n)
    MapObfuscatorUtils.obfuscateAttributes(n)
    MapObfuscatorUtils.obfuscateConnectors(n)
    NodeModel m = n.delegate
    MapObfuscatorUtils.obfuscateImagePath(m)
}
mindMap.root.text = newStem
LogUtils.info("$NAME: save ${targetFile.name}")
def allowInteraction = true
mindMap.save(allowInteraction)


class MMFileObfuscator {
    private final File sourceFile
    private final File targetFile
    private final GPathResult map
    private final oldToNewStyleName
    private static final UTF8 = 'UTF-8'
    private static final STYLE_NAME_PREFIX = 'Style'
    private static final MAP_STYLE = 'MapStyle'
    private static final STYLES_USER_DEFINED = 'styles.user-defined'
    private static final TEXT = 'TEXT'
    private static final NAMES_OF_NODES_WITH_STYLE_REF = ['node', 'conditional_style']
    private static final STYLE_REF = 'STYLE_REF'

    MMFileObfuscator(File sourceFile, File targetFile) {
        this.sourceFile = sourceFile
        this.targetFile = targetFile
        map = parseXml(sourceFile ?: targetFile)
        oldToNewStyleName = makeOldToNewStyleNameMap()
    }

    MMFileObfuscator(File targetFile) {
        this(null, targetFile)
    }

    static GPathResult parseXml(File file) {
        def slurper = new XmlSlurper()
        slurper.setFeature('http://apache.org/xml/features/disallow-doctype-decl', false)
        def doctype = '<!DOCTYPE map [<!ENTITY nbsp "&#160;">]>'
        def text = doctype + file.getText(UTF8)
        return slurper.parseText(text)
    }

    Map<String, String> makeOldToNewStyleNameMap() {
        def customStyleNames = getUserDefinedCustomStyleNames()
        def numberOfDigits = (customStyleNames.size() as String).size()
        def old2new = new HashMap<String, String>(customStyleNames.size())
        def styleNameFormat = "${STYLE_NAME_PREFIX}%0${numberOfDigits}d"
        while (true) {
            customStyleNames.eachWithIndex { oldName, i ->
                old2new[oldName] = sprintf(styleNameFormat, i + 1)
            }
            def foundTheSameNewStyleNameInOldStyleNames = old2new.values().any { newName -> old2new.containsKey(newName) }
            if (!foundTheSameNewStyleNameInOldStyleNames)
                break
            styleNameFormat = "_${styleNameFormat}"
        }
        return old2new
    }

    def getMapStyle() {
        return map.node.hook.find { it.@NAME == MAP_STYLE }
    }

    def getUserDefinedStyleParent() {
        return getMapStyle().map_styles.stylenode.stylenode.find { it.@LOCALIZED_TEXT == STYLES_USER_DEFINED }
    }

    GPathResult getUserDefinedCustomStyleNodes() {
        NodeChildren styleNodes = getUserDefinedStyleParent().stylenode
        return styleNodes.findAll { NodeChild it -> it.attributes().any { it.key == TEXT } }
    }

    List<String> getUserDefinedCustomStyleNames() {
        return getUserDefinedCustomStyleNodes().collect { it.@TEXT.text() }
    }

    GPathResult renameStylesInUsualPlaces() {
        getUserDefinedCustomStyleNodes().each { n ->
            n.@TEXT = oldToNewStyleName[n.@TEXT.text()]
        }
        map.node.'**'.each { n ->
            if (n.name() in NAMES_OF_NODES_WITH_STYLE_REF) {
                for (e in n.attributes()) {
                    if (e.key == STYLE_REF) {
                        n.@STYLE_REF = oldToNewStyleName[e.value]
                        break
                    }
                }
            }
        }
        return map
    }

    String getObfuscatedXml() {
        renameStylesInUsualPlaces()
        return XmlUtil.serialize(map).replaceFirst(/^<\?xml version="1\.0" encoding="UTF-8"\?>/, '')
    }

    void obfuscate() {
        targetFile.setText(obfuscatedXml, UTF8)
    }
}

class MapObfuscatorUtils {
    private static RX_HEX = ~/^[0-9A-Fa-f]{2}/
    private static PERCENT = '%'
    private static RX_W = /\w/
    private static SCRIPT = 'script'
    private static CONNECTOR_LABEL_NAMES = ['sourceLabel', 'middleLabel', 'targetLabel']

    static void informAlreadyObfuscated() {
        def title = 'Already obfuscated'
        def message = 'The mindmap is already obfuscated'
        UITools.informationMessage(UITools.frame, message, title, JOptionPane.INFORMATION_MESSAGE)
    }

    static boolean confirmSaveMap(NodeModel nodeModel) {
        def title = 'Save map?'
        def message = '''The mindmap is modified
Save it and proceeding with the obfuscation?'''
        def resp = UITools.showConfirmDialog(nodeModel, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
        return resp == 0
    }

    static boolean confirmOverwrite(File targetFile, NodeModel sourceModel) {
        def title = 'Overwrite obfuscated?'
        def msg = "The file already exists:\n${targetFile.name}\nOverwrite it?"
        def decision = UITools.showConfirmDialog(sourceModel, msg, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
        return decision == 0
    }

    static String x(String text) {
        return !text ? text : text.replaceAll(RX_W, 'x')
    }

    static void obfuscateCore(Node n) {
        if (n.text.startsWith('<html>'))
            n.text = x(n.to.plain)
        else if (!n.text.startsWith('='))
            n.text = x(n.text)
    }

    static void obfuscateDetails(Node n) {
        def text = n.details?.text
        if (text && !text.startsWith('='))
            n.details = x(text)
    }

    static void obfuscateNote(Node n) {
        def text = n.note?.text
        if (text && !text.startsWith('='))
            n.note = x(text)
    }

    static void obfuscateLinks(Node n) {
        def uri = n.link.uri
        if (uri.is(null))
            return
        def stringUri = uri.toString()
        if (stringUri.startsWith('#'))
            return
        int exceptLastXSegments = 1
        if (stringUri.matches('^https?://.*') && !stringUri.replaceAll('(^https?://|/$)', '').contains('/'))
            exceptLastXSegments = 0  // no path: obfuscate http host
        n.link.uri = _obfuscateUriExceptLastSegmentsAndHash(stringUri, exceptLastXSegments)
    }

    static URI _obfuscateUriExceptLastSegmentsAndHash(String uriString, int exceptLastXSegments = 0) {
        def pathHash = uriString.split('#')
        def uriArray = pathHash[0].split('/')
        def uriArraySize = uriArray.size()
        uriArray.eachWithIndex { part, i ->
            if (i > 0 && i < uriArraySize - exceptLastXSegments)
                uriArray[i] = xUri(part)
        }
        def newUriString = uriArray.join('/') + (pathHash.size() == 2 ? '#' + pathHash[1] : '')
        return newUriString.toURI()
    }

    static String xUri(text) {
        def parts = text.split(PERCENT)
        if (parts.size() > 1) {
            int i = 0
            parts.collect { i++ == 0 ? x(it) : it.find(RX_HEX) ? it.size() == 2 ? it[0..<2] : it[0..<2] + x(it[2..-1]) : x(it) }.join(PERCENT)
        } else
            return x(text)
    }

    static void obfuscateAttributes(Node n) {
        n.attributes.each { attr ->
            if (!attr.key.startsWith(SCRIPT)) {
                def value = attr.value
                if (value instanceof String) {
                    if (!value.startsWith('='))
                        attr.value = x(value)
                }
            }
        }
    }

    static void obfuscateConnectors(Node n) {
        for (conn in n.connectorsOut) {
            for (propertyName in (CONNECTOR_LABEL_NAMES)) {
                String label = conn."$propertyName"
                if (!label.is(null))
                    conn."$propertyName" = x(label)
            }
        }
    }

    static void obfuscateImagePath(NodeModel nodeModel) {
        def extResource = nodeModel.getExtension(ExternalResource.class)
        if (extResource) {
            def obfuscatedUri = _obfuscateUriExceptLastSegmentsAndHash(extResource.uri.toString(), 1)
            def newExtResource = new ExternalResource(obfuscatedUri)
            def vc = Controller.currentController.modeController.getExtension(ViewerController.class)
            vc.undoableDeactivateHook(nodeModel)
            vc.undoableActivateHook(nodeModel, newExtResource)
        }
    }
}
