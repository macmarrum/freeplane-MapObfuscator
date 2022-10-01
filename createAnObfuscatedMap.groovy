// @ExecutionModes({ON_SINGLE_NODE})


import org.freeplane.api.Node
import org.freeplane.core.ui.components.UITools
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils
import org.freeplane.view.swing.features.filepreview.ExternalResource
import org.freeplane.view.swing.features.filepreview.ViewerController

import javax.swing.*

final OBFUSCATED_PREFIX = 'obfuscated~'

def c = ScriptUtils.c()
def node = ScriptUtils.node()
NodeModel nodeModel = node.delegate

if (node.mindMap.file.name.startsWith(OBFUSCATED_PREFIX)) {
    informAlreadyObfuscated()
    return
}

if (!node.mindMap.saved) {
    if (confirmSaveMap(nodeModel)) {
        def allowInteraction = true
        node.mindMap.save(allowInteraction)
    } else
        return
}

def file = node.mindMap.file
if (file.is(null))
    return

def newName = OBFUSCATED_PREFIX + file.name
def newStem = newName.replaceAll(/\.mm$/, '')
def targetFile = new File(file.parentFile, newName)
def openedMap = c.openMindMaps.find { it.file == targetFile }
def isOkToObfuscate = true
if (!openedMap.is(null)) {
    isOkToObfuscate = false
    if (confirmCloseObfuscated(nodeModel)) {
        def allowInteraction = true
        openedMap.save(allowInteraction)
        def force = true
        openedMap.close(force, allowInteraction)
        isOkToObfuscate = true
    }
}
if (!isOkToObfuscate)
    return

def mindMap = c.mapLoader(file).withView().unsetMapLocation().mindMap
mindMap.root.findAll().each { Node n ->
    obfuscateCore(n)
    obfuscateDetails(n)
    obfuscateNote(n)
    obfuscateLinks(n)
    obfuscateAttributes(n)
    obfuscateConnectors(n)
    NodeModel m = n.delegate
    obfuscateImagePath(m)
}
mindMap.root.text = newStem
mindMap.name = newStem

def isOkToSave = !file.exists()
if (!isOkToSave && confirmOverwrite(targetFile, nodeModel))
    isOkToSave = true
if (isOkToSave)
    mindMap.saveAs(targetFile)

static def informAlreadyObfuscated() {
    def title = 'Already obfuscated'
    def message = 'The mindmap is already obfuscated'
    UITools.informationMessage(UITools.frame, message, title, JOptionPane.INFORMATION_MESSAGE)
}

static def confirmSaveMap(NodeModel nodeModel) {
    def title = 'Save map?'
    def message = '''The mindmap is modified
Save it and proceeding with the obfuscation?'''
    def resp = UITools.showConfirmDialog(nodeModel, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
    return resp == 0
}

static def confirmCloseObfuscated(NodeModel nodeModel) {
    def title = 'Close obfuscated?'
    def message = '''An obfuscated copy is already open
Close it and proceed with obfuscation?'''
    def resp = UITools.showConfirmDialog(nodeModel, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
    return resp == 0
}

static x(CharSequence msg) {
    return msg.replaceAll(/\w/, 'x')
}

static obfuscateCore(Node n) {
    if (n.text.startsWith('<html>'))
        n.text = x(n.to.plain)
    else if (!n.text.startsWith('='))
        n.text = x(n.text)
}

static obfuscateDetails(Node n) {
    def details = n.details?.text
    if (details && !details.startsWith('='))
        n.details = x(details)
}

static obfuscateNote(Node n) {
    def note = n.note?.text
    if (note && !note.startsWith('='))
        n.note = x(note)
}

static obfuscateLinks(Node n) {
    def uri = n.link.uri
    if (uri.is(null))
        return
    def stringUri = uri.toString()
    if (stringUri.startsWith('#'))
        return
    int exceptLastSegmentCount = 1
    if (stringUri.matches('^https?://.*') && !stringUri.replaceAll('(^https?://|/$)', '').contains('/'))
        exceptLastSegmentCount = 0  // obfuscate http hosts
    n.link.uri = _obfuscateUriExceptLastSegmentsAndHash(stringUri, exceptLastSegmentCount)
}

static _obfuscateUriExceptLastSegmentsAndHash(String uriString, int exceptLastSegmentCount = 0) {
    def pathHash = uriString.split('#')
    def uriArray = pathHash[0].split('/')
    def uriArraySize = uriArray.size()
    uriArray.eachWithIndex { part, i ->
        uriArray[i] = i > 0 && i < uriArraySize - exceptLastSegmentCount ? x(part) : part
    }
    def newUriString = uriArray.join('/') + (pathHash.size() == 2 ? '#' + pathHash[1] : '')
    return newUriString.toURI()
}

static obfuscateAttributes(Node n) {
    n.attributes.each { attr ->
        def value = attr.value
        if (value instanceof String) {
            if (!value.startsWith('='))
                attr.value = x(value)
        }
    }
}

static obfuscateConnectors(Node n) {
    for (conn in n.connectorsOut) {
        for (propertyName in ['sourceLabel', 'middleLabel', 'targetLabel']) {
            String label = conn."$propertyName"
            if (!label.is(null))
                conn."$propertyName" = x(label)
        }
    }
}

static obfuscateImagePath(NodeModel nodeModel) {
    def extResource = nodeModel.getExtension(ExternalResource.class)
    if (extResource) {
        def obfuscatedUri = _obfuscateUriExceptLastSegmentsAndHash(extResource.uri.toString(), 1)
        def newExtResource = new ExternalResource(obfuscatedUri)
        def vc = Controller.currentController.modeController.getExtension(ViewerController.class)
        vc.undoableDeactivateHook(nodeModel)
        vc.undoableActivateHook(nodeModel, newExtResource)
    }
}

static boolean confirmOverwrite(File targetFile, NodeModel sourceModel) {
    def title = 'Overwrite obfuscated?'
    def msg = "The file already exists:\n${targetFile.name}\nOverwrite it?"
    def decision = UITools.showConfirmDialog(sourceModel, msg, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
    return decision == 0
}
