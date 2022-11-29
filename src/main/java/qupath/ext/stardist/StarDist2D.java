/*-
 * Copyright 2020-2022 QuPath developers, University of Edinburgh
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

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.locationtech.jts.algorithm.Centroid;
import org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.VWSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.stardist.OpCreators.ImageNormalizationBuilder;
import qupath.ext.stardist.OpCreators.TileOpCreator;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.analysis.features.ObjectMeasurements.Compartments;
import qupath.lib.analysis.features.ObjectMeasurements.Measurements;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.objects.CellTools;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.dnn.DnnModel;
import qupath.opencv.dnn.DnnModelParams;
import qupath.opencv.dnn.DnnModels;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

/**
 * Cell detection based on the following method:
 * <pre>
 *   Uwe Schmidt, Martin Weigert, Coleman Broaddus, and Gene Myers.
 *     "Cell Detection with Star-convex Polygons."
 *   <i>International Conference on Medical Image Computing and Computer-Assisted Intervention (MICCAI)</i>, Granada, Spain, September 2018.
 * </pre>
 * See the main repo at https://github.com/mpicbg-csbd/stardist
 * <p>
 * Very much inspired by stardist-imagej at https://github.com/mpicbg-csbd/stardist-imagej but re-written from scratch to use OpenCV and 
 * adapt the method of converting predictions to contours (very slightly) to be more QuPath-friendly.
 * <p>
 * Models are expected in the same format as required by the Fiji plugin, or converted to a frozen .pb file for use with OpenCV.
 * 
 * @author Pete Bankhead (this implementation, but based on the others)
 */
public class StarDist2D implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(StarDist2D.class);
	
	/**
	 * Default tile width and height.
	 */
	public static int defaultTileSize = 1024;
	
	/**
	 * Builder to help create a {@link StarDist2D} with custom parameters.
	 */
	public static class Builder {
		
		private boolean doLog;
		
		private int nThreads = -1;
		
		private String modelPath = null;
		private DnnModel<?> dnn = null;
		private ColorTransform[] channels = new ColorTransform[0];
		
		private double threshold = 0.5;
		
		private int pad = 32;
		
		private double simplifyDistance = 1.4;
		private double cellExpansion = Double.NaN;
		private double cellConstrainScale = Double.NaN;
		private boolean ignoreCellOverlaps = false;

		private double pixelSize = Double.NaN;
				
		private int tileWidth = -1;
		private int tileHeight = -1;
		
		// Optional layout string, following the bioimage.io spec
		private String layout;
		
		private Function<ROI, PathObject> creatorFun;
		
		private PathClass globalPathClass;
		private Map<Integer, PathClass> classifications;
		
		private boolean measureShape = false;
		private Collection<Compartments> compartments = Arrays.asList(Compartments.values());
		private Collection<Measurements> measurements;
		
		private boolean keepClassifiedBackground = false;
		
		private boolean constrainToParent = true;
		
		private TileOpCreator globalPreprocessing;
		private List<ImageOp> preprocessing = new ArrayList<>();
		
		private boolean includeProbability = false;
		
		private Builder(String modelPath) {
			this.modelPath = modelPath;
		}
		
		private Builder(DnnModel<?> dnn) {
			this.dnn = dnn;
		}
		
		/**
		 * Probability threshold to apply for detection, between 0 and 1.
		 * @param threshold
		 * @return this builder
		 * @see #includeProbability(boolean)
		 */
		public Builder threshold(double threshold) {
			this.threshold = threshold;
			return this;
		}
		
		/**
		 * Add preprocessing operations, if required.
		 * @param ops
		 * @return this builder
		 */
		public Builder preprocess(ImageOp... ops) {
			for (var op : ops)
				this.preprocessing.add(op);
			return this;
		}
		
		/**
		 * Add an {@link TileOpCreator} to generate preprocessing operations based upon the 
		 * entire image, rather than per tile.
		 * <p>
		 * Note that only a single such operation is permitted, which is applied after 
		 * channel extraction but <i>before</i> any other preprocessing.
		 * <p>
		 * The intended use is with {@link OpCreators#imageNormalizationBuilder()} to perform 
		 * normalization based upon percentiles computed across the image, rather than per tile.
		 * 
		 * @param global preprocessing operation
		 * @return this builder
		 */
		public Builder preprocess(TileOpCreator global) {
			this.globalPreprocessing = global;
			return this;
		}

		
		/**
		 * Request that progress is logged at the INFO level.
		 * If this is not specified, progress is only logged at the DEBUG level.
		 * @return this builder
		 */
		public Builder doLog() {
			this.doLog = true;
			return this;
		}
		
		
		/**
		 * Optional layout string giving the axes of the input required 
		 * by the model, following the Bioimage Model Zoo spec for axes.
		 * <p>
		 * Generally it should be possible to leave this unspecified, 
		 * but the option exists for cases where the model format might be 
		 * different from what is expected.
		 * <p>
		 * An example string would be {@code "yxc"} indicating channels-last,
		 * or {@code "byxc"} indicating that a batch index is required.
		 * 
		 * @param layout
		 * @return
		 */
		public Builder layout(String layout) {
			this.layout = layout;
			return this;
		}
		
		/**
		 * Customize the extent to which contours are simplified.
		 * Simplification reduces the number of vertices, which in turn can reduce memory requirements and 
		 * improve performance.
		 * <p>
		 * Implementation note: this currently uses the Visvalingam-Whyatt algorithm.
		 * 
		 * @param distance simplify distance threshold; set &le; 0 to turn off additional simplification
		 * @return this builder
		 */
		public Builder simplify(double distance) {
			this.simplifyDistance = distance;
			return this;
		}
		
		
		/**
		 * Specify channels. Useful for detecting nuclei for one channel 
		 * within a multi-channel image, or potentially for trained models that 
		 * support multi-channel input.
		 * @param channels 0-based indices of the channels to use
		 * @return this builder
		 */
		public Builder channels(int... channels) {
			return channels(Arrays.stream(channels)
					.mapToObj(c -> ColorTransforms.createChannelExtractor(c))
					.toArray(ColorTransform[]::new));
		}
		
		/**
		 * Specify channels by name. Useful for detecting nuclei for one channel 
		 * within a multi-channel image, or potentially for trained models that 
		 * support multi-channel input.
		 * @param channels 0-based indices of the channels to use
		 * @return this builder
		 */
		public Builder channels(String... channels) {
			return channels(Arrays.stream(channels)
					.map(c -> ColorTransforms.createChannelExtractor(c))
					.toArray(ColorTransform[]::new));
		}
		
		/**
		 * Define the channels (or color transformers) to apply to the input image.
		 * <p>
		 * This makes it possible to supply color deconvolved channels, for example.
		 * @param channels
		 * @return this builder
		 */
		public Builder channels(ColorTransform... channels) {
			this.channels = channels.clone();
			return this;
		}
		
		/**
		 * Amount by which to expand detected nuclei to approximate the cell area.
		 * Units are the same as for the {@link PixelCalibration} of the input image.
		 * <p>
		 * Warning! This is rather experimental, relying heavily on JTS and a convoluted method of 
		 * resolving overlaps using a Voronoi tessellation.
		 * <p>
		 * In short, be wary.
		 * @param distance
		 * @return this builder
		 */
		public Builder cellExpansion(double distance) {
			this.cellExpansion = distance;
			return this;
		}
		
		/**
		 * Constrain any cell expansion defined using {@link #cellExpansion(double)} based upon 
		 * the nucleus size. Only meaningful for values &gt; 1; the nucleus is expanded according 
		 * to the scale factor, and used to define the maximum permitted cell expansion.
		 * 
		 * @param scale
		 * @return this builder
		 */
		public Builder cellConstrainScale(double scale) {
			this.cellConstrainScale = scale;
			return this;
		}
		
		/**
		 * Create annotations rather than detections (the default).
		 * If cell expansion is not zero, the nucleus will be included as a child object.
		 * 
		 * @return this builder
		 */
		public Builder createAnnotations() {
			this.creatorFun = r -> PathObjects.createAnnotationObject(r);
			return this;
		}
		
		/**
		 * Specify a mapping between StarDist predicted classifications (if available) and QuPath classifications.
		 * 
		 * @param classifications
		 * @return this builder
		 * 
		 * @see #classify(PathClass)
		 */
		public Builder classifications(Map<Integer, PathClass> classifications) {
			this.classifications = new HashMap<>(classifications);
			return this;
		}

		/**
		 * Specify a mapping between StarDist predicted classifications (if available) and QuPath classification names.
		 * This is a convenience method that creates {@link PathClass} objects from Strings, then passes them to {@link #classifications(Map)}.
		 * 
		 * @param classifications
		 * @return this builder
		 */
		public Builder classificationNames(Map<Integer, String> classifications) {
			return classifications(classifications.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> PathClass.fromString(e.getValue()))));
		}
		
		/**
		 * When using {@link #classifications(Map)}, optionally keep objects classified as background (i.e. 0).
		 * The default is to remove such objects immediately, before resolving overlaps.
		 * @param keep
		 * @return this builder
		 */
		public Builder keepClassifiedBackground(boolean keep) {
			this.keepClassifiedBackground = keep;
			return this;
		}

		/**
		 * Request that a classification is applied to all created objects.
		 * Note that if a StarDist model supporting classifications is used and {@link #classifications(Map)} is specified, 
		 * the StarDist prediction will take precedence. The classification specified here will be applied only to objects 
		 * that have not already been classified based upon the prediction and mapping.
		 * 
		 * @param pathClass
		 * @return this builder
		 * 
		 * @see #classifications(Map)
		 * @see #classificationNames(Map)
		 */
		public Builder classify(PathClass pathClass) {
			this.globalPathClass = pathClass;
			return this;
		}
		
		/**
		 * Request that a classification is applied to all created objects.
		 * This is a convenience method that get a {@link PathClass} from a String representation.
		 * 
		 * @param pathClassName
		 * @return this builder
		 * @see #classifications(Map)
		 * @see #classificationNames(Map)
		 */
		public Builder classify(String pathClassName) {
			return classify(PathClass.fromString(pathClassName, (Integer)null));
		}
		
		/**
		 * If true, ignore overlaps when computing cell expansion.
		 * @param ignore
		 * @return this builder
		 */
		public Builder ignoreCellOverlaps(boolean ignore) {
			this.ignoreCellOverlaps = ignore;
			return this;
		}
		
		/**
		 * If true, constrain nuclei and cells to any parent annotation (default is true).
		 * @param constrainToParent
		 * @return this builder
		 */
		public Builder constrainToParent(boolean constrainToParent) {
			this.constrainToParent = constrainToParent;
			return this;
		}
		
		/**
		 * Specify the number of threads to use for processing.
		 * If you encounter problems, setting this to 1 may help to resolve them by preventing 
		 * multithreading.
		 * @param nThreads
		 * @return this builder
		 */
		public Builder nThreads(int nThreads) {
			this.nThreads = nThreads;
			return this;
		}
		
		/**
		 * Request default intensity measurements are made for all available cell compartments.
		 * @return this builder
		 */
		public Builder measureIntensity() {
			this.measurements = Arrays.asList(
					Measurements.MEAN,
					Measurements.MEDIAN,
					Measurements.MIN,
					Measurements.MAX,
					Measurements.STD_DEV);
			return this;
		}
		
		/**
		 * Request specified intensity measurements are made for all available cell compartments.
		 * @param measurements the measurements to make
		 * @return this builder
		 */
		public Builder measureIntensity(Collection<Measurements> measurements) {
			this.measurements = new ArrayList<>(measurements);
			return this;
		}
		
		/**
		 * Request shape measurements are made for the detected cell or nucleus.
		 * @return this builder
		 */
		public Builder measureShape() {
			measureShape = true;
			return this;
		}
		
		/**
		 * Specify the compartments within which intensity measurements are made.
		 * Only effective if {@link #measureIntensity()} and {@link #cellExpansion(double)} have been selected.
		 * @param compartments cell compartments for intensity measurements
		 * @return this builder
		 */
		public Builder compartments(Compartments...compartments) {
			this.compartments = Arrays.asList(compartments);
			return this;
		}
		
		/**
		 * Optionally include the prediction probability as a measurement for the object.
		 * This can be helpful if detection is applied with a low (generous) probability threshold, 
		 * with the intention of filtering out less likely detections later.
		 * 
		 * @param include true if the probability should be included, false otherwise
		 * @return this builder
		 * @see #threshold(double)
		 */
		public Builder includeProbability(boolean include) {
			this.includeProbability = include;
			return this;
		}
		
		/**
		 * Resolution at which the cell detection should be run.
		 * The units depend upon the {@link PixelCalibration} of the input image.
		 * <p>
		 * The default is to use the full resolution of the input image.
		 * <p>
		 * For an image calibrated in microns, the recommended default is approximately 0.5.
		 * 
		 * @param pixelSize
		 * @return this builder
		 */
		public Builder pixelSize(double pixelSize) {
			this.pixelSize = pixelSize;
			return this;
		}
		
		/**
		 * Size in pixels of a tile used for detection.
		 * Note that tiles are independently normalized, and therefore tiling can impact 
		 * the results. Default is 1024.
		 * @param tileSize
		 * @return this builder
		 */
		public Builder tileSize(int tileSize) {
			return tileSize(tileSize, tileSize);
		}
		
		/**
		 * Size in pixels of a tile used for detection.
		 * Note that tiles are independently normalized, and therefore tiling can impact 
		 * the results. Default is 1024.
		 * @param tileWidth
		 * @param tileHeight
		 * @return this builder
		 */
		public Builder tileSize(int tileWidth, int tileHeight) {
			this.tileWidth = tileWidth;
			this.tileHeight = tileHeight;
			return this;
		}
		
		/**
		 * Amount to pad tiles to reduce boundary artifacts.
		 * @param pad padding in pixels; width and height of tiles will be increased by pad x 2.
		 * @return this builder
		 */
		public Builder padding(int pad) {
			this.pad = pad;
			return this;
		}
				
		/**
		 * Apply percentile normalization separately to the input image channels.
		 * <p>
		 * Note that this can be used in combination with {@link #preprocess(ImageOp...)}, 
		 * in which case the order in which the operations are applied depends upon the order 
		 * in which the methods of the builder are called.
		 * <p>
		 * Warning! This is applied on a per-tile basis. This can result in artifacts and false detections 
		 * without background/constant regions. 
		 * Consider using {@link #inputAdd(double...)} and {@link #inputScale(double...)} as alternative 
		 * normalization strategies, if appropriate constants can be determined to apply globally.
		 * 
		 * @param min minimum percentile
		 * @param max maximum percentile
		 * @return this builder
		 * @see #normalizePercentiles(double, double, boolean, double)
		 */
		public Builder normalizePercentiles(double min, double max) {
			return normalizePercentiles(min, max, true, 0.0);
		}
		
		
		/**
		 * Apply percentile normalization to the input image channels, or across all channels jointly.
		 * <p>
		 * Note that this can be used in combination with {@link #preprocess(ImageOp...)}, 
		 * in which case the order in which the operations are applied depends upon the order 
		 * in which the methods of the builder are called.
		 * <p>
		 * Warning! This is applied on a per-tile basis. This can result in artifacts and false detections 
		 * without background/constant regions. 
		 * Consider using {@link #inputAdd(double...)} and {@link #inputScale(double...)} as alternative 
		 * normalization strategies, if appropriate constants can be determined to apply globally.
		 * 
		 * @param min minimum percentile
		 * @param max maximum percentile
		 * @param perChannel if true, normalize each channel separately; if false, normalize channels jointly
		 * @param eps small constant to apply
		 * @return this builder
		 * @since v0.4.0
		 */
		public Builder normalizePercentiles(double min, double max, boolean perChannel, double eps) {
			this.preprocessing.add(ImageOps.Normalize.percentile(min, max, perChannel, eps));
			return this;
		}
				
		
		/**
		 * Add an offset as a preprocessing step.
		 * Usually the value will be negative. Along with {@link #inputScale(double...)} this can be used as an alternative (global) normalization.
		 * <p>
		 * Note that this can be used in combination with {@link #preprocess(ImageOp...)}, 
		 * in which case the order in which the operations are applied depends upon the order 
		 * in which the methods of the builder are called.
		 * 
		 * @param values either a single value to add to all channels, or an array of values equal to the number of channels
		 * @return this builder
		 * @see #inputSubtract(double...)
		 * @see #inputScale(double...)
		 */
		public Builder inputAdd(double... values) {
			this.preprocessing.add(ImageOps.Core.add(values));
			return this;
		}
		
		/**
		 * Subtract an offset as a preprocessing step.
		 * <p>
		 * Note that this can be used in combination with {@link #preprocess(ImageOp...)}, 
		 * in which case the order in which the operations are applied depends upon the order 
		 * in which the methods of the builder are called.
		 * 
		 * @param values either a single value to subtract from all channels, or an array of values equal to the number of channels
		 * @return this builder
		 * @since v0.4.0
		 * @see #inputAdd(double...)
		 * @see #inputScale(double...)
		 */
		public Builder inputSubtract(double... values) {
			this.preprocessing.add(ImageOps.Core.subtract(values));
			return this;
		}
		
		/**
		 * Multiply by a scale factor as a preprocessing step.
		 * Along with {@link #inputAdd(double...)} this can be used as an alternative (global) normalization.
		 * <p>
		 * Note that this can be used in combination with {@link #preprocess(ImageOp...)}, 
		 * in which case the order in which the operations are applied depends upon the order 
		 * in which the methods of the builder are called.
		 * 
		 * @param values either a single value to add to all channels, or an array of values equal to the number of channels
		 * @return this builder
		 * @see #inputAdd(double...)
		 * @see #inputSubtract(double...)
		 */
		public Builder inputScale(double... values) {
			this.preprocessing.add(ImageOps.Core.multiply(values));
			return this;
		}
		
		/**
		 * Create a {@link StarDist2D}, all ready for detection.
		 * @return
		 */
		public StarDist2D build() {
			var stardist = new StarDist2D();
			
//			var padding = pad > 0 ? Padding.symmetric(pad) : Padding.empty();
			var dnn = this.dnn;
			if (dnn == null) {
				var file = new File(modelPath);
				if (!file.exists()) {
					throw new IllegalArgumentException("I couldn't find the model file " + file.getAbsolutePath());
				}
				try {
					var params = DnnModelParams.builder()
							.files(file)
							.layout(layout)
							.build();
					dnn = DnnModels.buildModel(params);
					if (dnn != null)
						logger.debug("Loaded model {} as {}", modelPath, dnn);
				} catch (Exception e) {
					logger.error("Unable to load model file: " + e.getLocalizedMessage(), e);
					throw new RuntimeException("Unable to load StarDist model from " + modelPath, e);
				}
				// Try using legacy TensorFlow approach
				if (dnn == null) {
					try {
						// For backwards compatibility, we try to support TensorFlow if the extension is installed
						var clsTF = Class.forName("qupath.ext.tensorflow.TensorFlowTools");
						var method = clsTF.getMethod("createDnnModel", String.class);
						dnn = (DnnModel<?>)method.invoke(null, modelPath);
						logger.debug("Loaded model {} with TensorFlow", modelPath);
					} catch (Exception e) {
						logger.error("Unable to load TensorFlow with reflection - are you sure it is available and on the classpath?");
						logger.error(e.getLocalizedMessage(), e);
						throw new RuntimeException("Unable to load StarDist model from " + modelPath, e);
					}
				}
			}
			
			stardist.op = ImageOps.buildImageDataOp(channels);
					
			stardist.globalPreprocess = globalPreprocessing;
			stardist.preprocess = new ArrayList<>(preprocessing);
			
			stardist.dnn = dnn;
			stardist.threshold = threshold;
			stardist.pixelSize = pixelSize;
			stardist.cellConstrainScale = cellConstrainScale;
			stardist.cellExpansion = cellExpansion;
			stardist.tileWidth = tileWidth;
			stardist.tileHeight = tileHeight;
			stardist.pad = pad;
			stardist.includeProbability = includeProbability;
			stardist.ignoreCellOverlaps = ignoreCellOverlaps;
			stardist.measureShape = measureShape;
			stardist.doLog = doLog;
			stardist.simplifyDistance = simplifyDistance;
			stardist.nThreads = nThreads;
			stardist.constrainToParent = constrainToParent;
			stardist.creatorFun = creatorFun;
			stardist.globalPathClass = globalPathClass;
			stardist.classifications = classifications;
			stardist.keepClassifiedBackground = keepClassifiedBackground;
			
			stardist.compartments = new LinkedHashSet<>(compartments);
			
			if (measurements != null)
				stardist.measurements = new LinkedHashSet<>(measurements);
			else
				stardist.measurements = Collections.emptyList();
			
			return stardist;
		}
		
	}
	
	private boolean doLog = false;
	
	private double simplifyDistance = 1.4;
	
	private double threshold;
	
	private ImageDataOp op;
	private TileOpCreator globalPreprocess;
	private List<ImageOp> preprocess;
	private DnnModel<?> dnn;
	
	private double pixelSize;
	private double cellExpansion;
	private double cellConstrainScale;
	private boolean ignoreCellOverlaps;
	
	private Function<ROI, PathObject> creatorFun;
	private PathClass globalPathClass;
	private Map<Integer, PathClass> classifications;
	private boolean keepClassifiedBackground = false;
	
	private boolean constrainToParent = true;
	
	private int nThreads = -1;
	
	private boolean includeProbability = false;
	
	private int tileWidth = 1024;
	private int tileHeight = 1024;
	
	private int pad = 0;

	private boolean measureShape = false;

	private Collection<ObjectMeasurements.Compartments> compartments;
	private Collection<ObjectMeasurements.Measurements> measurements;
	
	private AtomicBoolean firstRun = new AtomicBoolean(true);
	private boolean cancelRuns = false;
	
	
	/**
	 * Detect cells within one or more parent objects, firing update events upon completion.
	 * 
	 * @param imageData the image data containing the object
	 * @param parents the parent objects; existing child objects will be removed, and replaced by the detected cells
	 */
	public void detectObjects(ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
		runInPool(() -> detectObjectsImpl(imageData, parents));		
	}

	/**
	 * Detect cells within a parent object.
	 * 
	 * @param imageData the image data containing the object
	 * @param parent the parent object; existing child objects will be removed, and replaced by the detected cells
	 * @param fireUpdate if true, a hierarchy update will be fired on completion
	 */
	public void detectObjects(ImageData<BufferedImage> imageData, PathObject parent, boolean fireUpdate) {
		runInPool(() -> detectObjectsImpl(imageData, parent, fireUpdate));
	}
	
	/**
	 * Optionally submit runnable to a thread pool. This limits the parallelization used by parallel streams.
	 * @param runnable
	 */
	private void runInPool(Runnable runnable) {
		if (nThreads > 0) {
			if (nThreads == 1)
				log("Processing with {} thread", nThreads);
			else
				log("Processing with {} threads", nThreads);
			// Using an outer thread poll impacts any parallel streams created inside
			var pool = new ForkJoinPool(nThreads);
			try {
				pool.submit(() -> runnable.run());
			} finally {
				pool.shutdown();
				try {
					pool.awaitTermination(24, TimeUnit.HOURS);
				} catch (InterruptedException e) {
					logger.warn("Process was interrupted! " + e.getLocalizedMessage(), e);
				}
			}
		} else {
			runnable.run();	
		}
	}
	
		
	private void detectObjectsImpl(ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {

		if (parents.isEmpty())
			return;
		if (parents.size() == 1) {
			detectObjects(imageData, parents.iterator().next(), true);
			return;
		}
		log("Processing {} parent objects", parents.size());
		if (nThreads >= 0)
			parents.stream().forEach(p -> detectObjects(imageData, p, false));
		else
			parents.parallelStream().forEach(p -> detectObjects(imageData, p, false));
		
		// Fire a global update event
		imageData.getHierarchy().fireHierarchyChangedEvent(imageData.getHierarchy());
	}
	
	
	/**
	 * Detect cells within a parent object.
	 * 
	 * @param imageData the image data containing the object
	 * @param parent the parent object; existing child objects will be removed, and replaced by the detected cells
	 * @param fireUpdate if true, a hierarchy update will be fired on completion
	 */
	private void detectObjectsImpl(ImageData<BufferedImage> imageData, PathObject parent, boolean fireUpdate) {
		Objects.requireNonNull(parent);
		// Lock early, so the user doesn't make modifications
		boolean wasLocked = parent.isLocked();
		parent.setLocked(true);
		
		List<PathObject> detections = detectObjects(imageData, parent.getROI());	
		
		if (cancelRuns) {
			logger.warn("StarDist detection cancelled for {}", parent);
			if (!wasLocked)
				parent.setLocked(false);
			return;
		}
		
		parent.clearChildObjects();
		parent.addChildObjects(detections);
		if (fireUpdate)
			imageData.getHierarchy().fireHierarchyChangedEvent(imageData.getHierarchy(), parent);
	}
	
	
	
	/**
	 * Detect cells within a {@link ROI}.
	 * @param imageData image to which the ROI belongs
	 * @param roi region of interest which which to detect cells. If null, the entire image will be used.
	 * @return the detected objects. Note that these will not automatically be added to the object hierarchy.
	 */
	public List<PathObject> detectObjects(ImageData<BufferedImage> imageData, ROI roi) {

		var resolution = imageData.getServer().getPixelCalibration();
		if (Double.isFinite(pixelSize) && pixelSize > 0) {
			double downsample = pixelSize / resolution.getAveragedPixelSize().doubleValue();
			resolution = resolution.createScaledInstance(downsample, downsample);
		}
		int tw = tileWidth <= 0 ? defaultTileSize : tileWidth;
		int th = tileHeight <= 0 ? defaultTileSize : tileWidth;
		
		// The opServer is needed only to get tile requests, or calculate global normalization percentiles
		var opServer = ImageOps.buildServer(imageData, op, resolution, tw - pad*2, th - pad*2);
//		var opServer = ImageOps.buildServer(imageData, op, resolution, tileWidth-pad*2, tileHeight-pad*2);
		
		RegionRequest request;
		if (roi == null)
			request = RegionRequest.createInstance(opServer);
		else
			request = RegionRequest.createInstance(
				opServer.getPath(),
				opServer.getDownsampleForResolution(0),
				roi);

		// Get all the required tiles that intersect with the mask ROI
		var mask = roi == null ? null : roi.getGeometry();
		var tiles = opServer.getTileRequestManager().getTileRequests(request)
				.stream()
				.filter(t -> mask == null || mask.intersects(GeometryTools.createRectangle(t.getImageX(), t.getImageY(), t.getImageWidth(), t.getImageHeight())))
				.collect(Collectors.toList());
		
		// Detect all potential nuclei
		var server = imageData.getServer();
		var cal = server.getPixelCalibration();
		double expansion = cellExpansion / cal.getAveragedPixelSize().doubleValue();
		var plane = request.getImagePlane();
		
		// Compute op with preprocessing
		var fullPreprocess = new ArrayList<ImageOp>();
		fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));
		
		// Do global preprocessing calculations, if required
		if (globalPreprocess != null) {
			try {
				var normalizeOps = globalPreprocess.createOps(op, imageData, roi, request.getImagePlane());
				fullPreprocess.addAll(normalizeOps);
			} catch (IOException e) {
				throw new RuntimeException("Exception computing global normalization", e);
			}
		}
		
		if (!preprocess.isEmpty()) {
			fullPreprocess.addAll(preprocess);
		}
		if (fullPreprocess.size() > 1)
			fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));

		var opWithPreprocessing = op.appendOps(fullPreprocess.toArray(ImageOp[]::new));

		// Detect all potential nuclei
		if (tiles.size() > 1)
			log("Detecting nuclei for {} tiles", tiles.size());
		else
			log("Detecting nuclei");
		var nuclei = tiles.parallelStream()
				.flatMap(t -> detectObjectsForTile(opWithPreprocessing, dnn, imageData, t.getRegionRequest(), tiles.size() > 1, mask).stream())
				.collect(Collectors.toList());
		
		if (cancelRuns)
			return Collections.emptyList();
		
		// Filter nuclei again if we need to for resolving tile overlaps
		if (tiles.size() > 1) {
			log("Resolving nucleus overlaps");
			nuclei = filterNuclei(nuclei);
		}
		
		// Convert to detections, dilating to approximate cells if necessary
		// Drop cells if they fail (rather than catastrophically give up)
		var detections = nuclei.parallelStream()
				.map(n -> {
					try {
						return convertToObject(n, plane, expansion, constrainToParent ? mask : null);
					} catch (Exception e) {
						logger.warn("Error converting to object: " + e.getLocalizedMessage(), e);
						return null;
					}
				}).filter(n -> n != null)
				.collect(Collectors.toList());
		
		// Resolve cell overlaps, if needed
		if (expansion > 0 && !ignoreCellOverlaps) {
			log("Resolving cell overlaps");
			if (creatorFun != null) {
				// It's awkward, but we need to temporarily convert to cells and back
				var cells = detections.stream().map(c -> objectToCell(c)).collect(Collectors.toList());
				cells = CellTools.constrainCellOverlaps(cells);
				detections = cells.stream().map(c -> cellToObject(c, creatorFun)).collect(Collectors.toList());
			} else
				detections = CellTools.constrainCellOverlaps(detections);
		}
		
		// Add shape measurements, if needed
		if (measureShape)
			detections.parallelStream().forEach(c -> ObjectMeasurements.addShapeMeasurements(c, cal));
		
		// Add intensity measurements, if needed
		if (!detections.isEmpty() && !measurements.isEmpty()) {
			log("Making measurements");
			var stains = imageData.getColorDeconvolutionStains();
			var builder = new TransformedServerBuilder(server);
			if (stains != null) {
				List<Integer> stainNumbers = new ArrayList<>();
				for (int s = 1; s <= 3; s++) {
					if (!stains.getStain(s).isResidual())
						stainNumbers.add(s);
				}
				builder.deconvolveStains(stains, stainNumbers.stream().mapToInt(i -> i).toArray());
			}
			
			var server2 = builder.build();
			double downsample = resolution.getAveragedPixelSize().doubleValue() / cal.getAveragedPixelSize().doubleValue();
			
			detections.parallelStream().forEach(cell -> {
				try {
					ObjectMeasurements.addIntensityMeasurements(server2, cell, downsample, measurements, compartments);					
				} catch (IOException e) {
					log(e.getLocalizedMessage(), e);
				}
			});
			
		}
		
		log("Detected {} cells", detections.size());

		return detections;
	}
	
	
	
	private static PathObject objectToCell(PathObject pathObject) {
		ROI roiNucleus = null;
		var children = pathObject.getChildObjects();
		if (children.size() == 1)
			roiNucleus = children.iterator().next().getROI();
		else if (children.size() > 1)
			throw new IllegalArgumentException("Cannot convert object with multiple child objects to a cell!");
		return PathObjects.createCellObject(pathObject.getROI(), roiNucleus, pathObject.getPathClass(), pathObject.getMeasurementList());
	}
	
	private static PathObject cellToObject(PathObject cell, Function<ROI, PathObject> creator) {
		var parent = creator.apply(cell.getROI());
		var nucleusROI = cell instanceof PathCellObject ? ((PathCellObject)cell).getNucleusROI() : null;
		if (nucleusROI != null) {
			var nucleus = creator.apply(nucleusROI);
			nucleus.setPathClass(cell.getPathClass());
			parent.addChildObject(nucleus);
		}
		parent.setPathClass(cell.getPathClass());
		var cellMeasurements = cell.getMeasurementList();
		if (!cellMeasurements.isEmpty()) {
			try (var ml = parent.getMeasurementList()) {
				ml.putAll(cellMeasurements);
			}
		}
		return parent;
	}
	
	
	
	private void log(String message, Object... arguments) {
		if (doLog)
			logger.info(message, arguments);
		else
			logger.debug(message, arguments);			
	}
	
	
	private PathObject convertToObject(PotentialNucleus nucleus, ImagePlane plane, double cellExpansion, Geometry mask) {
		var geomNucleus = simplify(nucleus.geometry);
		PathObject pathObject;
		if (cellExpansion > 0) {
//			cellExpansion = geomNucleus.getPrecisionModel().makePrecise(cellExpansion);
//			cellExpansion = Math.round(cellExpansion);
			// Note that prior to QuPath v0.4.0 an extra fix was needed here
			var geomCell = CellTools.estimateCellBoundary(geomNucleus, cellExpansion, cellConstrainScale);
			if (mask != null) {
				geomCell = GeometryTools.attemptOperation(geomCell, g -> g.intersection(mask));
				// Fix nucleus overlaps (added v0.4.0)
				var geomCell2 = geomCell;
				geomNucleus = GeometryTools.attemptOperation(geomNucleus, g -> g.intersection(geomCell2));
				geomNucleus = GeometryTools.ensurePolygonal(geomNucleus);
			}
			geomCell = simplify(geomCell);
			
			// Intersection with complex mask could give linestrings - which we want to remove
			geomCell = GeometryTools.ensurePolygonal(geomCell);
			
			if (geomCell.isEmpty()) {
				logger.warn("Empty cell boundary at {} will be skipped", nucleus.geometry.getCentroid());
				return null;
			}
			if (geomNucleus.isEmpty()) {
				logger.warn("Empty nucleus at {} will be skipped", nucleus.geometry.getCentroid());
				return null;
			}
			var roiCell = GeometryTools.geometryToROI(geomCell, plane);
			var roiNucleus = GeometryTools.geometryToROI(geomNucleus, plane);
			if (creatorFun == null)
				pathObject = PathObjects.createCellObject(roiCell, roiNucleus, null, null);
			else {
				pathObject = creatorFun.apply(roiCell);
				if (roiNucleus != null) {
					pathObject.addChildObject(creatorFun.apply(roiNucleus));
				}
			}
		} else {
			if (mask != null) {
				geomNucleus = GeometryTools.attemptOperation(geomNucleus, g -> g.intersection(mask));
				geomNucleus = GeometryTools.ensurePolygonal(geomNucleus);
				if (geomNucleus.isEmpty()) {
					return null;
				}
			}
			var roiNucleus = GeometryTools.geometryToROI(geomNucleus, plane);
			if (creatorFun == null)
				pathObject = PathObjects.createDetectionObject(roiNucleus);
			else
				pathObject = creatorFun.apply(roiNucleus);
		}
		if (includeProbability) {
        	try (var ml = pathObject.getMeasurementList()) {
        		ml.put("Detection probability", nucleus.getProbability());
        	}
        }
		
		// Set classification, if available
		PathClass pathClass;
		if (classifications == null)
			pathClass = globalPathClass;
		else
			pathClass = classifications.getOrDefault(nucleus.getClassification(), globalPathClass);
		
		if (pathClass != null && pathClass.isValid())
			pathObject.setPathClass(pathClass);
		return pathObject;
	}
	
	
	private Geometry simplify(Geometry geom) {
		if (simplifyDistance <= 0)
			return geom;
		try {
			return VWSimplifier.simplify(geom, simplifyDistance);
		} catch (Exception e) {
			return geom;
		}
	}
	
	
	private static int[] range(int startInclusive, int endExclusive) {
		int n = endExclusive - startInclusive;
		int[] output = new int[n];
		for (int i = 0; i < n; i++)
			output[i] = startInclusive + i;
		return output;
	}
	
	
	private static Mat extractChannels(Mat mat, int... channels) {
		Mat output;
		int n = channels.length;
		if (n == 0) {
			output = new Mat();			
		} else if (n == 1) {
			output = new Mat();
			opencv_core.extractChannel(mat, output, channels[0]);
		} else {
			int[] pairs = new int[n * 2];
			for (int i = 0; i < n; i++) {
				pairs[i*2] = channels[i];
				pairs[i*2+1] = i;
			}
			output = new Mat(mat.rows(), mat.cols(), opencv_core.CV_MAKE_TYPE(mat.depth(), n));
			opencv_core.mixChannels(mat, 1, output, 1, pairs, n);
		}
		return output;
	}
	
	
	private static Padding ensureSize(Mat mat, int width, int height, int borderType) {
		int x1 = 0, x2 = 0, y1 = 0, y2 = 0;
		int w = mat.cols();
		int h = mat.rows();
		
		Padding padding = Padding.empty();
		
		boolean pad = false;
		if (w < width) {
			x1 = (width - w) / 2;
			x2 = (width - w - x1);
			w = width;
			pad = true;
		}
		if (h < height) {
			y1 = (height - h) / 2;
			y2 = (height - h - y1);
			h = height;
			pad = true;
		}
		if (pad) {
			opencv_core.copyMakeBorder(mat, mat, y1, y2, x1, x2, borderType);
			padding = Padding.getPadding(x1, x2, y1, y2);
		}
		if (w != width && h != height)
			opencv_imgproc.resize(mat, mat, new Size(width, height));
		return padding;
	}
	
	
	private List<PotentialNucleus> detectObjectsForTile(ImageDataOp op, DnnModel<?> dnn, ImageData<BufferedImage> imageData, RegionRequest request, boolean excludeOnBounds, Geometry mask) {

		List<PotentialNucleus> nuclei;
		
		if (Thread.currentThread().isInterrupted())
			cancelRuns = true;
		
		if (cancelRuns)
			Collections.emptyList();
		
		// Create a mask around pixels we can use
		var regionMask = GeometryTools.createRectangle(request.getX(), request.getY(), request.getWidth(), request.getHeight());
		if (mask == null)
			mask = regionMask;
		else
			mask = GeometryTools.attemptOperation(mask, m -> m.intersection(regionMask));

		// Create a padded request, if we need one
		RegionRequest requestPadded = request;
		if (pad > 0) {
			double downsample = request.getDownsample();
			var server = imageData.getServer();
			int x1 = (int)Math.max(0, Math.round(request.getX() - downsample * pad));
			int y1 = (int)Math.max(0, Math.round(request.getY() - downsample * pad));
			int x2 = (int)Math.min(server.getWidth(), Math.round(request.getMaxX() + downsample * pad));
			int y2 = (int)Math.min(server.getHeight(), Math.round(request.getMaxY() + downsample * pad));
			requestPadded = RegionRequest.createInstance(server.getPath(), downsample, x1, y1, x2-x1, y2-y1, request.getZ(), request.getT());
		}
		
//		// Hack to visualize the tiles that are computed (for debugging)
//		imageData.getHierarchy().addPathObject(
//				PathObjects.createAnnotationObject(
//						ROIs.createRectangleROI(request),
//						PathClassFactory.getPathClass("Temporary")
//						));
		
		try (var scope = new PointerScope()) {
			Mat mat;
			try {
				mat = op.apply(imageData, requestPadded);
			} catch (IOException e) {
				logger.error(e.getLocalizedMessage(), e);
				return Collections.emptyList();
			}
						
			// Calculate image width & height.
			// These need to be consistent with the expected maximum number of pooling operations
			// to avoid shape problems.
			int expectedPooling = 6; // A generous estimate (usually 3 or 4 expected)
			int multiple = (int)Math.pow(2, expectedPooling);
			int tw = (int)Math.ceil(mat.cols()/(double)multiple) * multiple;
			int th = (int)Math.ceil(mat.rows()/(double)multiple) * multiple;
//			
			// Ensure we have a Mat of the right size
			var padding = ensureSize(mat, tw, th, opencv_core.BORDER_REFLECT);
			
			boolean isFirstRun = firstRun.getAndSet(false);
			
			Map<String, Mat> output;
//			synchronized(dnn) {
				output = dnn.convertAndPredict(Map.of(DnnModel.DEFAULT_INPUT_NAME, mat));
//			}
			Mat matProb = null;
			Mat matRays = null;
			Mat matClassifications = null;
			if (output.size() == 1) {
				// Split channels to extract probability, ray and (possibly) classification images
				var matOutput = output.values().iterator().next();
				int nChannels = matOutput.channels();
				int nClassifications = classifications == null ? 0 : classifications.size();
				int nRays = nChannels - 1 - nClassifications;
				matProb = extractChannels(matOutput, 0);
				matRays = extractChannels(matOutput, range(1, nRays+1));
				matClassifications = nClassifications == 0 ? null : extractChannels(matOutput, range(nRays+1, nChannels));
			} else {
				// Split output as needed
				// We require that probabilities are single-channel, and there are more rays than classifications
				for (var entry : output.entrySet()) {
					var temp = entry.getValue();
					if (temp.channels() == 1)
						matProb = temp;
					else if (matRays == null)
						matRays = temp;
					else {
						if (temp.channels() > matRays.channels()) {
							matClassifications = matRays;
							matRays = temp;
						} else
							matClassifications = temp;
					}
				}
			}
			
			// Warn if we have weird dimensions on the first run
			if (isFirstRun) {
				if (classifications != null && !classifications.isEmpty()) {
					int nClassifications = classifications.size();
					int nChannels = matClassifications == null ? 0 : matClassifications.channels();
					// We might not specify a background classification, but if we have very different numbers from the prediction we should report that
					if (nClassifications > nChannels || nClassifications < nChannels-1)
						logger.warn("{} classifications provided, {} available in the prediction", nClassifications, nChannels);
					else
						logger.debug("{} classifications provided, {} available in the prediction", nClassifications, nChannels);
				}
			}
			
			// Depending upon model export, we might have a half resolution prediction that needs to be rescaled
			long inputWidth = mat.cols();
			long inputHeight = mat.rows();
			if (inputWidth <= 0 || inputHeight <= 0)
				throw new RuntimeException("Mat dimensions are unknown!");
			double scaleX = Math.round((double)inputWidth / matProb.cols());
			double scaleY = Math.round((double)inputHeight / matProb.rows());
			if (scaleX != 1.0 || scaleY != 1.0) {
				if (scaleX != 2.0 || scaleY != 2.0)
					logger.warn("Unexpected StarDist rescaling x={}, y={}", scaleX, scaleY);
				else
					logger.debug("StarDist rescaling x={}, y={}", scaleX, scaleY);
			}
			
			// Convert predictions to potential nuclei
			FloatIndexer indexerProb = matProb.createIndexer();
			FloatIndexer indexerRays = matRays.createIndexer();
			FloatIndexer indexerClassifications = matClassifications == null ? null : matClassifications.createIndexer();
			nuclei = createNuclei(indexerProb, indexerRays, indexerClassifications,
					requestPadded.getDownsample(),
					requestPadded.getX() - requestPadded.getDownsample() * padding.getX1(),
					requestPadded.getY() - requestPadded.getDownsample() * padding.getY1(),
					scaleX,
					scaleY,
					mask);
			
			// Exclude anything that overlaps the right/bottom boundary of a region
			if (excludeOnBounds) {
				var iter = nuclei.iterator();
				while (iter.hasNext()) {
					var n = iter.next();
					var env = n.geometry.getEnvelopeInternal();
					if (env.getMaxX() >= requestPadded.getMaxX() || env.getMaxY() >= requestPadded.getMaxY())
						iter.remove();
				}
			}
			
		}
		
		return filterNuclei(nuclei);
	}
	
	
//	private static void cropInPlace(Mat mat, Padding padding, double scaleX, double scaleY) {
//		if (mat == null || padding.isEmpty())
//			return;
//		
//		int x = (int)Math.round(padding.getX1() / scaleX);
//		int y = (int)Math.round(padding.getY1() / scaleY);
//		int w = mat.cols() - (int)Math.round(padding.getXSum() / scaleX);
//		int h = mat.rows() - (int)Math.round(padding.getYSum() / scaleY);
//		
//		mat.put(OpenCVTools.crop(mat, x, y, w, h));
//	}
	
	
	
	/**
	 * Create a builder to customize detection parameters.
	 * This accepts either TensorFlow's savedmodel format (if TensorFlow is available) or alternatively a frozen 
	 * .pb file compatible with OpenCV's DNN module.
	 * @param modelPath path to the StarDist/TensorFlow model to use for prediction.
	 * @return
	 */
	public static Builder builder(String modelPath) {
		var builder = maybeCreateBioimageIoBuilder(modelPath);
		if (builder == null)
			return new Builder(modelPath);
		else {
			return builder;
		}
	}
	
	
	/**
	 * Maybe initialize the builder from a BioimageIO model spec... if we can
	 * @param path
	 * @return
	 */
	private static Builder maybeCreateBioimageIoBuilder(String path) {
		var p = Paths.get(path);
		if (!Files.exists(p))
			return null;
		try {
			if (isYamlFile(p) || (Files.isDirectory(p) && Files.list(p).anyMatch(StarDist2D::isYamlFile))) {
				return StarDistBioimageIo.builder(p);
			}
		} catch (IOException e) {
			logger.debug("Exception attempting to parse BioimageIOSpec: " + e.getLocalizedMessage(), e);
		} catch (UnsatisfiedLinkError e) {
			logger.debug("Unable to parse BioimageIOSpec: " + e.getLocalizedMessage(), e);				
		}
		return null;
	}
	
	private static boolean isYamlFile(Path path) {
		if (Files.isRegularFile(path)) {
			var name = path.getFileName().toString().toLowerCase();
			return name.endsWith(".yml") || name.endsWith(".yaml");
		}
		return false;
	}
	
	
	
	/**
	 * Create a builder to customize detection parameters, using a provided {@link DnnModel} for prediction.
	 * This provides a way to use an alternative machine learning library and model file, rather than the default 
	 * (OpenCV or TensorFlow).
	 * @param dnn the model to use for prediction
	 * @return
	 */
	public static Builder builder(DnnModel<?> dnn) {
		return new Builder(dnn);		
	}
	
	
	/**
	 * Build a normalization op that can be based upon the entire (2D) image, rather than only local tiles.
	 * <p>
	 * Example:
	 * <pre>
	 * <code>
	 *   var builder = StarDist2D.builder()
	 *   	.preprocess(
	 *   		StarDist2D.imageNormalizationBuilder()
	 *   			.percentiles(0, 99.8)
	 *   			.perChannel(false)
	 *   			.downsample(10)
	 *   			.build()
	 *   	).pixelSize(0.5) // Any other options to customize StarDist2D
	 *   	.build()
	 * </code>
	 * </pre>
	 * <p>
	 * Note that currently this requires downsampling the image to a manageable size.
	 * 
	 * @return
	 */
	public static ImageNormalizationBuilder imageNormalizationBuilder() {
		return new ImageNormalizationBuilder();
	}
	
	
	/**
	 * Create a potential nucleus.
	 * @param indexerProb probability values
	 * @param indexerRays ray values
	 * @param indexerClass classification probabilities (optional)
	 * @param downsample downsample for the region request, used to convert coordinates
	 * @param originX x-coordinate for the top left of the image, used to convert coordinates
	 * @param originY y-coordinate for the top left of the image, used to convert coordinates
	 * @param scaleX scaling to apply to x pixel index; normally 1.0, but may be 2.0 if passing downsampled output
	 * @param scaleY scaling to apply to y pixel index; normally 1.0, but may be 2.0 if passing downsampled output
	 * @param mask optional geometry mask, in the full image space
	 * @return list of potential nuclei, sorted in descending order of probability
	 */
	private List<PotentialNucleus> createNuclei(FloatIndexer indexerProb, FloatIndexer indexerRays, FloatIndexer indexerClass, double downsample, double originX, double originY, double scaleX, double scaleY, Geometry mask) {
	    long[] sizes = indexerProb.sizes();
	    long[] inds = new long[3];
	    int h = (int)sizes[0];
	    int w = (int)sizes[1];
	    
	    int nRays = (int)indexerRays.size(2);
	    double[][] rays = sinCosAngles(nRays);
	    double[] raySine = rays[0];
	    double[] rayCosine = rays[1];
	    
	    int nClasses = indexerClass == null ? 0 : (int)indexerClass.size(2);

	    var nuclei = new ArrayList<PotentialNucleus>();
	    
	    var locator = mask == null ? null : new SimplePointInAreaLocator(mask);

	    var factory = GeometryTools.getDefaultFactory();
	    var precisionModel = factory.getPrecisionModel();
	    for (int y = 0; y < h; y++) {
	        inds[0] = y;
	        for (int x = 0; x < w; x++) {
	            inds[1] = x;
	            inds[2] = 0;
	            double prob = indexerProb.get(inds);
	            if (prob < threshold)
	                continue;
	            var coords = new ArrayList<Coordinate>();
	            Coordinate lastCoord = null;
	            for (int a = 0; a < nRays; a++) {
	                inds[2] = a;
	                double val = indexerRays.get(inds);
	                // We can get NaN
	                if (!Double.isFinite(val))
	                	continue;
	                // Python implementation imposes a minimum value
	                val = Math.max(1e-3, val);
	                // Create coordinate & add if it is distinct
	                double xx = precisionModel.makePrecise(originX + (x * scaleX + val * rayCosine[a]) * downsample);
	                double yy = precisionModel.makePrecise(originY + (y * scaleY + val * raySine[a]) * downsample);
	                var coord = new Coordinate(xx, yy);
	                if (!Objects.equals(coord, lastCoord))
	                	coords.add(coord);
	            }
	            // We need at least 3 for a reasonable nucleus
	            if (coords.size() < 3)
	            	continue;
	            else if (!coords.get(0).equals(coords.get(coords.size()-1)))
	            	coords.add(coords.get(0));
	            try {
		            var polygon = factory.createPolygon(coords.toArray(Coordinate[]::new));
		            if (locator == null || locator.locate(new Centroid(polygon).getCentroid()) != Location.EXTERIOR) {
		            	var geom = simplify(polygon);
		            	// Get classification, if available
		            	int classification = -1;
		            	if (indexerClass != null) {
		            		double maxProb = Double.NEGATIVE_INFINITY;
			            	for (int c = 0; c < nClasses; c++) {
			            		inds[2] = c;
			            		double probClass = indexerClass.get(inds);
			            		if (probClass > maxProb) {
				            		classification = c;
				            		maxProb = probClass;
			            		}
			            	}
		            	}
		            	if (classification != 0 || keepClassifiedBackground)
		            		nuclei.add(new PotentialNucleus(geom, prob, classification));
		            }
	            } catch (Exception e) {
	            	logger.warn("Error creating nucleus: " + e.getLocalizedMessage(), e);
	            }
	        }
	    }
	    return nuclei;
	}


	private static List<PotentialNucleus> filterNuclei(List<PotentialNucleus> potentialNuclei) {
		
		// Sort in descending order of probability
		Collections.sort(potentialNuclei, Comparator.comparingDouble((PotentialNucleus n) -> n.getProbability()).reversed());
		
		// Create array of nuclei to keep & to skip
	    var nuclei = new LinkedHashSet<PotentialNucleus>();
	    var skippedNucleus = new HashSet<PotentialNucleus>();
	    int skipErrorCount = 0;
	    
	    // Create a spatial cache to find overlaps more quickly
	    // (Because of later tests, we don't need to update envelopes even though geometries may be modified)
	    Map<Geometry, Envelope> envelopes = new HashMap<>();
	    var tree = new STRtree();
	    for (var nuc : potentialNuclei) {
	    	var env = nuc.geometry.getEnvelopeInternal();
	    	envelopes.put(nuc.geometry, env);
	    	tree.insert(env, nuc);
	    }
	    
	    var preparingFactory = new PreparedGeometryFactory();
	    
	    for (var nucleus : potentialNuclei) {
	        if (skippedNucleus.contains(nucleus))
	            continue;
	        
	        nuclei.add(nucleus);
        	var envelope = envelopes.computeIfAbsent(nucleus.geometry, g -> g.getEnvelopeInternal());
        	
        	@SuppressWarnings("unchecked")
			var overlaps = (List<PotentialNucleus>)tree.query(envelope);
        	
        	// Remove the overlaps that we can be sure don't apply using quick tests, to avoid expensive ones
        	var iter = overlaps.iterator();
        	while (iter.hasNext()) {
        		var nucleus2 = iter.next();
        		if (nucleus2 == nucleus || skippedNucleus.contains(nucleus2) || nuclei.contains(nucleus2))
        			iter.remove();
        		else {
        			// Envelope text needed because nuclei can have been modified
	        		var env = envelopes.computeIfAbsent(nucleus2.geometry, g -> g.getEnvelopeInternal());
	            	if (!envelope.intersects(env))
	            		iter.remove();
        		}
        	}
        	
        	// If we need to compare a lot of intersections, preparing the geometry can speed things up
        	PreparedGeometry prepared = null;
        	if (overlaps.size() > 5) {
        		prepared = preparingFactory.create(nucleus.geometry);
        	}
        	for (var nucleus2 : overlaps) {
        		// If we have an overlap, retain the higher-probability nucleus only (i.e. the one we met first)
        		// Try to refine other nuclei
	            try {
	            	boolean checkDifference = true;
	            	if (prepared == null) {
	            		// We could check for intersection, but it seems faster to just compute difference
	            		// (this would warrant some more systematic checking though)
	            		checkDifference = true;//nucleus.geometry.intersects(nucleus2.geometry);
	            	} else
	            		checkDifference = prepared.intersects(nucleus2.geometry);
	                if (checkDifference) {
	                	// Retain the nucleus only if it is not fragmented, or less than half its original area
	                    var difference = nucleus2.geometry.difference(nucleus.geometry);
	                    
	                    // Discard linestrings
	                    if (difference instanceof GeometryCollection)
	                    	difference = GeometryTools.ensurePolygonal(difference);
	                    
	                    if (difference instanceof Polygon && difference.getArea() > nucleus2.fullArea / 2.0)
	                        nucleus2.geometry = difference;
	                    else {
	                    	skippedNucleus.add(nucleus2);
	                    }
	                }
	            } catch (Exception e) {
                	skippedNucleus.add(nucleus2);
	            	skipErrorCount++;
	            }

        	}
	    }
	    if (skipErrorCount > 0) {
	    	int skipCount = skippedNucleus.size();
	    	String s = skipErrorCount == 1 ? "1 nucleus" : skipErrorCount + " nuclei";
	    	logger.warn("Skipped {} due to error in resolving overlaps ({}% of all skipped)", 
	    			s, GeneralTools.formatNumber(skipErrorCount*100.0/skipCount, 1));
	    }
	    return new ArrayList<>(nuclei);
	}
	
	
	private static double[][] sinCosAngles(int n) {
	    double[][] angles = new double[2][n];
	    for (int i = 0; i < n; i++) {
	        double theta = 2 * Math.PI / n * i;
	        angles[0][i] = Math.sin(theta);
	        angles[1][i] = Math.cos(theta);
	    }
	    return angles;
	}
	
	
	private static class PotentialNucleus {
		
		private Geometry geometry;
	    private double fullArea;
	    private double probability;
	    private int classification;

	    PotentialNucleus(Geometry geom, double prob, int classification) {
	        this.geometry = geom;
	        this.probability = prob;
	        this.classification = classification;
	        this.fullArea = geom.getArea();
	    }

	    double getProbability() {
	        return probability;
	    };
	    
	    int getClassification() {
	    	return classification;
	    }
		
	}


	/**
	 * Close and cleanup resources.
	 * 
	 * @implNote In practice, this means close any {@link DnnModel} stored if it is an instance of 
	 * {@link Closeable} or {@link AutoCloseable}.
	 * This can be important to avoid memory leaks, particularly if using a GPU.
	 */
	@Override
	public void close() throws Exception {
		if (dnn instanceof Closeable) {
			((Closeable) dnn).close();
		} else if (dnn instanceof AutoCloseable)
			((AutoCloseable) dnn).close();
	}
	
	
}
