package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.oro.text.perl.Perl5Util;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.goobi.managedbeans.LoginBean;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.UploadedFile;

import de.intranda.goobi.plugins.massuploadutils.MassUploadedFile;
import de.intranda.goobi.plugins.massuploadutils.MassUploadedFileStatus;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
@Data
public class LecosUploadWorkflowPlugin implements IWorkflowPlugin, IPlugin {

    private String allowedTypes;

    private List<MassUploadedFile> uploadedFiles = new ArrayList<>();
    private User user;
    private File tempFolder;

    private Map<String, List<MassUploadedFile>> processFilenameMap = new HashMap<>();
    private Map<String, Boolean> processTitleChecks = new HashMap<>();

    private String processTemplateName;
    private BeanHelper bHelper = new BeanHelper();
    private String title = "intranda_workflow_lecosUpload";

    private String metadataDocumentType;
    private Perl5Util perlUtil = new Perl5Util();

    @Override
    public String getGui() {
        return "/uii/plugin_workflow_lecosUpload.xhtml";
    }

    /**
     * Constructor
     */
    public LecosUploadWorkflowPlugin() {
        log.info("Mass upload plugin started");
        XMLConfiguration conf = ConfigPlugins.getPluginConfig(title);
        allowedTypes = conf.getString("allowed-file-extensions", "/(\\.|\\/)(gif|jpe?g|png|tiff?|jp2|pdf)$/");

        processTemplateName = conf.getString("processTemplateName");

        metadataDocumentType = conf.getString("metadataDocumentType", "Monograph");

        LoginBean login = (LoginBean) Helper.getManagedBeanValue("#{LoginForm}");
        if (login != null) {
            user = login.getMyBenutzer();
        }
    }

    @Override
    public PluginType getType() {
        return PluginType.Workflow;
    }

    /**
     * Handle the upload of a file
     * 
     * @param event
     */
    public void uploadFile(FileUploadEvent event) {
        try {
            if (tempFolder == null) {
                tempFolder = new File(ConfigurationHelper.getInstance().getTemporaryFolder(), user.getLogin());
                if (!tempFolder.exists()) {
                    if (!tempFolder.mkdirs()) {
                        throw new IOException("Upload folder for user could not be created: " + tempFolder.getAbsolutePath());
                    }
                }
            }
            UploadedFile upload = event.getFile();
            saveFileTemporary(upload.getFileName(), upload.getInputstream());
        } catch (IOException e) {
            log.error("Error while uploading files", e);
        }

    }

    public void sortFiles() {
        this.uploadedFiles.sort(Comparator.comparing(MassUploadedFile::getFilename));

        Collections.sort(uploadedFiles);
    }

    /**
     * Save the uploaded file temporary in the tmp-folder inside of goobi in a subfolder for the user
     * 
     * @param fileName
     * @param in
     * @throws IOException
     */
    private void saveFileTemporary(String fileName, InputStream in) throws IOException {
        if (tempFolder == null) {
            tempFolder = new File(ConfigurationHelper.getInstance().getTemporaryFolder(), user.getLogin());
            if (!tempFolder.exists()) {
                if (!tempFolder.mkdirs()) {
                    throw new IOException("Upload folder for user could not be created: " + tempFolder.getAbsolutePath());
                }
            }
        }

        OutputStream out = null;
        try {
            File file = new File(tempFolder, fileName);
            out = new FileOutputStream(file);
            int read = 0;
            byte[] bytes = new byte[1024];
            while ((read = in.read(bytes)) != -1) {
                out.write(bytes, 0, read);
            }
            out.flush();
            MassUploadedFile muf = new MassUploadedFile(file, fileName);

            assignProcessToFile(muf, null);

            uploadedFiles.add(muf);
        } catch (IOException e) {
            log.error(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
    }

    /**
     * Cancel the entire process and delete the uploaded files
     */
    public void cleanUploadFolder() {
        for (MassUploadedFile uploadedFile : uploadedFiles) {
            uploadedFile.getFile().delete();
        }
        uploadedFiles = new ArrayList<>();

        processFilenameMap.clear();
        processTitleChecks.clear();
    }

    /**
     * All uploaded files shall now be moved to the correct processes
     */
    public void startInserting() {

        Process template = ProcessManager.getProcessByExactTitle(processTemplateName);
        Prefs prefs = template.getRegelsatz().getPreferences();
        DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
        DocStructType logicalType = prefs.getDocStrctTypeByName(metadataDocumentType);
        MetadataType pathimagefilesType = prefs.getMetadataTypeByName("pathimagefiles");
        MetadataType identifierType = prefs.getMetadataTypeByName("CatalogIDDigital");

        for (String processTitle : processFilenameMap.keySet()) {
            List<MassUploadedFile> images = processFilenameMap.get(processTitle);

            // create a new process
            Process newProcess = new Process();
            newProcess.setTitel(processTitle);
            newProcess.setIstTemplate(false);
            newProcess.setInAuswahllisteAnzeigen(false);
            newProcess.setProjekt(template.getProjekt());
            newProcess.setRegelsatz(template.getRegelsatz());
            newProcess.setDocket(template.getDocket());
            bHelper.SchritteKopieren(template, newProcess);
            bHelper.ScanvorlagenKopieren(template, newProcess);
            bHelper.WerkstueckeKopieren(template, newProcess);
            bHelper.EigenschaftenKopieren(template, newProcess);

            try {
                ProcessManager.saveProcess(newProcess);
            } catch (DAOException e) {
                log.error(e);
            }

            // copy images to process folder
            for (MassUploadedFile muf : images) {
                if (muf.getStatus() == MassUploadedFileStatus.OK) {
                    try {
                        Path src = Paths.get(muf.getFile().getAbsolutePath());
                        Path targetFolder = Paths.get(newProcess.getImagesTifDirectory(false));
                        if (!StorageProvider.getInstance().isFileExists(targetFolder)) {
                            StorageProvider.getInstance().createDirectories(targetFolder);
                        }
                        Path target = Paths.get(targetFolder.toString(), muf.getFilename());
                        StorageProvider.getInstance().copyFile(src, target);
                    } catch (IOException | InterruptedException | SwapException | DAOException e) {
                        muf.setStatus(MassUploadedFileStatus.ERROR);
                        muf.setStatusmessage("File could not be copied");
                        log.error("Error while copying file during mass upload", e);
                        Helper.setFehlerMeldung("Error while copying file during mass upload", e);
                    }
                    //                    muf.getFile().delete();
                }
            }

            // create new dummy mets file
            try {
                Fileformat mm = new MetsMods(prefs);
                DigitalDocument dd = new DigitalDocument();
                mm.setDigitalDocument(dd);

                DocStruct physical = dd.createDocStruct(physicalType);
                dd.setPhysicalDocStruct(physical);

                // imagepath
                Metadata newmd = new Metadata(pathimagefilesType);
                newmd.setValue("file://" + newProcess.getImagesTifDirectory(false));
                physical.addMetadata(newmd);

                DocStruct logical = dd.createDocStruct(logicalType);
                dd.setLogicalDocStruct(logical);

                // identifier
                Metadata identifier = new Metadata(identifierType);
                identifier.setValue(processTitle.replaceAll("\\W", "_"));
                logical.addMetadata(identifier);

                newProcess.writeMetadataFile(mm);
            } catch (UGHException | IOException | InterruptedException | SwapException | DAOException e) {
                log.error(e);
            }

            // check if automatic steps must be executed

            for (Step s : newProcess.getSchritte()) {
                if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                    ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                    myThread.start();
                }
            }
        }

        Helper.setMeldung(Helper.getTranslation("intranda_workflow_lecosProcessCreated", "" + processFilenameMap.size()));

        cleanUploadFolder();
    }

    /**
     * check for uploaded file if a correct process can be found and assigned
     * 
     * @param uploadedFile
     */
    private void assignProcessToFile(MassUploadedFile uploadedFile, Map<String, List<Process>> searchCache) {
        // get the relevant part of the file name

        String processTitle = null;
        if (perlUtil.match("/.*(BA_\\d+[_-](\\d+)).*\\.jpg/", uploadedFile.getFilename())) {
            processTitle = perlUtil.group(1);
        } else {
            uploadedFile.setStatusmessage(Helper.getTranslation("intranda_workflow_lecosWrongFilename"));
            uploadedFile.setStatus(MassUploadedFileStatus.ERROR);
        }
        // check if process already exists
        if (processTitle != null) {
            processTitle = processTitle.replace("-", "_");
            if (!processTitleChecks.containsKey(processTitle)) {
                Process p = ProcessManager.getProcessByExactTitle(processTitle);
                if (p != null) {
                    processTitleChecks.put(processTitle, true);
                } else {
                    processTitleChecks.put(processTitle, false);
                }
            }

            if (processTitleChecks.get(processTitle)) {
                uploadedFile.setStatusmessage(Helper.getTranslation("intranda_workflow_lecosProcessExists", processTitle));
                uploadedFile.setStatus(MassUploadedFileStatus.ERROR);
            } else {
                uploadedFile.setStatus(MassUploadedFileStatus.OK);
                uploadedFile.setProcessTitle(processTitle);
                Set<String> processNames = processFilenameMap.keySet();
                for (String otherTitle : new HashSet<>(processNames)) {
                    if (otherTitle.startsWith(processTitle)) {
                        // processTitle = correct value
                        List<MassUploadedFile> otherImages = processFilenameMap.get(otherTitle);

                        processFilenameMap.remove(otherTitle);
                        for (MassUploadedFile muf : otherImages) {
                            muf.setProcessTitle(processTitle);
                        }
                        if (processFilenameMap.containsKey(processTitle)) {
                            List<MassUploadedFile> processImages = processFilenameMap.get(processTitle);
                            processImages.addAll(otherImages);
                            processFilenameMap.put(processTitle, processImages);

                        } else {
                            processFilenameMap.put(processTitle, otherImages);
                        }

                    } else if (processTitle.startsWith(otherTitle)) {
                        processTitle = otherTitle;
                        uploadedFile.setProcessTitle(otherTitle);
                    }
                }

                if (processFilenameMap.containsKey(processTitle)) {
                    List<MassUploadedFile> images = processFilenameMap.get(processTitle);
                    images.add(uploadedFile);
                } else {
                    List<MassUploadedFile> images = new ArrayList<>();
                    images.add(uploadedFile);
                    processFilenameMap.put(processTitle, images);
                }
            }
        }
    }

    public boolean getShowInsertButton() {
        boolean showInsertButton =
                this.uploadedFiles.size() > 0 && this.uploadedFiles.stream().allMatch(muf -> muf.getStatus() != MassUploadedFileStatus.UNKNWON);
                return showInsertButton;
    }

    public boolean isShowInsertButton() {
        return getShowInsertButton();
    }

}
