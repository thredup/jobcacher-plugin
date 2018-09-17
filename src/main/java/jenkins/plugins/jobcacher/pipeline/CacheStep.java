/*
 * The MIT License
 *
 * Copyright 2016 Peter Hayes.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.plugins.jobcacher.pipeline;

import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import jenkins.plugins.itemstorage.GlobalItemStorage;
import jenkins.plugins.jobcacher.Cache;
import jenkins.plugins.jobcacher.CacheDescriptor;
import jenkins.plugins.jobcacher.CacheManager;
import jenkins.plugins.jobcacher.Messages;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wrapping workflow step that automatically seeds the specified path with the previous run and on exit of the
 * block, saves that cache to the configured item storage.
 */
@SuppressWarnings("unused")
public class CacheStep extends AbstractStepImpl {
    private long maxCacheSize = 0L;
    private List<Cache> caches = new ArrayList<>();

    @DataBoundConstructor
    public CacheStep(long maxCacheSize, List<Cache> caches) {
        this.maxCacheSize = maxCacheSize;
        this.caches = caches;
    }

    @SuppressWarnings("unused")
    public long getMaxCacheSize() {
        return maxCacheSize;
    }

    public List<Cache> getCaches() {
        return caches;
    }

    public static class ExecutionImpl extends AbstractStepExecutionImpl {

        private static final long serialVersionUID = 1L;

        private static final Logger logger = Logger.getLogger(ExecutionImpl.class.getName());

        @Inject(optional = true)
        private transient CacheStep cacheStep = null;

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();

            Run run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars initialEnvironment = context.get(EnvVars.class);

            listener.getLogger().println("Going to download cache...");
            logger.info(">>> Staring cache download");
            logger.info(">>> " + cacheStep.caches.size() + " caches to populate");

            List<Cache.Saver> cacheSavers = CacheManager.cache(GlobalItemStorage.get().getStorage(), run, workspace, launcher, listener, initialEnvironment, cacheStep.caches);

            logger.info(">>> Cache download competed");
            listener.getLogger().println("Cache downloaded");

            context.newBodyInvoker().
                    withContext(context).
                    withCallback(new ExecutionCallback(cacheStep.maxCacheSize, cacheStep.caches, cacheSavers)).
                    start();

            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stop(@Nonnull Throwable cause) throws Exception {
            // If someone canceled the run, just propagate the failure and do not attempt to cache
            logger.info(">>> Force stop");

            getContext().onFailure(cause);
        }
    }

    public static class ExecutionCallback extends BodyExecutionCallback {
        private static final long serialVersionUID = 1L;

        private static final Logger logger = Logger.getLogger(ExecutionCallback.class.getName());

        private long maxCacheSize;
        private List<Cache> caches;
        private List<Cache.Saver> cacheSavers;

        public ExecutionCallback(long maxCacheSize, List<Cache> caches, List<Cache.Saver> cacheSavers) {
            this.maxCacheSize = maxCacheSize;
            this.caches = caches;
            this.cacheSavers = cacheSavers;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            try {
                complete(context);

                context.onSuccess(result);
            } catch (Throwable t) {
                context.onFailure(t);
            }
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            try {
                // attempt to save the caches even though we failed
                complete(context);
            } catch (Throwable e) {
                t.addSuppressed(e);
            }

            context.onFailure(t);
        }

        public void complete(StepContext context) throws IOException, InterruptedException {

            Run run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            Launcher launcher = context.get(Launcher.class);
            TaskListener listener = context.get(TaskListener.class);

            listener.getLogger().println("Going to upload cache...");
            logger.info(">>> Staring cache upload");
            logger.info(">>> " + cacheSavers.size() + " caches to upload");

            CacheManager.save(GlobalItemStorage.get().getStorage(), run, workspace, launcher, listener, maxCacheSize, caches, cacheSavers);

            logger.info(">>> Cache upload complete");
            listener.getLogger().println("Cache uploaded");
        }
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        /**
         * Constructor.
         */
        @SuppressWarnings("unused")
        public DescriptorImpl() {
            super(ExecutionImpl.class);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getFunctionName() {
            return "cache";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.CacheStep_DisplayName();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }


        @SuppressWarnings("unused")
        public List<CacheDescriptor> getCacheDescriptors() {
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
                return jenkins.getDescriptorList(Cache.class);
            } else {
                return Collections.emptyList();
            }
        }
    }
}
