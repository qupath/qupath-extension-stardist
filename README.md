[![Extension docs](https://img.shields.io/badge/docs-qupath_stardist-red)](https://qupath.readthedocs.io/en/stable/docs/deep/stardist.html)
[![Forum](https://img.shields.io/badge/forum-image.sc-green)](https://forum.image.sc/tag/qupath)
[![Downloads (latest release)](https://img.shields.io/github/downloads-pre/qupath/qupath-extension-stardist/latest/total)](https://github.com/qupath/qupath-extension-stardist/releases/latest)
[![Downloads (all releases)](https://img.shields.io/github/downloads/qupath/qupath-extension-stardist/total)](https://github.com/qupath/qupath-extension-stardist/releases)

# QuPath StarDist extension

Welcome to the StarDist extension for [QuPath](http://qupath.github.io)!

This adds support for running the 2D version of StarDist nucleus detection developed by Uwe Schmidt and Martin Weigert.

The current version is written for QuPath v0.5.0 - the documentation is [here](https://qupath.readthedocs.io/en/0.5/docs/deep/stardist.html).

See what's new in the [changelog](CHANGELOG.md);

## Installing

**Better extension support in QuPath v0.5!**
See [readthedocs](https://qupath.readthedocs.io/en/0.5/docs/intro/extensions.html) for details.

To install the StarDist extension, download the latest `qupath-extension-stardist-[version].jar` file from [releases](https://github.com/qupath/qupath-extension-stardist/releases) and drag it onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

You might then need to restart QuPath (but not your computer).


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
