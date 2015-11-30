/*
 * Copyright 2012-2015 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.crawler;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.codelibs.core.misc.DynamicProperties;
import org.codelibs.fess.Constants;
import org.codelibs.fess.crawler.builder.RequestDataBuilder;
import org.codelibs.fess.crawler.client.CrawlerClient;
import org.codelibs.fess.crawler.client.smb.SmbClient;
import org.codelibs.fess.crawler.entity.RequestData;
import org.codelibs.fess.crawler.entity.ResponseData;
import org.codelibs.fess.crawler.entity.UrlQueue;
import org.codelibs.fess.crawler.log.LogType;
import org.codelibs.fess.es.client.FessEsClient;
import org.codelibs.fess.es.config.exentity.CrawlingConfig;
import org.codelibs.fess.helper.CrawlingConfigHelper;
import org.codelibs.fess.helper.CrawlingInfoHelper;
import org.codelibs.fess.helper.IndexingHelper;
import org.codelibs.fess.helper.SambaHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jcifs.smb.ACE;
import jcifs.smb.SID;

public class FessCrawlerThread extends CrawlerThread {
    private static final Logger logger = LoggerFactory.getLogger(FessCrawlerThread.class);

    @Override
    protected boolean isContentUpdated(final CrawlerClient client, final UrlQueue urlQueue) {
        final DynamicProperties crawlerProperties = ComponentUtil.getCrawlerProperties();
        if (crawlerProperties.getProperty(Constants.DIFF_CRAWLING_PROPERTY, Constants.TRUE).equals(Constants.TRUE)) {

            log(logHelper, LogType.CHECK_LAST_MODIFIED, crawlerContext, urlQueue);
            final long startTime = System.currentTimeMillis();

            final FessConfig fessConfig = ComponentUtil.getFessConfig();
            final CrawlingConfigHelper crawlingConfigHelper = ComponentUtil.getCrawlingConfigHelper();
            final CrawlingInfoHelper crawlingInfoHelper = ComponentUtil.getCrawlingInfoHelper();
            final SambaHelper sambaHelper = ComponentUtil.getSambaHelper();
            final IndexingHelper indexingHelper = ComponentUtil.getIndexingHelper();
            final FessEsClient fessEsClient = ComponentUtil.getElasticsearchClient();
            final boolean useAclAsRole = crawlerProperties.getProperty(Constants.USE_ACL_AS_ROLE, Constants.FALSE).equals(Constants.TRUE);

            final String url = urlQueue.getUrl();
            ResponseData responseData = null;
            try {
                final CrawlingConfig crawlingConfig = crawlingConfigHelper.get(crawlerContext.getSessionId());
                final Map<String, Object> dataMap = new HashMap<String, Object>();
                dataMap.put(fessConfig.getIndexFieldUrl(), url);
                final List<String> roleTypeList = new ArrayList<String>();
                for (final String roleType : crawlingConfig.getRoleTypeValues()) {
                    roleTypeList.add(roleType);
                }
                if (useAclAsRole && url.startsWith("smb://")) {
                    // head method
                    responseData = client.execute(RequestDataBuilder.newRequestData().head().url(url).build());
                    if (responseData == null) {
                        return true;
                    }

                    final ACE[] aces = (ACE[]) responseData.getMetaDataMap().get(SmbClient.SMB_ACCESS_CONTROL_ENTRIES);
                    if (aces != null) {
                        for (final ACE item : aces) {
                            final SID sid = item.getSID();
                            roleTypeList.add(sambaHelper.getAccountId(sid));
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug("smbUrl:" + responseData.getUrl() + " roleType:" + roleTypeList.toString());
                        }
                    }
                }
                dataMap.put(fessConfig.getIndexFieldRole(), roleTypeList);
                final String id = crawlingInfoHelper.generateId(dataMap);

                final Map<String, Object> document =
                        indexingHelper.getDocument(
                                fessEsClient,
                                id,
                                new String[] { fessConfig.getIndexFieldId(), fessConfig.getIndexFieldLastModified(),
                                        fessConfig.getIndexFieldAnchor(), fessConfig.getIndexFieldSegment(),
                                        fessConfig.getIndexFieldExpires(), fessConfig.getIndexFieldClickCount(),
                                        fessConfig.getIndexFieldFavoriteCount() });
                if (document == null) {
                    storeChildUrlsToQueue(urlQueue, getChildUrlSet(fessEsClient, id));
                    return true;
                }

                final Date expires = (Date) document.get(fessConfig.getIndexFieldExpires());
                if (expires != null && expires.getTime() < System.currentTimeMillis()) {
                    final Object idValue = document.get(fessConfig.getIndexFieldId());
                    if (idValue != null) {
                        indexingHelper.deleteDocument(fessEsClient, idValue.toString());
                    }
                    return true;
                }

                final Date lastModified = (Date) document.get(fessConfig.getIndexFieldLastModified());
                if (lastModified == null) {
                    return true;
                }

                if (responseData == null) {
                    // head method
                    responseData = client.execute(RequestDataBuilder.newRequestData().head().url(url).build());
                    if (responseData == null) {
                        return true;
                    }
                }

                final int httpStatusCode = responseData.getHttpStatusCode();
                if (httpStatusCode == 404) {
                    storeChildUrlsToQueue(urlQueue, getAnchorSet(document.get(fessConfig.getIndexFieldAnchor())));
                    indexingHelper.deleteDocument(fessEsClient, id);
                    return false;
                } else if (responseData.getLastModified() == null) {
                    return true;
                } else if (responseData.getLastModified().getTime() <= lastModified.getTime() && httpStatusCode == 200) {

                    log(logHelper, LogType.NOT_MODIFIED, crawlerContext, urlQueue);

                    responseData.setExecutionTime(System.currentTimeMillis() - startTime);
                    responseData.setParentUrl(urlQueue.getParentUrl());
                    responseData.setSessionId(crawlerContext.getSessionId());
                    responseData.setHttpStatusCode(org.codelibs.fess.crawler.Constants.NOT_MODIFIED_STATUS);
                    processResponse(urlQueue, responseData);

                    storeChildUrlsToQueue(urlQueue, getAnchorSet(document.get(fessConfig.getIndexFieldAnchor())));

                    return false;
                }
            } finally {
                if (responseData != null) {
                    IOUtils.closeQuietly(responseData.getResponseBody());
                }
            }
        }
        return true;
    }

    protected void storeChildUrlsToQueue(final UrlQueue urlQueue, final Set<RequestData> childUrlSet) {
        if (childUrlSet != null) {
            synchronized (crawlerContext.getAccessCountLock()) {
                // add an url
                storeChildUrls(childUrlSet, urlQueue.getUrl(), urlQueue.getDepth() != null ? urlQueue.getDepth() + 1 : 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected Set<RequestData> getAnchorSet(final Object obj) {
        List<String> anchorList;
        if (obj instanceof String) {
            anchorList = new ArrayList<String>();
            anchorList.add(obj.toString());
        } else if (obj instanceof List<?>) {
            anchorList = (List<String>) obj;
        } else {
            return null;
        }

        if (anchorList.isEmpty()) {
            return null;
        }

        final Set<RequestData> childUrlSet = new LinkedHashSet<>();
        for (final String anchor : anchorList) {
            childUrlSet.add(RequestDataBuilder.newRequestData().get().url(anchor).build());
        }
        return childUrlSet;
    }

    protected Set<RequestData> getChildUrlSet(final FessEsClient fessEsClient, final String id) {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final IndexingHelper indexingHelper = ComponentUtil.getIndexingHelper();
        final List<Map<String, Object>> docList =
                indexingHelper.getChildDocumentList(fessEsClient, id, new String[] { fessConfig.getIndexFieldUrl() });
        if (docList.isEmpty()) {
            return null;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Found documents: " + docList);
        }
        final Set<RequestData> urlSet = new HashSet<>(docList.size());
        for (final Map<String, Object> doc : docList) {
            final Object obj = doc.get(fessConfig.getIndexFieldUrl());
            if (obj != null) {
                urlSet.add(RequestDataBuilder.newRequestData().get().url(obj.toString()).build());
            }
        }
        return urlSet;
    }
}
