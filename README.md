# QuPath StarDist extension

Welcome to the StarDist extension for [QuPath](http://qupath.github.io)!

This adds support for running the 2D version of StarDist nucleus detection developed by Uwe Schmidt and Martin Weigert.

It is intended for the (at the time of writing) not-yet-released QuPath v0.3, and remains in a not-quite-complete state.

## Installing

To install the StarDist extension, download the latest `qupath-extension-stardist-[version].jar` file and drag it onto QuPath when it is running.
You will then be prompted to ask whether you want to copy the file to the appropriate folder.


## Running

### Using OpenCV DNN

To use the extension, you'll also need to download some pre-trained StarDist model files in *.pb* format.
You can find some [here](https://github.com/qupath/models/tree/main/stardist).

The StarDist extension is then run from a script, for example

```groovy
import qupath.ext.stardist.StarDist2D

// Specify the model .pb file (you will need to change this!)
def pathModel = '/path/to/dsb2018_heavy_augment.pb'

def stardist = StarDist2D.builder(pathModel)
        .threshold(0.5)              // Probability (detection) threshold
        .channels('DAPI')            // Select detection channel
        .normalizePercentiles(1, 99) // Percentile normalization
        .pixelSize(0.5)              // Resolution for detection
        .cellExpansion(5.0)          // Approximate cells based upon nucleus expansion
        .cellConstrainScale(1.5)     // Constrain cell expansion using nucleus size
        .measureShape()              // Add shape measurements
        .measureIntensity()          // Add cell measurements (in all compartments)
        .includeProbability(true)    // Add probability as a measurement (enables later filtering)
        .build()

// Run detection for the selected objects
def imageData = getCurrentImageData()
def pathObjects = getSelectedObjects()
if (pathObjects.isEmpty()) {
    Dialogs.showErrorMessage("StarDist", "Please select a parent object!")
    return
}
stardist.detectObjects(imageData, pathObjects)
println 'Done!'
```

For more examples, see [ReadTheDocs](https://qupath.readthedocs.io/en/stable/docs/advanced/stardist.html).

> The docs are currently a little out of date... most of the scripts should still work, but when using the extension, be sure to use the line `import qupath.ext.stardist.StarDist2D` rather than the older import statement with QuPath v0.2.


### Using TensorFlow

You can also use StarDist with TensorFlow, but the setup is a lot more awkward.

If you want to try it, you will need to build and install the [QuPath Tensorflow extension](https://github.com/qupath/qupath-extension-tensorflow), and use alternative StarDist models in *SavedModel* format.

You can download example *SavedModels* from StarDist's developers at https://github.com/stardist/stardist-imagej/tree/master/src/main/resources/models/2D

These will need to be unzipped, and the paths to the model directory included in the above script instead of the *.pb* file.

```groovy
// Specify the model directory (you will need to change this!)
def pathModel = '/path/to/dsb2018_heavy_augment'
```

> TensorFlow Java doesn't currently work with Apple Silicon, however OpenCV does.



### Converting a TensorFlow model for use with OpenCV

You can convert TensorFlow *SavedModel* directories trained using StarDist to a frozen, OpenCV-friendly *.pb* format with the help of [tf2onnx](https://github.com/onnx/tensorflow-onnx).

After installing both *TensorFlow* and *tf2onnx*, use:

```
python -m tf2onnx.convert --opset 10 --saved-model "/path/to/saved/model/directory" --output_frozen_graph "/path/to/output/model/file.pb"
```


## Citing

If you use this extension, you should cite the original StarDist publication

- Uwe Schmidt, Martin Weigert, Coleman Broaddus, and Gene Myers.  
[*Cell Detection with Star-convex Polygons*](https://arxiv.org/abs/1806.03535).  
International Conference on Medical Image Computing and Computer-Assisted Intervention (MICCAI), Granada, Spain, September 2018.

You should also cite the QuPath publication, as described [here](https://qupath.readthedocs.io/en/stable/docs/intro/citing.html).


## Building

### Extension + dependencies separately

You can build the QuPath StarDist extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs`
