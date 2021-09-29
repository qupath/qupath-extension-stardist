# QuPath StarDist extension

Welcome to the StarDist extension for [QuPath](http://qupath.github.io)!

This adds support for running the 2D version of StarDist nucleus detection developed by Uwe Schmidt and Martin Weigert.

It is intended for the (at the time of writing) not-yet-released QuPath v0.3, and remains in a not-quite-complete state.

> **Note:** The implementation has changed from QuPath v0.2, and the results may not be identical.
> One new feature is that nucleus classifications - see [the documentation](https://qupath.readthedocs.io/en/stable/docs/advanced/stardist.html) for more details.

## Installing

To install the StarDist extension, download the latest `qupath-extension-stardist-[version].jar` file from [releases](https://github.com/qupath/qupath-extension-stardist/releases) and drag it onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

You might then need to restart QuPath (but not your computer).


## Running

There are different ways to run the StarDist detection in QuPath, using different deep learning libraries.
Choosing which to use can be a balance between convenience and performance, which might vary according to your hardware.

### Using OpenCV DNN

The easiest (but not necessarily fastest) way to run StarDist is using OpenCV.

To do this, you'll also need to download some pre-trained StarDist model files in *.pb* format.
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

For more examples, including additional instructions to add GPU support, see [ReadTheDocs](https://qupath.readthedocs.io/en/stable/docs/advanced/stardist.html).

#### Converting a TensorFlow model for use with OpenCV

If you are training a new StarDist model, you will probably have a TensorFlow *SavedModel* directory.
You can convert this to a frozen, OpenCV-friendly *.pb* format with the help of [tf2onnx](https://github.com/onnx/tensorflow-onnx).

After installing both *TensorFlow* and *tf2onnx*, use:

```
python -m tf2onnx.convert --opset 10 --saved-model "/path/to/saved/model/directory" --output_frozen_graph "/path/to/output/model/file.pb"
```


### Using TensorFlow

You can also use StarDist with TensorFlow directly.
This means that the *SavedModel* does not need to be converted, but setup in QuPath takes more effort.

To try it, in additiont to adding the StarDist extension you will need to build and install the [QuPath Tensorflow extension](https://github.com/qupath/qupath-extension-tensorflow).

Compatible *SavedModels* from StarDist's developers can be found at https://github.com/stardist/stardist-imagej/tree/master/src/main/resources/models/2D

These will need to be unzipped, and the script to run StarDist changed as shown below:
```groovy
// Specify the model directory (you will need to change this!)
def pathModel = '/path/to/saved_model'
def dnn = qupath.ext.tensorflow.TensorFlowTools.createDnnModel(pathModel)

def stardist = StarDist2D.builder(dnn)
  ...
  .build()

...
```

> TensorFlow Java doesn't currently work with Apple Silicon, however OpenCV does.


### Using OpenVINO

You can also use StarDist with OpenVINO.

You will need to install [QuPath OpenVINO extension](https://github.com/dkurt/qupath-extension-openvino) from @dkurt, and follow the instructions in the extension ReadMe to convert the StarDist models.

The script to run StarDist then looks like:

```groovy
// Specify the model directory (you will need to change this!)
def pathModel = '/path/to/converted_model.xml'
var dnn = qupath.ext.openvino.OpenVINOTools.createDnnModel('/path/to/model.xml')

def stardist = StarDist2D.builder(dnn)
  ...
  .build()

...
```


## Citing

If you use this extension, you should cite the original StarDist publication

- Uwe Schmidt, Martin Weigert, Coleman Broaddus, and Gene Myers.  
[*Cell Detection with Star-convex Polygons*](https://arxiv.org/abs/1806.03535).  
International Conference on Medical Image Computing and Computer-Assisted Intervention (MICCAI), Granada, Spain, September 2018.

You should also cite the QuPath publication, as described [here](https://qupath.readthedocs.io/en/stable/docs/intro/citing.html).


## Building

You can build the QuPath StarDist extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs`
