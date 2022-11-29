/**
 * This script provides a general template for cell detection using StarDist in QuPath.
 * 
 * If you use this in published work, please remember to cite *both*:
 *  - the original StarDist paper (https://doi.org/10.48550/arXiv.1806.03535)
 *  - the original QuPath paper (https://doi.org/10.1038/s41598-017-17204-5)
 *  
 * There are lots of options to customize the detection - and this script shows most of them.
 * 
 * Please read what each option means, then remove the ones you don't want - 
 * and adjust the ones that you care about.
 */

import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.scripting.QP

// IMPORTANT! Replace this with the path to your StarDist model
// You can find some at https://github.com/qupath/models
def modelPath = "/path/to/model.pb"

// Customize how the StarDist detection should be applied.
// This uses a 'builder' to make it easier to add lots of options.
// IMPORTANT! You probably don't need all these - 
// read the descriptions & remove the lines you don't want
def stardist = StarDist2D
    .builder(modelPath)
    .preprocess(                 // Apply normalization, calculating values across the whole image
        StarDist2D.imageNormalizationBuilder()
            .maxDimension(4096)    // Figure out how much to downsample large images to make sure the width & height are <= this value
//          .downsample(1)         // Optional alternative to maxDimension to use the full-resolution image for normalization
                                   // (this is a good idea for small images, but a very bad idea for large images)
            .percentiles(0, 99.8)  // Calculate image percentiles to use for normalization
            .build()
		)
//    .channels('DAPI')            // Select detection channel (usually useful for fluorescence, not needed for RGB);
                                 // the channel can be selected by name or index/number (where 0 is the first channel)
    .threshold(0.5)              // Probability (detection) threshold
    .pixelSize(0.5)              // Resolution for detection
    .tileSize(1024)              // Specify width & height of the tile used for prediction
    .cellExpansion(5.0)          // Approximate cells based upon nucleus expansion
    .cellConstrainScale(1.5)     // Constrain cell expansion using nucleus size
    .ignoreCellOverlaps(false)   // Set to true if you don't care if cells expand into one another
    .measureShape()              // Add shape measurements
    .measureIntensity()          // Add cell measurements (in all compartments)
    .includeProbability(true)    // Add probability as a measurement (enables later filtering)
    .nThreads(4)                 // Limit the number of threads used for (possibly parallel) processing
    .simplify(1)                 // Control how polygons are 'simplified' to remove unnecessary vertices
    .doLog()                     // Use this to log a bit more information while running the script
    .createAnnotations()         // Generate annotation objects using StarDist, rather than detection objects
    .constrainToParent(false)    // Prevent nuclei/cells expanding beyond any parent annotations (default is true)
    .classify("Tumor")           // Automatically classify all created objects as 'Tumor'
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