package io.github.macmarrum.freeplane

import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.NodeChild
import groovy.xml.slurpersupport.NodeChildren
import org.freeplane.api.MindMap
import org.freeplane.api.Node
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.HtmlUtils
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.FreeplaneScriptBaseClass
import org.freeplane.plugin.script.proxy.ConvertibleDate
import org.freeplane.plugin.script.proxy.ConvertibleNumber
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.view.swing.features.filepreview.ExternalResource
import org.freeplane.view.swing.features.filepreview.ViewerController

import javax.swing.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

final NAME = 'MapObfuscator'
final OBFUSCATED_SUFFIX = '~obfuscated'

def c = ScriptUtils.c()
def node = ScriptUtils.node()
def config = new FreeplaneScriptBaseClass.ConfigProperties()
NodeModel nodeModel = node.delegate

class Opt {
    public static mo_put_file_name_in_root_core = true
    public static mo_obfuscate_core = true
    public static mo_obfuscate_details = true
    public static mo_obfuscate_note = true
    public static mo_preserve_html = true
    public static mo_obfuscate_links = true
    public static mo_preserve_last_segment_in_links = false
    public static mo_obfuscate_attribute_values = true
    public static mo_obfuscate_connectors = true
    public static mo_obfuscate_image_paths = true
    public static mo_obfuscate_formulas = true
    public static mo_obfuscate_scripts = true
    public static mo_obfuscate_style_names = true
    public static date_format = 'yyyy-MM-dd'
}

final RX_MO = ~/^mo_/
final MAP_OBFUSCATOR = 'MapObfuscator.'
Opt.class.declaredFields.each {
    if (!it.synthetic) {
        switch (Opt."${it.name}".class) {
            case Boolean:
                Opt."${it.name}" = config.getBooleanProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR))
                break
            case Integer:
                Opt."${it.name}" = config.getIntProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR))
                break
            case String:
                Opt."${it.name}" = config.getProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR))
                break
        }
    }
}

def allowInteraction = true
if (!node.mindMap.saved) {
    if (MapObfuscatorUtils.confirmSaveMap(nodeModel)) {
        node.mindMap.save(allowInteraction)
    } else
        return
}

def file = node.mindMap.file
if (file.is(null))
    return

def (stem, ext) = MapObfuscatorUtils.splitExtension(file.name)
if (stem.endsWith(OBFUSCATED_SUFFIX)) {
    MapObfuscatorUtils.informAlreadyObfuscated()
    return
}

def obfuscatedName = "$stem$OBFUSCATED_SUFFIX.$ext".toString()
def obfuscatedStem = "$stem$OBFUSCATED_SUFFIX".toString()
def obfuscatedFile = new File(file.parentFile, obfuscatedName)

def force = false
def disallowInteraction = false
def openObfuscated = c.openMindMaps.find { MindMap it -> it.name == obfuscatedStem }
if (openObfuscated) {
    openObfuscated.save(disallowInteraction)
    openObfuscated.close(force, allowInteraction)
}

def isOkToObfuscate = true
if (obfuscatedFile.exists()) {
    isOkToObfuscate = MapObfuscatorUtils.confirmOverwrite(obfuscatedFile, nodeModel)
}
if (!isOkToObfuscate)
    return
Files.copy(file.toPath(), obfuscatedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

if (Opt.mo_obfuscate_style_names) {
    LogUtils.info("$NAME: search and replace in ${obfuscatedFile.name}")
    MapObfuscatorUtils.obfuscateStyleNames(obfuscatedFile)
}

LogUtils.info("$NAME: open and obfuscate ${obfuscatedFile.name}")
def mindMap = c.mapLoader(obfuscatedFile).withView().mindMap

def findSinglesAndCloneLeaders(Node n, List<Node> singlesAndLeaders, List<Node> subtrees, List<Node> clones) {
//    printf('>> findSinglesAndCloneLeaders %s %s ', n.id, n.shortText)
    if (n in subtrees) {
//        println('- subtree clone (skipping it and its children)')
        return singlesAndLeaders
    }
    def nodesSharingContentAndSubtree = n.nodesSharingContentAndSubtree
    subtrees.addAll(nodesSharingContentAndSubtree)
    if (n !in clones) {
//        printf('- single/leader')
        singlesAndLeaders << n
        clones.addAll(n.nodesSharingContent - nodesSharingContentAndSubtree)
    } else {
//        printf('- clone (skipping)')
    }
    def children = n.children
//    println(" - processing children (${children.size()})")
    children.each { Node it ->
        findSinglesAndCloneLeaders(it, singlesAndLeaders, subtrees, clones)
    }
    return singlesAndLeaders
}

def singlesAndCloneLeaders = findSinglesAndCloneLeaders(mindMap.root, new LinkedList<Node>(), new LinkedList<Node>(), new LinkedList<Node>())
singlesAndCloneLeaders.each { Node n ->
    if (Opt.mo_obfuscate_core)
        MapObfuscatorUtils.obfuscateCore(n)
    if (Opt.mo_obfuscate_details)
        MapObfuscatorUtils.obfuscateDetails(n)
    if (Opt.mo_obfuscate_note)
        MapObfuscatorUtils.obfuscateNote(n)
    if (Opt.mo_obfuscate_links)
        MapObfuscatorUtils.obfuscateLinks(n)
    if (Opt.mo_obfuscate_attribute_values)
        MapObfuscatorUtils.obfuscateAttributeValues(n)
    if (Opt.mo_obfuscate_connectors)
        MapObfuscatorUtils.obfuscateConnectors(n)
    NodeModel m = n.delegate
    if (Opt.mo_obfuscate_image_paths)
        MapObfuscatorUtils.obfuscateImagePath(m)
}
if (Opt.mo_put_file_name_in_root_core)
    mindMap.root.text = obfuscatedStem
LogUtils.info("$NAME: save ${obfuscatedFile.name}")
mindMap.save(allowInteraction)


class MmXml {
    public final File file
    public final GPathResult map
    public static final UTF8 = 'UTF-8'
    public static final STYLE_NAME_PREFIX = 'Style'
    public static final MAP_STYLE = 'MapStyle'
    public static final STYLES_USER_DEFINED = 'styles.user-defined'
    public static final TEXT = 'TEXT'
    public static final XML_REPLACEMENTS = ['<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;']
    public static final TEXT_FORMAT = ' TEXT="%s"'
    public static final STYLE_REF_FORMAT = ' STYLE_REF="%s"'
    public static final NAMES_OF_NODES_WITH_STYLE_REF = ['node', 'conditional_style', 'arrowlink']

    MmXml(File file) {
        this.file = file
        map = parseXml(file)
    }

    static parseXml(File file) {
        def text = '<!DOCTYPE map [<!ENTITY nbsp "&#160;">]>' + file.getText(UTF8)
        return new XmlSlurper(false, false, true).parseText(text)
    }

    Map<String, String> getStyleNameOldToNew() {
        def customStyleNames = getUserDefinedCustomStyleNames()
        def numberOfDigits = (customStyleNames.size() as String).size()
        def oldToNew = new LinkedHashMap<String, String>(customStyleNames.size())
        def styleNameFormat = "${STYLE_NAME_PREFIX}%0${numberOfDigits}d"
        String oldNameInPlainText
        while (true) {
            customStyleNames.eachWithIndex { oldName, i ->
                oldNameInPlainText = oldName.replace(XML_REPLACEMENTS)
                oldToNew[oldNameInPlainText] = sprintf(styleNameFormat, i + 1)
            }
            def foundNewStyleNameInOldStyleNames = oldToNew.values().any { newName -> oldToNew.containsKey(newName) }
            if (!foundNewStyleNameInOldStyleNames)
                break
            styleNameFormat = "_${styleNameFormat}"
        }
        return oldToNew
    }

    Map<String, String> getStyleReplacements() {
        def oldToNew = getStyleNameOldToNew()
        def replacements = new LinkedHashMap<String, String>(oldToNew.size() * 2)
        String key
        String value
        oldToNew.each { e ->
            key = sprintf(TEXT_FORMAT, e.key)
            value = sprintf(TEXT_FORMAT, e.value)
            replacements[key] = value
            key = sprintf(STYLE_REF_FORMAT, e.key)
            value = sprintf(STYLE_REF_FORMAT, e.value)
            replacements[key] = value
        }
        return replacements
    }

    def getMapStyle() {
        return map.node.hook.find { it.@NAME == MAP_STYLE }
    }

    def getUserDefinedStyleParent() {
        return getMapStyle().map_styles.stylenode.stylenode.find { it.@LOCALIZED_TEXT == STYLES_USER_DEFINED }
    }

    /** Assumption: GPathResult/NodeChildren returned by findAll contains NodeChild elements in the order of appearance
     */
    GPathResult getUserDefinedCustomStyleNodes() {
        NodeChildren styleNodes = getUserDefinedStyleParent().stylenode
        return styleNodes.findAll { NodeChild it -> it.attributes().any { it.key == TEXT } }
    }

    List<String> getUserDefinedCustomStyleNames() {
        def nodes = getUserDefinedCustomStyleNodes()
        return nodes.collect(new ArrayList<String>(nodes.size()), { it.@TEXT.text() })
    }
}

class FSBCImpl extends FreeplaneScriptBaseClass {

    @Override
    Object run() {
        return null
    }
}

class MapObfuscatorUtils {
    private static final UTF8 = 'UTF-8'
    private static final LT = '<'
    private static final GT = '>'
    private static final AMP = '&'
    private static final SCL = ';'
    private static final MENUITEM = 'menuitem:'
    private static final EXECUTE = 'execute:'
    private static final RX_HEX = ~/^[0-9A-Fa-f]{2}/
    private static final RX_HREF_SRC = ~/(?i)(?:(?<= href=")|(?<= src="))([^"]+)(?=")/
    private static final MAILTO = 'mailto:'
    private static final AT = '@'
    private static final HASH = '#'
    private static final SLASH = '/'
    private static final PERCENT = '%'
    private static final HTML = '<html>'
    private static final EQ = '='
    private static final RX_ALNUM_OR_NOT_ASCII = ~/\p{Alnum}|[^\p{ASCII}]/
    private static final OBFUSCATED_FORMULA = /='obfuscated formula'/
    private static final SCRIPT = 'script'
    private static final OBFUSCATED_SCRIPT = '// obfuscated script'
    private static final CONNECTOR_LABEL_NAMES = ['sourceLabel', 'middleLabel', 'targetLabel']
    private static final XDATE = new FSBCImpl().format(Date.parse('yyyy-MM-dd', '1970-01-01'), Opt.date_format)
    private static final XNUMBER = 123
    private static final DOT = /\./

    static void informAlreadyObfuscated() {
        def title = 'Already obfuscated'
        def message = 'The mindmap is already obfuscated'
        UITools.informationMessage(UITools.frame, message, title, JOptionPane.INFORMATION_MESSAGE)
    }

    static boolean confirmSaveMap(NodeModel nodeModel) {
        def title = 'Save map?'
        def message = 'The mindmap is modified\nSave it and proceeding with the obfuscation?'
        def resp = UITools.showConfirmDialog(nodeModel, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
        return resp == 0
    }

    static boolean confirmOverwrite(File obfuscatedFile, NodeModel sourceModel) {
        def title = 'Overwrite obfuscated?'
        def msg = "The file already exists:\n${obfuscatedFile.name}\nOverwrite it?"
        def decision = UITools.showConfirmDialog(sourceModel, msg, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
        return decision == 0
    }

    static void obfuscateStyleNames(File obfuscatedFile) {
        obfuscatedFile.setText(getTextWithRenamedStyles(obfuscatedFile), UTF8)
    }

    static String getTextWithRenamedStyles(File obfuscatedFile) {
        def styleReplacements = new MmXml(obfuscatedFile).styleReplacements
        return obfuscatedFile.getText(UTF8).replace(styleReplacements)
    }

    static String x(String text) {
        return !text ? text : text.replaceAll(RX_ALNUM_OR_NOT_ASCII, 'x')
    }

    static String xHtml(String text) {
        def isTagInProgress = false
        def isEntityInProgress = false
        def list = text.toList()
        list.eachWithIndex { it, i ->
            if (it == LT)
                isTagInProgress = true
            else if (it == GT)
                isTagInProgress = false
            else if (it == AMP)
                isEntityInProgress = true
            else if (isEntityInProgress && it == SCL)
                isEntityInProgress = false
            if (!(isTagInProgress || isEntityInProgress) && it ==~ RX_ALNUM_OR_NOT_ASCII)
                list[i] = 'x'
        }
        return list.join('')
    }

    static String xHrefIfEnabled(String text) {
        return Opt.mo_obfuscate_links ? text.replaceAll(RX_HREF_SRC) { _obfuscateStringUri(it[0] as String) } : text
    }

    static void obfuscateCore(Node n) {
        def text = n.text
        if (text) {
            def convertible = n.to
            if (convertible instanceof ConvertibleDate)
                n.text = XDATE
            else if (convertible instanceof ConvertibleNumber)
                n.text = XNUMBER
            else {
                if (text.startsWith(HTML))
                    n.text = Opt.mo_preserve_html ? xHtml(xHrefIfEnabled(text)) : x(HtmlUtils.htmlToPlain(text))
                else {
                    if (!text.startsWith(EQ))
                        n.text = x(text)
                    else if (Opt.mo_obfuscate_formulas)
                        n.text = OBFUSCATED_FORMULA
                }
            }
        }
    }

    static void obfuscateDetails(Node n) {
        def text = n.detailsText
        if (text) {
            if (text.startsWith(HTML)) {
                def plain = HtmlUtils.htmlToPlain(text)
                if (!plain.startsWith(EQ))
                    n.details = Opt.mo_preserve_html ? xHtml(xHrefIfEnabled(text)) : x(plain)
                else if (Opt.mo_obfuscate_formulas)
                    n.details = OBFUSCATED_FORMULA
            } else {
                if (!text.startsWith(EQ))
                    n.details = x(text)
                else if (Opt.mo_obfuscate_formulas)
                    n.details = OBFUSCATED_FORMULA
            }
        }
    }

    static void obfuscateNote(Node n) {
        def text = n.noteText
        if (text) {
            if (text.startsWith(HTML)) {
                def plain = HtmlUtils.htmlToPlain(text)
                if (Opt.mo_obfuscate_formulas || !plain.startsWith(EQ))
                    n.note = Opt.mo_preserve_html ? xHtml(xHrefIfEnabled(text)) : x(plain)
            } else if (Opt.mo_obfuscate_formulas || !text.startsWith(EQ))
                n.note = x(text)
        }
    }

    static void obfuscateLinks(Node n) {
        def uri = n.link.uri
        if (uri.is(null))
            return
        def stringUri = uri.toString()
        if (!stringUri.startsWith(MENUITEM) && !stringUri.startsWith(EXECUTE))
            n.link.uri = _obfuscateStringUri(stringUri).toURI()
    }

    static String _obfuscateStringUri(String stringUri) {
        if (stringUri.startsWith(HASH))
            return stringUri
        int preserveLastXSegments = Opt.mo_preserve_last_segment_in_links ? 1 : 0
        def isHttp = stringUri.matches('^https?://.*')
        def strippedUri = stringUri.replaceAll('(^https?://|/$)', '')
        if (isHttp && !strippedUri.contains(SLASH))
            preserveLastXSegments = 0  // no path; obfuscate http host
        return _obfuscateUriExceptLastSegmentsAndHash(stringUri, preserveLastXSegments)
    }

    static String _obfuscateUriExceptLastSegmentsAndHash(String stringUri, int preserveLastXSegments = 0) {
        String newUriString
        if (stringUri.startsWith(MAILTO))
            newUriString = MAILTO + stringUri[7..-1].split(AT).collect { xUri(it) }.join(AT)
        else if (stringUri.contains(SLASH)) {
            def pathHash = stringUri.split(HASH)
            def uriArray = pathHash[0].split(SLASH)
            def uriArraySize = uriArray.size()
            uriArray.eachWithIndex { part, i ->
                if (i > 0 && i < uriArraySize - preserveLastXSegments)
                    uriArray[i] = xUri(part)
            }
            newUriString = uriArray.join(SLASH) + (pathHash.size() == 2 ? HASH + pathHash[1] : '')
        } else
            newUriString = xUri(stringUri)
        return newUriString
    }

    static String xUri(text) {
        def parts = text.split(PERCENT)
        if (parts.size() > 1) {
            int i = 0
            parts.collect { i++ == 0 ? x(it) : it.find(RX_HEX) ? it.size() == 2 ? it[0..<2] : it[0..<2] + x(it[2..-1]) : x(it) }.join(PERCENT)
        } else
            return x(text)
    }

    static void obfuscateAttributeValues(Node n) {
        n.attributes.each { attr ->
            if (!attr.key.startsWith(SCRIPT)) {
                def value = attr.value
                if (value instanceof String) {
                    if (!value.startsWith(EQ))
                        attr.value = x(value)
                    else if (Opt.mo_obfuscate_formulas)
                        attr.value = OBFUSCATED_FORMULA
                } else if (value instanceof Date)
                    attr.value = XDATE
                else if (value instanceof Number)
                    attr.value = XNUMBER
                else if (value.class.name == 'org.freeplane.core.util.Hyperlink' || value instanceof URI) {
                    // Hyperlink doesn't exist yet in 1.8.0; URI is used
                    def stringUri = attr.value.toString()
                    if (!(stringUri.startsWith(HASH) || stringUri.startsWith(MENUITEM) || stringUri.startsWith(EXECUTE)))
                        attr.value = _obfuscateStringUri(stringUri).toURI()
                }
            } else if (Opt.mo_obfuscate_scripts)
                attr.value = OBFUSCATED_SCRIPT
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
            def obfuscatedUri = _obfuscateStringUri(extResource.uri.toString()).toURI()
            def newExtResource = new ExternalResource(obfuscatedUri)
            def vc = Controller.currentController.modeController.getExtension(ViewerController.class)
            vc.undoableDeactivateHook(nodeModel)
            vc.undoableActivateHook(nodeModel, newExtResource)
        }
    }

    static List<String> splitExtension(String filename) {
        return filename.reverse().split(DOT, 2).collect { it.reverse() }.reverse()
    }
}
// @ExecutionModes({ON_SINGLE_NODE})
