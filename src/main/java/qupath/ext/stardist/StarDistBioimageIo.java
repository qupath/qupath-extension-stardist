/*-
 * Copyright 2022 QuPath developers, University of Edinburgh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package qupath.ext.stardist;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.bioimageio.spec.BioimageIoSpec.BioimageIoModel;
import qupath.bioimageio.spec.BioimageIoSpec.Processing;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.Binarize;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.Clip;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ScaleLinear;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ScaleMeanVariance;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ScaleRange;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.Sigmoid;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ZeroMeanUnitVariance;
import qupath.bioimageio.spec.BioimageIoSpec.WeightsEntry;
import qupath.ext.stardist.OpCreators.TileOpCreator;
import qupath.lib.common.GeneralTools;
import qupath.lib.io.GsonTools;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

/**
 * Helper class to create a StarDist2D builder, initializing it froma  BioimageIO Model Zoo file.
 * 
 * @author Pete Bankhead
 * @since v0.4.0
 */
class StarDistBioimageIo {
	
	private static final Logger logger = LoggerFactory.getLogger(StarDistBioimageIo.class);
	
	
	static StarDist2D.Builder parseModel(Path path) throws IOException {
		return parseModel(BioimageIoSpec.parseModel(path));
	}
	
	
	static StarDist2D.Builder parseModel(BioimageIoModel model) {
				
		logger.info("Initializing builder from BioImage Model Zoo spec");
		
		// Handle input
		var inputs = model.getInputs();
		if (inputs.size() != 1) {
			throw new IllegalArgumentException("Expected 1 input, but found " + inputs.size());
		}
		var input = inputs.get(0);
		var inputAxes = input.getAxes().toLowerCase();
		int indX = inputAxes.indexOf("x");
		int indY = inputAxes.indexOf("y");
		var shape = input.getShape();
		int tileWidth = StarDist2D.defaultTileSize, tileHeight = StarDist2D.defaultTileSize;
		var inputShapeArray = shape.getTargetShape(
				BioimageIoSpec.createShapeArray(inputAxes, Map.of('x', tileWidth, 'y', tileHeight), 1));
		tileWidth = inputShapeArray[inputAxes.indexOf('x')];
		tileHeight = inputShapeArray[inputAxes.indexOf('y')];
		
		// Handle preprocessing
		TileOpCreator globalOpCreator = null;
		List<ImageOp> preprocessing = new ArrayList<>();
		boolean warnLogged = true;
		for (var preprocess : input.getPreprocessing()) {
			if (preprocessing.isEmpty()) {
				if (preprocess instanceof ScaleLinear) {
					var zeroMean = (ZeroMeanUnitVariance)preprocess;
					var axes = zeroMean.getAxes();
					boolean perChannel = axes == null ? true : !axes.contains("c");
					logger.info("Setting");
					globalOpCreator = OpCreators.imageNormalizationBuilder()
							.zeroMeanUnitVariance(true)
							.perChannel(perChannel)
							.build();
					continue;
				} else if (preprocess instanceof ScaleRange) {
					var scaleRange = (ScaleRange)preprocess;
					var axes = scaleRange.getAxes();
					boolean perChannel = axes == null ? true : !axes.contains("c");
					globalOpCreator = OpCreators.imageNormalizationBuilder()
							.percentiles(scaleRange.getMinPercentile(),
									scaleRange.getMaxPercentile())
							.eps(scaleRange.getEps())
							.perChannel(perChannel)
							.build();
					continue;					
				} else {
					var op = transformToOp(preprocess);
					if (op != null) {
						if (!warnLogged) {
							logger.warn("Adding local preprocessing operation {}", preprocess);
						}
						preprocessing.add(op);
					} else {
						logger.warn("Unsupported preprocessing {}", preprocess);
					}
				}
			}
		}
		
		
		// Get halo from output
		for (var output : model.getOutputs()) {
			if (!output.getPostprocessing().isEmpty()) {
				logger.warn("Custom postprocessing not supported for StarDist2D - will be ignored");
				break;
			}
		}
		var output = model.getOutputs().get(0);
		var axes = output.getAxes().toLowerCase();
		indX = axes.indexOf("x");
		indY = axes.indexOf("y");
		int[] halo = output.getHalo();
		int padding = -1;
		if (halo.length > 0) {
			int padX = halo[indX];
			int padY = halo[indY];
			if (padX == padY) {
				padding = padX;
			} else {
				logger.warn("Halo should be the same in x and y, cannot use {} and {}", padX, padY);
				padding = 0;
			}
		}
		
		// Create the builder
		var weights = model.getWeights(WeightsEntry.TENSORFLOW_SAVED_MODEL_BUNDLE);
		
		// Try to build a model path - taking any unzipped version if we can
		var modelUri = model.getBaseURI().resolve(weights.getSource());
		var modelPath = GeneralTools.toPath(modelUri);
		if (modelPath != null) {
			String absolutePath = modelPath.toAbsolutePath().toString();
			if (absolutePath.toLowerCase().endsWith(".zip")) {
				var pathUnzipped = Paths.get(absolutePath.substring(absolutePath.length()-4));
				if (Files.isDirectory(pathUnzipped)) {
					logger.info("Replacing {} with unzipped version {}", modelPath.getFileName(), pathUnzipped.getFileName());
					modelPath = pathUnzipped;
				}
			}
		}
		var builder = StarDist2D.builder(modelPath.toString());
				
		// Try to parse custom config - this mostly provides thresholds
		var config = model.getConfig().getOrDefault("stardist", null);
		if (config == null) {
			logger.warn("No StarDist-specific configuration found in the model");
		} else if (config instanceof Map) {
			var map = (Map<?, ?>)config;
			var version = map.get("stardist_version");
			if (version instanceof String)
				logger.debug("StarDist version: {}", version);
			var thresholds = map.getOrDefault("thresholds", null);
			if (thresholds instanceof Map) {
				var thresholdsMap = (Map<?, ?>)thresholds;
				var nms = thresholdsMap.getOrDefault("nms", null);
				if (nms != null)
					logger.warn("NMS threshold {} will be ignored (custom NMS threshold not supported)", nms);
				var prob = thresholdsMap.getOrDefault("prob", null);
				if (prob instanceof Number) {
					logger.info("Setting probability threshold to {}", prob);
					builder.threshold(((Number)prob).doubleValue());
				}
			}
		} else {
			logger.warn("StarDist config is not a map - cannot parse {}", config);
		}
		
		// Handle normalization
		if (globalOpCreator != null)
			builder.preprocess(globalOpCreator);
		if (!preprocessing.isEmpty())
			builder.preprocess(preprocessing.toArray(ImageOp[]::new));
		
		// Set padding from halo
		if (padding >= 0)
			builder.padding(padding);
		
		if (tileWidth > 0 && tileHeight > 0)
			builder.tileSize(tileWidth, tileHeight);

		
		return builder;
	}
	
	
	
	private static ImageOp transformToOp(Processing transform) {
		
		if (transform instanceof Binarize) {
			var binarize = (Binarize)transform;
			return ImageOps.Threshold.threshold(binarize.getThreshold());
		}
		
		if (transform instanceof Clip) {
			var clip = (Clip)transform;
			return ImageOps.Core.clip(clip.getMin(), clip.getMax());
		}
		
		if (transform instanceof ScaleLinear) {
			var scale = (ScaleLinear)transform;
			// TODO: Consider axes
			return ImageOps.Core.sequential(
					ImageOps.Core.multiply(scale.getGain()),
					ImageOps.Core.add(scale.getOffset())					
					);
		}

		if (transform instanceof ScaleMeanVariance) {
			// TODO: Figure out if possible to somehow support ScaleMeanVariance
			var scale = (ScaleMeanVariance)transform;
			logger.warn("Unsupported transform {} - cannot access reference tensor {}", transform, scale.getReferenceTensor());
			return null;
		}

		if (transform instanceof ScaleRange) {
			var scale = (ScaleRange)transform;
			var mode = warnIfUnsupportedMode(transform.getName(), scale.getMode(), List.of(Processing.ProcessingMode.PER_SAMPLE));
			assert mode == Processing.ProcessingMode.PER_SAMPLE; // TODO: Consider how to support per dataset
			var axes = scale.getAxes();
			boolean perChannel = false;
			if (axes != null)
				perChannel = !axes.contains("c");
			else
				logger.warn("Axes not specified for {} - channels will be normalized jointly", transform);
			return ImageOps.Normalize.percentile(scale.getMinPercentile(), scale.getMaxPercentile(), perChannel, scale.getEps());
		}

		if (transform instanceof Sigmoid) {
			return ImageOps.Normalize.sigmoid();
		}
		
		if (transform instanceof ZeroMeanUnitVariance) {
			var zeroMeanUnitVariance = (ZeroMeanUnitVariance)transform;
			var mode = warnIfUnsupportedMode(transform.getName(), zeroMeanUnitVariance.getMode(), List.of(Processing.ProcessingMode.PER_SAMPLE, Processing.ProcessingMode.FIXED));
			if (mode == Processing.ProcessingMode.PER_SAMPLE) {
				var axes = zeroMeanUnitVariance.getAxes();
				boolean perChannel = false;
				if (axes != null) {
					perChannel = !axes.contains("c");
					// Try to check axes are as expected
					if (!(sameAxes(axes, "xy") || sameAxes(axes, "xyc")))
						logger.warn("Unsupported axes {} for {} - I will use {} instead", axes, transform.getName(), perChannel ? "xy" : "xyc");
				} else
					logger.warn("Axes not specified for {} - channels will be normalized jointly", transform);

				return ImageOps.Normalize.zeroMeanUnitVariance(perChannel, zeroMeanUnitVariance.getEps());
//				return ImageOps.Normalize.zeroMeanUnitVariance(perChannel, zeroMeanUnitVariance.getEps());
			} else {
				assert mode == Processing.ProcessingMode.FIXED;
				double[] std = zeroMeanUnitVariance.getStd();
				// In specification, eps is added
				for (int i = 0; i < std.length; i++)
					std[i] += zeroMeanUnitVariance.getEps();
				return ImageOps.Core.sequential(
						ImageOps.Core.subtract(zeroMeanUnitVariance.getMean()),
						ImageOps.Core.divide(std)
						);
			}
		}

		logger.warn("Unknown transform {} - cannot convert to ImageOp", transform);
		return null;
	}
	
	
	private static boolean sameAxes(String input, String target) {
		if (Objects.equals(input, target))
			return true;
		if (input == null || target == null || input.length() != target.length())
			return false;
		var inputArray = input.toLowerCase().toCharArray();
		var targetArray = target.toLowerCase().toCharArray();
		Arrays.sort(inputArray);
		Arrays.sort(targetArray);
		return Arrays.equals(inputArray, targetArray);
	}
	
	
	private static Processing.ProcessingMode warnIfUnsupportedMode(String transformName, Processing.ProcessingMode mode, List<Processing.ProcessingMode> allowed) {
		if (mode == null || mode == Processing.ProcessingMode.PER_DATASET) {
			logger.warn("Unsupported mode {} for {}, will be switched to {}", mode, transformName, allowed.get(0));
			return allowed.get(0);
		}
		return mode;
	}
	
	
	/**
	 * Main method to test models can be parsed.
	 * @param args
	 */
	public static void main(String[] args) {
		
		if (args.length == 0) {
			System.err.println("No model paths found!");
			return;
		}
			
		for (var arg : args) {
			try {
				var model = BioimageIoSpec.parseModel(new File(arg));
				
				var builder = parseModel(model);
				
				System.out.println(
						GsonTools.getInstance(true).toJson(builder)
						);
				
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}
	

}
