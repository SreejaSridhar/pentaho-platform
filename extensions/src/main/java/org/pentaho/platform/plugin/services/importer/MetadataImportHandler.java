/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.plugin.services.importer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.repository.DomainAlreadyExistsException;
import org.pentaho.metadata.repository.DomainIdNullException;
import org.pentaho.metadata.repository.DomainStorageException;
import org.pentaho.metadata.util.XmiParser;
import org.pentaho.platform.api.mimetype.IMimeType;
import org.pentaho.platform.api.repository2.unified.IPlatformImportBundle;
import org.pentaho.platform.plugin.services.importexport.PentahoMetadataFileInfo;
import org.pentaho.platform.plugin.services.metadata.IAclAwarePentahoMetadataDomainRepositoryImporter;
import org.pentaho.platform.plugin.services.metadata.IModelAnnotationsAwareMetadataDomainRepositoryImporter;
import org.pentaho.platform.plugin.services.metadata.IPentahoMetadataDomainRepositoryImporter;
import org.pentaho.platform.repository.RepositoryFilenameUtils;
import org.pentaho.platform.repository.messages.Messages;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * Class Description
 *
 * @author <a href="mailto:dkincade@pentaho.com">David M. Kincade</a>, nbaker
 */
public class MetadataImportHandler implements IPlatformImportHandler {
  private static final Log log = LogFactory.getLog( MetadataImportHandler.class );

  private static final Messages messages = Messages.getInstance();

  // The name of the property used to determine if metadata source was a DSW
  private static final String DSW_SOURCE_PROPERTY = "AGILE_BI_GENERATED_SCHEMA";
  private static final String AGILE_BI_VERSION_PROPERTY = "AGILE_BI_VERSION";
  private static final String WIZARD_GENERATED_PROPERTY = "WIZARD_GENERATED_SCHEMA";

  private List<IMimeType> mimeTypes;

  IPentahoMetadataDomainRepositoryImporter metadataRepositoryImporter;

  public MetadataImportHandler( List<IMimeType> mimeTypes,
                                final IPentahoMetadataDomainRepositoryImporter metadataImporter ) {
    this.mimeTypes = mimeTypes;
    if ( metadataImporter == null ) {
      throw new IllegalArgumentException();
    }
    this.metadataRepositoryImporter = metadataImporter;
  }

  @Override
  public void importFile( IPlatformImportBundle file ) throws PlatformImportException {
    String domainId = processMetadataFile( file );
    // bundle may have language files supplied with it.
    if ( file.getChildBundles() != null ) {
      for ( IPlatformImportBundle child : file.getChildBundles() ) {
        processLocaleFile( child, domainId );
      }
    }

  }

  /**
   * Processes the file as a metadata file and returns the domain name. It will import the file into the Pentaho
   * Metadata Domain Repository.
   *
   * @param bundle
   * @return
   */
  protected String processMetadataFile( final IPlatformImportBundle bundle ) throws PlatformImportException {
    final String domainId = (String) bundle.getProperty( "domain-id" );

    if ( domainId == null ) {
      throw new PlatformImportException( "Bundle missing required domain-id property" );
    }
    try {
      log.debug( "Importing as metadata - [domain=" + domainId + "]" );
      final InputStream inputStream;
      if ( bundle.isPreserveDsw() ) {
        // storeDomain needs to be able to close the stream
        inputStream = cloneStream( bundle.getInputStream() );
      } else {
        inputStream = StripDswFromStream( bundle.getInputStream() );
      }

      if ( metadataRepositoryImporter instanceof IAclAwarePentahoMetadataDomainRepositoryImporter ) {
        IAclAwarePentahoMetadataDomainRepositoryImporter importer =
          (IAclAwarePentahoMetadataDomainRepositoryImporter) metadataRepositoryImporter;

        importer.storeDomain( inputStream, domainId, bundle.overwriteInRepository(),
          bundle.isApplyAclSettings() ? bundle.getAcl() : null );
      } else {
        metadataRepositoryImporter.storeDomain( inputStream, domainId, bundle.overwriteInRepository() );
      }

      if ( metadataRepositoryImporter instanceof IModelAnnotationsAwareMetadataDomainRepositoryImporter ) {
        // Store annotations xml with the domain if it exists
        final String annotationsXml =
            (String) bundle.getProperty(
                IModelAnnotationsAwareMetadataDomainRepositoryImporter.PROPERTY_NAME_ANNOTATIONS );
        if ( StringUtils.isNotBlank( annotationsXml ) ) {
          // Save annotations
          IModelAnnotationsAwareMetadataDomainRepositoryImporter
              importer =
              (IModelAnnotationsAwareMetadataDomainRepositoryImporter) metadataRepositoryImporter;
          importer.storeAnnotationsXml( domainId, annotationsXml );
        }
      }

      return domainId;
    } catch ( DomainIdNullException dine ) {
      throw new PlatformImportException( dine.getMessage(), PlatformImportException.PUBLISH_TO_SERVER_FAILED, dine );
    } catch ( DomainStorageException dse ) {
      throw new PlatformImportException( dse.getMessage(), PlatformImportException.PUBLISH_TO_SERVER_FAILED, dse );
    } catch ( DomainAlreadyExistsException daee ) {
      throw new PlatformImportException( messages
          .getString( "PentahoPlatformImporter.ERROR_0007_PUBLISH_SCHEMA_EXISTS_ERROR" ),
          PlatformImportException.PUBLISH_SCHEMA_EXISTS_ERROR, daee
      );
    } catch ( Exception e ) {
      final String errorMessage =
          messages.getErrorString( "MetadataImportHandler.ERROR_0001_IMPORTING_METADATA", domainId, e
              .getLocalizedMessage() );
      log.error( errorMessage, e );
      throw new PlatformImportException( errorMessage, e );
    }
  }

  private InputStream cloneStream( InputStream inputStream ) throws Exception {
    byte[] contents = IOUtils.toByteArray( inputStream );
    return new ByteArrayInputStream( contents );
  }

  InputStream StripDswFromStream( InputStream inputStream ) throws Exception {
    // Check if this is valid xml
    InputStream inputStream2 = null;
    String xmi = null;
    XmiParser xmiParser = new XmiParser();
    try {
      byte[] is = IOUtils.toByteArray( inputStream );
      xmi = new String( is, "UTF-8" );

      // now, try to see if the xmi can be parsed (ie, check if it's valid xmi)
      Domain domain = xmiParser.parseXmi( new java.io.ByteArrayInputStream( is ) );

      boolean changed = false;
      Iterator<LogicalModel> iterator = domain.getLogicalModels().iterator();
      while ( iterator.hasNext() ) {
        LogicalModel logicalModel = iterator.next();
        Object property = logicalModel.getProperty( DSW_SOURCE_PROPERTY ); //$NON-NLS-1$
        if ( property != null ) {
          // This metadata file came from a DataSourceWizard, it may have embedded mondrian schema
          // that would incorrectly inform the system that there is mondrian schema attached. By
          // definition we only want to import the metadata portion.
          if ( logicalModel.getProperty( LogicalModel.PROPERTY_OLAP_DIMS ) != null ) {
            // This logical model is an Olap model that needs to be removed from metadata
            iterator.remove();
          } else {
            // Remove properties that make this a DSW
            logicalModel.removeChildProperty( DSW_SOURCE_PROPERTY );
            logicalModel.removeChildProperty( AGILE_BI_VERSION_PROPERTY );
            logicalModel.removeChildProperty( WIZARD_GENERATED_PROPERTY );
          }
          changed = true;
        }
      }
      if ( changed ) {
        // The model was modified, regenerate the xml
        xmi = xmiParser.generateXmi( domain );
      }

      // xmi is valid. Create a new inputstream for the actual import action.
      inputStream2 = new java.io.ByteArrayInputStream( xmi.getBytes( "UTF-8" ) );
    } catch ( Exception e ) {
      throw new PlatformImportException( e.getMessage(), PlatformImportException.PUBLISH_TO_SERVER_FAILED, e );
    }

    return inputStream2;
  }

  private void processLocaleFile( final IPlatformImportBundle bundle, String domainId ) throws PlatformImportException {
    final String fullFilename = RepositoryFilenameUtils.concat( "/", bundle.getName() );
    final PentahoMetadataFileInfo info = new PentahoMetadataFileInfo( fullFilename );

    if ( domainId == null ) {
      // try to resolve domainId from bundle
      domainId = (String) bundle.getProperty( "domain-id" );

    }
    if ( domainId == null ) {
      throw new PlatformImportException( "Bundle missing required domain-id property" );
    }
    try {
      log.debug( "Importing [" + info.getPath() + "] as properties - [domain=" + domainId + " : locale="
          + info.getLocale() + "]" );
      metadataRepositoryImporter.addLocalizationFile( domainId, info.getLocale(), bundle.getInputStream(), true );

    } catch ( Exception e ) {
      final String errorMessage =
          messages.getErrorString( "MetadataImportHandler.ERROR_0002_IMPORTING_LOCALE_FILE", info.getPath(), domainId,
              info.getLocale(), e.getLocalizedMessage() );
      log.error( errorMessage, e );
      throw new PlatformImportException( errorMessage, e );
    }
  }

  @Override
  public List<IMimeType> getMimeTypes() {
    return mimeTypes;
  }

}
