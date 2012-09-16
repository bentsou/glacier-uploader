package com.bentsou.glacieruploader;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.glacier.AmazonGlacierClient;
import com.amazonaws.services.glacier.TreeHashGenerator;
import com.amazonaws.services.glacier.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.glacier.model.CompleteMultipartUploadResult;
import com.amazonaws.services.glacier.model.DescribeVaultOutput;
import com.amazonaws.services.glacier.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.glacier.model.InitiateMultipartUploadResult;
import com.amazonaws.services.glacier.model.ListVaultsRequest;
import com.amazonaws.services.glacier.model.ListVaultsResult;
import com.amazonaws.services.glacier.model.UploadMultipartPartRequest;
import com.amazonaws.services.glacier.model.UploadMultipartPartResult;
import com.amazonaws.util.BinaryUtils;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.JFileChooser;
import javax.swing.SwingWorker;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.util.NbBundle.Messages;
import org.openide.windows.TopComponent;

/**
 * Top component which displays something.
 */
@ConvertAsProperties(
    dtd = "-//com.bentsou.glacieruploader//GacierUpload//EN",
autostore = false)
@TopComponent.Description(
    preferredID = "GacierUploadTopComponent",
iconBase = "com/bentsou/glacieruploader/upload.png",
persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "editor", openAtStartup = false)
@ActionID(category = "Window",
          id = "com.bentsou.glacieruploader.GacierUploadTopComponent")
@ActionReference(path = "Menu/Window" /*, position = 333 */)
@TopComponent.OpenActionRegistration(
    displayName = "#CTL_GacierUploadAction",
preferredID = "GacierUploadTopComponent")
@Messages({
    "CTL_GacierUploadAction=GacierUpload",
    "CTL_GacierUploadTopComponent=Gacier Uploader",
    "HINT_GacierUploadTopComponent=Gacier Uploader"
})
public final class GlacierUploadTopComponent extends TopComponent {
    private JFileChooser fileChooser = new JFileChooser();
    private AWSCredentials credentials;
    private AmazonGlacierClient client;

    private static String partSize = "1048576";

    public GlacierUploadTopComponent() {
        initComponents();
        setName(Bundle.CTL_GacierUploadTopComponent());
        setToolTipText(Bundle.HINT_GacierUploadTopComponent());
        
        /* Setup selectFileButton. */
        selectFileButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                int retunVal =
                    fileChooser.showOpenDialog(GlacierUploadTopComponent.this);
                if (retunVal == JFileChooser.APPROVE_OPTION) {
                    File file = fileChooser.getSelectedFile();
                    filenameLabel.setText(file.getAbsolutePath());
                    uploadButton.setEnabled(true);
                }
            }
        });
        
        /* Setup connectButton. */
        connectButton.addActionListener(new ConnectButtonListener());

        /* Setup uploadButton. */
        uploadButton.setEnabled(false);
        uploadButton.addActionListener(new UploadButtonListener());
    }

    private class ConnectButtonListener implements ActionListener {

        @Override
        public void actionPerformed(ActionEvent e) {
            credentials = new BasicAWSCredentials(
                                            accessKeyField.getText().trim(),
                                            secretKeyField.getText().trim());
            client = new AmazonGlacierClient(credentials);
            client.setEndpoint("https://glacier.us-east-1.amazonaws.com/");
            
            ListVaultsRequest listVaultReq = new ListVaultsRequest();                    
            
            try {
                ListVaultsResult result = client.listVaults(listVaultReq);
                for (DescribeVaultOutput output : result.getVaultList()) {
                    vaultComboBox.addItem(output.getVaultName());
                }
            } catch (AmazonClientException ex) {
                DialogDescriptor.Message dd = new DialogDescriptor.Message(
                        ex.getMessage(), DialogDescriptor.ERROR_MESSAGE);
                DialogDisplayer.getDefault().notifyLater(dd);
            }
        }
    }
    
    private class UploadButtonListener implements ActionListener {

        @Override
        public void actionPerformed(ActionEvent e) {

            UploadWorker worker = new UploadWorker(filenameLabel.getText());
            worker.execute();
        }
    }

    private String initiateMultipartUpload() {
        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest()
                    .withVaultName((String) vaultComboBox.getSelectedItem())
                    .withArchiveDescription(descriptionField.getText())
                    .withPartSize(partSize);
        InitiateMultipartUploadResult result =
                client.initiateMultipartUpload(request);
        return result.getUploadId();
    }

    private String CompleteMultiPartUpload(String uploadId, String checksum) {
        File file = new File(filenameLabel.getText());

        CompleteMultipartUploadRequest compRequest =
                new CompleteMultipartUploadRequest()
                    .withVaultName((String) vaultComboBox.getSelectedItem())
                    .withUploadId(uploadId)
                    .withChecksum(checksum)
                .withArchiveSize(String.valueOf(file.length()));
        
        CompleteMultipartUploadResult compResult =
                client.completeMultipartUpload(compRequest);
        
        return compResult.getLocation();
    }
    
    private class UploadWorker extends SwingWorker<String, String> {
        private ProgressHandle progress;
        private SimpleDateFormat df;
        private String absolutePath;
        
        
        public UploadWorker(String absolutePath) {
            this.absolutePath = absolutePath;
            
            df = (SimpleDateFormat) SimpleDateFormat.getInstance();
            df.applyPattern("yyyy-MM-dd H:m:s:S");
            outputArea.append("Start uploading...\n"); 
            
            progress = ProgressHandleFactory.createHandle("Uploading");
            progress.start();
        }

        @Override
        protected String doInBackground() throws Exception {
            String uploadId = initiateMultipartUpload();
            uploadIdField.setText(uploadId);
            
            int filePosition = 0;
            long currentPosition = 0;
            byte[] buffer = new byte[Integer.valueOf(partSize)];
            List<byte[]> binaryChecksums = new LinkedList<>();

            Path path = Paths.get(absolutePath);
            
            InputStream fileToUpload2 = Files.newInputStream(path); 
            String contentRange;
            int read = 0;

            while (currentPosition < Files.size(path)) {
                read = fileToUpload2.read(buffer, filePosition, buffer.length);
                if (read == -1) {
                    break;
                }
                byte[] bytesRead = Arrays.copyOf(buffer,read);

                contentRange = String.format("bytes %s-%s/*",
                                    currentPosition, currentPosition + read - 1);
                String checksum = TreeHashGenerator.calculateTreeHash(
                                        new ByteArrayInputStream(bytesRead));
                byte[] binaryChecksum = BinaryUtils.fromHex(checksum);
                binaryChecksums.add(binaryChecksum);

                UploadMultipartPartRequest partRequest =
                        new UploadMultipartPartRequest()
                            .withVaultName((String) vaultComboBox.getSelectedItem())
                            .withBody(new ByteArrayInputStream(bytesRead))
                            .withChecksum(checksum)
                            .withRange(contentRange)
                            .withUploadId(uploadId);
                try {
                    UploadMultipartPartResult partResult =
                            client.uploadMultipartPart(partRequest);
                    publish("Part uploaded, checksum: " + 
                            partResult.getChecksum() + "\n");                    

                } catch (AmazonServiceException ex) {
                    DialogDescriptor.Message dd = new DialogDescriptor.Message(
                            ex.getMessage(), DialogDescriptor.ERROR_MESSAGE);
                    DialogDisplayer.getDefault().notifyLater(dd);                                       
                }


                currentPosition = currentPosition + read;
            }

            String checksum = TreeHashGenerator.calculateTreeHash(binaryChecksums);
            String location = CompleteMultiPartUpload(uploadId, checksum);
            publish("Location: " +location + "\n");
            publish ("done");
            return checksum;
        }

        @Override
        protected void process(List<String> chunks) {
            Date currentDate = new Date();
            for (String chunk : chunks) {
                outputArea.append("[" + df.format(currentDate) + "] " + chunk);                
            }
        }

        @Override
        protected void done() {
            progress.finish();
            try {
                String checksum = get();
                outputArea.append("Final checksum: " + checksum);                
            } catch (InterruptedException | ExecutionException e) {
                DialogDescriptor.Message dd = new DialogDescriptor.Message(
                        e.getMessage(), DialogDescriptor.ERROR_MESSAGE);
                DialogDisplayer.getDefault().notifyLater(dd);                       
            }
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        selectFileButton = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jSeparator1 = new javax.swing.JSeparator();
        jLabel2 = new javax.swing.JLabel();
        accessKeyField = new javax.swing.JTextField();
        jLabel3 = new javax.swing.JLabel();
        secretKeyField = new javax.swing.JTextField();
        jLabel4 = new javax.swing.JLabel();
        jSeparator2 = new javax.swing.JSeparator();
        filenameLabel = new javax.swing.JLabel();
        uploadButton = new javax.swing.JButton();
        jLabel5 = new javax.swing.JLabel();
        connectButton = new javax.swing.JButton();
        vaultComboBox = new javax.swing.JComboBox();
        jLabel6 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        descriptionField = new javax.swing.JTextField();
        jLabel8 = new javax.swing.JLabel();
        jSeparator3 = new javax.swing.JSeparator();
        jLabel9 = new javax.swing.JLabel();
        uploadIdField = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        outputArea = new javax.swing.JTextArea();
        jLabel10 = new javax.swing.JLabel();

        selectFileButton.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        org.openide.awt.Mnemonics.setLocalizedText(selectFileButton, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.selectFileButton.text")); // NOI18N

        jLabel1.setFont(new java.awt.Font("Arial", 1, 16)); // NOI18N
        jLabel1.setForeground(new java.awt.Color(102, 102, 102));
        org.openide.awt.Mnemonics.setLocalizedText(jLabel1, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel1.text")); // NOI18N

        jLabel2.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel2.setForeground(new java.awt.Color(102, 102, 102));
        jLabel2.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel2, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel2.text")); // NOI18N

        accessKeyField.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        accessKeyField.setText(org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.accessKeyField.text")); // NOI18N

        jLabel3.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel3.setForeground(new java.awt.Color(102, 102, 102));
        jLabel3.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel3, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel3.text")); // NOI18N

        secretKeyField.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        secretKeyField.setText(org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.secretKeyField.text")); // NOI18N

        jLabel4.setFont(new java.awt.Font("Arial", 1, 16)); // NOI18N
        jLabel4.setForeground(new java.awt.Color(102, 102, 102));
        org.openide.awt.Mnemonics.setLocalizedText(jLabel4, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel4.text")); // NOI18N

        filenameLabel.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        filenameLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(filenameLabel, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.filenameLabel.text")); // NOI18N

        uploadButton.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        org.openide.awt.Mnemonics.setLocalizedText(uploadButton, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.uploadButton.text")); // NOI18N

        jLabel5.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel5.setForeground(new java.awt.Color(102, 102, 102));
        jLabel5.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel5, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel5.text")); // NOI18N

        connectButton.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        org.openide.awt.Mnemonics.setLocalizedText(connectButton, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.connectButton.text")); // NOI18N

        vaultComboBox.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N

        jLabel6.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel6.setForeground(new java.awt.Color(102, 102, 102));
        jLabel6.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel6, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel6.text")); // NOI18N

        jLabel7.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel7.setForeground(new java.awt.Color(102, 102, 102));
        jLabel7.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel7, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel7.text")); // NOI18N

        descriptionField.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        descriptionField.setText(org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.descriptionField.text")); // NOI18N

        jLabel8.setFont(new java.awt.Font("Arial", 1, 16)); // NOI18N
        jLabel8.setForeground(new java.awt.Color(102, 102, 102));
        org.openide.awt.Mnemonics.setLocalizedText(jLabel8, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel8.text")); // NOI18N

        jLabel9.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel9.setForeground(new java.awt.Color(102, 102, 102));
        jLabel9.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel9, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel9.text")); // NOI18N

        uploadIdField.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        uploadIdField.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(uploadIdField, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.uploadIdField.text")); // NOI18N

        outputArea.setColumns(20);
        outputArea.setFont(new java.awt.Font("Arial", 0, 11)); // NOI18N
        outputArea.setRows(5);
        jScrollPane1.setViewportView(outputArea);

        jLabel10.setFont(new java.awt.Font("Arial", 1, 12)); // NOI18N
        jLabel10.setForeground(new java.awt.Color(102, 102, 102));
        jLabel10.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        org.openide.awt.Mnemonics.setLocalizedText(jLabel10, org.openide.util.NbBundle.getMessage(GlacierUploadTopComponent.class, "GlacierUploadTopComponent.jLabel10.text")); // NOI18N

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jSeparator3)
                    .addComponent(jSeparator1)
                    .addComponent(jSeparator2)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel1)
                            .addComponent(jLabel4)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                    .addComponent(jLabel5, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(jLabel6, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(jLabel7, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(descriptionField, javax.swing.GroupLayout.PREFERRED_SIZE, 354, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(filenameLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(selectFileButton))
                                    .addComponent(vaultComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, 354, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(uploadButton)))
                            .addComponent(jLabel8)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(jLabel9)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(uploadIdField))
                            .addComponent(jLabel10)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                    .addComponent(jLabel3, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(jLabel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(connectButton)
                                    .addComponent(accessKeyField, javax.swing.GroupLayout.PREFERRED_SIZE, 400, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(secretKeyField, javax.swing.GroupLayout.PREFERRED_SIZE, 400, javax.swing.GroupLayout.PREFERRED_SIZE))))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel1)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator1, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(accessKeyField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel3)
                    .addComponent(secretKeyField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(connectButton)
                .addGap(18, 18, 18)
                .addComponent(jLabel4)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator2, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(vaultComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel6)
                    .addComponent(filenameLabel)
                    .addComponent(selectFileButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel7)
                    .addComponent(descriptionField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(uploadButton)
                .addGap(18, 18, 18)
                .addComponent(jLabel8)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator3, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel9)
                    .addComponent(uploadIdField))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel10)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 158, Short.MAX_VALUE)
                .addContainerGap())
        );
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTextField accessKeyField;
    private javax.swing.JButton connectButton;
    private javax.swing.JTextField descriptionField;
    private javax.swing.JLabel filenameLabel;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JSeparator jSeparator1;
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JTextArea outputArea;
    private javax.swing.JTextField secretKeyField;
    private javax.swing.JButton selectFileButton;
    private javax.swing.JButton uploadButton;
    private javax.swing.JLabel uploadIdField;
    private javax.swing.JComboBox vaultComboBox;
    // End of variables declaration//GEN-END:variables
    @Override
    public void componentOpened() {
        // TODO add custom code on component opening
    }

    @Override
    public void componentClosed() {
        // TODO add custom code on component closing
    }

    void writeProperties(java.util.Properties p) {
        // better to version settings since initial version as advocated at
        // http://wiki.apidesign.org/wiki/PropertyFiles
        p.setProperty("version", "1.0");
        // TODO store your settings
    }

    void readProperties(java.util.Properties p) {
        String version = p.getProperty("version");
        // TODO read your settings according to their version
    }
}
