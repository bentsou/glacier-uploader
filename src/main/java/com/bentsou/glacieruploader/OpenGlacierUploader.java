
package com.bentsou.glacieruploader;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle.Messages;

@ActionID(category = "Tools",
          id = "com.bentsou.glacieruploader.OpenGlacierUploader")
@ActionRegistration(iconBase = "com/bentsou/glacieruploader/upload.png",
                    displayName = "#CTL_OpenGlacierUploader")
@ActionReferences({@ActionReference(path = "Toolbars/Extra", position = 100)})
@Messages("CTL_OpenGlacierUploader=Glacier Uploader")
public final class OpenGlacierUploader implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        GlacierUploadTopComponent top = new GlacierUploadTopComponent();
        top.open();
        top.requestActive();
    }
}
