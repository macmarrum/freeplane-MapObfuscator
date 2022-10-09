// @ExecutionModes({ON_SINGLE_NODE})


import org.freeplane.api.Node
import org.freeplane.core.ui.components.UITools
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.view.swing.features.filepreview.ExternalResource
import org.freeplane.view.swing.features.filepreview.ViewerController

import javax.swing.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
MapObfuscatorUtils.obfuscateStyles(targetFile)

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
def allowInteraction = true
mindMap.save(allowInteraction)


class MapObfuscatorUtils {
    private static rxHex = ~/^[0-9A-Fa-f]{2}/
    private static percent = '%'

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

    static void obfuscateStyles(File targetFile) {

    }

    static String x(CharSequence msg) {
        return msg.replaceAll(/\w/, 'x')
    }

    static void obfuscateCore(Node n) {
        if (n.text.startsWith('<html>'))
            n.text = x(n.to.plain)
        else if (!n.text.startsWith('='))
            n.text = x(n.text)
    }

    static void obfuscateDetails(Node n) {
        def details = n.details?.text
        if (details && !details.startsWith('='))
            n.details = x(details)
    }

    static void obfuscateNote(Node n) {
        def note = n.note?.text
        if (note && !note.startsWith('='))
            n.note = x(note)
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
            exceptLastXSegments = 0  // obfuscate http hosts
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
        def parts = text.split(percent)
        if (parts.size() > 1) {
            int i = 0
            parts.collect { i++ == 0 ? x(it) : it.find(rxHex) ? it.size() == 2 ? it[0..<2] : it[0..<2] + x(it[2..-1]) : x(it) }.join(percent)
        } else
            return x(text)
    }

    static void obfuscateAttributes(Node n) {
        n.attributes.each { attr ->
            if (!attr.key.startsWith('script')) {
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
            for (propertyName in ['sourceLabel', 'middleLabel', 'targetLabel']) {
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
