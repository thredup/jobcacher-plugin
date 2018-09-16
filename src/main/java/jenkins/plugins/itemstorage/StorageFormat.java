package jenkins.plugins.itemstorage;

/**
 * Determines how cache is stored on external storage.
 */
public enum StorageFormat {
    /**
     * Cache is stored as a directory.
     */
    DIRECTORY,

    /**
     * Cache is stored as a single ZIP file, archived before upload storing and unarchived after download.
     */
    ZIP
}
