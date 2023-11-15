## v0.5.0

### Enhancements

* Support for QuPath v0.5.0
* Improved support for TensorFlow via Deep Java Library
* Optionally use the model name for a model stored in the user directory
  * May be in a 'stardist' or 'models' subdirectory
  * Provides an alternative to specifying the full model path
* Reduce non-essential logging messages to 'debug' level


## v0.4.0

### Enhancements

* Support for QuPath v0.4.0 and Deep Java Library (via the [Deep Java Library extension](https://github.com/qupath/qupath-extension-djl/blob/main/README.md))
* Support for initializing the model builder from a Bioimage Model Zoo spec
* Support for preprocessing based upon the full (downsampled) image (https://github.com/qupath/qupath-extension-stardist/issues/20)
* Support for both per-channel and joint channel normalization (https://github.com/qupath/qupath-extension-stardist/issues/14)
* The documentation is now [here](https://qupath.readthedocs.io/en/0.4/docs/deep/stardist.html)

## v0.3.2

### Bug fixes

* Fix bug applying detection to a ROI on a z-stack / time series, whereby the default plane would be used for detection (at least if padding > 0).
  * See https://forum.image.sc/t/cell-detection-with-stardist-on-2d-stack-images/73264/5 for details.

## v0.3.1

### Bug fixes

* Invalid operation for scaling (https://github.com/qupath/qupath-extension-stardist/issues/17)
* QuPath with CUDA doesn’t release GPU memory after StarDist segmentation Usage & Issues qupath (https://github.com/qupath/qupath-extension-stardist/issues/11)
  * You'll need to add `stardist.close()` at the end of any scripts (assuming you've used `stardist` as the variable name, as it is [here](https://qupath.readthedocs.io/en/stable/docs/advanced/stardist.html))
* Reduces some occurrences of the dreaded `TopologyException`
  * More will hopefully be removed in the next main QuPath release

## v0.3.0

* First version, written to be compatible with QuPath v0.3.0