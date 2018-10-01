package jenkins.plugins.jobcacher;

import hudson.Extension;
import hudson.FilePath;
import jenkins.plugins.itemstorage.ObjectPath;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.logging.Logger;

@SuppressWarnings("unused")
public class DependencyCache extends ArbitraryFileCache {

    private static final Logger logger = Logger.getLogger(DependencyCache.class.getName());

    private final String dependencyDescriptor;
    private String dependencyDigest = null;

    @DataBoundConstructor
    public DependencyCache(String path, String includes, String excludes, String dependencyDescriptor) {
        super(path, includes, excludes);
        this.dependencyDescriptor = dependencyDescriptor;
    }

    @Override
    protected ObjectPath resolveCachePath(ObjectPath cache, @Nullable FilePath workspace, String path)
            throws IOException, InterruptedException {
        ObjectPath parentCachePath = super.resolveCachePath(cache, workspace, path);
        if (workspace == null) {
            return parentCachePath;
        }

        FilePath dependencyDescriptorFile = workspace.child(dependencyDescriptor);
        logger.info(">>> Get available or compute dependency descriptor digest for " + dependencyDescriptorFile);
        if (dependencyDigest == null)
            dependencyDigest = dependencyDescriptorFile.digest();
        logger.info(">>> Digest is " + dependencyDigest);
        return parentCachePath.child(dependencyDigest);
    }

    @Extension
    @Symbol("dependency")
    public static final class DescriptorImpl extends CacheDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.DependencyCache_displayName();
        }
    }

}
