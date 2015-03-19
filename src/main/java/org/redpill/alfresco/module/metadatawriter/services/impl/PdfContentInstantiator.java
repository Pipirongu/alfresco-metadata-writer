package org.redpill.alfresco.module.metadatawriter.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.redpill.alfresco.module.metadatawriter.services.ContentFacade;
import org.redpill.alfresco.module.metadatawriter.services.MetadataContentInstantiator;
import org.redpill.alfresco.module.metadatawriter.services.pdfbox.impl.PdfboxFacade;

public class PdfContentInstantiator implements MetadataContentInstantiator {

  @Override
  public ContentFacade create(InputStream inputStream, OutputStream outputStream) {
    return new PdfboxFacade(inputStream, outputStream);
  }

  @Override
  public ContentFacade create(ContentReader reader, ContentWriter writer) throws IOException {
    return create(reader.getContentInputStream(), writer.getContentOutputStream());
  }

  @Override
  public boolean supports(String mimetype) {
    return MimetypeMap.MIMETYPE_PDF.equalsIgnoreCase(mimetype);
  }

}
