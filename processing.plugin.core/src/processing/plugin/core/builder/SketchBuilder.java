/**
 * Copyright (c) 2010 Ben Fry and Casey Reas
 *
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * Contributors:
 *     Chris Lonnen - initial API and implementation
 */
package processing.plugin.core.builder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jdt.core.JavaModelException;

import processing.app.Preferences;
import processing.app.debug.RunnerException;
import processing.app.preproc.PdePreprocessor;
import processing.app.preproc.PreprocessResult;

import processing.plugin.core.ProcessingCore;
import processing.plugin.core.ProcessingUtilities;
import processing.plugin.core.model.LibraryFolder;

/**
 * Builder for Processing Sketches.
 * <p>
 * Preprocesses .pde sketches into Java. Errors returned are reflected back on the source files.
 * The SketchNature class is tightly integrated and manages the configuration so this builder 
 * works together with the JDT, so woe be to those who would carelessly manipulate this builder 
 * directly.
 * <p>
 * The builder is compatible with the PDE, and it expects sketches to be laid out with the
 * same folder structure. It may store metadata and temporary build files in the sketch 
 * file system but these will not change how the PDE interacts with it. Users should be 
 * able to use the PDE interchangeably with this builder.
 * <p>
 * Though this implements the Incremental Project Builder, the preprocessor is not incremental
 * and forces all builds to be full builds. To save a little bit of time, any build request is
 * treated as a full build without an inspection of the resource delta.
 */
public class SketchBuilder extends IncrementalProjectBuilder{	

	/** The identifier for the Processing builder (value <code>"processing.plugin.core.processingbuilder"</code>). */
	public static final String BUILDER_ID = ProcessingCore.PLUGIN_ID + ".sketchBuilder";

	/** Parent marker for Processing created markers (value <code>"processing.plugin.core.processingMarker"</code>). */
	public static final String PROCESSINGMARKER = ProcessingCore.PLUGIN_ID + ".processingMarker";

	/** Problem marker for processing preprocessor issues (value <code>"processing.plugin.core.preprocError"</code>). */
	public static final String PREPROCMARKER = ProcessingCore.PLUGIN_ID + ".preprocError";

	/** Problem marker for processing compile issues value <code>"processing.plugin.core.compileError"</code> */
	public static final String COMPILEMARKER = ProcessingCore.PLUGIN_ID + ".compileError";

	/** All of these need to be set for the Processing.app classes. */
	static{
		Preferences.set("editor.tabs.size", "4");
		Preferences.set("preproc.substitute_floats","true");
		Preferences.set("preproc.web_colors", "true");
		Preferences.set("preproc.color_datatype", "true");
		Preferences.set("preproc.enhanced_casting", "true");
		Preferences.set("preproc.substitute.unicode", "true");
		Preferences.set("preproc.output_parse.tree", "false");
		Preferences.set("export.application.fullscreen", "false");
		Preferences.set("run.present.bgcolor", "#666666");
		Preferences.set("export.application.stop", "true");
		Preferences.set("run.present.stop.color", "#cccccc");
		Preferences.set("run.window.bgcolor", "#ECE9D8");
		Preferences.set("preproc.imports.list", "java.applet.*,java.awt.Dimension,java.awt.Frame,java.awt.event.MouseEvent,java.awt.event.KeyEvent,java.awt.event.FocusEvent,java.awt.Image,java.io.*,java.net.*,java.text.*,java.util.*,java.util.zip.*,java.util.regex.*");
	}

	// TODO move this stuff to a model
	//
	// Right now an ad-hoc model is implied by the SketchProject methods and
	// some of these fields. A proper model should manage these state objects,
	// manage markers, and be controlled by the SketchProject object.
	//
	// A good starting model would look like processing.app.Sketch, separated
	// from the UI. Possible create it in the PDE, and extend it here for marker 
	// management and such.
	//
	// Further extensions would add flexibility, possibly integrate things
	// with the JDT more completely.

	/** Full paths to source folders for the JDT  */
	private ArrayList<IPath>srcFolderPathList;

	/** Full paths to jars required to compile the sketch */
	private ArrayList<IPath>libraryJarPathList;

	/**
	 * Triggered by platform Clean command. 
	 * <p>
	 * Reset any state from previous builds. 
	 */
	protected void clean(IProgressMonitor monitor) throws CoreException{
		SketchProject sketch = SketchProject.forProject(this.getProject());
		deleteP5ProblemMarkers(sketch.getProject());
		srcFolderPathList = new ArrayList<IPath>();
		libraryJarPathList = new ArrayList<IPath>();
		// if this is the first run of the builder the build folder will not be stored yet,
		// but if there is an old build folder from an earlier session it should still be nuked.
		// get the handle to it from the project's configuration
		IFolder build = sketch.getBuildFolder();
		if (build != null) {
			for( IResource r : build.members()) {
				r.delete(true, monitor);
			}
		}

		// any other cleaning stuff goes here
		// Eventually, a model (controlled by SketchProject) should manage the markers,
		// folders, etc. Cleaning the model should be in a method called beCleaned() 
		// in the SketchProject. This method will then look something like:
		// sketch.beCleaned();
	}

	/**
	 * Build the sketch project.
	 * <p>
	 * This usually means grabbing all the Processing files and compiling them
	 * to Java source files and moving them into a designated folder where the
	 * JDT will grab them and build them.
	 * <p>
	 * For now all builds are full builds because the preprocessor does not
	 * handle incremental builds.
	 */
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException{
		IProject project =  this.getProject();
		SketchProject sketch = SketchProject.forProject(project);
		switch (kind) {
		case FULL_BUILD: 
			return this.fullBuild(sketch, monitor);
		case AUTO_BUILD:
			return this.autoBuild(sketch, monitor);
		case INCREMENTAL_BUILD:
			return this.incrementalBuild(sketch, monitor);
		default:
			return null; // everything falls through to return null
		}
	}

	/** Handles platform auto builds */
	protected IProject[] autoBuild(SketchProject sketchProject, IProgressMonitor monitor) throws CoreException{
		//System.err.println("Auto Build");
		IResourceDelta delta = this.getDelta(this.getProject());
		IncrementalChangeProcessor changeProcessor = new IncrementalChangeProcessor(sketchProject);
		boolean shouldBuild = changeProcessor.resourceChanged(delta);
		if (shouldBuild) {
			fullBuild(sketchProject, monitor);
		}
		return null;
	}

	/** Incremental builds are ignored. */
	protected IProject[] incrementalBuild(SketchProject sketchProject, IProgressMonitor monitor) {
		//System.err.println("Incremental Build");

		// triggered by launching a sketch or explicitly by a user iff auto build is off
		// if auto build is on, launching a sketch triggers an auto build first
		// save a few cycles by ignoring these
		return null;
	}

	/**
	 * Full build from scratch.
	 * <p>
	 * Try to clean out and old markers from derived files. They may not be present,
	 * but if they are wipe 'em out and get along with the business of building.
	 * This can be a long running process, so we use a monitor.
	 */	
	protected IProject[] fullBuild( SketchProject sketchProject, IProgressMonitor monitor) throws CoreException {
		//System.err.println("Full Build of " + sketchProject.getProject().getName());

		clean(monitor); // tabula rasa

		IProject sketch = sketchProject.getProject();
		if (sketch == null || !sketch.isAccessible()) {
			ProcessingCore.logError("Sketch is inaccessible. Aborting build process.", null);
			return null;
		}

		sketchProject.wasLastBuildSuccessful = false; 
		IFolder buildFolder = sketchProject.getBuildFolder(); // created by the getter
		if (buildFolder == null) {
			ProcessingCore.logError("Build folder could not be accessed.", null);
			return null;
		}

		IFile mainFile = sketchProject.getMainFile();
		if (!mainFile.isAccessible()) {
			reportProblem(
				"Could not find "+ sketch.getName() + ".pde, please rename your primary sketch file.",
				sketch, -1, true 
			);
			return null;
		}

		monitor.beginTask("Sketch Build", 40); // not sure how much work to do here
		if (checkCancel(monitor)) { return null; }
		/* If the code folder exists:
		 *   Find any .jar files in it and its subfolders
		 *     Add their paths to the library jar list for addition to the class path later on 
		 *     Get the packages of those jars so they can be added to the imports
		 *   Add it to the class path source folders
		 */

		IFolder codeFolder = sketchProject.getCodeFolder();	// may not exist
		String[] codeFolderPackages = null;
		if (codeFolder != null && codeFolder.exists()) {
			String codeFolderClassPath = ProcessingUtilities.contentsToClassPath(codeFolder.getLocation().toFile());
			for( String s : codeFolderClassPath.split(File.pathSeparator)) {
				if (!s.isEmpty()) {
					libraryJarPathList.add(new Path(s).makeAbsolute());
				}
			}
			codeFolderPackages = ProcessingUtilities.packageListFromClassPath(codeFolderClassPath);
		}

		monitor.worked(10);
		if (checkCancel(monitor)) { return null; }
		/* concatenate the individual .pde files into one large file. 
		 * Using temporary session properties attached to IResource files, mark where the file
		 * starts and ends in the bigCode file. This information is used later for mapping
		 * errors back to their source.
		 */

		StringBuffer bigCode = new StringBuffer();
		int bigCount = 0; // line count

		for (IResource file : sketch.members()) {
			if ("pde".equalsIgnoreCase(file.getFileExtension())) { 
				file.setSessionProperty(new QualifiedName(BUILDER_ID, "preproc start"), bigCount);
				String content = ProcessingUtilities.readFile((IFile) file);
				bigCode.append(content);
				bigCode.append("\n");
				bigCount += ProcessingUtilities.getLineCount(content);
				file.setSessionProperty(new QualifiedName(BUILDER_ID, "preproc end"), bigCount);
			}
		}

		monitor.worked(10);
		if (checkCancel(monitor)) { return null; } 
		// Feed everything to the preprocessor

		PdePreprocessor preproc = new PdePreprocessor(sketch.getName(), 4);
		PreprocessResult result = null;
		try {
			IFile output = buildFolder.getFile(sketch.getName()+".java");
			StringWriter stream = new StringWriter();

			result = preproc.write(stream, bigCode.toString(), codeFolderPackages);

			sketchProject.sketch_width = -1;
			sketchProject.sketch_height = -1;
			sketchProject.renderer = "";

			String scrubbed = ProcessingUtilities.scrubComments(stream.toString());
			String[] matches = ProcessingUtilities.match(scrubbed, ProcessingUtilities.SIZE_REGEX);	
			if (matches != null) {
				try {
					int wide = Integer.parseInt(matches[1]);
					int high = Integer.parseInt(matches[2]);

					if (wide > 0) {
						sketchProject.sketch_width = wide;
					} else {
						ProcessingCore.logInfo("Width cannot be negative. Using default width instead.");
					}
					
					if (high > 0) {
						sketchProject.sketch_height = high;
					} else { 
						ProcessingCore.logInfo("Height cannot be negative. Using default height instead.");
					}
					
					if (matches.length==4) {
						sketchProject.renderer = matches[3].trim();
					}
					// "Actually matches.length should always be 4..." - Processing Sketch.java

				} catch (NumberFormatException e) {
					ProcessingCore.logInfo(
							"Found a reference to size, but it didn't seem to contain numbers. "
							+ "Will use default sizes instead."
					);
				}
			}  // else no size() command found, defaults are used

			ByteArrayInputStream inStream = new ByteArrayInputStream(stream.toString().getBytes());
			try{
				if (!output.exists()) {
					output.create(inStream, true, monitor);
					//TODO resource change listener to trace back JDT errors
					//	IWorkspace w = ResourcesPlugin.getWorkspace();
					//	IResourceChangeListener rcl = new ProblemListener(output);
					//	w.addResourceChangeListener(rcl);
				} else {
					output.setContents(inStream, true, false, monitor);
				}
			} finally {
				stream.close();
				inStream.close();
			}

			srcFolderPathList.add(buildFolder.getFullPath());

		} catch(antlr.RecognitionException re) {

			IResource errorFile = null; // if this remains null, the error is reported back on the sketch itself with no line number
			int errorLine = re.getLine() - 1;

			for (IResource file : sketch.members()) {
				if ("pde".equalsIgnoreCase(file.getFileExtension())) { 
					int low = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc start"));
					int high = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc end"));
					if (low <= errorLine && high > errorLine) {
						errorFile = file;
						errorLine -= low;
						break;
					}
				}
			}

			// mark the whole project if no file will step forward.
			if (errorFile == null) {
				errorFile = sketch;
				errorLine = - 1;
			} 

			reportProblem(re.getMessage(), errorFile, errorLine, true);
			return null; // bail early
		} catch (antlr.TokenStreamRecognitionException tsre) { 

			// System.out.println("and then she tells me " + tsre.toString());
			String mess = "^line (\\d+):(\\d+):\\s"; // a regexp to grab the line and column from the exception

			String[] matches = ProcessingUtilities.match(tsre.toString(), mess);
			IResource errorFile = null; 
			int errorLine = -1;
			if (matches != null) {
				errorLine = Integer.parseInt(matches[1]) - 1;
				for (IResource file : sketch.members()) {
					if ("pde".equalsIgnoreCase(file.getFileExtension())) {
						int low = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc start"));
						int high = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc end"));
						if (low <= errorLine && high > errorLine) {
							errorFile = file;
							errorLine -= low;
							break;
						}
					}
				}
			} 

			// If no file was found or the regex failed
			if (errorFile == null) {
				errorFile = sketch;
				errorLine = -1;
			} 

			reportProblem(tsre.getMessage(), errorFile, errorLine, true);		
			return null; // bail early
		} catch (RunnerException re) {
			/* 
			 * This error is not addressed in the PDE. I've only seen it correspond to
			 * an unclosed, double quote mark (").
			 */
			IResource errorFile = null; // if this remains null, the error is reported back on the sketch itself with no line
			int errorLine = re.getCodeLine() + 1; // always reported 1 line early

			for( IResource file : sketch.members()) {
				if ("pde".equalsIgnoreCase(file.getFileExtension())) {
					int low = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc start"));
					int high = (Integer) file.getSessionProperty(new QualifiedName(BUILDER_ID, "preproc end"));
					if (low <= errorLine && high > errorLine) {
						errorFile = file;
						errorLine -= low;
						break;
					}
				}			
			}

			// mark the whole project if no file will step forward.
			if (errorFile == null) {
				errorFile = sketch;
				errorLine = -1;
			}

			reportProblem(re.getMessage(), errorFile, errorLine, true);
			return null; // bail
		} catch (Exception e) {
			ProcessingCore.logError(e); 
			return null; // bail
		}

		monitor.worked(10);
		if (checkCancel(monitor)) { return null; }

		// Library import checking

		boolean importProblems = false;
		sketchProject.libraryPaths.clear();
		for (String importPackage : result.extraImports) {
			int dot = importPackage.lastIndexOf('.');
			String entry = (dot == -1) ? importPackage : importPackage.substring(0, dot);
			LibraryFolder libFolder = ProcessingCore.getCore().getLibraryModel().getLibraryFolder(entry);
			if (libFolder == null ) {
				// The user is trying to import something we won't be able to find.
				reportProblem(
						"Library import \""+ entry +"\" could not be found. Check the library folder in your sketchbook.",
						sketch, -1, true 
				);
				importProblems=true;
				continue;
			} 
			// found what they're looking for!
			libraryJarPathList.add( new Path(libFolder.getJarPath()) ); 
			sketchProject.libraryPaths.add( new Path(libFolder.getJarPath()) );
		}

		if (importProblems) {
			return null; // bail after all errors are found.
		}

		monitor.worked(10);
		if (checkCancel(monitor)) { return null; } 

		// Add data folder if there is stuff in it
		IFolder dataFolder = sketchProject.getDataFolder();
		if (dataFolder.isAccessible()) {
			if (dataFolder.members().length > 0) srcFolderPathList.add(dataFolder.getFullPath());
		}

		// Almost there! Set a new classpath using all this stuff we've computed.

		// Even though the list types are specified, Java still tosses errors when I try 
		// to cast them. So instead I'm stuck with this idiom.

		IPath[] libPaths = new IPath[libraryJarPathList.size()];

		int i=0;
		for(IPath path : libraryJarPathList) {
			libPaths[i++] = path;
		}

		IPath[] srcPaths = new IPath[srcFolderPathList.size()];

		i=0;
		for(IPath path : srcFolderPathList) {
			srcPaths[i++] = path;
		}

		try{
			sketchProject.updateClasspathEntries( srcPaths, libPaths);
		} catch (JavaModelException e) {
			ProcessingCore.logError("There was a problem setting the compiler class path.", e);
			return null; // bail !
		}

		// everything is cool
		sketchProject.wasLastBuildSuccessful = true;
		return null;
	}

	/** Delete all of the existing P5 problem markers. */
	protected static void deleteP5ProblemMarkers(IResource resource) throws CoreException{
		if (resource != null && resource.exists()) {
			resource.deleteMarkers(SketchBuilder.PROCESSINGMARKER , true, IResource.DEPTH_INFINITE);
		}
	}

	/**
	 * Generates and assigns a processing problem marker.
	 * <p>
	 * A negative line number indicates that the problem could not be tied back to a specific line.
	 * Message strings generated by the preprocessor will be translated into a more readable form.
	 */
	private void reportProblem(String message, IResource problemFile, int lineNumber, boolean isError) {
		// translate error messages to a friendlier form
		if (message.equals("expecting RCURLY, found 'null'"))
			message = "Found one too many { characters without a } to match it.";
		if (message.indexOf("expecting RBRACK") != -1)
			message = "Syntax error, maybe a missing right ] character?";
		if (message.indexOf("expecting SEMI") != -1)
			message = "Syntax error, maybe a missing semicolon?";
		if (message.indexOf("expecting RPAREN") != -1)
			message = "Syntax error, maybe a missing right parenthesis?";
		if (message.indexOf("preproc.web_colors") != -1)
			message = "A web color (such as #ffcc00) must be six digits.";

		try{
			IMarker marker = problemFile.createMarker(SketchBuilder.PREPROCMARKER);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, isError ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING);
			if (lineNumber > -1)	marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
		} catch(CoreException e) {
			ProcessingCore.logError(e);
			return;
		}
	}

	/**
	 * Check for interruptions.
	 * <p>
	 * The build process can run long, so if the user cancels things toss an exception.
	 * Builds also tie up the workspace, so check to see if something is demanding to
	 * interrupt it and let it. Usually these interruptions would force a rebuild of the
	 * system anyhow, nullifying the work being done now, so save some cycles and let
	 * it budge in line.
	 * </p> 
	 * @return true if the build is hogging the resource thread
	 * @throws OperationCanceledException is the user cancels
	 */
	private boolean checkCancel(IProgressMonitor monitor) {
		if (monitor.isCanceled()) { 
			throw new OperationCanceledException();
		}
		if (isInterrupted()) { 
			return true;
		}
		return false;
	}
}
