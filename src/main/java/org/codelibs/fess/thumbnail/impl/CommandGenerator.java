/*
 * Copyright 2012-2017 CodeLibs Project and the Others.
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
package org.codelibs.fess.thumbnail.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.ServletContext;

import org.apache.commons.io.IOUtils;
import org.codelibs.core.io.CopyUtil;
import org.codelibs.core.lang.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandGenerator extends BaseThumbnailGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CommandGenerator.class);

    @Resource
    protected ServletContext application;

    protected List<String> commandList;

    protected long commandTimeout = 30 * 1000L;// 30sec

    protected File baseDir;

    private volatile Timer destoryTimer;

    @PostConstruct
    public void init() {
        if (baseDir == null) {
            baseDir = new File(application.getRealPath("/"));
        }
        destoryTimer = new Timer("CommandGeneratorDestoryTimer-" + System.currentTimeMillis(), true);
    }

    @Override
    public void destroy() {
        destoryTimer.cancel();
        destoryTimer = null;
    }

    @Override
    public boolean generate(final String thumbnailId, final File outputFile) {
        if (logger.isDebugEnabled()) {
            logger.debug("Generate Thumbnail: " + thumbnailId);
        }

        if (outputFile.exists()) {
            if (logger.isDebugEnabled()) {
                logger.debug("The thumbnail file exists: " + outputFile.getAbsolutePath());
            }
            return true;
        }

        final File parentFile = outputFile.getParentFile();
        if (!parentFile.exists()) {
            parentFile.mkdirs();
        }
        if (!parentFile.isDirectory()) {
            logger.warn("Not found: " + parentFile.getAbsolutePath());
            return false;
        }

        return process(thumbnailId, responseData -> {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("thumbnail_", "");
                CopyUtil.copy(responseData.getResponseBody(), tempFile);

                final String tempPath = tempFile.getAbsolutePath();
                final String outputPath = outputFile.getAbsolutePath();
                final List<String> cmdList = new ArrayList<>();
                for (final String value : commandList) {
                    cmdList.add(expandPath(value.replace("${url}", tempPath).replace("${outputFile}", outputPath)));
                }

                executeCommand(thumbnailId, cmdList);

                if (outputFile.isFile() && outputFile.length() == 0) {
                    logger.warn("Thumbnail File is empty. ID is " + thumbnailId);
                    if (outputFile.delete()) {
                        logger.info("Deleted: " + outputFile.getAbsolutePath());
                    }
                    updateThumbnailField(thumbnailId, StringUtil.EMPTY);
                    return false;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Thumbnail File: " + outputPath);
                }
                return true;
            } catch (final Exception e) {
                logger.warn("Failed to process ", e);
                if (tempFile != null && !tempFile.delete()) {
                    logger.debug("Failed to delete " + tempFile.getAbsolutePath());
                }
                updateThumbnailField(thumbnailId, StringUtil.EMPTY);
                return false;
            }
        });

    }

    protected void executeCommand(final String thumbnailId, final List<String> cmdList) {
        ProcessBuilder pb = null;
        Process p = null;

        if (logger.isDebugEnabled()) {
            logger.debug("Thumbnail Command: " + cmdList);
        }

        TimerTask task = null;
        try {
            pb = new ProcessBuilder(cmdList);
            pb.directory(baseDir);
            pb.redirectErrorStream(true);

            p = pb.start();

            task = new ProcessDestroyer(p, cmdList);
            try {
                destoryTimer.schedule(task, commandTimeout);

                String line;
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
                    while ((line = br.readLine()) != null) {
                        if (logger.isDebugEnabled()) {
                            logger.debug(line);
                        }
                    }
                } finally {
                    IOUtils.closeQuietly(br);
                }

                p.waitFor();
            } catch (final Exception e) {
                p.destroy();
            }
        } catch (final Exception e) {
            logger.warn("Failed to generate a thumbnail of " + thumbnailId, e);
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    protected static class ProcessDestroyer extends TimerTask {

        private final Process p;

        private final List<String> commandList;

        protected ProcessDestroyer(final Process p, final List<String> commandList) {
            this.p = p;
            this.commandList = commandList;
        }

        @Override
        public void run() {
            logger.warn("CommandGenerator is timed out: " + commandList);
            try {
                p.destroy();
            } catch (final Exception e) {
                logger.warn("Failed to stop destroyer.", e);
            }
        }
    }

    public void setCommandList(final List<String> commandList) {
        this.commandList = commandList;
    }

    public void setCommandTimeout(final long commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    public void setBaseDir(final File baseDir) {
        this.baseDir = baseDir;
    }

}
