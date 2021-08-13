package mica.process;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.roi.ROIWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.text.TextWindow;
import loci.formats.FormatException;
import mica.gui.ProgressDialog;
import org.apache.commons.io.FilenameUtils;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;
import loci.plugins.in.ImportProcess;

import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import javax.imageio.ImageIO;


public class BatchRunner extends Thread {

	private final Client client;
	private final ProcessingProgress progress;

	private final Map<String, TableWrapper> tables = new HashMap<>();

	private boolean inputOnOMERO;
	private boolean saveImage;
	private boolean saveROIs;
	private boolean saveResults;
	private boolean saveLog;
	private boolean loadROIs;
	private boolean clearROIs;
	private boolean outputOnOMERO;
	private boolean outputOnLocal;
	private long inputDatasetId;
	private long outputDatasetId;
	private String directoryIn;
	private String directoryOut;
	private String macro;
	private String suffix;

	private RoiManager rm;

	private BatchListener listener;


	public BatchRunner(Client client) {
		super();
		this.client = client;
		this.progress = new ProgressLog(Logger.getLogger(getClass().getName()));
	}


	public BatchRunner(Client client, ProcessingProgress progress) {
		super();
		this.client = client;
		this.progress = progress;
	}


	private void initRoiManager() {
		rm = RoiManager.getInstance2();
		if(rm == null) rm = RoiManager.getRoiManager();
	}


	private void setState(String text) {
		if (progress != null) progress.setState(text);
	}


	private void setProgress(String text) {
		if (progress != null) progress.setProgress(text);
	}


	private void setDone() {
		if (progress != null) progress.setDone();
	}


	/**
	 * If this thread was constructed using a separate
	 * {@code Runnable} run object, then that
	 * {@code Runnable} object's {@code run} method is called;
	 * otherwise, this method does nothing and returns.
	 * <p>
	 * Subclasses of {@code Thread} should override this method.
	 *
	 * @see #start()
	 * @see Thread#Thread(ThreadGroup, Runnable, String)
	 */
	@Override
	public void run() {
		if (progress instanceof ProgressDialog) {
			((ProgressDialog) progress).setVisible(true);
		}

		try {
			if (!outputOnLocal) {
				setState("Temporary directory creation...");
				directoryOut = Files.createTempDirectory("Fiji_analysis").toString();
			}
			if (inputOnOMERO) {
				setState("Images recovery from OMERO...");
				DatasetWrapper dataset = client.getDataset(inputDatasetId);
				List<ImageWrapper> images = dataset.getImages(client);
				setState("Macro running...");
				runMacro(images);
			} else {
				setState("Images recovery from input folder...");
				List<String> images = getImagesFromDirectory(getDirectoryIn());
				setState("Macro running...");
				runMacroOnLocalImages(images);
			}
			setProgress("");
			uploadTables();

			if (!outputOnLocal) {
				setState("Temporary directory deletion...");
				if (!deleteTemp(directoryOut)) {
					IJ.log("Temp directory may not be deleted.");
				}
			}
			setState("");
			setDone();
		} catch (Exception e3) {
			setDone();
			setProgress("Macro cancelled");
			if (e3.getMessage() != null && e3.getMessage().equals("Macro cancelled")) {
				IJ.run("Close");
			}
			IJ.error(e3.getMessage());
		} finally {
			if (listener != null) listener.onThreadFinished();
		}
	}


	private void deleteROIs(ImageWrapper image) {
		setState("ROIs deletion from OMERO");
		try {
			List<ROIWrapper> rois = image.getROIs(client);
			for (ROIWrapper roi : rois) {
				client.delete(roi);
			}
		} catch (ExecutionException | OMEROServerError | ServiceException | AccessException exception) {
			IJ.log(exception.getMessage());
		} catch (InterruptedException e) {
			IJ.log(e.getMessage());
			Thread.currentThread().interrupt();
		}
	}


	private List<String> getImagesFromDirectory(String directory) {
		//""" List all image's paths contained in a directory """
		File dir = new File(directory);
		File[] files = dir.listFiles();
		List<String> pathsImagesIni = new ArrayList<>();
		for (File value : Objects.requireNonNull(files)) {
			String file = value.getAbsolutePath();
		//	File f = new File(file);
		//	try {
		/*		if( ImageIO.read(f) != null) */pathsImagesIni.add(file);
		//	} catch (IOException e) {
		//	}
		}
		return pathsImagesIni;
	}


	private List<Roi> getIJRois(ImagePlus imp) {
		List<Roi> ijRois = new ArrayList<>();
		Overlay overlay = imp.getOverlay();
		if (overlay != null) {
			ijRois.addAll(Arrays.asList(overlay.toArray()));
		}
		ijRois.addAll(Arrays.asList(rm.getRoisAsArray()));
		for (Roi roi : ijRois) roi.setImage(imp);
		return ijRois;
	}


	private List<ROIWrapper> getRoisFromIJ(ImagePlus imp, String property) {
		List<ROIWrapper> rois = new ArrayList<>();
		if (imp != null) {
			List<Roi> ijRois = getIJRois(imp);
			rois = ROIWrapper.fromImageJ(ijRois, property);
		}

		return rois;
	}


	private String todayDate() {
		return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
	}


	void runMacro(List<ImageWrapper> images) {
		//""" Run a macro on images and save the result """
		String property = "ROI";
		ij.WindowManager.closeAllWindows();
		String call = "0";
		int index = 0;
		for (ImageWrapper image : images) {
			setProgress("Image " + (index + 1) + "/" + images.size());
			long outputImageId = image.getId();

			// Open image from OMERO
			ImagePlus imp = openImage(image);
			ImagePlus imageInput = imp;
			// If image could not be loaded, continue to next image.
			if (imp == null) continue;
			long ijId = imp.getID();

			// Initialize ROI Manager
			initRoiManager();

			// Load ROIs
			if(loadROIs) loadROIs(image, imp);

			// Define paths
			String title = removeExtension(imp.getTitle());

			imp.show();

			// Analyse the images.
			IJ.runMacroFile(macro, call);
			call = "1";

			imp.changes = false; // Prevent "Save Changes?" dialog
			imp = WindowManager.getCurrentImage();
			if (saveImage) {
				if (imp == null) IJ.error("Invalid choice : There is no new image.");
				Integer count = 0;
				while (imp != null) {
					if (imp.getID() != ijId) {
						List<Long> ids = saveImage(title);
						if (!ids.isEmpty()) {
							outputImageId = ids.get(0);
						}
					}
					if (count == 0){
						saveROIs(outputImageId, imp, title, property);
						saveResults(imp, outputImageId, title, property);
						if (saveLog) {
							String path = directoryOut + File.separator + title + "Log.txt";
							IJ.selectWindow("Log");
							IJ.saveAs("txt", path);
							uploadFile(outputImageId, path);
						}

						count++;
					}
					imp.close();
					imp = WindowManager.getCurrentImage();
				}
			}
			else {
				if (imp != null) imp = imageInput;
				List<Long> ids = saveImage(title);
				if (!ids.isEmpty()) {
					outputImageId = ids.get(0);
				}
				saveROIs(outputImageId, imp, title, property);
				saveResults(imp, outputImageId, title, property);
				if (saveLog) {
					String path = directoryOut + File.separator + title + "Log.txt";
					IJ.selectWindow("Log");
					IJ.saveAs("txt", path);
					uploadFile(outputImageId, path);
				}
			}
			closeWindows();
			index++;
		}
	}


	void runMacroOnLocalImages(List<String> images) throws IOException, FormatException {
		//""" Run a macro on images from local computer and save the result """
		String property = "ROI";
		WindowManager.closeAllWindows();
		String appel = "0";
		int index = 0;
		for (String image : images) {
			// Open the image
			ImporterOptions options = new ImporterOptions();
			options.setId(image);

			ImportProcess process = new ImportProcess(options);
			process.execute();
			int n = process.getSeriesCount();
			for(int i=0; i<n; i++) {
				String msg = String.format("File %d/%d, image %d/%d", index+1, images.size(), i, n);
				setProgress(msg);
				options.setSeriesOn(i, true);
				ImagePlus[] imps = BF.openImagePlus(options);
				ImagePlus imp = imps[0];
				long ijId = imp.getID();
				imp.show();

				// Initialize ROI Manager
				initRoiManager();

				// Remove extension from title
				String title = removeExtension(imp.getTitle());

				// Analyse the images
				IJ.runMacroFile(macro, appel);
				appel = "1";

				// Save and Close the various components
				Long outputImageId = null;
				imp.changes = false; // Prevent "Save Changes?" dialog
				imp = WindowManager.getCurrentImage();
				if (saveImage) {
					if (imp == null) IJ.error("Invalid choice : There is no new image.");
					Integer count = 0;
					while (imp != null) {
						if (imp.getID() != ijId) {
							List<Long> ids = saveImage(title);
							if (!ids.isEmpty()) {
								outputImageId = ids.get(0);
							}
						}
						if (count == 0){
							saveROIs(outputImageId, imp, title, property);
							saveResults(imp, outputImageId, title, property);
							if (saveLog) {
								String path = directoryOut + File.separator + title + "Log.txt";
								IJ.selectWindow("Log");
								IJ.saveAs("txt", path);
							}

							count++;
						}
						imp.close();
						imp = WindowManager.getCurrentImage();
					}
				}
				else {
					List<Long> ids = saveImage(title);
					if (!ids.isEmpty()) {
						outputImageId = ids.get(0);
					}
					saveROIs(outputImageId, imp, title, property);
					saveResults(imp, outputImageId, title, property);
					if (saveLog) {
						String path = directoryOut + File.separator + title + "Log.txt";
						IJ.selectWindow("Log");
						IJ.saveAs("txt", path);
					}
				}
				closeWindows();
				options.setSeriesOn(i, false);
			}
			index++;
		}
	}


	private String removeExtension(String title) {
		if (title.matches("(.*)qptiff(.*)")) {
			return title.replace(".qptiff", "_");
		} else {
			return FilenameUtils.removeExtension(title);
		}
	}


	private ImagePlus openImage(ImageWrapper image) {
		setState("Opening image from OMERO...");
		ImagePlus imp = null;
		try {
			imp = image.toImagePlus(client);
		} catch (ExecutionException | ServiceException | AccessException e) {
			IJ.error("Could not load image: " + e.getMessage());
		}
		return imp;
	}


	private void loadROIs(ImageWrapper image, ImagePlus imp) {
		rm.reset(); // Reset ROI manager to clear previous ROIs
		List<Roi> ijRois = new ArrayList<>();
		try {
			ijRois = ROIWrapper.toImageJ(image.getROIs(client));
		} catch (ExecutionException | ServiceException | AccessException e) {
			IJ.error("Could not import ROIs: " + e.getMessage());
		}
		for (Roi ijRoi : ijRois) {
			ijRoi.setImage(imp);
			rm.addRoi(ijRoi);
		}
	}


	private List<Long> saveImage(String title) {
		List<Long> ids = new ArrayList<>();
		if (saveImage) {
			String path = directoryOut + File.separator + title + suffix + ".tif";
			IJ.saveAs("TIFF", path);
			if (outputOnOMERO) {
				try {
					setState("Import on OMERO...");
					DatasetWrapper dataset = client.getDataset(outputDatasetId);
					ids = dataset.importImage(client, path);
				} catch (Exception e) {
					IJ.error("Could not import image: " + e.getMessage());
				}
			}
		}
		return ids;
	}


	private void saveROIs(Long imageId, ImagePlus imp, String title, String property) {
		// save of ROIs
		if (saveROIs && outputOnLocal) {  //  local save
			setState("Saving ROIs...");
			rm.runCommand("Deselect"); // deselect ROIs to save them all
			rm.runCommand("Save", directoryOut + File.separator + title + "_" + todayDate() + "_RoiSet.zip");
		}
		if (saveROIs && outputOnOMERO && imageId != null) { // save on Omero
			setState("Saving ROIs on OMERO...");
			List<ROIWrapper> rois = getRoisFromIJ(imp, property);
			try {
				ImageWrapper image = client.getImage(imageId);
				if (clearROIs) {
					deleteROIs(image);
				}
				for (ROIWrapper roi : rois) {
					roi.setImage(image);
					image.saveROI(client, roi);
				}
				loadROIs(image, imp); // reload ROIs
			} catch (ServiceException | AccessException | ExecutionException e) {
				IJ.error("Could not import ROIs to OMERO: " + e.getMessage());
			}
		}
	}


	private void saveResults(ImagePlus imp, Long imageId, String title, String property) {
		if (saveResults) {
			String resultsName = null;
			List<Roi> ijRois = getIJRois(imp);
			setState("Saving results files...");
			ResultsTable rt = ResultsTable.getResultsTable();
			if(rt != null) {
				resultsName = rt.getTitle();
				String path = directoryOut + File.separator + resultsName + "_" + title + "_" + todayDate() + ".csv";
				rt.save(path);
				if (outputOnOMERO) {
					appendTable(rt, imageId, ijRois, property);
					uploadFile(imageId, path);
				}
			}
			String[] candidates = WindowManager.getNonImageTitles();
			for (String candidate : candidates) {
				rt = ResultsTable.getResultsTable(candidate);

				// Skip if rt is null or if results already processed
				if(rt == null || rt.getTitle().equals(resultsName)) continue;

				String path = directoryOut + File.separator + candidate + "_" + title + "_" + todayDate() + ".csv";
				rt.save(path);
				if (outputOnOMERO) {
					appendTable(rt, imageId, ijRois, property);
					uploadFile(imageId, path);
				}
			}
		}
	}


	private void uploadFile(Long imageId, String path) {
		if(imageId != null) {
			try {
				setState("Uploading results files...");
				ImageWrapper image = client.getImage(imageId);
				image.addFile(client, new File(path));
			} catch (ExecutionException | ServiceException | AccessException e) {
				IJ.error("Error adding file to image:" + e.getMessage());
			} catch (InterruptedException e) {
				IJ.error("Error adding file to image:" + e.getMessage());
				Thread.currentThread().interrupt();
			}
		}
	}


	private void appendTable(ResultsTable results, Long imageId, List<Roi> ijRois, String property) {
		String resultsName = results.getTitle();
		TableWrapper table = tables.get(resultsName);
		try {
			if (table == null) {
				tables.put(resultsName, new TableWrapper(client, results, imageId, ijRois, property));
			} else {
				table.addRows(client, results, imageId, ijRois, property);
			}
		} catch (ServiceException | AccessException | ExecutionException e) {
			IJ.error("Could not create or append table: " + e.getMessage());
		}
	}


	private void uploadTables() {
		if (outputOnOMERO) {
			setState("Uploading tables...");
			try {
				DatasetWrapper dataset = client.getDataset(outputDatasetId);
				for (Map.Entry<String, TableWrapper> entry : tables.entrySet()) {
					String name = entry.getKey();
					TableWrapper table = entry.getValue();
					String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
					String newName;
					if (name == null || name.equals("")) newName = timestamp + "_" + table.getName();
					else newName = timestamp + "_" + name;
					table.setName(newName);
					dataset.addTable(client, table);
				}
			} catch (ExecutionException | ServiceException | AccessException e) {
				IJ.error("Could not save table: " + e.getMessage());
			}
		}
	}


	private boolean deleteTemp(String tmpDir) {
		//""" Delete the local copy of temporary files and directory """
		boolean deleted = true;
		File dir = new File(tmpDir);
		File[] entries = dir.listFiles();
		if (entries != null) {
			try {
				for (File entry : entries) {
					deleted &= Files.deleteIfExists(entry.toPath());
				}
				deleted &= Files.deleteIfExists(dir.toPath());
			} catch (IOException e) {
				IJ.error("Could not delete files: " + e.getMessage());
			}
		}
		return deleted;
	}


	private void closeWindows() {
		for (Frame frame : WindowManager.getNonImageWindows()) {
			if (frame instanceof TextWindow) {
				((TextWindow) frame).close(false);
			}
		}
		rm.reset();
		WindowManager.closeAllWindows();
	}


	public Client getClient() {
		return client;
	}


	public long getOutputDatasetId() {
		return outputDatasetId;
	}


	public void setOutputDatasetId(Long outputDatasetId) {
		if (outputDatasetId != null) this.outputDatasetId = outputDatasetId;
	}


	public long getInputDatasetId() {
		return inputDatasetId;
	}


	public void setInputDatasetId(Long inputDatasetId) {
		if (inputDatasetId != null) this.inputDatasetId = inputDatasetId;
	}


	public boolean shouldSaveROIs() {
		return saveROIs;
	}


	public void setSaveROIs(boolean saveROIs) {
		this.saveROIs = saveROIs;
	}


	public boolean shouldSaveResults() {
		return saveResults;
	}


	public void setSaveResults(boolean saveResults) {
		this.saveResults = saveResults;
	}


	public boolean isInputOnOMERO() {
		return inputOnOMERO;
	}


	public void setInputOnOMERO(boolean inputOnOMERO) {
		this.inputOnOMERO = inputOnOMERO;
	}


	public boolean shouldSaveImage() {
		return saveImage;
	}


	public void setSaveImage(boolean saveImage) {
		this.saveImage = saveImage;
	}


	public boolean shouldLoadROIs() {
		return loadROIs;
	}


	public void setLoadROIS(boolean loadROIs) {
		this.loadROIs = loadROIs;
	}


	public boolean shouldClearROIs() {
		return clearROIs;
	}


	public void setClearROIS(boolean clearROIs) {
		this.clearROIs = clearROIs;
	}


	public String getDirectoryIn() {
		return directoryIn;
	}


	public void setDirectoryIn(String directoryIn) {
		this.directoryIn = directoryIn;
	}


	public String getDirectoryOut() {
		return directoryOut;
	}


	public void setDirectoryOut(String directoryOut) {
		this.directoryOut = directoryOut;
	}


	public String getMacro() {
		return macro;
	}


	public void setMacro(String macro) {
		this.macro = macro;
	}


	public String getSuffix() {
		return suffix;
	}


	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}


	public boolean isOutputOnOMERO() {
		return outputOnOMERO;
	}


	public void setOutputOnOMERO(boolean outputOnOMERO) {
		this.outputOnOMERO = outputOnOMERO;
	}


	public boolean isOutputOnLocal() {
		return outputOnLocal;
	}


	public void setOutputOnLocal(boolean outputOnLocal) {
		this.outputOnLocal = outputOnLocal;
	}

	public void setsaveLog(boolean saveLog) {
		this.saveLog = saveLog;
	}

	public void addListener(BatchListener listener) {
		this.listener = listener;
	}

}
