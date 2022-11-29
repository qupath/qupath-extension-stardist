/*-
 * Copyright 2020-2021 QuPath developers, University of Edinburgh
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

/**
 * Install StarDist as an extension.
 * <p>
 * Currently this doesn't really do anything much, beyond including a reference 
 * in the listed extensions of QuPath and enabling some compatibility/update checks.
 * StarDist itself is only accessible via scripting.
 * In the future, the extension may also add a UI.
 * 
 * @author Pete Bankhead
 */
public class StarDistExtension implements QuPathExtension, GitHubProject {

	private static final Logger logger = LoggerFactory.getLogger(StarDistExtension.class);
	
	private boolean isInstalled = false;
	
	private static final Map<String, String> SCRIPTS = Map.of(
			"StarDist H&E nucleus detection script", "scripts/StarDistHE.groovy",
			"StarDist brightfield cell detection script", "scripts/StarDistDeconvolved.groovy",
			"StarDist fluorescence cell detection script", "scripts/StarDistFluorescence.groovy",
			"StarDist full cell detection script", "scripts/StarDistTemplate.groovy"
			);
	
	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled)
			return;
		
		// Does nothing
		for (var entry : SCRIPTS.entrySet()) {
			try {
				var script = entry.getValue();
				var command = entry.getKey();
				var url = StarDist2D.class.getClassLoader().getResource(script);
				if (url == null) {
					logger.warn("Unable to find script URL for {}", script);
					continue;
				}
				var uri = url.toURI();
				if (uri != null) {
					MenuTools.addMenuItems(
			                qupath.getMenu("Extensions>StarDist", true),
			                new Action(command, e -> openScript(qupath, uri)));
				}
			} catch (URISyntaxException e) {
				logger.error(e.getLocalizedMessage(), e);
			}
		}
	}

	@Override
	public String getName() {
		return "StarDist extension";
	}

	@Override
	public String getDescription() {
		return "Run StarDist nucleus detection via scripting.\n"
				+ "See the extension repository for citation information.";
	}
	
	@Override
	public Version getQuPathVersion() {
		return Version.parse("0.4.0-SNAPSHOT");
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "qupath", "qupath-extension-stardist");
	}
	
	
	private static void openScript(QuPathGUI qupath, URI uri) {
		var editor = qupath.getScriptEditor();
		if (editor == null) {
			logger.error("No script editor is available!");
			return;
		}
		try {
			var script = Files.readString(Paths.get(uri));
			qupath.getScriptEditor().showScript("StarDist detection", script);
		} catch (IOException e) {
			logger.error("Unable to open script: " + e.getLocalizedMessage(), e);
		}
	}
	

}
