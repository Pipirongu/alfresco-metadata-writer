package org.redpill.alfresco.module.metadatawriter.aspect.impl;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.node.NodeServicePolicies.OnAddAspectPolicy;
import org.alfresco.repo.node.NodeServicePolicies.OnUpdatePropertiesPolicy;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.TransactionListener;
import org.alfresco.repo.transaction.TransactionListenerAdapter;
import org.alfresco.repo.version.VersionServicePolicies.AfterCreateVersionPolicy;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.lock.LockStatus;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.redpill.alfresco.module.metadatawriter.factories.MetadataServiceRegistry;
import org.redpill.alfresco.module.metadatawriter.factories.UnknownServiceNameException;
import org.redpill.alfresco.module.metadatawriter.model.MetadataWriterModel;
import org.redpill.alfresco.module.metadatawriter.services.MetadataService;
import org.redpill.alfresco.module.metadatawriter.services.MetadataService.UpdateMetadataException;
import org.springframework.beans.factory.InitializingBean;

public class ExportMetadataAspect implements AfterCreateVersionPolicy, OnUpdatePropertiesPolicy, OnAddAspectPolicy, InitializingBean {

  private static final Log LOG = LogFactory.getLog(ExportMetadataAspect.class);

  private PolicyComponent _policyComponent;

  private NodeService _nodeService;

  private DictionaryService _dictionaryService;

  private LockService _lockService;

  private MetadataServiceRegistry _metadataServiceRegistry;

  private ThreadPoolExecutor _threadPoolExecutor;

  private TransactionListener _transactionListener;

  private TransactionService _transactionService;

  private BehaviourFilter _behaviourFilter;

  private static final Object KEY_NODE_REF = ExportMetadataAspect.class.getName() + ".nodeRef";

  public void setThreadPoolExecutor(ThreadPoolExecutor threadPoolExecutor) {
    _threadPoolExecutor = threadPoolExecutor;
  }

  public void setTransactionService(TransactionService transactionService) {
    _transactionService = transactionService;
  }

  public void setPolicyComponent(PolicyComponent policyComponent) {
    _policyComponent = policyComponent;
  }

  public void setNodeService(NodeService nodeService) {
    _nodeService = nodeService;
  }

  public void setDictionaryService(DictionaryService dictionaryService) {
    _dictionaryService = dictionaryService;
  }

  public void setLockService(LockService lockService) {
    _lockService = lockService;
  }

  public void setMetadataServiceRegistry(MetadataServiceRegistry metadataServiceRegistry) {
    _metadataServiceRegistry = metadataServiceRegistry;
  }

  public void setBehaviourFilter(BehaviourFilter behaviourFilter) {
    _behaviourFilter = behaviourFilter;
  }

  @Override
  public void onUpdateProperties(final NodeRef nodeRef, final Map<QName, Serializable> before, final Map<QName, Serializable> after) {
    if (!_nodeService.exists(nodeRef)) {
      return;
    }

    if (_lockService.getLockStatus(nodeRef) != LockStatus.NO_LOCK) {
      return;
    }

    verifyMetadataExportableNode(nodeRef, MetadataWriterModel.ASPECT_METADATA_WRITEABLE);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Properties updated for node " + nodeRef);
    }

    // Only update properties if before and after differ
    if(propertiesUpdatedRequireExport(before, after)) {
    	updateProperties(nodeRef);    	
    }
    else {
    	if (LOG.isDebugEnabled()) {
    	      LOG.debug("Property updates did not require metadata export for node " + nodeRef);
    	}
    }
  }

  /**
   * Ensures that metadata only exported when it has to
   * @param before
   * @param after
   * @return
   */
  //TODO: Should add black list of properties ignored in comparison to avoid unnecessary updates
  private boolean propertiesUpdatedRequireExport(
		  final Map<QName, Serializable> before,
		  final Map<QName, Serializable> after) {
	  
	  return !before.equals(after);
	  
  }

/**
   * After create version is needed to catch the "new" version label set.
   */
  @Override
  public void afterCreateVersion(final NodeRef versionableNode, final Version version) {
    if (!_nodeService.exists(versionableNode)) {
      return;
    }

    if (_lockService.getLockStatus(versionableNode) != LockStatus.NO_LOCK) {
      return;
    }

    verifyMetadataExportableNode(versionableNode, MetadataWriterModel.ASPECT_METADATA_WRITEABLE);

    if (LOG.isDebugEnabled()) {
      LOG.debug("After create version for node " + versionableNode);
    }

    updateProperties(versionableNode);
    
  }

  @Override
  public void onAddAspect(final NodeRef nodeRef, final QName aspectTypeQName) {
    if (!_nodeService.exists(nodeRef)) {
      return;
    }

    if (_lockService.getLockStatus(nodeRef) != LockStatus.NO_LOCK) {
      return;
    }

    verifyMetadataExportableNode(nodeRef, MetadataWriterModel.ASPECT_METADATA_WRITEABLE);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Aspect updated for node " + nodeRef);
    }
    
    updateProperties(nodeRef);
  }

  // ---------------------------------------------------
  // Private methods
  // ---------------------------------------------------
  private void verifyMetadataExportableNode(final NodeRef node, final QName aspectQName) {
    assert null != node : "Provided node is null!";
    assert _nodeService.hasAspect(node, aspectQName) : "Node " + node + " does not have mandatory aspect " + aspectQName;
  }

  private void updateProperties(final NodeRef node) {
    AlfrescoTransactionSupport.bindListener(_transactionListener);

    AlfrescoTransactionSupport.bindResource(KEY_NODE_REF, node);
  }

  private void doUpdateProperties(final NodeRef node) {
    final String serviceName = (String) _nodeService.getProperty(node, MetadataWriterModel.PROP_METADATA_SERVICE_NAME);

    final Serializable failOnUnsupportedValue = _nodeService.getProperty(node, MetadataWriterModel.PROP_METADATA_FAIL_ON_UNSUPPORTED);

    boolean failOnUnsupported = true;

    if (failOnUnsupportedValue != null) {
      failOnUnsupported = (Boolean) failOnUnsupportedValue;
    }

    if (_lockService.getLockStatus(node) != LockStatus.NO_LOCK) {
      return;
    }

    if (_dictionaryService.isSubClass(_nodeService.getType(node), ContentModel.TYPE_FOLDER)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Metadata could not be exported for folder-nodes!");
      }
    } else if (null != serviceName) {
      try {
        final MetadataService s = _metadataServiceRegistry.findService(serviceName);

        _behaviourFilter.disableAllBehaviours();

        s.write(node, _nodeService.getProperties(node));
      } catch (final UnknownServiceNameException e) {
        LOG.warn("Could not find Metadata service named " + serviceName, e);
      } catch (final UpdateMetadataException ume) {
        if (failOnUnsupported) {
          throw new AlfrescoRuntimeException("Could not write properties " + _nodeService.getProperties(node) + " to node "
                  + _nodeService.getProperty(node, ContentModel.PROP_NAME), ume);
        } else {
          LOG.error("Could not write properties " + _nodeService.getProperties(node) + " to node " + _nodeService.getProperty(node, ContentModel.PROP_NAME), ume);
        }
      } catch (final Exception ex) {
        // catch the general error and log it
        LOG.error("Could not write properties " + _nodeService.getProperties(node) + " to node " + _nodeService.getProperty(node, ContentModel.PROP_NAME), ex);
      }
    } else {
      LOG.info("No Metadata service specified for node " + node);
    }
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    _policyComponent.bindClassBehaviour(OnUpdatePropertiesPolicy.QNAME, MetadataWriterModel.ASPECT_METADATA_WRITEABLE, new JavaBehaviour(this,
            "onUpdateProperties", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

    _policyComponent.bindClassBehaviour(OnAddAspectPolicy.QNAME, MetadataWriterModel.ASPECT_METADATA_WRITEABLE, new JavaBehaviour(this,
            "onAddAspect", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

    // set up the transaction listener
    _transactionListener = new MetadataWriterTransactionListener();
  }

  private class MetadataWriterTransactionListener extends TransactionListenerAdapter {

    @Override
    public void afterCommit() {
      NodeRef nodeRef = AlfrescoTransactionSupport.getResource(KEY_NODE_REF);

      //Map<QName, Serializable> properties = AlfrescoTransactionSupport.getResource(KEY_PROPERTIES);

      Runnable runnable = new MetadataWriterUpdater(nodeRef);

      _threadPoolExecutor.execute(runnable);
    }
  }

  private class MetadataWriterUpdater implements Runnable {

    private NodeRef _nodeRef;

    public MetadataWriterUpdater(NodeRef nodeRef) {
      _nodeRef = nodeRef;
    }

    @Override
    public void run() {
      AuthenticationUtil.runAs(new AuthenticationUtil.RunAsWork<String>() {

        public String doWork() throws Exception {
          RetryingTransactionHelper.RetryingTransactionCallback<Void> callback = new RetryingTransactionHelper.RetryingTransactionCallback<Void>() {

            public Void execute() throws Throwable {
              doUpdateProperties(_nodeRef);

              return null;
            }

          };

          try {
            RetryingTransactionHelper txnHelper = _transactionService.getRetryingTransactionHelper();

            txnHelper.doInTransaction(callback, false, true);

            if (LOG.isDebugEnabled()) {
              LOG.debug("Successfully wrote metadata properties on node: " + _nodeRef);
            }
          } catch (Exception ex) {
            LOG.error("Failed to write metadata properties to node: " + _nodeRef, ex);
          }

          return "";
        }

      }, AuthenticationUtil.getSystemUserName());
    }
  }

}

