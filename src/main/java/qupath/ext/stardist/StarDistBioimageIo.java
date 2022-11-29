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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.bioimageio.spec.BioimageIoSpec;
import qupath.bioimageio.spec.BioimageIoSpec.BioimageIoModel;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ScaleLinear;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ScaleRange;
import qupath.bioimageio.spec.BioimageIoSpec.Processing.ZeroMeanUnitVariance;
import qupath.bioimageio.spec.BioimageIoSpec.WeightsEntry;
import qupath.ext.stardist.OpCreators.TileOpCreator;
import qupath.lib.common.GeneralTools;
import qupath.lib.io.GsonTools;
import qupath.opencv.ml.BioimageIoTools;
import qupath.opencv.ops.ImageOp;

/**
 * Helper class to create a StarDist2D builder, initializing it from a BioimageIO Model Zoo file.
 * 
 * @author Pete Bankhead
 * @since v0.4.0
 */
class StarDistBioimageIo {
	
	private static final Logger logger = LoggerFactory.getLogger(StarDistBioimageIo.class);
	
	private static int DEFAULT_MAX_DIM = 4096;
	private static double DEFAULT_DOWNSAMPLE = Double.NaN;
	
	/**
	 * Create a builder by parsing the model spec given by the file path.
	 * The syntax is intended to be 'Groovy-friendly', using the map as first argument to request optional named parameters.
	 * @param params optional named parameters for global normalization; supports "maxDim" and "downsample"
	 * @param path
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(Map<String, ?> params, String path) throws IOException {
		return builder(params, Paths.get(path));
	}
	
	/**
	 * Create a builder by parsing the model spec given by the file path.
	 * @param path
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(String path) throws IOException {
		return builder(Collections.emptyMap(), Paths.get(path));
	}

	/**
	 * Create a builder by parsing the model spec given by the file.
	 * The syntax is intended to be 'Groovy-friendly', using the map as first argument to request optional named parameters.
	 * @param params optional named parameters for global normalization; supports "maxDim" and "downsample"
	 * @param file
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(Map<String, ?> params, File file) throws IOException {
		return builder(params, file.toPath());
	}
	
	/**
	 * Create a builder by parsing the model spec given by the file.
	 * @param file
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(File file) throws IOException {
		return builder(Collections.emptyMap(), file);
	}
	
	/**
	 * Create a builder by parsing the model spec given by the path.
	 * @param path
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(Path path) throws IOException {
		return builder(Collections.emptyMap(), path);
	}
	
	/**
	 * Create a builder by parsing the model spec given by the path.
	 * The syntax is intended to be 'Groovy-friendly', using the map as first argument to request optional named parameters.
	 * @param params optional named parameters for global normalization; supports "maxDim" (int) and "downsample" (double).
	 * @param path
	 * @return
	 * @throws IOException
	 */
	static StarDist2D.Builder builder(Map<String, ?> params, Path path) throws IOException {
		
		int maxDim = DEFAULT_MAX_DIM;
		double downsample = DEFAULT_DOWNSAMPLE;
		
		if (params != null && !params.isEmpty()) {
			Object val = params.getOrDefault("maxDim", null);
			if (val instanceof Number)
				maxDim = ((Number)val).intValue();
			else if (val != null)
				logger.warn("Unsupported value for maxDim {} (must be an integer)", val);
	
			val = params.getOrDefault("downsample", null);
			if (val instanceof Number)
				downsample = ((Number)val).doubleValue();
			else if (val != null)
				logger.warn("Unsupported value for downsample {} (must be an integer)", val);
		}
		
		logger.debug("Creating builder from {} with maxDim={}, downsample={}", path.getFileName(), maxDim, downsample);
		return builder(BioimageIoSpec.parseModel(path), maxDim, downsample);
	}
		
	
	static StarDist2D.Builder builder(BioimageIoModel model, int globalMaxDim, double globalDownsample) {
				
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
		// Here, we make it global if we can
		TileOpCreator globalOpCreator = null;
		List<ImageOp> preprocessing = new ArrayList<>();
		boolean warnLogged = true;
		for (var preprocess : input.getPreprocessing()) {
			if (preprocessing.isEmpty()) {
				if (preprocess instanceof ScaleLinear) {
					var zeroMean = (ZeroMeanUnitVariance)preprocess;
					var axes = zeroMean.getAxes();
					boolean perChannel = axes == null ? true : !axes.contains("c");
					logger.info("Normalization by zero-mean-unit-variance (perChannel={}, maxDim={}, downsample={})", perChannel, globalMaxDim, globalDownsample);
					globalOpCreator = OpCreators.imageNormalizationBuilder()
							.zeroMeanUnitVariance(true)
							.perChannel(perChannel)
							.maxDimension(globalMaxDim)
							.downsample(globalDownsample)
							.build();
					continue;
				} else if (preprocess instanceof ScaleRange) {
					var scaleRange = (ScaleRange)preprocess;
					var axes = scaleRange.getAxes();
					boolean perChannel = axes == null ? true : !axes.contains("c");
					logger.info("Normalization by percentile (min={}, max={}, perChannel={}; maxDim={}, downsample={})",
							scaleRange.getMinPercentile(), scaleRange.getMaxPercentile(),
							perChannel,
							globalMaxDim, globalDownsample);
					globalOpCreator = OpCreators.imageNormalizationBuilder()
							.percentiles(scaleRange.getMinPercentile(),
									scaleRange.getMaxPercentile())
							.eps(scaleRange.getEps())
							.maxDimension(globalMaxDim)
							.downsample(globalDownsample)
							.perChannel(perChannel)
							.build();
					continue;					
				} else {
					var op = BioimageIoTools.transformToOp(preprocess);
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
				var pathUnzipped = Paths.get(absolutePath.substring(0, absolutePath.length()-4));
				if (Files.isDirectory(pathUnzipped)) {
					logger.info("Replacing {} with unzipped version {}", modelPath.getFileName(), pathUnzipped.getFileName());
					modelPath = pathUnzipped;
				} else
					logger.warn("Zipped model directories not supported!");
					logger.warn("Please unzip {} and try again", modelPath);
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

		if (axes != null)
			builder.layout(axes);
		
		return builder;
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
				var builder = builder(null, arg);
				
				System.out.println(
						GsonTools.getInstance(true).toJson(builder)
						);
				
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}
	

}
