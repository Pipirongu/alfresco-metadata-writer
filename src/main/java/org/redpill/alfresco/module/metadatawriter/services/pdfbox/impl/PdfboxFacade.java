package org.redpill.alfresco.module.metadatawriter.services.pdfbox.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Calendar;
import java.util.Date;

import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.util.TempFileProvider;
import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.redpill.alfresco.module.metadatawriter.services.ContentFacade;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;

public class PdfboxFacade implements ContentFacade {

  private final InputStream _inputStream;

  private final OutputStream _outputStream;

  private PDDocument _document;

  public PdfboxFacade(final InputStream inputStream, final OutputStream outputStream) {
    if (inputStream == null) {
      throw new ContentIOException("The input stream is null!");
    }

    try {
      _inputStream = inputStream;

      _outputStream = outputStream;

      _document = PDDocument.load(inputStream);
    } catch (final IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void writeMetadata(final String field, final Serializable value) throws ContentException {
    final PdfboxMetadata metadata = PdfboxMetadata.find(field);

    metadata.update(field, value, this);
  }

  @Override
  public void save() throws ContentException {
    PdfReader reader = null;

    PdfStamper stamper = null;

    File tempFile = null;

    try {
      // Only save changes if the document is not encrypted, otherwise the
      // document will be corrupted.
      if (!_document.isEncrypted()) {
        tempFile = TempFileProvider.createTempFile("metadatawriter_", ".pdf");

        _document.setAllSecurityToBeRemoved(true);

        _document.save(tempFile.getAbsolutePath());

        reader = new PdfReader(tempFile.getAbsolutePath());

        reader.removeUsageRights();

        stamper = new PdfStamper(reader, _outputStream);
        
        stamper.setFullCompression();
      } else {
        // Just copy the input stream to the output stream. With no changes
        // done.
        IOUtils.copyLarge(_inputStream, _outputStream);
      }
    } catch (final Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      closeQuietly(stamper);

      closeQuietly(reader);

      IOUtils.closeQuietly(_outputStream);
      
      IOUtils.closeQuietly(_inputStream);

      closeQuietly(tempFile);
    }
  }

  @Override
  public void abort() throws ContentException {
    IOUtils.closeQuietly(_inputStream);
    IOUtils.closeQuietly(_outputStream);
  }

  public void setAuthor(final String author) {
    _document.getDocumentInformation().setAuthor(Normalizer.normalize(author, Form.NFKC));
  }

  public void setTitle(final String title) {
    _document.getDocumentInformation().setTitle(Normalizer.normalize(title, Form.NFKC));
  }

  public void setKeywords(final String keywords) {
    _document.getDocumentInformation().setKeywords(Normalizer.normalize(keywords, Form.NFKC));
  }

  public void setCreateDateTime(final Date date) {
    final Calendar calendar = Calendar.getInstance();

    calendar.setTime(date);

    _document.getDocumentInformation().setCreationDate(calendar);
  }

  public void setCustomMetadata(final String field, final String value) {
    _document.getDocumentInformation().setCustomMetadataValue(field, Normalizer.normalize(value, Form.NFKC));
  }

  private void closeQuietly(PdfStamper stamper) {
    try {
      stamper.close();
    } catch (Exception ex) {
      // do nothing here
    }
  }

  private void closeQuietly(PdfReader reader) {
    try {
      reader.close();
    } catch (Exception ex) {
      // do nothing here
    }
  }

  private void closeQuietly(File tempFile) {
    try {
      tempFile.delete();
    } catch (Exception ex) {
      // do nothing here
    }
  }

}
