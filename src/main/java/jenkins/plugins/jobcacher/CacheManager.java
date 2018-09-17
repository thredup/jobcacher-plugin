package jenkins.plugins.jobcacher;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.plugins.itemstorage.ItemStorage;
import jenkins.plugins.itemstorage.ObjectPath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * @author Peter Hayes
 */
public class CacheManager {
    private static final Logger logger = Logger.getLogger(CacheManager.class.getName());

    // Could potential grow indefinitely as jobs are created and destroyed
    private static Map<String, Object> locks = new HashMap<>();

    public static ObjectPath getCachePath(ItemStorage storage, Job<?, ?> job) {
        // TODO: share cache across different branches of multibranch project
        // TODO: e.g. if job's parent() is MultiBranchProject then use it instead job for object path
        logger.info(String.format(">> getCachePath(%s, %s)", storage, job.getFullName()));
        return storage.getObjectPath(job, "cache");
    }

    public static ObjectPath getCachePath(ItemStorage storage, Run<?, ?> run) {
        return getCachePath(storage, run.getParent());
    }

    private static Object getLock(Job j) {
        String jobFullName = j.getFullName();
        Object lock = locks.get(jobFullName);
        if (lock == null) {
            lock = new Object();
            locks.put(jobFullName, lock);
        }
        return lock;
    }

    /**
     * Internal method only
     */
    public static List<Cache.Saver> cache(ItemStorage storage, Run run, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment, List<Cache> caches) throws IOException, InterruptedException {
        ObjectPath cachePath = getCachePath(storage, run);

        logger.fine("Preparing cache for build " + run);

        // Lock the cache for reading - would be nice to make it more fine grain for multiple readers of cache
        List<Cache.Saver> cacheSavers = new ArrayList<>();
        // TODO: do we need locks here?
//        synchronized (getLock(run.getParent())) {
            for (Cache cache : caches) {
                cacheSavers.add(cache.cache(cachePath, run, workspace, launcher, listener, initialEnvironment));
            }
//        }
        return cacheSavers;
    }

    /**
     * Internal method only
     */
    public static void save(ItemStorage storage, Run run, FilePath workspace, Launcher launcher, TaskListener listener, long maxCacheSize, List<Cache> caches, List<Cache.Saver> cacheSavers) throws IOException, InterruptedException {
        ObjectPath cachePath = getCachePath(storage, run);

        // First calculate size of cache to check if it should just be deleted
        long totalSize = 0L;
        for (Cache.Saver saver : cacheSavers) {
            totalSize += saver.calculateSize(cachePath, run, workspace, launcher, listener);
        }

        // synchronize on the build's parent object as we are going to write to the shared cache
        // TODO: synchronization might not be needed for single-file ZIP cache format
//        synchronized (getLock(run.getParent())) {

            // If total size is greater than configured maximum, delete all caches to start fresh next build
            if (totalSize > maxCacheSize * 1024 * 1024) {
                listener.getLogger().println("Removing job cache as it has grown beyond configured maximum size of " +
                        maxCacheSize + "M. Next build will start with no cache.");

                if (cachePath.exists()) {
                    cachePath.deleteRecursive();
                } else {
                    listener.getLogger().println("Cache does not exist even though max cache was reached." +
                            "  You may want to consider increasing maximum cache size.");
                }
            } else {
                // Otherwise, request each cache to save itself for the next build
                logger.info(">>> Saving cache for build " + run);
                for (Cache.Saver saver : cacheSavers) {
                    saver.save(cachePath, run, workspace, launcher, listener);
                }
            }
//        }

        // Add a build action so that users can navigate the cache stored on master through UI
        run.addAction(new CacheBuildLastAction(caches));
    }
}
