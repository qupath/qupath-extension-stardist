/**
 * This script provides a general template for nucleus detection using StarDist in QuPath.
 * This example assumes you have an RGB color image, e.g. a brightfield H&E slide.
 * 
 * If you use this in published work, please remember to cite *both*:
 *  - the original StarDist paper (https://doi.org/10.48550/arXiv.1806.03535)
 *  - the original QuPath paper (https://doi.org/10.1038/s41598-017-17204-5)
 *  
 * There are lots of options to customize the detection - this script shows some 
 * of the main ones. Check out other scripts and the QuPath docs for more info.
 */

import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP
 
// IMPORTANT! Replace this with the path to your StarDist model
// that takes 3 channel RGB as input (e.g. he_heavy_augment.pb)
// You can find some at https://github.com/qupath/models
// (Check credit & reuse info before downloading)
def modelPath = "/path/to/model.pb"

// Customize how the StarDist detection should be applied
// Here some reasonable default options are specified
def stardist = StarDist2D
    .builder(modelPath)
    .normalizePercentiles(1, 99) // Percentile normalization
    .threshold(0.5)              // Probability (detection) threshold
    .pixelSize(0.5)              // Resolution for detection
    .measureShape()              // Add shape measurements
    .measureIntensity()          // Add nucleus measurements
    .build()
	 
// Define which objects will be used as the 'parents' for detection
// Use QP.getAnnotationObjects() if you want to use all annotations, rather than selected objects
def pathObjects = QP.getSelectedObjects()
 
// Run detection for the selected objects
def imageData = QP.getCurrentImageData()
if (pathObjects.isEmpty()) {
    QP.getLogger().error("No parent objects are selected!")
    return
}
stardist.detectObjects(imageData, pathObjects)
stardist.close() // This can help clean up & regain memory
println('Done!')