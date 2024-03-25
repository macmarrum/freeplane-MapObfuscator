/*
 * Copyright (C) 2022-2024   macmarrum
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
    public static mo_obfuscate_template_paths = true
    public static date_format = 'yyyy-MM-dd'
}

final RX_MO = ~/^mo_/
final MAP_OBFUSCATOR = 'MapObfuscator.'
Opt.class.declaredFields.each {
    if (!it.synthetic) {
        switch (Opt."${it.name}".class) {
            case Boolean:
                Opt."${it.name}" = config.getProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR), Opt."${it.name}" as String) == 'true'
                break
            case Integer:
                Opt."${it.name}" = config.getIntProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR), Opt."${it.name}")
                break
            case String:
                Opt."${it.name}" = config.getProperty(it.name.replaceFirst(RX_MO, MAP_OBFUSCATOR), Opt."${it.name}")
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

def obfuscatedName = "$stem$OBFUSCATED_SUFFIX.$ext" as String
def obfuscatedStem = "$stem$OBFUSCATED_SUFFIX" as String
def obfuscatedFile = new File(file.parentFile, obfuscatedName)

def noForce = false
def disallowInteraction = false
def openObfuscated = c.openMindMaps.find { MindMap it -> it.name == obfuscatedStem }
if (openObfuscated) {
    openObfuscated.save(disallowInteraction)
    openObfuscated.close(noForce, allowInteraction)
}

def isOkToObfuscate = true
if (obfuscatedFile.exists()) {
    isOkToObfuscate = MapObfuscatorUtils.confirmOverwrite(obfuscatedFile, nodeModel)
}
if (!isOkToObfuscate)
    return
Files.copy(file.toPath(), obfuscatedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

if (Opt.mo_obfuscate_template_paths || Opt.mo_obfuscate_style_names) {
    def msg = []
    if (Opt.mo_obfuscate_template_paths)
        msg << 'template paths'
    if (Opt.mo_obfuscate_style_names)
        msg << 'style names'
    LogUtils.info("$NAME: obfuscating ${msg.join(', ')} in ${obfuscatedFile.name}")
    MapObfuscatorUtils.obfuscateStyleNamesAndOrTemplatePaths(obfuscatedFile)
}

LogUtils.info("$NAME: opening ${obfuscatedFile.name}")
def mindMap = c.mapLoader(obfuscatedFile).withView().mindMap

def findSinglesAndCloneLeaders(Node n, List<Node> singlesAndLeaders, List<Node> subtrees, List<Node> clones) {
    //printf('>> findSinglesAndCloneLeaders %s %s ', n.id, n.shortText)
    if (n in subtrees) {
        //println('- subtree clone (skipping it and its children)')
        return singlesAndLeaders
    }
    def nodesSharingContentAndSubtree = n.nodesSharingContentAndSubtree
    subtrees.addAll(nodesSharingContentAndSubtree)
    if (n !in clones) {
        //printf('- single/leader')
        singlesAndLeaders << n
        clones.addAll(n.nodesSharingContent - nodesSharingContentAndSubtree)
    } else {
        //printf('- clone (skipping)')
    }
    def children = n.children
    //println(" - processing children (${children.size()})")
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
        MapObfuscatorUtils.obfuscateLink(n)
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

    /** In the first-level {@code <node ...>} element(s), find a {@code <hook ...>} which has the attribute {@code NAME="MapStyle"} */
    def getHookMapStyle() {
        return map.node.hook.find { it.@NAME == MAP_STYLE }
    }

    def getUserDefinedStylesParent() {
        return hookMapStyle.map_styles.stylenode.stylenode.find { it.@LOCALIZED_TEXT == STYLES_USER_DEFINED }
    }

    /** Find styles created by User, as opposed to styles that come with Freeplane,
     * like {@code <stylenode LOCALIZED_TEXT="styles.important">}.
     * Assumption: GPathResult/NodeChildren returned by findAll has NodeChild elements in the order of appearance
     */
    GPathResult getUserDefinedCustomStyleNodes() {
        NodeChildren styleNodes = getUserDefinedStylesParent().stylenode
        return styleNodes.findAll { NodeChild it -> it.attributes().any { it.key == TEXT } }
    }

    List<String> getUserDefinedCustomStyleNames() {
        def nodes = getUserDefinedCustomStyleNodes()
        return nodes.collect(new ArrayList<String>(nodes.size()), { it.@TEXT.text() })
    }

    String getAssociatedTemplate() {
        return hookMapStyle.properties.@associatedTemplateLocation
    }

    String getFollowedMap() {
        return hookMapStyle.properties.@followedTemplateLocation
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
    private static final RX_HEX = ~/^[0-9A-Fa-f]{2}/
    private static final RX_HREF_SRC = ~/(?i)(?:(?<= href=")|(?<= src="))([^"]+)(?=")/
    private static final RX_SCHEME_AT_START = ~/(?i)^[a-z][a-z0-9.+-]+:.*/
    private static final COLON = ':'
    private static final MENUITEM = 'menuitem'
    private static final EXECUTE = 'execute'
    private static final MAILTO = 'mailto'
    private static final FILE = 'file'
    private static final TEMPLATE = 'template'
    private static final HASH = '#'
    private static final SLASH = '/'
    private static final DOUBLE_SLASH = '//'
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
    private static final ASSOCIATED_TEMPLATE_LOCATION = 'associatedTemplateLocation'
    private static final FOLLOWED_TEMPLATE_LOCATION = 'followedTemplateLocation'

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

    static void obfuscateLink(Node n) {
        def uri = n.link.uri
        if (uri)
            n.link.uri = _obfuscateUri(uri)
    }

    static String _obfuscateStringUri(String stringUri) {
        if (stringUri.startsWith(HASH))
            return stringUri
        String scheme
        String rest
        String schemeSpecificPart
        String fragment
        if (stringUri ==~ RX_SCHEME_AT_START)
            (scheme, rest) = stringUri.split(COLON, 2)
        else
            rest = stringUri
        if (rest.contains(HASH))
            (schemeSpecificPart, fragment) = rest.split(HASH)
        else
            schemeSpecificPart = rest
        int preserveLastXSegments = Opt.mo_preserve_last_segment_in_links ? 1 : 0
        //if (isFileOrTemplate(scheme))
        //    preserveLastXSegments = 0
        if (isHostOnlyHttp(scheme, schemeSpecificPart))
            preserveLastXSegments = 0 // no path; obfuscate http host, irrespective of mo_preserve_last_segment_in_links
        return "${scheme ? scheme + COLON : ''}${_obfuscateSspExceptLastSegments(schemeSpecificPart, preserveLastXSegments)}${fragment ? HASH + fragment : ''}" as String
    }

    def static isFileOrTemplate(String scheme) {
        return scheme in [null, FILE, TEMPLATE]
    }

    static isHostOnlyHttp(String scheme, String schemeSpecificPart) {
        return scheme in ['https', 'http'] && !schemeSpecificPart.replaceAll('^//|/$', '').contains(SLASH)
    }

    static URI _obfuscateUri(URI uri) {
        if (!uri || uri.scheme in [MENUITEM, EXECUTE]) {
            return uri
        } else if (uri.scheme == MAILTO) {
            return new URI(MAILTO, x(uri.schemeSpecificPart), null)
        } else {
            int lastXSegmentsToPreserve = Opt.mo_preserve_last_segment_in_links ? 1 : 0
            //if (isFileOrTemplate(uri.scheme))
            //    lastXSegmentsToPreserve = 0
            if (isHostOnlyHttp(uri.scheme, uri.schemeSpecificPart))
                lastXSegmentsToPreserve = 0 // no path; obfuscate http host, irrespective of mo_preserve_last_segment_in_links
            def obfuscatedSsp = _obfuscateSspExceptLastSegments(uri.schemeSpecificPart, lastXSegmentsToPreserve)
            return new URI(uri.scheme, obfuscatedSsp, uri.fragment)
        }
    }

    static String _obfuscateSspExceptLastSegments(String schemeSpecificPart, int lastXSegmentsToPreserve = 0) {
        //println(":: _obfuscateSspExceptLastSegments($schemeSpecificPart, $lastXSegmentsToPreserve)")
        def startsWithDoubleSlash = schemeSpecificPart.startsWith(DOUBLE_SLASH)
        if (startsWithDoubleSlash)
            schemeSpecificPart = schemeSpecificPart[2..-1]
        def segmentArray = schemeSpecificPart.split(SLASH)
        def segmentArraySize = segmentArray.size()
        segmentArray.eachWithIndex { segment, i ->
            if (i < segmentArraySize - lastXSegmentsToPreserve)
                segmentArray[i] = xUri(segment)
        }
        def obfuscatedSsp = (startsWithDoubleSlash ? DOUBLE_SLASH : '') + segmentArray.join(SLASH)
        //println(":: _obfuscateSspExceptLastSegments => $obfuscatedSsp")
        return obfuscatedSsp
    }

    static String xUri(String text) {
        if (!text)
            return text
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
                else if (value.class.name == 'org.freeplane.core.util.Hyperlink')
                    attr.value = _obfuscateUri(value.uri)
                else if (value instanceof URI) // Hyperlink doesn't exist yet in 1.8.0; URI is used
                    attr.value = _obfuscateUri(value)
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
            def obfuscatedUri = _obfuscateUri(extResource.uri)
            def newExtResource = new ExternalResource(obfuscatedUri)
            def vc = Controller.currentController.modeController.getExtension(ViewerController.class)
            vc.undoableDeactivateHook(nodeModel)
            vc.undoableActivateHook(nodeModel, newExtResource)
        }
    }

    static void obfuscateStyleNamesAndOrTemplatePaths(File obfuscatedFile) {
        def obfuscatedText = getTextWithRenamedStylesAndOrTemplatePaths(obfuscatedFile)
        obfuscatedFile.setText(obfuscatedText, UTF8)
    }

    static String getTextWithRenamedStylesAndOrTemplatePaths(File obfuscatedFile) {
        def mmXml = new MmXml(obfuscatedFile)
        def replacements = new HashMap<String, String>()
        if (Opt.mo_obfuscate_template_paths)
            replacements.putAll(getTemplatePathsReplacements(mmXml))
        if (Opt.mo_obfuscate_style_names) {
            replacements.putAll(mmXml.styleReplacements)
        }
        return obfuscatedFile.getText(UTF8).replace(replacements)
    }

    static Map<String, String> getTemplatePathsReplacements(MmXml mmXml) {
        def r = new HashMap<String, String>()
        def associatedTemplate = mmXml.associatedTemplate
        if (associatedTemplate) {
            def obfuscatedAT = _obfuscateStringUri(associatedTemplate)
            if (obfuscatedAT != associatedTemplate)
                r["$ASSOCIATED_TEMPLATE_LOCATION=\"$associatedTemplate\"" as String] = "$ASSOCIATED_TEMPLATE_LOCATION=\"$obfuscatedAT\"" as String
        }
        def followedMap = mmXml.followedMap
        if (followedMap) {
            def obfuscatedFM = _obfuscateStringUri(followedMap)
            if (obfuscatedFM != followedMap)
                r["$FOLLOWED_TEMPLATE_LOCATION=\"$followedMap\"" as String] = "$FOLLOWED_TEMPLATE_LOCATION=\"$obfuscatedFM\"" as String
        }
        return r
    }

    static List<String> splitExtension(String filename) {
        return filename.reverse().split(DOT, 2).collect { it.reverse() }.reverse()
    }
}
// @ExecutionModes({ON_SINGLE_NODE})
