package jenkins.plugins.jobcacher;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import jenkins.SlaveToMasterFileCallable;
import jenkins.plugins.itemstorage.ObjectPath;
import org.apache.commons.io.FilenameUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class DependencyCache extends ArbitraryFileCache {

    private static final Logger logger = Logger.getLogger(DependencyCache.class.getName());

    private final String dependencyDescriptor;

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
        logger.info(">>> Going to compute dependency descriptor digest for " + dependencyDescriptorFile);
        String dependencyDigest = dependencyDescriptorFile.act(
            new SlaveToMasterFileCallable<String>() {
                @Override
                public String invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
                    Path pathToFile = file.toPath();
                    String fileType = Files.probeContentType(pathToFile);
                    if (fileType==null) fileType = "unknown";
                    String fileExt = FilenameUtils.getExtension(file.getPath());
                    List<String> textTypeExts = Arrays.asList("json","lock","xml","yml","yaml","txt");
                    boolean isText = textTypeExts.contains(fileExt) || fileType.startsWith("text");
                    logger.fine(">>> Key file type detected as "+(isText?"text":"binary"));
                    //TODO remove excess logging below
                    if (isText) {
                        logger.finest(">>> Key file contains" + Files.lines(file.toPath()).count() + " lines");
                        logger.fine(">>> Text digest: " +
                            Util.getDigestOf(Files.lines(file.toPath()).collect(Collectors.joining("\n"))));
                    }
                    logger.fine(">>> Key file params{Path: "+pathToFile+","+
                                "Detected type: "+fileType+",Extension: "+fileExt+"}");
                    logger.fine(">>> Bin digest: " + Util.getDigestOf(file));
                    return isText ?
                            Util.getDigestOf(Files.lines(file.toPath()).collect(Collectors.joining("\n"))) :
                            Util.getDigestOf(file);
                }
            }
        );
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
